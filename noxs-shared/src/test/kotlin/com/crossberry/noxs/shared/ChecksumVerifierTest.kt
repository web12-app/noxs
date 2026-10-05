package com.crossberry.noxs.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecksumVerifierTest {

    @Test fun `known sha256 of empty input`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ChecksumVerifier.sha256Hex("".byteInputStream())
        )
    }

    @Test fun `known sha256 of abc`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ChecksumVerifier.sha256Hex("abc".byteInputStream())
        )
    }

    @Test fun `match comparison is case insensitive`() {
        assertTrue(ChecksumVerifier.matches("ABCDEF", "abcdef"))
        assertFalse(ChecksumVerifier.matches("abc", "abd"))
        assertFalse(ChecksumVerifier.matches("abc", "abcd"))
        assertFalse(ChecksumVerifier.matches("", "abc"))
        assertFalse(ChecksumVerifier.matches("abc", ""))
    }

    @Test fun `large stream matches incremental read`() {
        val data = ByteArray(300 * 1024) { (it % 251).toByte() }
        val hex = ChecksumVerifier.sha256Hex(data.inputStream())
        assertTrue(hex.length == 64 && hex.all { it in "0123456789abcdef" })
    }
}
