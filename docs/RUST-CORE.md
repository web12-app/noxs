# Noxs Rust Performance Core

Rust owns the performance-critical and security-critical Noxs operations
(Noxs platform spec §27). Kotlin stays responsible for Android
orchestration; C++ remains limited to PTY/terminal-native components;
Python remains Linux-side tooling. Functionality is never duplicated
across languages.

## Module map

| module     | responsibility                                              |
|------------|-------------------------------------------------------------|
| validation | URL / package-id / semver policy (mirrors NoxsUrlGuard)      |
| security   | path traversal + symlink escape policy                       |
| checksum   | pure-Rust SHA-256, constant-time verification                |
| archive    | ustar member safety walker (runs BEFORE extraction)          |
| download   | validated sinks, retry policy, cancellation, digest-first    |
| dependency | topological resolver (deterministic, cycle-detecting)        |
| package    | `.nx.pkg` asset naming + architecture matching               |
| tasks      | task engine: queued/running/paused/completed/failed/cancelled |
| logs       | bounded severity-filtered ring (unbounded logs forbidden)    |
| ffi        | stable C ABI for the Kotlin bridge                           |

Full details: `rust/README.md`.

## FFI surface (stable)

    noxs_url_check(url_ptr, len)                                  -> status
    noxs_sha256_verify(data_ptr, len, hex_ptr, hex_len)            -> status
    noxs_tar_validate(data_ptr, len)                               -> status
    noxs_describe(out_ptr, out_len)                                -> required length

Rules: no panics across the boundary, every export returns a `Status`
code, outputs never contain secrets, internal paths or stack traces.

## Kotlin integration

`NoxsNativeCore.kt` loads `libnoxs_core.so` via `System.loadLibrary` and
falls back to pure-Kotlin implementations when the library is missing
(JVM tests, unmatched ABIs). The app must never break because a native
artifact is absent.

## Task engine (spec §29)

Every task carries taskId, state, progress, start/end time, error and
cancellation state. States: queued / running / paused / completed /
failed / cancelled. Cancellation is cooperative: long operations check
the cancel flag between work units and stop cleanly — Noxs never kills
unrelated processes. Long-running background work (installs, downloads,
environment setup) surfaces as a Noxs notification with Open / Stop
actions (spec §30).

## Performance rules (spec §35)

- measure before optimizing (startup time, terminal latency, FFI overhead,
  download throughput, memory/storage I/O)
- no unnecessary polling, duplicate threads, busy loops or large copies
- no blocking the Android UI thread or the WebView thread
- no unbounded logs or task queues; asynchronous everywhere for long work

## Build

    cargo test               # full suite (also runs in CI)
    cargo build --release    # produces libnoxs_core.so

Zero external dependencies: builds offline for aarch64, x86_64 and armv7.

Attribution: Crossberry / web12-app — the Noxs project.
