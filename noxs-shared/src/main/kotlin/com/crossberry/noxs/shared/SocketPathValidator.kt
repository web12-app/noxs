/*
 * Noxs — original implementation.
 * Validation and mapping for unix-socket paths created inside the Debian
 * rootfs. Sockets are only permitted in the runtime directories (/run,
 * /var/run — including /var/run/noxs) and /tmp; this keeps the sandbox's
 * IPC surface small and lets the app map guest paths onto its private
 * storage (rootfs/var/run/noxs/host) without permitting arbitrary paths.
 */
package com.crossberry.noxs.shared

object SocketPathValidator {

    private val ALLOWED_PREFIXES = listOf(
        NoxsConstants.RUN_DIR + "/",          // /run/
        NoxsConstants.VAR_RUN_DIR + "/",      // /var/run/
        "/tmp/"
    )

    /** Lexical normalize: collapse '//', '/./' and resolve '..' without I/O. */
    fun normalize(path: String): String {
        val absolute = path.startsWith("/")
        val out = ArrayDeque<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeLast() // at root: ignored
                else -> out.addLast(segment)
            }
        }
        val joined = out.joinToString("/")
        return when {
            joined.isEmpty() -> if (absolute) "/" else ""
            absolute -> "/$joined"
            else -> joined
        }
    }

    fun isAllowedSocketPath(path: String): Boolean {
        if (!path.startsWith("/")) return false
        val normalized = normalize(path)
        // A directory itself is never a socket path: require a real entry
        // below the allowed prefix ("/var/run" is rejected, "/var/run/x" is ok).
        val underAllowed = ALLOWED_PREFIXES.any { prefix ->
            normalized.startsWith(prefix) && normalized.length > prefix.length &&
                !normalized.substring(prefix.length).endsWith("/")
        }
        if (!underAllowed) return false
        // No control characters / NUL (bionic sockets would reject them anyway).
        if (normalized.any { it.code < 0x20 || it.code == 0x7f }) return false
        return true
    }

    /**
     * Maps a validated guest path onto its physical location under the
     * rootfs directory inside the app sandbox.
     */
    fun physicalPath(rootfsRoot: String, guestPath: String): String {
        val normalizedGuest = normalize(guestPath)
        require(normalizedGuest.startsWith("/")) { "guest path must be absolute" }
        val root = rootfsRoot.trimEnd('/')
        return "$root$normalizedGuest"
    }
}
