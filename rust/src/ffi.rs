//! ffi — the stable C ABI surface exposed to Kotlin (spec §27).
//!
//! Contract:
//!  - every export is `extern "C"`, never panics across the boundary and
//!    returns a [`Status`] code
//!  - output buffers are caller-owned; exports write at most `out_len`
//!    bytes and return the required length so the caller can size buffers
//!  - no secrets, no internal paths, no stack traces in outputs
//!
//! The Kotlin side loads `libnoxs_core.so` through `System.loadLibrary`
//! behind the NoxsNativeCore facade and degrades gracefully when the
//! library is absent (pure-JVM fallbacks keep the app functional).

use crate::archive;
use crate::checksum;
use crate::validation;
use crate::Status;

/// Check a URL against the Noxs load policy. Returns a [`Status`] code.
#[no_mangle]
pub extern "C" fn noxs_url_check(url: *const u8, url_len: usize) -> i32 {
    let Some(url) = slice_from_raw(url, url_len) else {
        return Status::InvalidArgument as i32;
    };
    match std::str::from_utf8(url) {
        Ok(text) => match validation::check_url(text) {
            validation::UrlDecision::Allowed => Status::Ok as i32,
            validation::UrlDecision::Rejected => Status::InvalidUrl as i32,
        },
        Err(_) => Status::InvalidArgument as i32,
    }
}

/// Verify SHA-256: `data` vs expected lowercase hex in `expected_hex`
/// (exactly 64 bytes). Returns `Status::Ok` on match, `CHECKSUM_FAILED`
/// otherwise. Verification is never bypassed.
#[no_mangle]
pub extern "C" fn noxs_sha256_verify(
    data: *const u8,
    data_len: usize,
    expected_hex: *const u8,
    expected_len: usize,
) -> i32 {
    let (Some(bytes), Some(expected)) = (
        slice_from_raw(data, data_len),
        slice_from_raw(expected_hex, expected_len),
    ) else {
        return Status::InvalidArgument as i32;
    };
    let Ok(expected_text) = std::str::from_utf8(expected) else {
        return Status::InvalidArgument as i32;
    };
    if checksum::verify(expected_text, bytes) {
        Status::Ok as i32
    } else {
        Status::ChecksumFailed as i32
    }
}

/// Validate a tar stream (already in memory) before extraction.
/// Returns `Status::Ok` when every member passes the safety walker.
#[no_mangle]
pub extern "C" fn noxs_tar_validate(data: *const u8, data_len: usize) -> i32 {
    let Some(bytes) = slice_from_raw(data, data_len) else {
        return Status::InvalidArgument as i32;
    };
    match archive::validate_tar(bytes) {
        Ok(_) => Status::Ok as i32,
        Err(status) => status as i32,
    }
}

/// Write the core description into `out`; returns the required length
/// (callers can retry with a bigger buffer when it exceeds `out_len`).
///
/// # Safety
/// The caller guarantees `out` points to `out_len` writable bytes; null or
/// zero-length inputs are rejected inside.
#[no_mangle]
#[allow(clippy::not_unsafe_ptr_arg_deref)]
pub extern "C" fn noxs_describe(out: *mut u8, out_len: usize) -> usize {
    let text = crate::runtime::describe();
    if out.is_null() {
        return text.len();
    }
    let Some(buffer) = (unsafe { unsafe_out(out, out_len) }) else {
        return text.len();
    };
    let take = text.len().min(out_len);
    buffer[..take].copy_from_slice(&text.as_bytes()[..take]);
    text.len()
}

// ------------------------------------------------------------------ helpers

/// Raw pointer to slice, guarded: null and zero-length inputs fail cleanly.
fn slice_from_raw(pointer: *const u8, len: usize) -> Option<&'static [u8]> {
    if pointer.is_null() || len == 0 {
        return None;
    }
    // The caller guarantees the buffer lives for the duration of the call;
    // the guard above rejects the only inputs that could be invalid here.
    Some(unsafe { std::slice::from_raw_parts(pointer, len) })
}

/// Mutable raw pointer to slice, guarded the same way.
///
/// # Safety
/// The caller guarantees `out` points to `out_len` writable bytes for the
/// duration of the call.
unsafe fn unsafe_out(out: *mut u8, out_len: usize) -> Option<&'static mut [u8]> {
    if out.is_null() || out_len == 0 {
        return None;
    }
    Some(unsafe { std::slice::from_raw_parts_mut(out, out_len) })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn url_check_codes() {
        let ok = b"https://example.com";
        assert_eq!(noxs_url_check(ok.as_ptr(), ok.len()), Status::Ok as i32);
        let bad = b"javascript:alert(1)";
        assert_eq!(noxs_url_check(bad.as_ptr(), bad.len()), Status::InvalidUrl as i32);
        assert_eq!(noxs_url_check(std::ptr::null(), 8), Status::InvalidArgument as i32);
    }

    #[test]
    fn checksum_codes() {
        let data = b"payload";
        let expected = checksum::hex(&checksum::sha256(data));
        assert_eq!(
            noxs_sha256_verify(data.as_ptr(), data.len(), expected.as_ptr(), expected.len()),
            Status::Ok as i32
        );
        let wrong = "0".repeat(64);
        assert_eq!(
            noxs_sha256_verify(data.as_ptr(), data.len(), wrong.as_ptr(), wrong.len()),
            Status::ChecksumFailed as i32
        );
    }
}
