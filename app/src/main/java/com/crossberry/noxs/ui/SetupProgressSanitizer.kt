package com.crossberry.noxs.ui

/** Converts internal bootstrap output into a small, safe set of setup messages. */
internal object SetupProgressSanitizer {
    fun message(raw: String): String? {
        val lower = raw.lowercase()
        if (lower.contains("http://") || lower.contains("https://") ||
            lower.contains("rootfs url") || lower.contains("proot bundled") ||
            lower.startsWith("arch=") || lower.contains("extracted files=") ||
            lower.contains("status-old") || lower.contains("permission denied") ||
            lower.contains("password set")) return null

        return when {
            lower.contains("resuming") -> "Resuming your download"
            lower.contains("mirror") -> "Trying an alternate download server"
            lower.contains("retry") || lower.contains("reconnect") -> "Reconnecting…"
            lower.contains("download") -> "Downloading your Linux environment"
            lower.contains("checksum") || lower.contains("verif") -> "Checking downloaded files"
            lower.contains("extract") -> "Preparing your files"
            lower.contains("storage") -> "Checking free storage"
            lower.contains("apt") || lower.contains("dpkg") || lower.contains("package") ||
                lower.contains("certificate") || lower.contains("metadata") || lower.contains("https") ||
                lower.contains("http") -> "Preparing secure connections"
            lower.contains("password") || lower.contains("account") -> "Creating your Linux account"
            else -> null
        }
    }
}
