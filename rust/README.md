# noxs-core — Rust Performance Core

The Noxs high-performance core (Noxs platform spec §27): package engine,
checksum verification, archive validation, secure-extraction policy,
dependency resolution, download orchestration and the background task
engine. Kotlin remains responsible for Android orchestration; C++ stays
limited to PTY/terminal-native work; Rust owns everything where
correctness, concurrency or throughput matter.

## Module map

| module     | responsibility                                             |
|------------|------------------------------------------------------------|
| validation | URL / package-id / semver policy (mirrors NoxsUrlGuard)    |
| security   | path traversal + symlink escape policy                     |
| checksum   | pure-Rust SHA-256, constant-time verification              |
| archive    | ustar member safety walker (runs BEFORE extraction)        |
| download   | validated sinks, retry policy, cancellation, digest-first  |
| dependency | topological resolver (deterministic, cycle-detecting)      |
| package    | `.nx.pkg` asset naming + architecture matching             |
| tasks      | task engine: queued/running/paused/completed/failed/cancelled |
| logs       | bounded severity-filtered ring (unbounded logs forbidden)  |
| runtime    | version metadata                                           |
| ffi        | stable C ABI (`noxs_url_check`, `noxs_sha256_verify`, `noxs_tar_validate`, `noxs_describe`) |

## Design rules

- **Zero external dependencies** — builds offline for aarch64, x86_64 and
  armv7; every line is auditable inside this repository.
- **No panics across FFI** — every export returns a `Status` code.
- **HTTPS stays on Android** — `DownloadManager` owns the transport with
  its certificate validation; Rust consumes validated streams and enforces
  size limits, retries, cancellation and digest verification
  (checksum-before-rename, never bypassed).
- **Verification is fatal** — checksum mismatch or unsafe archive members
  always stop the pipeline; nothing is ever skipped "to make it work".

## Build

    cargo test          # full test suite
    cargo build --release   # libnoxs_core.so (cdylib) + rlib

The Android side loads the library through
`app/src/main/java/com/crossberry/noxs/runtime/NoxsNativeCore.kt`, which
falls back to pure-Kotlin implementations when the `.so` is not present
for the current ABI — the app never breaks because of a missing native
artifact.

## Attribution

Crossberry / web12-app — the Noxs project.
