/*
 * Noxs — original implementation.
 * Streaming SHA-256 verification for bootstrap archives and binaries.
 */
package com.noxs.linux.shared

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

object ChecksumVerifier {

    private val HEX = "0123456789abcdef".toCharArray()

    fun sha256Hex(file: File): String {
        FileInputStream(file).use { return sha256Hex(it) }
    }

    fun sha256Hex(stream: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return toHex(md.digest())
    }

    /** Length-safe, constant-time-ish comparison of two hex digests. */
    fun matches(expectedHex: String, actualHex: String): Boolean {
        val a = expectedHex.trim().lowercase()
        val b = actualHex.trim().lowercase()
        if (a.isEmpty() || b.isEmpty()) return false
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    fun verify(file: File, expectedHex: String): Result = matches(expectedHex, sha256Hex(file)).let { ok ->
        if (ok) Result.Verified(sha256Hex(file)) else Result.Mismatch(sha256Hex(file))
    }

    sealed class Result {
        data class Verified(val sha256: String) : Result()
        data class Mismatch(val actualSha256: String) : Result()
    }

    private fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(out)
    }
}
