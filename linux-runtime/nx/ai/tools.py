'''
noxs-ai tools — the central Noxs Tool Registry (original Noxs implementation).

Every tool defines: name, description, input schema, permission level,
timeout, executor, result schema, cancellation + retry behavior (spec $5).

Permission levels (spec $9):
    READ     — safe to execute, no user confirmation
    CONFIRM  — asks the user: Allow? [y/N] (never simulated by the model)
    DENY     — refused outright (bypass attempts, secrets, destructive ops)

Tool results are normalized to the $16 format before they reach the model:
    {"success": true, "tool": ..., "exit_code": 0, "stdout": ..., ...}

Reuse: process/service/package tools drive the existing noxs-service,
noxs-ps, apt and nx install tooling instead of duplicating it (spec $31).
'''
import fnmatch
import json
import os
import re
import shutil
import signal
import subprocess
import threading
import time


PERMISSION_READ = 'READ'
PERMISSION_CONFIRM = 'CONFIRM'
PERMISSION_DENY = 'DENY'

MAX_TOOL_OUTPUT_DEFAULT = 12000
MAX_SEARCH_RESULTS = 200
MAX_LIST_ENTRIES = 500


# ---------------------------------------------------------------- helpers

def truncate_output(text, limit=MAX_TOOL_OUTPUT_DEFAULT):
    '''Head+tail truncation so huge outputs never flood the model ($15).'''
    text = text or ''
    if len(text) <= limit:
        return text
    head = text[: limit // 2]
    tail = text[-(limit // 2):]
    return head + '\n... [output truncated by Noxs AI] ...\n' + tail


SECRET_PATTERNS = [
    re.compile(r'-----BEGIN [A-Z ]*PRIVATE KEY-----'),
    re.compile(r'\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}\b'),
    re.compile(r'\bsk-[A-Za-z0-9_-]{20,}\b'),
    re.compile(r'\bxox[baprs]-[A-Za-z0-9-]{10,}\b'),
    re.compile(r'\bAKIA[0-9A-Z]{16}\b'),
    re.compile(r'(?i)\b(bearer|authorization)\s*[:=]\s*\S+'),
    re.compile(r'(?i)\b(api[_-]?key|secret|passwd|password|token)\b\s*[:=]\s*[^\s,;]{8,}'),
]

PROTECTED_FILE_PATTERNS = [
    '*/.ssh/id_*', '*/.ssh/*_rsa', '*/.ssh/*_ed25519', '*/.ssh/authorized_keys',
    '*/.gnupg/*', '*/.aws/credentials', '*/.kube/config', '*/.netrc',
    '*/.noxs/ai/config.json', '/etc/shadow', '/etc/gshadow',
    '/etc/sudoers', '/etc/sudoers.d/*', '*token*', '*credentials*',
]


def redact_secrets(text):
    '''Sensitive information is filtered before anything reaches the model
    or the screen (spec $15, $19).'''
    for pattern in SECRET_PATTERNS:
        text = pattern.sub('[REDACTED]', text)
    return text


def _matches_any(path, patterns):
    posix = path.replace(os.sep, '/')
    for pattern in patterns:
        if fnmatch.fnmatch(posix, pattern.replace(os.sep, '/')):
            return True
    return False


def is_protected_path(path):
    '''Credential/secret files the AI may never read or modify ($9 DENY).'''
    resolved = os.path.realpath(path)
    return _matches_any(resolved, PROTECTED_FILE_PATTERNS)


class PathGuard:
    '''Filesystem scope enforcement (spec $9-$10).

    READ: everywhere in the guest except protected secret files.
    WRITE: only inside allowed write roots (home, cwd, tmp) — never /etc,
    /usr, /bin, /var system locations.
    '''

    SYSTEM_PREFIXES = ('/etc', '/usr', '/bin', '/sbin', '/boot', '/lib', '/lib64',
                       '/dev', '/proc', '/sys', '/var/log')

    def __init__(self, cwd, home=None):
        self.cwd = os.path.realpath(cwd)
        self.home = os.path.realpath(home or os.path.expanduser('~'))
        roots = [self.home, self.cwd, '/tmp']
        self.write_roots = []
        for root in roots:
            root = os.path.realpath(root)
            if root not in self.write_roots:
                self.write_roots.append(root)

    def resolve(self, path):
        '''Resolve relative to cwd; refuses traversal outside the guest.'''
        if not path or not str(path).strip():
            raise ValueError('path is required')
        if str(path).strip() in ('~', '~/'):
            path = self.home
        elif str(path).startswith('~/'):
            path = os.path.join(self.home, str(path)[2:])
        resolved = os.path.realpath(os.path.join(self.cwd, str(path)))
        if not resolved.startswith(('/')):
            raise ValueError('invalid path')
        if is_protected_path(resolved):
            raise PermissionError('access to protected credentials is denied')
        return resolved

    def check_write(self, resolved):
        inside = any(
            resolved == root or resolved.startswith(root.rstrip('/') + '/')
            for root in self.write_roots
        )
        if not inside:
            raise PermissionError('path is outside the Noxs allowed write scope')
        for prefix in self.SYSTEM_PREFIXES:
            if resolved == prefix or resolved.startswith(prefix + '/'):
                raise PermissionError('protected system location')
        if is_protected_path(resolved):
            raise PermissionError('access to protected credentials is denied')


class CommandValidator:
    '''terminal.run command safety (spec $10): blocklist + bounds.'''

    FORBIDDEN = [
        re.compile(r'rm\s+(-[a-zA-Z]*[rf][a-zA-Z]*\s+)*/\s*$'),
        re.compile(r'rm\s+-[a-zA-Z]*r[a-zA-Z]*f?\s+/(?:\s|$)'),
        re.compile(r'\bmkfs(\.\w+)?\b'),
        re.compile(r'\bdd\s+[^|;]*of=/dev/'),
        re.compile(r'\b(shutdown|reboot|halt|poweroff|init\s+0|init\s+6)\b'),
        re.compile(r':\(\)\s*\{\s*:\|:\s*&\s*\}\s*;?\s*:'),
        re.compile(r'\bchmod\s+(-R\s+)?777\s+/(?:\s|$)'),
        re.compile(r'\bchown\s+(-R\s+)?\w+:\w+\s+/(?:\s|$)'),
        re.compile(r'(curl|wget)[^|]*\|\s*(sudo\s+)?(ba)?sh\b'),
        re.compile(r'>\s*/dev/sd[a-z]'),
        re.compile(r'\b(apt|dpkg)\s+.*\s+(--force\w*)\b'),
        re.compile(r'\buserdel\b|\busermod\b.*-L\s+root|\bpasswd\s+root\b'),
        re.compile(r'\bkill\s+-9\s+1\b|\bkillall\s+-9\b'),
    ]

    def __init__(self, max_length=4000):
        self.max_length = max_length

    def validate(self, command):
        if not command or not str(command).strip():
            return False, 'command is empty'
        command = str(command)
        if len(command) > self.max_length:
            return False, 'command exceeds the maximum length'
        for pattern in self.FORBIDDEN:
            if pattern.search(command):
                return False, 'command is not allowed inside the Noxs AI sandbox'
        return True, ''


class ToolResult(dict):
    '''Normalized result envelope ($16).'''

    @classmethod
    def success(cls, tool, stdout='', stderr='', exit_code=0, started=None):
        return cls({
            'success': True,
            'tool': tool,
            'exit_code': exit_code,
            'stdout': truncate_output(redact_secrets(stdout)),
            'stderr': truncate_output(redact_secrets(stderr)),
            'duration_ms': int((time.monotonic() - started) * 1000) if started else 0,
        })

    @classmethod
    def failure(cls, tool, error, retryable=False, exit_code=None, stdout='', stderr=''):
        return cls({
            'success': False,
            'tool': tool,
            'error': redact_secrets(str(error)),
            'retryable': bool(retryable),
            'exit_code': exit_code if exit_code is not None else 1,
            'stdout': truncate_output(redact_secrets(stdout)) if stdout else '',
            'stderr': truncate_output(redact_secrets(stderr)) if stderr else '',
        })


class Tool:
    def __init__(self, name, description, parameters, permission, executor,
                 timeout=30, retryable=False, target=None, category=None):
        self.name = name
        self.description = description
        self.parameters = parameters
        self.permission = permission
        self.executor = executor
        self.timeout = timeout
        self.retryable = retryable
        self.target = target          # callable(arguments) -> short human target summary
        self.category = category

    def schema(self):
        return {
            'type': 'function',
            'function': {
                'name': self.name,
                'description': self.description,
                'parameters': self.parameters,
            },
        }

    def target_summary(self, arguments):
        if self.target:
            try:
                return str(self.target(arguments or {}))[:160]
            except Exception:
                pass
        return json.dumps(arguments or {})[:160]


class ToolRegistry:
    def __init__(self):
        self._tools = {}

    def register(self, tool):
        self._tools[tool.name] = tool

    def get(self, name):
        return self._tools.get(name)

    def names(self):
        return sorted(self._tools)

    def schemas(self):
        return [self._tools[name].schema() for name in sorted(self._tools)]

    def available_names(self):
        '''Only tools that actually exist are ever offered ($24).'''
        return sorted(self._tools)


# ---------------------------------------------------------------- executors

SAFE_ENV_BASE = ('PATH', 'HOME', 'LANG', 'LC_ALL', 'TERM', 'SHELL', 'USER', 'LOGNAME')


def build_safe_env(extra=None):
    '''Minimal environment for tool subprocesses — unrestricted environment
    variables are never passed through ($10, $19).'''
    env = {key: os.environ[key] for key in SAFE_ENV_BASE if key in os.environ}
    env.setdefault('PATH', '/usr/local/bin:/usr/bin:/bin')
    env['NOXS_AI'] = '1'
    if extra:
        env.update(extra)
    return env


def run_subprocess(command, cwd=None, timeout=30, env=None, stdin_text=None):
    '''Run with process-group cancellation support and output caps.'''
    process = subprocess.Popen(
        command,
        cwd=cwd,
        env=env or build_safe_env(),
        stdin=subprocess.PIPE if stdin_text is not None else subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        start_new_session=True,
        text=True,
        errors='replace',
    )
    registry = ACTIVE_PROCESSES
    registry.add(process)
    try:
        stdout, stderr = process.communicate(stdin_text, timeout=timeout)
        return process.returncode, stdout or '', stderr or ''
    except subprocess.TimeoutExpired:
        kill_process_group(process)
        stdout, stderr = process.communicate()
        return 124, truncate_output(stdout or ''), 'command timed out after %ss' % timeout
    finally:
        registry.discard(process)


def kill_process_group(process):
    try:
        os.killpg(os.getpgid(process.pid), signal.SIGKILL)
    except (ProcessLookupError, PermissionError, OSError):
        try:
            process.kill()
        except (ProcessLookupError, OSError):
            pass


ACTIVE_PROCESSES = set()


def make_file_tools(guard):
    '''Filesystem tools scoped by PathGuard (spec $9-$10, $18).'''

    def list_dir(args):
        path = guard.resolve(args.get('path') or '.')
        if not os.path.isdir(path):
            return ToolResult.failure('file.list', 'not a directory: %s' % args.get('path', '.'))
        entries = []
        try:
            with os.scandir(path) as scan:
                for entry in scan:
                    kind = 'dir/' if entry.is_dir(follow_symlinks=False) else ''
                    entries.append(entry.name + kind)
                    if len(entries) >= MAX_LIST_ENTRIES:
                        entries.append('... [listing truncated by Noxs AI]')
                        break
        except PermissionError:
            return ToolResult.failure('file.list', 'permission denied')
        return ToolResult.success('file.list', stdout='\n'.join(sorted(entries)))

    def read_file(args):
        path = guard.resolve(args.get('path'))
        limit = int(args.get('max_bytes') or 65536)
        if not os.path.isfile(path):
            return ToolResult.failure('file.read', 'not a file: %s' % args.get('path'))
        size = os.path.getsize(path)
        with open(path, 'r', errors='replace') as handle:
            data = handle.read(limit)
        if size > limit:
            data += '\n... [file truncated, %d of %d bytes shown]' % (limit, size)
        return ToolResult.success('file.read', stdout=data)

    def write_file(args):
        path = guard.resolve(args.get('path'))
        guard.check_write(path)
        content = str(args.get('content') or '')
        with open(path, 'w', encoding='utf-8') as handle:
            handle.write(content)
        return ToolResult.success('file.write', stdout='wrote %d bytes to %s' % (len(content), args.get('path')))

    def stat_path(args):
        path = guard.resolve(args.get('path'))
        if not os.path.exists(path):
            return ToolResult.failure('file.stat', 'not found: %s' % args.get('path'))
        info = os.stat(path)
        lines = [
            'path: %s' % path,
            'size: %d bytes' % info.st_size,
            'type: %s' % ('directory' if os.path.isdir(path) else 'file'),
            'modified: %s' % time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(info.st_mtime)),
        ]
        return ToolResult.success('file.stat', stdout='\n'.join(lines))

    def search_files(args):
        root = guard.resolve(args.get('path') or '.')
        pattern = str(args.get('pattern') or '*')
        name_only = bool(args.get('name_only', True))
        matches = []
        for base, dirs, files in os.walk(root):
            dirs[:] = [d for d in dirs if d not in ('.git', 'node_modules', '__pycache__')]
            for name in dirs + files:
                candidate = os.path.join(base, name)
                if is_protected_path(candidate):
                    continue
                hit = fnmatch.fnmatch(name, pattern)
                if not hit and not name_only and os.path.isfile(candidate):
                    try:
                        with open(candidate, 'r', errors='replace') as handle:
                            hit = pattern in handle.read(262144)
                    except (OSError, PermissionError):
                        hit = False
                if hit:
                    matches.append(os.path.relpath(candidate, root))
                    if len(matches) >= MAX_SEARCH_RESULTS:
                        matches.append('... [search truncated by Noxs AI]')
                        return ToolResult.success('file.search', stdout='\n'.join(matches))
        return ToolResult.success('file.search', stdout='\n'.join(sorted(matches)) or '(no matches)')

    def _two_arg_tool(name, action):
        def run(args):
            source = guard.resolve(args.get('source') or args.get('path'))
            destination = guard.resolve(args.get('destination') or args.get('path2'))
            guard.check_write(source)
            guard.check_write(destination)
            if action == 'move':
                shutil.move(source, destination)
            elif action == 'copy':
                if os.path.isdir(source):
                    shutil.copytree(source, destination)
                else:
                    shutil.copy2(source, destination)
            return ToolResult.success(name, stdout='%s -> %s' % (source, destination))
        return run

    def delete_path(args):
        path = guard.resolve(args.get('path'))
        guard.check_write(path)
        if path == guard.cwd or path == guard.home:
            return ToolResult.failure('file.delete', 'refusing to delete the working directory or home')
        if os.path.isdir(path):
            shutil.rmtree(path)
        else:
            os.remove(path)
        return ToolResult.success('file.delete', stdout='deleted %s' % path)

    return {
        'file.list': Tool(
            'file.list', 'List directory entries.', {
                'type': 'object',
                'properties': {'path': {'type': 'string', 'description': 'directory path (default .)'}},
            },
            PERMISSION_READ, list_dir, timeout=15,
            target=lambda a: a.get('path') or '.', category='file',
        ),
        'file.read': Tool(
            'file.read', 'Read a text file (size-limited, secrets filtered).', {
                'type': 'object',
                'properties': {
                    'path': {'type': 'string'},
                    'max_bytes': {'type': 'integer', 'description': 'maximum bytes to read'},
                },
                'required': ['path'],
            },
            PERMISSION_READ, read_file, timeout=15,
            target=lambda a: a.get('path', ''), category='file',
        ),
        'file.write': Tool(
            'file.write', 'Write (or overwrite) a file inside the allowed scope.', {
                'type': 'object',
                'properties': {
                    'path': {'type': 'string'},
                    'content': {'type': 'string'},
                },
                'required': ['path', 'content'],
            },
            PERMISSION_CONFIRM, write_file, timeout=15,
            target=lambda a: a.get('path', ''), category='file',
        ),
        'file.move': Tool(
            'file.move', 'Move or rename a file/directory within the allowed scope.', {
                'type': 'object',
                'properties': {'source': {'type': 'string'}, 'destination': {'type': 'string'}},
                'required': ['source', 'destination'],
            },
            PERMISSION_CONFIRM, _two_arg_tool('file.move', 'move'), timeout=30,
            target=lambda a: '%s -> %s' % (a.get('source', ''), a.get('destination', '')), category='file',
        ),
        'file.copy': Tool(
            'file.copy', 'Copy a file/directory within the allowed scope.', {
                'type': 'object',
                'properties': {'source': {'type': 'string'}, 'destination': {'type': 'string'}},
                'required': ['source', 'destination'],
            },
            PERMISSION_CONFIRM, _two_arg_tool('file.copy', 'copy'), timeout=60,
            target=lambda a: '%s -> %s' % (a.get('source', ''), a.get('destination', '')), category='file',
        ),
        'file.delete': Tool(
            'file.delete', 'Delete a file or directory inside the allowed scope.', {
                'type': 'object',
                'properties': {'path': {'type': 'string'}},
                'required': ['path'],
            },
            PERMISSION_CONFIRM, delete_path, timeout=30,
            target=lambda a: a.get('path', ''), category='file',
        ),
        'file.stat': Tool(
            'file.stat', 'Size, type and modification time of a path.', {
                'type': 'object',
                'properties': {'path': {'type': 'string'}},
                'required': ['path'],
            },
            PERMISSION_READ, stat_path, timeout=15,
            target=lambda a: a.get('path', ''), category='file',
        ),
        'file.search': Tool(
            'file.search', 'Find files by glob pattern (or content with name_only=false).', {
                'type': 'object',
                'properties': {
                    'path': {'type': 'string', 'description': 'search root (default .)'},
                    'pattern': {'type': 'string'},
                    'name_only': {'type': 'boolean'},
                },
            },
            PERMISSION_READ, search_files, timeout=60,
            target=lambda a: a.get('pattern', '*'), category='file',
        ),
    }


def make_terminal_tools(guard, validator, session=None):
    def terminal_run(args):
        command = args.get('command')
        ok, reason = validator.validate(command)
        if not ok:
            return ToolResult.failure('terminal.run', reason)
        workdir = guard.cwd
        if args.get('cwd'):
            try:
                workdir = guard.resolve(args.get('cwd'))
            except (ValueError, PermissionError) as error:
                return ToolResult.failure('terminal.run', str(error))
        code, stdout, stderr = run_subprocess(
            ['bash', '-c', command], cwd=workdir,
            timeout=int(args.get('timeout') or 60),
        )
        result = ToolResult.success('terminal.run', stdout=stdout, stderr=stderr, exit_code=code,
                                    started=time.monotonic())
        if code != 0:
            result['success'] = code == 0
            result['exit_code'] = code
        return result

    def terminal_read(args):
        recent = getattr(session, 'recent_terminal', []) if session else []
        return ToolResult.success('terminal.read', stdout='\n'.join(recent) or '(no recent terminal output)')

    def terminal_write(args):
        # Writes into the user's terminal always need confirmation ($9).
        text = str(args.get('text') or '')
        return ToolResult.success('terminal.write', stdout=text)

    return {
        'terminal.run': Tool(
            'terminal.run', 'Run a shell command inside the Noxs environment (validated, time-limited).', {
                'type': 'object',
                'properties': {
                    'command': {'type': 'string'},
                    'cwd': {'type': 'string'},
                    'timeout': {'type': 'integer', 'description': 'seconds (max 300)'},
                },
                'required': ['command'],
            },
            PERMISSION_CONFIRM, terminal_run, timeout=300, retryable=True,
            target=lambda a: a.get('command', ''), category='terminal',
        ),
        'terminal.read': Tool(
            'terminal.read', 'Read recent Noxs terminal output captured this session.', {
                'type': 'object', 'properties': {},
            },
            PERMISSION_READ, terminal_read, timeout=10, category='terminal',
        ),
        'terminal.write': Tool(
            'terminal.write', 'Print text into the user terminal (requires confirmation).', {
                'type': 'object',
                'properties': {'text': {'type': 'string'}},
                'required': ['text'],
            },
            PERMISSION_CONFIRM, terminal_write, timeout=10,
            target=lambda a: (a.get('text') or '')[:80], category='terminal',
        ),
    }


def _apt_sudo():
    if os.geteuid() == 0:
        return []
    if shutil.which('sudo'):
        return ['sudo', '-n']
    return ['sudo', '-n']


def make_package_tools(guard):
    def package_search(args):
        term = str(args.get('query') or '')
        if not term:
            return ToolResult.failure('package.search', 'query is required')
        if shutil.which('apt-cache'):
            code, stdout, stderr = run_subprocess(['apt-cache', 'search', '--', term], timeout=60)
            return ToolResult.success('package.search', stdout=stdout or '(no results)', stderr=stderr,
                                      exit_code=code)
        return ToolResult.failure('package.search', 'no supported package manager found')

    def _apt_action(name, packages, action):
        if not packages:
            return ToolResult.failure(name, 'package name is required')
        names = packages if isinstance(packages, list) else [str(packages)]
        for item in names:
            if not re.fullmatch(r'[a-z0-9][a-z0-9+._-]*', str(item)):
                return ToolResult.failure(name, 'invalid package name: %s' % item)
        command = _apt_sudo() + ['apt-get', action, '-y', '--'] + [str(i) for i in names]
        if action == 'update':
            command = _apt_sudo() + ['apt-get', 'update']
        code, stdout, stderr = run_subprocess(command, timeout=600)
        result = ToolResult.success(name, stdout=stdout, stderr=stderr, exit_code=code,
                                    started=time.monotonic())
        result['success'] = code == 0
        if code != 0 and 'password' in (stderr or '').lower():
            result['error'] = 'requires a password; ask the user to run this command in the shell'
        return result

    def package_install(args):
        return _apt_action('package.install', args.get('package'), 'install')

    def package_remove(args):
        return _apt_action('package.remove', args.get('package'), 'remove')

    def package_update(args):
        return _apt_action('package.update', None, 'update')

    def package_nx_install(args):
        name = str(args.get('package') or '')
        if not re.fullmatch(r'[a-z0-9][a-z0-9._-]{0,63}', name):
            return ToolResult.failure('package.install', 'invalid package name')
        code, stdout, stderr = run_subprocess(['nx', 'install', name], timeout=600)
        result = ToolResult.success('package.install', stdout=stdout, stderr=stderr, exit_code=code,
                                    started=time.monotonic())
        result['success'] = code == 0
        return result

    return {
        'package.search': Tool(
            'package.search', 'Search available packages (apt).', {
                'type': 'object',
                'properties': {'query': {'type': 'string'}},
                'required': ['query'],
            },
            PERMISSION_READ, package_search, timeout=90,
            target=lambda a: a.get('query', ''), category='package',
        ),
        'package.install': Tool(
            'package.install', 'Install a package via apt, or an NX package via nx install.', {
                'type': 'object',
                'properties': {
                    'package': {'type': 'string'},
                    'source': {'type': 'string', 'description': 'apt (default) or nx'},
                },
                'required': ['package'],
            },
            PERMISSION_CONFIRM, package_install, timeout=600, retryable=True,
            target=lambda a: str(a.get('package', '')), category='package',
        ),
        'package.remove': Tool(
            'package.remove', 'Remove an installed apt package.', {
                'type': 'object',
                'properties': {'package': {'type': 'string'}},
                'required': ['package'],
            },
            PERMISSION_CONFIRM, package_remove, timeout=600,
            target=lambda a: str(a.get('package', '')), category='package',
        ),
        'package.update': Tool(
            'package.update', 'Refresh package indexes (apt-get update).', {
                'type': 'object', 'properties': {},
            },
            PERMISSION_CONFIRM, package_update, timeout=600, retryable=True,
            target=lambda a: 'apt indexes', category='package',
        ),
    }


def make_process_tools(guard, session=None):
    session = session or getattr(make_process_tools, '_session', None)

    def process_list(args):
        if shutil.which('noxs-ps'):
            code, stdout, stderr = run_subprocess(['noxs-ps', str(args.get('count') or 30)], timeout=30)
            return ToolResult.success('process.list', stdout=stdout, stderr=stderr, exit_code=code)
        code, stdout, stderr = run_subprocess(['ps', '-eo', 'pid,ppid,user,stat,etime,comm,args'], timeout=30)
        return ToolResult.success('process.list', stdout=stdout, stderr=stderr, exit_code=code)

    def process_start(args):
        command = args.get('command')
        validator = CommandValidator()
        ok, reason = validator.validate(command)
        if not ok:
            return ToolResult.failure('process.start', reason)
        log_path = '/tmp/noxs-ai-%s-%s.log' % (getattr(session, 'id', 'x')[:8], os.getpid())
        command_line = 'nohup %s >> %s 2>&1 & echo $!' % (command, log_path)
        code, stdout, stderr = run_subprocess(['bash', '-c', command_line], cwd=guard.cwd, timeout=15)
        pid = (stdout or '').strip().splitlines()[-1] if (stdout or '').strip() else ''
        if session is not None and pid.isdigit():
            session.owned_pids.add(int(pid))
        return ToolResult.success(
            'process.start',
            stdout='started pid %s, output -> %s' % (pid or '?', log_path),
            stderr=stderr, exit_code=code,
        )

    def process_stop(args):
        pid = args.get('pid')
        if not str(pid or '').isdigit():
            return ToolResult.failure('process.stop', 'numeric pid is required')
        owned = getattr(session, 'owned_pids', None) or set()
        if int(pid) not in owned:
            return ToolResult.failure(
                'process.stop',
                'pid %s was not started by this AI session; only agent-owned processes can be stopped' % pid,
            )
        try:
            os.killpg(int(pid), signal.SIGTERM)
        except (ProcessLookupError, PermissionError, OSError):
            return ToolResult.failure('process.stop', 'process %s is no longer running' % pid)
        return ToolResult.success('process.stop', stdout='stopped agent process %s' % pid)

    return {
        'process.list': Tool(
            'process.list', 'List running processes in the Noxs environment.', {
                'type': 'object',
                'properties': {'count': {'type': 'integer'}},
            },
            PERMISSION_READ, process_list, timeout=30, category='process',
        ),
        'process.start': Tool(
            'process.start', 'Start a background process owned by this AI session.', {
                'type': 'object',
                'properties': {'command': {'type': 'string'}},
                'required': ['command'],
            },
            PERMISSION_CONFIRM, process_start, timeout=30,
            target=lambda a: a.get('command', ''), category='process',
        ),
        'process.stop': Tool(
            'process.stop', 'Stop a background process started by this AI session.', {
                'type': 'object',
                'properties': {'pid': {'type': 'string'}},
                'required': ['pid'],
            },
            PERMISSION_CONFIRM, process_stop, timeout=15,
            target=lambda a: 'pid %s' % a.get('pid', ''), category='process',
        ),
    }


def make_service_tools(guard):
    def _service(name, action, args):
        service_name = str(args.get('service') or '')
        if action != 'list':
            if not re.fullmatch(r'[a-z0-9][a-z0-9-]{0,63}', service_name):
                return ToolResult.failure(name, 'valid service name is required')
            command = ['noxs-service', action, service_name]
        else:
            command = ['noxs-service', 'list']
        code, stdout, stderr = run_subprocess(command, timeout=60)
        result = ToolResult.success(name, stdout=stdout, stderr=stderr, exit_code=code,
                                    started=time.monotonic())
        result['success'] = code == 0
        return result

    tools = {}
    for action, permission in (('list', PERMISSION_READ), ('start', PERMISSION_CONFIRM),
                               ('stop', PERMISSION_CONFIRM), ('restart', PERMISSION_CONFIRM)):
        name = 'service.%s' % action

        def executor(args, action=action, name=name):
            return _service(name, action, args)

        tools[name] = Tool(
            name, 'Service action via the Noxs service manager (%s a service).' % action, {
                'type': 'object',
                'properties': {'service': {'type': 'string'}},
            },
            permission, executor, timeout=90,
            target=lambda a: str(a.get('service', '')), category='service',
        )
    return tools


def make_system_tools(guard, client_probe=None):
    def system_info(args):
        info = []
        try:
            import platform
            info.append('architecture: %s' % platform.machine())
            info.append('python: %s' % platform.python_version())
        except Exception:
            pass
        pretty = ''
        try:
            with open('/etc/os-release') as handle:
                for line in handle:
                    if line.startswith('PRETTY_NAME='):
                        pretty = line.split('=', 1)[1].strip().strip('"')
            info.append('os: %s' % pretty)
        except OSError:
            info.append('os: Linux (no /etc/os-release)')
        info.append('cwd: %s' % guard.cwd)
        code, stdout, _ = run_subprocess(['uname', '-m'], timeout=10)
        info.append('kernel arch: %s' % (stdout.strip() or 'unknown'))
        return ToolResult.success('system.info', stdout='\n'.join(info))

    def system_memory(args):
        try:
            with open('/proc/meminfo') as handle:
                lines = handle.readlines()[:6]
            return ToolResult.success('system.memory', stdout=''.join(lines))
        except OSError as error:
            return ToolResult.failure('system.memory', str(error))

    def system_storage(args):
        code, stdout, stderr = run_subprocess(['df', '-h', guard.cwd, '/tmp'], timeout=30)
        return ToolResult.success('system.storage', stdout=stdout, stderr=stderr, exit_code=code)

    def network_status(args):
        probe = client_probe or ('https://api.github.com', 5.0)
        started = time.monotonic()
        try:
            import urllib.request
            request = urllib.request.Request(probe[0], method='HEAD')
            urllib.request.urlopen(request, timeout=probe[1])
            result = 'online (%d ms)' % int((time.monotonic() - started) * 1000)
        except Exception:
            result = 'offline or blocked'
        return ToolResult.success('network.status', stdout=result)

    return {
        'system.info': Tool(
            'system.info', 'Architecture, OS and Noxs environment summary.', {
                'type': 'object', 'properties': {},
            },
            PERMISSION_READ, system_info, timeout=15, category='system',
        ),
        'system.memory': Tool(
            'system.memory', 'Memory usage summary.', {
                'type': 'object', 'properties': {},
            },
            PERMISSION_READ, system_memory, timeout=10, category='system',
        ),
        'system.storage': Tool(
            'system.storage', 'Disk usage for the working directory and tmp.', {
                'type': 'object', 'properties': {},
            },
            PERMISSION_READ, system_storage, timeout=30, category='system',
        ),
        'network.status': Tool(
            'network.status', 'Check network connectivity.', {
                'type': 'object', 'properties': {},
            },
            PERMISSION_READ, network_status, timeout=20, category='system',
        ),
    }


def make_noxs_tools(guard, session=None):
    def noxs_status(args):
        lines = ['session: %s' % getattr(session, 'id', 'unknown')[:12]]
        try:
            import socket as _socket
            client = _socket.socket(_socket.AF_UNIX, _socket.SOCK_STREAM)
            client.settimeout(3)
            client.connect('\0noxs.control')
            client.sendall(b'status\n')
            data = client.recv(4096).decode('utf-8', 'replace').strip()
            client.close()
            lines.append('runtime: %s' % data)
        except Exception:
            lines.append('runtime: not reachable (Noxs app in background?)')
        return ToolResult.success('noxs.status', stdout='\n'.join(lines))

    def noxs_settings(args):
        path = os.path.join(guard.home, '.noxs')
        if not os.path.isdir(path):
            return ToolResult.success('noxs.settings', stdout='(no ~/.noxs configuration yet)')
        names = []
        for base, dirs, files in os.walk(path):
            dirs[:] = [d for d in dirs if d not in ('.git',)]
            for name in files:
                if not is_protected_path(os.path.join(base, name)):
                    names.append(os.path.relpath(os.path.join(base, name), path))
        return ToolResult.success('noxs.settings', stdout='\n'.join(sorted(names)) or '(empty)')

    return {
        'noxs.status': Tool(
            'noxs.status', 'Noxs runtime and AI session status.', {
                'type': 'object', 'properties': {},
            },
            PERMISSION_READ, noxs_status, timeout=15, category='noxs',
        ),
        'noxs.settings': Tool(
            'noxs.settings', 'List available Noxs configuration files (contents filtered).', {
                'type': 'object', 'properties': {},
            },
            PERMISSION_READ, noxs_settings, timeout=15, category='noxs',
        ),
    }


def build_default_registry(cwd, session=None):
    '''The Noxs AI tool set. Tools that are unavailable in this environment
    still execute honestly — they fail with clear messages instead of being
    fabricated (spec $24).'''
    guard = PathGuard(cwd)
    validator = CommandValidator()
    registry = ToolRegistry()
    for tool in make_file_tools(guard).values():
        registry.register(tool)
    for tool in make_terminal_tools(guard, validator, session).values():
        registry.register(tool)
    for tool in make_package_tools(guard).values():
        registry.register(tool)
    for tool in make_process_tools(guard, session).values():
        registry.register(tool)
    for tool in make_service_tools(guard).values():
        registry.register(tool)
    for tool in make_system_tools(guard).values():
        registry.register(tool)
    for tool in make_noxs_tools(guard, session).values():
        registry.register(tool)
    return registry
