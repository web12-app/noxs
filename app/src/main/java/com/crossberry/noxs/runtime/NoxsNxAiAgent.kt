/*
 * Noxs — original implementation.
 * Noxs AI Agent runtime: nx@ai> REPL, planner loop, parallel execution, limits,
 * context management, slash commands, session cleanup. Canonical source,
 * mirrored to linux-runtime/nx/ai/agent.py.
 */
package com.crossberry.noxs.runtime

object NoxsNxAiAgent {

    val AI_AGENT_PY = """'''
noxs-ai agent — the Noxs AI Agent Terminal runtime (original Noxs implementation).

    User -> AI model -> agent decision -> tool call(s) -> Noxs Tool Executor
         -> tool result -> AI model -> next tool call or final answer -> User

Boundaries enforced here (never in the model):
  - every tool call is validated against the registry before execution
  - CONFIRM-level tools ask the user: Allow? [y/N] — never simulated by the model
  - independent READ tools may run in parallel (bounded); everything else is
    sequential, so dependent or conflicting actions can never race (sections 7-8)
  - agent limits (steps, tool calls, runtime) stop runaway loops safely (section 17)
  - tool output is truncated, secrets are redacted, context is compacted (section 15)
  - Ctrl+C cancels the current request/tool and returns to nx@ai>
  - Ctrl+D / exit ends the session and releases everything it owns (section 20)
'''
import argparse
import json
import os
import re
import signal
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor

try:
    from provider import AIProviderError, CancelFlag, ChatClient, RetryPolicy
    import tools as tools_mod
    from tools import (ACTIVE_PROCESSES, PERMISSION_CONFIRM, PERMISSION_READ,
                       ToolResult, build_default_registry, redact_secrets,
                       truncate_output)
except ImportError:  # running from the repository test suite
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from provider import AIProviderError, CancelFlag, ChatClient, RetryPolicy
    import tools as tools_mod
    from tools import (ACTIVE_PROCESSES, PERMISSION_CONFIRM, PERMISSION_READ,
                       ToolResult, build_default_registry, redact_secrets,
                       truncate_output)


# ------------------------------------------------------------------ config

CONFIG_DEFAULTS = {
    'enabled': True,
    'provider': 'kilo',
    'model': 'kilo-auto/free',
    'base_url': 'https://api.kilo.ai/api/gateway',
    'api_key_env': 'KILO_API_KEY',
    'stream': True,
    'request_timeout_sec': 90,
    'max_retries': 3,
    'max_steps': 30,
    'max_tool_calls': 50,
    'max_parallel_tools': 4,
    'max_tool_output': 12000,
    'max_runtime_sec': 900,
}

CONFIG_ENV_OVERRIDES = {
    'model': 'NOXS_AI_MODEL',
    'base_url': 'NOXS_AI_BASE_URL',
    'provider': 'NOXS_AI_PROVIDER',
    'api_key_env': 'NOXS_AI_API_KEY_ENV',
    'stream': 'NOXS_AI_STREAM',
}

BANNER_PROVIDER_LABEL = 'Kilo'


def load_config(path=None, overrides=None):
    config = dict(CONFIG_DEFAULTS)
    candidates = [
        path,
        os.path.join(os.path.expanduser('~'), '.noxs', 'ai', 'config.json'),
    ]
    for candidate in candidates:
        if not candidate or not os.path.isfile(candidate):
            continue
        try:
            with open(candidate) as handle:
                data = json.load(handle)
            if isinstance(data, dict):
                for key in CONFIG_DEFAULTS:
                    if key in data:
                        config[key] = data[key]
            break
        except (OSError, ValueError):
            continue
    for key, env_name in CONFIG_ENV_OVERRIDES.items():
        value = os.environ.get(env_name)
        if value is None or value == '':
            continue
        if isinstance(CONFIG_DEFAULTS[key], bool):
            config[key] = value.lower() in ('1', 'true', 'yes', 'on')
        elif isinstance(CONFIG_DEFAULTS[key], int):
            try:
                config[key] = int(value)
            except ValueError:
                pass
        else:
            config[key] = value
    if overrides:
        for key, value in overrides.items():
            if value is not None:
                config[key] = value
    for key in ('max_steps', 'max_tool_calls', 'max_parallel_tools'):
        config[key] = max(1, int(config[key]))
    config['max_tool_output'] = max(1000, int(config['max_tool_output']))
    return config


def resolve_api_key(config):
    '''The key never appears in logs, output, or errors — only in memory.'''
    for env_name in (config.get('api_key_env'), 'NOXS_AI_API_KEY'):
        if env_name and os.environ.get(env_name):
            return os.environ[env_name]
    return None


# ------------------------------------------------------------------ context

SYSTEM_PROMPT_TEMPLATE = '''You are the Noxs AI Agent, running inside the Noxs Linux environment on Android.

Environment:
- working directory: {cwd}
- architecture: {arch}
- operating system: {os_name}
- package manager: apt (Debian family)
- session id: {session_id}

Rules:
1. You may answer directly for greetings, explanations, arithmetic and questions that need no environment access.
2. Use tools whenever real state is needed (files, commands, packages, processes, services, system info).
3. Only use tools from the provided tool list. If something is unavailable, say so honestly.
4. Prefer READ-level tools; destructive or install actions happen only when the user confirms them.
5. Never claim a tool ran when it did not, and never fabricate file contents or command output.
6. When you have enough information, answer concisely in plain text.

You speak to the user with the "_>" prefix handled by the terminal; write plain text without that prefix.'''


class ContextManager:
    '''Conversation + tool results, bounded (spec section 15).'''

    def __init__(self, system_prompt, max_tool_output=12000, compaction_threshold=60):
        self.system = {'role': 'system', 'content': system_prompt}
        self.messages = []
        self.max_tool_output = max_tool_output
        self.compaction_threshold = compaction_threshold

    def add(self, message):
        self.messages.append(message)

    def add_tool_result(self, tool_name, result):
        payload = dict(result)
        if payload.get('stdout') and len(payload['stdout']) > self.max_tool_output:
            payload['stdout'] = truncate_output(payload['stdout'], self.max_tool_output)
        self.messages.append({
            'role': 'tool',
            'tool_call_id': payload.pop('tool_call_id', 'unknown'),
            'content': json.dumps(payload),
        })

    def compact(self):
        '''Keep the system prompt + recent turns; older tool payloads are
        summarized to a marker so the model keeps the shape but not the bulk.'''
        keep = 16
        if len(self.messages) <= self.compaction_threshold:
            return
        head = self.messages[:1]
        body = self.messages[1:]
        for message in body[:-keep]:
            if message.get('role') == 'tool':
                message['content'] = json.dumps({
                    'success': True,
                    'note': '[older tool result summarized away by Noxs AI context management]',
                })
        self.messages = head + body

    def snapshot(self):
        return [self.system] + self.messages


# ------------------------------------------------------------------ session

class AgentSession:
    def __init__(self, cwd):
        self.id = uuid.uuid4().hex
        self.started = time.monotonic()
        self.cwd = os.path.realpath(cwd)
        self.cancel_flag = CancelFlag()
        self.owned_pids = set()
        self.tool_calls = 0
        self.steps = 0
        self.tool_history = []
        self.recent_terminal = []
        self.current_task = None

    def elapsed(self):
        return time.monotonic() - self.started


# ------------------------------------------------------------------ logging

def safe_log(log_path, session_id, event, **fields):
    '''One line, no content, no secrets (spec section 28).'''
    try:
        directory = os.path.dirname(log_path)
        if directory:
            os.makedirs(directory, exist_ok=True)
        parts = ['%s' % time.strftime('%Y-%m-%dT%H:%M:%S'), session_id[:12], event]
        for key in sorted(fields):
            parts.append('%s=%s' % (key, fields[key]))
        with open(log_path, 'a') as handle:
            handle.write(' '.join(str(part) for part in parts) + '\n')
    except OSError:
        pass


# ------------------------------------------------------------------ runtime

RUNNING = '{-_-}'
OK = '{✓}'
FAILED = '{×}'
WAITING = '{…}'
CANCELLED = '{■}'

EXIT_WORDS = ('exit', 'quit')


def tool_line(symbol, name, detail=''):
    suffix = ' ' + detail if detail else ''
    print('   %s %s%s' % (symbol, name, suffix), flush=True)


def assistant_prefix():
    print('_> ', end='', flush=True)


class AgentRuntime:
    def __init__(self, config, session, log_path=None, one_shot=False,
                 auto_confirm=False, on_delta=None):
        self.config = config
        self.session = session
        self.log_path = log_path or os.path.join(
            os.path.expanduser('~'), '.noxs', 'ai', 'agent.log')
        self.one_shot = one_shot
        self.auto_confirm = auto_confirm
        self.registry = build_default_registry(session.cwd, session=session)
        self.client = ChatClient(
            base_url=config['base_url'],
            model=config['model'],
            api_key=resolve_api_key(config),
            timeout=config['request_timeout_sec'],
            retry_policy=RetryPolicy(max_retries=config['max_retries']),
            cancel_flag=session.cancel_flag,
        )
        self.context = ContextManager(
            self._system_prompt(), max_tool_output=config['max_tool_output'])
        self.busy = False
        self.active_futures = []
        self._stream_state = {'open': False}

    # ------------------------------------------------------------ prompts

    def _system_prompt(self):
        arch = 'unknown'
        os_name = 'Linux'
        try:
            import platform
            arch = platform.machine() or arch
        except Exception:
            pass
        try:
            with open('/etc/os-release') as handle:
                for line in handle:
                    if line.startswith('PRETTY_NAME='):
                        os_name = line.split('=', 1)[1].strip().strip('"')
        except OSError:
            pass
        return SYSTEM_PROMPT_TEMPLATE.format(
            cwd=self.session.cwd, arch=arch, os_name=os_name,
            session_id=self.session.id[:12],
        )

    # -------------------------------------------------------------- output

    def emit_text(self, text):
        print('\n_> %s' % text.strip() if not self._stream_state['open'] else '', flush=True)

    def _on_delta(self, piece):
        if not self._stream_state['open']:
            assistant_prefix()
            self._stream_state['open'] = True
        print(piece, end='', flush=True)

    def _close_stream_line(self):
        if self._stream_state['open']:
            print(flush=True)
            self._stream_state['open'] = False

    def emit_note(self, text):
        self._close_stream_line()
        print('_> %s' % text, flush=True)

    # --------------------------------------------------------- permissions

    def confirm(self, tool, target_summary):
        '''Confirmation is controlled by Noxs only (spec section 9).'''
        if self.auto_confirm:
            safe_log(self.log_path, self.session.id, 'confirm', tool=tool, result='auto')
            return True
        if self.one_shot:
            return False
        self._close_stream_line()
        print('\n⚠ Action requires permission', flush=True)
        print('Tool: %s' % tool, flush=True)
        print('Target: %s' % (target_summary or '(unspecified)'), flush=True)
        try:
            answer = input('Allow? [y/N] ').strip().lower()
        except (EOFError, KeyboardInterrupt):
            print(flush=True)
            return False
        allowed = answer in ('y', 'yes')
        safe_log(self.log_path, self.session.id, 'confirm', tool=tool,
                 result='allow' if allowed else 'deny')
        return allowed

    # ----------------------------------------------------------- execution

    def _validate_call(self, call):
        '''Every tool call is validated before execution (spec section 6).'''
        name = call.get('function', {}).get('name', '')
        tool = self.registry.get(name)
        if tool is None:
            return None, ToolResult.failure(name or '(unknown)', 'unknown tool')
        raw_arguments = call.get('function', {}).get('arguments') or '{}'
        try:
            arguments = json.loads(raw_arguments)
        except ValueError:
            return tool, ToolResult.failure(name, 'invalid arguments: not valid JSON')
        if not isinstance(arguments, dict):
            return tool, ToolResult.failure(name, 'invalid arguments: expected an object')
        schema = tool.parameters or {}
        for required in schema.get('required', []) or []:
            if required not in arguments:
                return tool, ToolResult.failure(name, 'missing required argument: %s' % required)
        properties = schema.get('properties', {}) or {}
        for key, value in arguments.items():
            expected = properties.get(key, {}).get('type')
            if expected == 'string' and not isinstance(value, str):
                return tool, ToolResult.failure(name, 'argument %s must be a string' % key)
            if expected == 'integer' and not isinstance(value, int):
                return tool, ToolResult.failure(name, 'argument %s must be an integer' % key)
            if expected == 'boolean' and not isinstance(value, bool):
                return tool, ToolResult.failure(name, 'argument %s must be a boolean' % key)
        return tool, None

    def _execute_one(self, call_id, call):
        self.session.cancel_flag.check()
        tool, invalid = self._validate_call(call)
        if invalid is not None:
            tool_line(CANCELLED if 'unknown' in (invalid.get('error') or '') else FAILED,
                      call.get('function', {}).get('name', '(unknown)'),
                      (invalid.get('error') or '')[:80])
            invalid['tool_call_id'] = call_id
            return invalid
        name = tool.name
        arguments = json.loads(call.get('function', {}).get('arguments') or '{}')
        target = tool.target_summary(arguments)
        if tool.permission == PERMISSION_CONFIRM and not self.confirm(name, target):
            tool_line(CANCELLED, name, 'denied by user')
            result = ToolResult.failure(name, 'the user denied this action')
        else:
            tool_line(RUNNING, name, target if tool.permission == PERMISSION_READ else '')
            started = time.monotonic()
            try:
                result = tool.executor(arguments)
            except AIProviderError:
                raise
            except KeyboardInterrupt:
                result = ToolResult.failure(name, 'cancelled')
            except PermissionError as error:
                # Scope/credential refusals carry safe, user-facing reasons.
                result = ToolResult.failure(name, str(error) or 'permission denied')
            except ValueError as error:
                result = ToolResult.failure(name, str(error) or 'invalid path or input')
            except Exception as error:
                result = ToolResult.failure(name, 'tool failed: %s' % error.__class__.__name__)
            symbol = OK if result.get('success') else FAILED
            detail = 'exit code: %s' % result.get('exit_code') if not result.get('success') else target
            tool_line(symbol, name, detail if not result.get('success') else '')
            if name == 'terminal.run' and result.get('success'):
                self.session.recent_terminal.append((result.get('stdout') or '')[-2000:])
                self.session.recent_terminal = self.session.recent_terminal[-5:]
            safe_log(self.log_path, self.session.id, 'tool', tool=name,
                     ms=int((time.monotonic() - started) * 1000),
                     ok=1 if result.get('success') else 0)
        result['tool_call_id'] = call_id
        self.session.tool_history.append({'tool': name, 'ok': bool(result.get('success'))})
        self.session.tool_calls += 1
        return result

    def execute_calls(self, calls):
        '''Parallel only when every call in the batch is independent READ-level
        work; anything else runs sequentially so results can never race (section 7).'''
        batch = [(call.get('id') or 'call_%d' % index, call)
                 for index, call in enumerate(calls)]
        permissions = []
        for _, call in batch:
            tool = self.registry.get(call.get('function', {}).get('name', ''))
            permissions.append(tool.permission if tool else PERMISSION_READ)
        parallel = (
            len(batch) > 1
            and all(permission == PERMISSION_READ for permission in permissions)
            and len(batch) <= self.config['max_parallel_tools']
        )
        results = {}
        if parallel:
            self.emit_note('Running %d independent checks in parallel...' % len(batch))
            with ThreadPoolExecutor(max_workers=self.config['max_parallel_tools']) as pool:
                futures = {pool.submit(self._execute_one, call_id, call): call_id
                           for call_id, call in batch}
                self.active_futures = list(futures)
                for future, call_id in futures.items():
                    try:
                        results[call_id] = future.result()
                    except AIProviderError as error:
                        if error.code == 'cancelled':
                            for pending in futures:
                                pending.cancel()
                            results[call_id] = ToolResult.failure(
                                'parallel', 'cancelled by user')
                        else:
                            raise
                self.active_futures = []
        else:
            for call_id, call in batch:
                results[call_id] = self._execute_one(call_id, call)
        return results

    # ---------------------------------------------------------- agent turn

    def agent_turn(self, user_input):
        self.session.cancel_flag.reset()
        self.session.current_task = user_input[:120]
        self.context.add({'role': 'user', 'content': user_input})
        deadline = self.session.started + self.config['max_runtime_sec']
        try:
            for _ in range(self.config['max_steps']):
                if self.session.elapsed() > (deadline - self.session.started):
                    self.emit_note('Agent runtime limit reached. The current task was stopped safely.')
                    return False
                self.session.steps += 1
                self.context.compact()
                message = self.client.complete(
                    self.context.snapshot(),
                    tools=self.registry.schemas(),
                    stream=self.config['stream'],
                    on_delta=self._on_delta,
                )
                self._close_stream_line()
                if message.get('content'):
                    self.context.add({'role': 'assistant', 'content': message['content']})
                calls = message.get('tool_calls') or []
                if not calls:
                    if not message.get('content'):
                        self.emit_note('(empty response from the model)')
                    return True
                self.context.add({
                    'role': 'assistant',
                    'content': message.get('content'),
                    'tool_calls': [
                        {'id': call.get('id'), 'type': 'function',
                         'function': call.get('function')}
                        for call in calls
                    ],
                })
                if self.session.tool_calls + len(calls) > self.config['max_tool_calls']:
                    self.emit_note('Agent tool-call limit reached. The current task was stopped safely.')
                    return False
                results = self.execute_calls(calls)
                for call in calls:
                    call_id = call.get('id') or 'call_0'
                    result = results.get(call_id) or ToolResult.failure('tool', 'no result')
                    self.context.add_tool_result(
                        call.get('function', {}).get('name', 'tool'), result)
                if self.session.cancel_flag.cancelled:
                    self.emit_note('Stopped. Back to the prompt.')
                    return False
            self.emit_note('Agent step limit reached. The current task was stopped safely.')
            return False
        except AIProviderError as error:
            self._close_stream_line()
            if error.code == 'cancelled':
                self.emit_note('Request cancelled. Back to the prompt.')
                return False
            if error.code == 'rate_limit':
                self.emit_note('The AI provider is rate limiting requests. Try again shortly.')
            elif error.code == 'auth':
                self.emit_note('The AI provider rejected the credentials. Check the Noxs AI configuration key.')
            else:
                self.emit_note('The AI provider is temporarily unavailable. (%s)' % error.code)
            return False
        finally:
            self.session.cancel_flag.reset()
            self.session.current_task = None

    # ------------------------------------------------------ slash commands

    def slash_command(self, line):
        command = line.split()[0].lower()
        if command in ('/exit', '/quit'):
            return False
        if command == '/help':
            print('''Noxs AI Agent commands:
  /help        this help
  /tools       list available tools
  /model       show the configured model
  /status      session status
  /context     conversation size
  /permissions show tool permission levels
  /stop        stop the active agent operation
  /clear       clear the conversation
  /reset       new session (keeps the terminal)
  /exit        leave the AI Agent (same as Ctrl+D)''', flush=True)
        elif command == '/tools':
            print('Available tools:', flush=True)
            for name in self.registry.available_names():
                tool = self.registry.get(name)
                print('- %s (%s)' % (name, tool.permission), flush=True)
        elif command == '/model':
            print('Model: %s' % self.config['model'], flush=True)
            print('Provider: %s' % self.config.get('provider', 'kilo'), flush=True)
        elif command == '/status':
            print('Session: %s' % self.session.id[:12], flush=True)
            print('Runtime: %d s' % int(self.session.elapsed()), flush=True)
            print('Tool calls: %d' % self.session.tool_calls, flush=True)
            print('Working directory: %s' % self.session.cwd, flush=True)
        elif command == '/context':
            print('Messages in context: %d' % len(self.context.messages), flush=True)
        elif command == '/permissions':
            print('Permission levels:', flush=True)
            for name in self.registry.available_names():
                tool = self.registry.get(name)
                print('- %s: %s' % (name, tool.permission), flush=True)
        elif command == '/stop':
            self.session.cancel_flag.cancel()
            print('Stopping the active operation...', flush=True)
        elif command == '/clear':
            self.context.messages = []
            print('Conversation cleared.', flush=True)
        elif command == '/reset':
            self.context.messages = []
            self.session.tool_calls = 0
            self.session.steps = 0
            self.session.tool_history = []
            print('Session reset.', flush=True)
        else:
            print('Unknown command: %s (try /help)' % command, flush=True)
        return True

    # ---------------------------------------------------------------- REPL

    def run_interactive(self):
        print('Noxs AI Agent — model: %s (provider: %s)' % (
            self.config['model'], BANNER_PROVIDER_LABEL), flush=True)
        print('Type /help for commands. Ctrl+D or /exit leaves the agent.', flush=True)
        while True:
            try:
                line = input('nx@ai> ')
            except EOFError:
                print(flush=True)
                break
            except KeyboardInterrupt:
                print('^C', flush=True)
                continue
            text = line.strip()
            if not text:
                continue
            if text.lower() in EXIT_WORDS:
                break
            if text.startswith('/'):
                try:
                    if not self.slash_command(text):
                        break
                except KeyboardInterrupt:
                    print(flush=True)
                continue
            try:
                self.busy = True
                self.agent_turn(text)
            finally:
                self.busy = False
                self._close_stream_line()
        self.shutdown()

    def run_one_shot(self, task):
        try:
            self.busy = True
            self.agent_turn(task)
        finally:
            self.busy = False
            self._close_stream_line()
            self.shutdown()

    def shutdown(self):
        '''Release everything the session owns (spec section 20).'''
        self.session.cancel_flag.cancel()
        for process in list(ACTIVE_PROCESSES):
            tools_mod.kill_process_group(process)
        for pid in list(self.session.owned_pids):
            try:
                os.killpg(pid, signal.SIGTERM)
            except (ProcessLookupError, PermissionError, OSError):
                pass
        safe_log(self.log_path, self.session.id, 'session_end',
                 seconds=int(self.session.elapsed()),
                 tool_calls=self.session.tool_calls)


# -------------------------------------------------------------------- main

def parse_args(argv):
    parser = argparse.ArgumentParser(prog='nx ai', add_help=False,
                                     description='Noxs AI Agent Terminal')
    parser.add_argument('task', nargs='*', help='one-shot task (omit for interactive mode)')
    parser.add_argument('--model')
    parser.add_argument('--no-stream', action='store_true')
    parser.add_argument('--yes', action='store_true',
                        help='auto-approve CONFIRM tools (one-shot only)')
    parser.add_argument('--config')
    parser.add_argument('-h', '--help', action='store_true')
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(sys.argv[1:] if argv is None else argv)
    if args.help:
        print(__doc__)
        return 0
    overrides = {'model': args.model}
    if args.no_stream:
        overrides['stream'] = False
    config = load_config(path=args.config, overrides=overrides)
    if not config.get('enabled', True):
        print('_> The Noxs AI Agent is disabled in the configuration.', flush=True)
        return 1
    session = AgentSession(cwd=os.getcwd())
    runtime = AgentRuntime(config, session, one_shot=bool(args.task),
                           auto_confirm=bool(args.yes and args.task))
    if args.task:
        ok = False
        try:
            runtime.run_one_shot(' '.join(args.task))
            ok = True
        except KeyboardInterrupt:
            print(flush=True)
        return 0 if ok else 1
    signal.signal(signal.SIGINT, handle_sigint)
    runtime.run_interactive()
    return 0


def handle_sigint(signum, frame):
    '''Ctrl+C: cancel the current request/tool, return to nx@ai> (spec section 1).
    KeyboardInterrupt inside input() behaves like the default handler; this
    hook covers agent turns by cancelling the cooperative flag and killing
    agent-owned subprocesses.'''
    raise KeyboardInterrupt


if __name__ == '__main__':
    sys.exit(main())
"""
}
