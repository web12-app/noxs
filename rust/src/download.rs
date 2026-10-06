//! download — validated sinks, retry policy and cancellation for transfers
//! (spec §27, §29-§30).
//!
//! HTTPS transport stays on the Android side (DownloadManager with its
//! certificate validation); the core consumes already-fetched byte streams,
//! enforces size limits, drives retries with exponential backoff, feeds
//! progress into the task engine and verifies digests before a download is
//! allowed to replace any file (checksum-before-rename, never bypassed).

use crate::checksum;
use crate::validation;
use std::io::Read;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DownloadOutcome {
    Completed,
    Cancelled,
    Failed,
}

/// Retry policy: bounded attempts, exponential backoff, honor cancel flag.
#[derive(Debug, Clone, Copy)]
pub struct RetryPolicy {
    pub max_attempts: u32,
    pub base_delay_ms: u64,
    pub max_delay_ms: u64,
}

impl Default for RetryPolicy {
    fn default() -> Self {
        RetryPolicy { max_attempts: 3, base_delay_ms: 500, max_delay_ms: 8_000 }
    }
}

impl RetryPolicy {
    pub fn delay_for_attempt(&self, attempt: u32) -> Duration {
        let shift = attempt.saturating_sub(1).min(5);
        let delay = self.base_delay_ms.saturating_mul(1u64 << shift);
        Duration::from_millis(delay.min(self.max_delay_ms))
    }
}

/// Progress reporter wired to the task engine.
pub type ProgressFn<'a> = dyn Fn(u64, u64) + Send + Sync + 'a;

/// Consume a validated stream into memory with hard limits + SHA-256.
/// The digest is returned so the caller can verify BEFORE renaming the
/// artifact into place.
pub fn read_into_buffer(
    mut stream: impl Read,
    max_bytes: u64,
    cancel: &AtomicUsize,
    progress: Option<&ProgressFn>,
) -> Result<(Vec<u8>, String), DownloadOutcome> {
    let mut buffer: Vec<u8> = Vec::new();
    let mut hasher = checksum::Sha256::new();
    let mut chunk = [0u8; 32 * 1024];
    loop {
        if cancel.load(Ordering::Relaxed) == 1 {
            return Err(DownloadOutcome::Cancelled);
        }
        match stream.read(&mut chunk) {
            Ok(0) => break,
            Ok(read) => {
                if buffer.len() as u64 + read as u64 > max_bytes {
                    return Err(DownloadOutcome::Failed);
                }
                hasher.update(&chunk[..read]);
                buffer.extend_from_slice(&chunk[..read]);
                if let Some(report) = progress {
                    report(buffer.len() as u64, max_bytes);
                }
            }
            Err(_) => return Err(DownloadOutcome::Failed),
        }
    }
    Ok((buffer, checksum::hex(&hasher.finish())))
}

/// Retry loop around any fetch closure (the Android transport supplies it).
pub fn fetch_with_retry<T>(
    policy: &RetryPolicy,
    cancel: &AtomicUsize,
    mut attempt: impl FnMut() -> Option<T>,
) -> Option<T> {
    for attempt_index in 1..=policy.max_attempts {
        if cancel.load(Ordering::Relaxed) == 1 {
            return None;
        }
        match attempt() {
            Some(value) => return Some(value),
            None => {
                if attempt_index == policy.max_attempts {
                    return None;
                }
                thread::sleep(policy.delay_for_attempt(attempt_index));
            }
        }
    }
    None
}

/// Validate a download URL against the Noxs policy before any byte moves.
pub fn url_allowed(url: &str) -> bool {
    validation::check_url(url) == validation::UrlDecision::Allowed
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;
    use std::sync::atomic::AtomicUsize;

    #[test]
    fn backoff_is_exponential_and_bounded() {
        let policy = RetryPolicy { max_attempts: 6, base_delay_ms: 100, max_delay_ms: 400 };
        assert_eq!(policy.delay_for_attempt(1), Duration::from_millis(100));
        assert_eq!(policy.delay_for_attempt(2), Duration::from_millis(200));
        assert_eq!(policy.delay_for_attempt(3), Duration::from_millis(400));
        assert_eq!(policy.delay_for_attempt(4), Duration::from_millis(400));
    }

    #[test]
    fn reads_digest_and_respects_limits() {
        let cancel = AtomicUsize::new(0);
        let data = b"noxs download payload";
        let result = read_into_buffer(Cursor::new(data.to_vec()), 1024, &cancel, None);
        let (buffer, digest) = result.expect("fits");
        assert_eq!(buffer, data.to_vec());
        assert_eq!(digest, checksum::hex(&checksum::sha256(data)));

        let too_big = read_into_buffer(Cursor::new(data.to_vec()), 8, &cancel, None);
        assert_eq!(too_big.unwrap_err(), DownloadOutcome::Failed);
    }

    #[test]
    fn cancel_stops_read() {
        let cancel = AtomicUsize::new(1);
        let result = read_into_buffer(Cursor::new(vec![0u8; 128]), 1024, &cancel, None);
        assert_eq!(result.unwrap_err(), DownloadOutcome::Cancelled);
    }

    #[test]
    fn retry_gives_up_after_max_attempts() {
        let policy = RetryPolicy { max_attempts: 3, base_delay_ms: 1, max_delay_ms: 2 };
        let mut calls = 0;
        let result: Option<u8> = fetch_with_retry(&policy, &AtomicUsize::new(0), || {
            calls += 1;
            None
        });
        assert!(result.is_none());
        assert_eq!(calls, 3);
    }
}
