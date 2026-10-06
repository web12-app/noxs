//! noxs-core — the Noxs high-performance core (original implementation).
//!
//! Module map (Noxs platform spec §27):
//!
//! | module       | responsibility                                            |
//! |--------------|-----------------------------------------------------------|
//! | validation   | URL + package-id policy (single source of truth for FFI)  |
//! | security     | path traversal / symlink escape / manifest policy         |
//! | checksum     | SHA-256 (pure Rust) + digest verification                 |
//! | archive      | tar member safety walker used before any extraction       |
//! | download     | validated chunk sinks, retry policy, cancellation         |
//! | dependency   | topological resolver for package dependency graphs        |
//! | package      | package manifest policies layered over validation         |
//! | tasks        | task engine: queued/running/paused/completed/failed/cancelled |
//! | logs         | bounded, severity-filtered log ring                       |
//! | runtime      | build/runtime metadata                                    |
//! | ffi          | stable C ABI surface for the Kotlin bridge                |
//!
//! Rules:
//!  - no panics across the FFI boundary; every export returns a status code
//!  - no secrets, internal paths or stack traces in error payloads
//!  - HTTPS transport stays on the Android side (DownloadManager); Rust
//!    validates and consumes already-validated streams
#![forbid(unsafe_op_in_unsafe_fn)]

pub mod archive;
pub mod checksum;
pub mod dependency;
pub mod download;
pub mod ffi;
pub mod logs;
pub mod package;
pub mod runtime;
pub mod security;
pub mod tasks;
pub mod validation;

/// FFI status codes shared by every export (spec §32: deterministic errors).
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Status {
    Ok = 0,
    InvalidArgument = 1,
    InvalidUrl = 2,
    ChecksumFailed = 3,
    UnsafeArchive = 4,
    Cancelled = 5,
    Unsupported = 6,
}

impl Status {
    pub fn from_code(code: i32) -> Option<Status> {
        Some(match code {
            0 => Status::Ok,
            1 => Status::InvalidArgument,
            2 => Status::InvalidUrl,
            3 => Status::ChecksumFailed,
            4 => Status::UnsafeArchive,
            5 => Status::Cancelled,
            6 => Status::Unsupported,
            _ => return None,
        })
    }
}
