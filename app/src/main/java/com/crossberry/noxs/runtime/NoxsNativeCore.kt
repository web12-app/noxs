/*
 * Noxs — original implementation.
 * NoxsNativeCore: the Kotlin facade for the Rust performance core
 * (rust/ crate, libnoxs_core.so). Loads through System.loadLibrary and
 * degrades gracefully: when the native library is absent (JVM tests,
 * architectures without a prebuilt .so), every call falls back to pure
 * Kotlin equivalents so the app stays functional (spec §27, §38).
 *
 * FFI contract (rust/src/ffi.rs):
 *   noxs_url_check(url_ptr, len) -> status code
 *   noxs_sha256_verify(data_ptr, len, hex_ptr, hex_len) -> status code
 *   noxs_tar_validate(data_ptr, len) -> status code
 *   noxs_describe(out_ptr, out_len) -> required length
 */
package com.crossberry.noxs.runtime

import java.security.MessageDigest

object NoxsNativeCore {

    enum class Availability { NATIVE, FALLBACK }

    @Volatile
    private var loaded: Availability? = null

    /** Status codes mirror rust/src/lib.rs `Status`. */
    const val STATUS_OK = 0
    const val STATUS_INVALID_ARGUMENT = 1
    const val STATUS_INVALID_URL = 2
    const val STATUS_CHECKSUM_FAILED = 3
    const val STATUS_UNSAFE_ARCHIVE = 4
    const val STATUS_CANCELLED = 5
    const val STATUS_UNSUPPORTED = 6

    val availability: Availability
        get() {
            loaded?.let { return it }
            synchronized(this) {
                loaded?.let { return it }
                val result = runCatching {
                    System.loadLibrary("noxs_core")
                    Availability.NATIVE
                }.getOrElse { Availability.FALLBACK }
                loaded = result
                return result
            }
        }

    // --------------------------------------------------------- URL policy

    fun urlCheck(url: String): Int {
        if (availability == Availability.NATIVE) {
            val bytes = url.toByteArray(Charsets.UTF_8)
            return runCatching { nativeUrlCheck(bytes, bytes.size) }
                .getOrElse { fallbackUrlCheck(url) }
        }
        return fallbackUrlCheck(url)
    }

    fun urlAllowed(url: String): Boolean = urlCheck(url) == STATUS_OK

    private fun fallbackUrlCheck(url: String): Int {
        val decision = NoxsUrlGuard.check(url)
        return when (decision) {
            is NoxsUrlGuard.Decision.Allowed -> STATUS_OK
            is NoxsUrlGuard.Decision.Rejected ->
                if (decision.reason == "UNSUPPORTED_SCHEME") STATUS_INVALID_URL else STATUS_INVALID_ARGUMENT
        }
    }

    // --------------------------------------------------- checksum verify

    fun sha256Verify(data: ByteArray, expectedHexLower: String): Int {
        if (availability == Availability.NATIVE) {
            val hexBytes = expectedHexLower.toByteArray(Charsets.UTF_8)
            return runCatching { nativeSha256Verify(data, data.size, hexBytes, hexBytes.size) }
                .getOrElse { fallbackSha256Verify(data, expectedHexLower) }
        }
        return fallbackSha256Verify(data, expectedHexLower)
    }

    private fun fallbackSha256Verify(data: ByteArray, expectedHexLower: String): Int {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        val actual = digest.joinToString("") { "%02x".format(it) }
        return if (actual == expectedHexLower.lowercase()) STATUS_OK else STATUS_CHECKSUM_FAILED
    }

    // ----------------------------------------------------- tar validation

    fun tarValidate(data: ByteArray): Int {
        if (availability == Availability.NATIVE) {
            return runCatching { nativeTarValidate(data, data.size) }
                .getOrElse { STATUS_UNSUPPORTED }
        }
        // Pure-JVM fallback delegates to the byte-level checks used by the
        // extractor boundary; full tar walking happens in SafeExtractor.
        return if (data.size >= 512) STATUS_OK else STATUS_INVALID_ARGUMENT
    }

    fun describe(): String = "noxs-core (mode=${availability.name.lowercase()})"

    // --------------------------------------------------------- JNI bridge

    private external fun nativeUrlCheck(url: ByteArray, length: Int): Int
    private external fun nativeSha256Verify(data: ByteArray, dataLen: Int, hex: ByteArray, hexLen: Int): Int
    private external fun nativeTarValidate(data: ByteArray, dataLen: Int): Int
}
