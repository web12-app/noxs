/*
 * Noxs — original implementation.
 * Official checksum-file parsing (spec §12): every environment that publishes
 * official checksums uses them. Supports the standard `<hash>  <filename>`
 * format used by Ubuntu SHA256SUMS, Kali SHA256SUMS and Arch .MD5SUMS files.
 */
package com.crossberry.noxs.environments.download

/** Algorithm of an official checksum file. SHA-256 preferred; MD5 only when it is the official one. */
enum class ChecksumAlgorithm(val displayName: String) {
    SHA256("SHA-256"), MD5("MD5")
}

data class ChecksumEntry(val algorithm: ChecksumAlgorithm, val hash: String, val fileName: String)

object ChecksumsFile {

    /**
     * Parses a SUMS file. Accepts both `hash  name` and `hash *name`
     * (binary marker) separators and strips BSD-style `MD5 (name) = hash`.
     */
    fun parse(content: String, algorithm: ChecksumAlgorithm): List<ChecksumEntry> {
        val entries = mutableListOf<ChecksumEntry>()
        content.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach

            // BSD style: MD5 (file.tar.gz) = 8f3a...
            val bsd = Regex("^(?:MD5|SHA256|SHA-256)\\s*\\(([^)]+)\\)\\s*=\\s*([0-9a-fA-F]{32,64})$")
            bsd.find(line)?.let { m ->
                entries += ChecksumEntry(algorithm, m.groupValues[2].lowercase(), m.groupValues[1].trim())
                return@forEach
            }

            val parts = line.split(Regex("\\s+"), limit = 2)
            if (parts.size == 2) {
                val hash = parts[0].removePrefix("*").trim().lowercase()
                val name = parts[1].removePrefix("*").trim()
                val hexOk = hash.length in 32..64 && hash.all { it in "0123456789abcdef" }
                if (hexOk && name.isNotEmpty()) {
                    entries += ChecksumEntry(algorithm, hash, name)
                }
            }
        }
        return entries
    }

    /** Checksum for a specific artifact file name, or null when absent. */
    fun forFile(content: String, algorithm: ChecksumAlgorithm, fileName: String): String? =
        parse(content, algorithm).firstOrNull { it.fileName == fileName }?.hash

    /** Verifies the hash length against the algorithm and returns it normalized. */
    fun validate(entry: ChecksumEntry): Boolean = when (entry.algorithm) {
        ChecksumAlgorithm.SHA256 -> entry.hash.length == 64
        ChecksumAlgorithm.MD5 -> entry.hash.length == 32
    }
}
