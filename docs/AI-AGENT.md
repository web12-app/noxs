# Noxs AI Agent Terminal — `nx ai`

An interactive, multi-tool AI agent built into the Noxs terminal
(original Noxs implementation, Noxs-only branding).

    $ nx ai

    nx@ai> hello
    _> Hello, I am the Noxs AI Agent.

    nx@ai> ls
    _> Wait, I'll read the directory.

       {-_-} file.list

    _> app.py
       package.json
       README.md
       src/

One-shot mode:

    nx ai "check node version"      # start -> tools -> answer -> exit

## Architecture

    User
     ↓
    AI model (provider-independent client)
     ↓
    Agent decision (planner loop, bounded)
     ↓
    Tool call(s)  {validated against the Tool Registry}
     ↓
    Noxs Tool Executor (existing noxs tooling — no duplicate systems)
     ↓
    Tool result {normalized, truncated, secret-filtered}
     ↓
    AI model → next tool call or final answer

Runtime layout (guest side, installed by the Noxs app):

    /usr/local/bin/nx                 dispatcher: nx ai -> ai-lib.sh
    /usr/local/lib/noxs-pkg/ai-lib.sh runtime checks + exec (python3)
    /usr/local/lib/noxs/ai/agent.py   REPL, agent loop, limits, slash commands
    /usr/local/lib/noxs/ai/provider.py provider-independent AI client
    /usr/local/lib/noxs/ai/tools.py   central Tool Registry + executors

The runtime is Python (standard library only — no pip dependencies): an
agent loop needs real JSON parsing, SSE streaming, threads and signals.
`nx ai` ensures python3 with the same honest auto-install pattern as the
NX Package System.

## Model configuration (spec §3, §26-§27)

Provider-independent by design — the provider, endpoint, model and key
environment variable are configuration, never agent code:

    ~/.noxs/ai/config.json

    {
      "enabled": true,
      "provider": "kilo",
      "model": "kilo-auto/free",
      "stream": true,
      "max_steps": 30,
      "max_tool_calls": 50,
      "max_parallel_tools": 4
    }

Environment overrides: `NOXS_AI_MODEL`, `NOXS_AI_BASE_URL`,
`NOXS_AI_PROVIDER`, `NOXS_AI_API_KEY_ENV`. The API key is read only from
the named environment variable and is **never** logged, printed, or
included in errors. No provider keys are embedded anywhere in Noxs.

Client behavior: streaming (SSE) and non-streaming, bounded exponential
backoff (default 3 retries), HTTP 429 honors `Retry-After`, timeouts,
provider-error mapping, model fallback through configuration, and
cooperative cancellation.

## Tools (spec §5-§8)

28 tools across terminal, file, package, process, service, system and
noxs categories. Every tool defines name, description, input schema,
permission level, timeout, executor, result schema and cancellation
support. Independent READ-level tools in one batch run in parallel
(bounded by `max_parallel_tools`); everything else runs sequentially so
dependent or conflicting actions can never race.

Permission levels (spec §9):

| level   | tools                                                                     |
|---------|---------------------------------------------------------------------------|
| READ    | file.list/read/stat/search, system.*, process.list, network.status, package.search, service.list, terminal.read, noxs.* |
| CONFIRM | terminal.run, file.write/move/copy/delete, package.install/remove/update, service.start/stop/restart, process.start/stop, terminal.write |

DENY by default: operations outside the Noxs filesystem scope, protected
credential files (`~/.ssh/`, `.gnupg/`, `.aws/`, `/etc/shadow`, ...),
destructive system commands, secret extraction. Confirmation is controlled
by Noxs and can never be simulated by the model:

    ⚠ Action requires permission
    Tool: file.delete
    Target: 24 files
    Allow? [y/N]

`terminal.run` commands pass a validator (destructive blocklist, length
cap), run with a minimal environment, enforced working directory, timeout
and output limits, and can always be cancelled.

## Safety and limits (spec §14-§17, §20)

- MAX_AGENT_STEPS=30, MAX_TOOL_CALLS=50, MAX_PARALLEL_TOOLS=4,
  MAX_TOOL_OUTPUT≈12k chars, MAX_RUNTIME=900s — all configurable.
- Tool results are normalized (`success/tool/exit_code/stdout/stderr/duration_ms`),
  truncated head+tail, and secret-redacted before reaching the model.
- Context compaction summarizes old tool payloads; the system prompt
  exposes only safe environment facts (arch, OS, cwd, tools) — never
  unrestricted environment variables.
- Each session has a unique id; exit cancels the active request, kills
  agent-owned processes (process groups) and releases temporary resources.
- Safe debug logging to `~/.noxs/ai/agent.log`: session, tool, duration,
  outcome — never keys, passwords, or file contents.

## Agent commands (spec §23)

    /help /tools /model /status /context /permissions /stop /clear /reset /exit

Ctrl+C cancels the current model request or tool and returns to `nx@ai>`.
Ctrl+D, `exit`, `quit` or `/exit` ends the session cleanly.

## Tests

- `scripts/test_nx_ai.py` — 35 offline tests (fake provider): agent loop,
  sequential/parallel tools, tool failure, permission gate, denial, step
  limit, unknown tool, invalid arguments, retry, rate limit, timeout,
  cancellation, secret filtering, protected paths, command validation.
- `scripts/test.sh --ai` wires them into CI.
- `NoxsNxAiTest` (JVM) verifies installation, dispatcher wiring, default
  model route, and that no provider keys are embedded.

Attribution: Crossberry / web12-app — the Noxs project.
