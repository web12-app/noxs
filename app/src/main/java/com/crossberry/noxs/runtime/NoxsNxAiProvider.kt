/*
 * Noxs — original implementation.
 * Noxs AI provider client: provider-independent chat completions with streaming,
 * retries, timeout, rate-limit handling and cancellation. Python is the honest
 * runtime for an agent loop; this Kotlin raw string is the canonical source
 * (mirrored to linux-runtime/nx/ai/provider.py by scripts/sync_nx.py).
 */
package com.crossberry.noxs.runtime

object NoxsNxAiProvider {

    val AI_PROVIDER_PY = """'''
noxs-ai provider — provider-independent AI client (original Noxs implementation).

The Agent Runtime never talks to a provider directly; it talks to this
module. The default route is the Kilo gateway with the kilo-auto/free
model, but provider, endpoint, model and key environment variable are all
configuration — replaceable without touching the agent.

Provider contract (OpenAI-compatible chat completions, the de-facto
standard):

    POST {base_url}/chat/completions
    {"model": ..., "messages": [...], "tools": [...], "stream": bool}

Security: the API key is read from the environment variable named by
configuration and is never logged, printed, or included in errors.
'''
import json
import socket
import time
import urllib.error
import urllib.request


class AIProviderError(Exception):
    '''Deterministic provider failure. code: timeout|rate_limit|unavailable|malformed|auth|cancelled'''

    def __init__(self, code, message, retry_after=None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.retry_after = retry_after


class RetryPolicy:
    '''Bounded exponential backoff. Never endless.'''

    def __init__(self, max_retries=3, base_delay=1.0, max_delay=16.0):
        self.max_retries = max_retries
        self.base_delay = base_delay
        self.max_delay = max_delay

    def delay_for(self, attempt, retry_after=None):
        '''attempt is 1-based (the attempt that failed).'''
        if retry_after is not None:
            try:
                return min(float(retry_after), self.max_delay)
            except (TypeError, ValueError):
                pass
        delay = self.base_delay * (2 ** max(0, attempt - 1))
        return min(delay, self.max_delay)

    def should_retry(self, error):
        if isinstance(error, AIProviderError):
            return error.code in ('unavailable', 'rate_limit', 'timeout')
        return False


class CancelFlag:
    '''Cooperative cancellation shared with the REPL signal handler.'''

    def __init__(self):
        self.cancelled = False

    def cancel(self):
        self.cancelled = True

    def reset(self):
        self.cancelled = False

    def check(self):
        if self.cancelled:
            raise AIProviderError('cancelled', 'Request cancelled.')


class StreamAccumulator:
    '''Assembles OpenAI-compatible streaming chunks into one message dict.'''

    def __init__(self):
        self.content_parts = []
        self.tool_calls = {}  # index -> {id, name, arguments-fragments}

    def feed(self, delta):
        if not isinstance(delta, dict):
            return
        piece = delta.get('content')
        if isinstance(piece, str) and piece:
            self.content_parts.append(piece)
        for call in delta.get('tool_calls') or []:
            if not isinstance(call, dict):
                continue
            index = call.get('index', 0)
            slot = self.tool_calls.setdefault(index, {'id': '', 'name': '', 'arguments': ''})
            if call.get('id'):
                slot['id'] = call['id']
            fn = call.get('function') or {}
            if fn.get('name'):
                slot['name'] = (slot['name'] or '') + fn['name'] if not call.get('id') else fn['name']
            if fn.get('arguments'):
                slot['arguments'] += fn['arguments']

    def message(self):
        tool_calls = []
        for index in sorted(self.tool_calls):
            slot = self.tool_calls[index]
            tool_calls.append({
                'id': slot['id'] or 'call_%d' % index,
                'type': 'function',
                'function': {'name': slot['name'], 'arguments': slot['arguments'] or '{}'},
            })
        return {
            'role': 'assistant',
            'content': ''.join(self.content_parts) or None,
            'tool_calls': tool_calls or None,
        }


def parse_sse_line(line):
    '''One SSE line -> decoded chunk dict, or None for keepalives/comments.'''
    line = line.strip()
    if not line.startswith('data:'):
        return None
    payload = line[5:].strip()
    if not payload or payload == '[DONE]':
        return None
    try:
        return json.loads(payload)
    except ValueError:
        return None


class ChatClient:
    '''Provider-independent chat client with streaming, retries, cancellation.'''

    def __init__(self, base_url, model, api_key=None, timeout=90,
                 retry_policy=None, cancel_flag=None, user_agent='Noxs-AI'):
        self.base_url = (base_url or '').rstrip('/')
        self.model = model
        self.api_key = api_key
        self.timeout = timeout
        self.retry_policy = retry_policy or RetryPolicy()
        self.cancel_flag = cancel_flag or CancelFlag()
        self.user_agent = user_agent
        self.last_retry_after = None

    # ------------------------------------------------------------- request

    def _build_request(self, messages, tools, stream):
        body = {
            'model': self.model,
            'messages': messages,
            'stream': bool(stream),
        }
        if tools:
            body['tools'] = tools
            body['tool_choice'] = 'auto'
        data = json.dumps(body).encode('utf-8')
        headers = {
            'Content-Type': 'application/json',
            'Accept': 'text/event-stream' if stream else 'application/json',
            'User-Agent': self.user_agent,
        }
        if self.api_key:
            headers['Authorization'] = 'Bearer %s' % self.api_key
        return urllib.request.Request(self.base_url + '/chat/completions', data=data, headers=headers)

    def _open(self, request):
        try:
            return urllib.request.urlopen(request, timeout=self.timeout)
        except urllib.error.HTTPError as error:
            if error.code == 429:
                retry_after = error.headers.get('Retry-After') if error.headers else None
                raise AIProviderError('rate_limit', 'The AI provider is rate limiting requests.',
                                      retry_after=retry_after)
            if error.code in (401, 403):
                raise AIProviderError('auth', 'The AI provider rejected the credentials.')
            if 500 <= error.code < 600:
                raise AIProviderError('unavailable', 'The AI provider is temporarily unavailable.')
            raise AIProviderError('unavailable', 'The AI provider returned an error.')
        except urllib.error.URLError as error:
            if isinstance(getattr(error, 'reason', None), socket.timeout):
                raise AIProviderError('timeout', 'The AI provider request timed out.')
            raise AIProviderError('unavailable', 'The AI provider could not be reached.')
        except socket.timeout:
            raise AIProviderError('timeout', 'The AI provider request timed out.')

    # ------------------------------------------------------------- public

    def complete(self, messages, tools=None, stream=False, on_delta=None):
        '''Return the assistant message dict. Retries bounded, honor Retry-After,
        raise AIProviderError('cancelled') when the cancel flag is set.'''
        self.last_retry_after = None
        attempt = 0
        while True:
            self.cancel_flag.check()
            request = self._build_request(messages, tools, stream)
            try:
                response = self._open(request)
                with response:
                    if stream:
                        return self._consume_stream(response, on_delta)
                    payload = json.loads(response.read().decode('utf-8', 'replace'))
                    return self._extract_message(payload)
            except AIProviderError as error:
                if error.code == 'cancelled' or not self.retry_policy.should_retry(error):
                    raise
                if attempt >= self.retry_policy.max_retries:
                    raise
                attempt += 1
                self.last_retry_after = error.retry_after
                delay = self.retry_policy.delay_for(attempt, error.retry_after)
                for _ in range(int(delay * 4)):
                    self.cancel_flag.check()
                    time.sleep(0.25)

    def _consume_stream(self, response, on_delta):
        accumulator = StreamAccumulator()
        while True:
            self.cancel_flag.check()
            try:
                raw = response.readline()
            except socket.timeout:
                raise AIProviderError('timeout', 'The AI provider stream timed out.')
            if not raw:
                break
            chunk = parse_sse_line(raw.decode('utf-8', 'replace'))
            if not chunk:
                continue
            choices = chunk.get('choices') or []
            if not choices:
                continue
            delta = choices[0].get('delta') or {}
            piece = delta.get('content')
            if piece and on_delta:
                on_delta(piece)
            accumulator.feed(delta)
            finish = choices[0].get('finish_reason')
            if finish:
                break
        return accumulator.message()

    @staticmethod
    def _extract_message(payload):
        choices = payload.get('choices') if isinstance(payload, dict) else None
        if not choices:
            raise AIProviderError('malformed', 'The AI provider returned an unexpected response.')
        message = choices[0].get('message')
        if not isinstance(message, dict):
            raise AIProviderError('malformed', 'The AI provider returned an unexpected response.')
        return {
            'role': 'assistant',
            'content': message.get('content'),
            'tool_calls': message.get('tool_calls') or None,
        }
"""
}
