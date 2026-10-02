/*
 * Noxs — original implementation.
 * Parser for /etc/noxs/services/<name>.conf definitions used by the noxs
 * service manager. Only KEY=VALUE lines; '#' starts a comment. Values may
 * contain spaces (COMMAND is exec'd as argv via shell-safe splitting in the
 * sandbox, never re-evaluated by a shell).
 */
package com.noxs.linux.shared

data class ServiceConfig(
    val name: String,
    val command: String,
    val user: String,
    val desc: String,
    val autoRestart: Boolean
) {
    /** pid files live in the sandbox runtime dir; the name must be safe. */
    val pidFilePath: String get() = "${NoxsConstants.NOXS_RUN_DIR}/$name.pid"

    fun toDefinition(): ServiceDefinition = ServiceDefinition(
        name = name, command = command, user = user,
        description = desc, autoRestart = autoRestart
    )

    companion object {

        /**
         * Loads every safe service definition from <rootfs>/etc/noxs/services/.
         * Missing directory or unparseable files are skipped (never fatal).
         */
        fun loadAll(rootfsPath: String): List<ServiceDefinition> {
            val dir = java.io.File(rootfsPath, NoxsConstants.NOXS_SERVICE_DIR.removePrefix("/"))
            if (!dir.isDirectory) return emptyList()
            val out = mutableListOf<ServiceDefinition>()
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".conf") } ?: return emptyList()
            for (f in files.sortedBy { it.name }) {
                val name = f.name.removeSuffix(".conf")
                if (!isServiceNameSafe(name)) continue
                try {
                    out += parse(name, f.readText()).toDefinition()
                } catch (_: IllegalArgumentException) {
                    // skip malformed definition
                }
            }
            return out
        }
    }
)

/** UI-facing immutable view of a service definition. */
data class ServiceDefinition(
    val name: String,
    val command: String,
    val user: String,
    val description: String,
    val autoRestart: Boolean
)

/** Snapshot of a service's runtime status (app-side probing only). */
data class ServiceStatus(
    val definition: ServiceDefinition,
    val running: Boolean,
    val pid: Int
)
        private val NAME_RE = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")

        fun isServiceNameSafe(name: String): Boolean = NAME_RE.matches(name)

        fun parse(name: String, text: String): ServiceConfig {
            require(isServiceNameSafe(name)) { "unsafe service name: $name" }
            var command = ""
            var user = NoxsConstants.DEFAULT_USER
            var desc = ""
            var autoRestart = true
            text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .forEach { line ->
                    val eq = line.indexOf('=')
                    if (eq <= 0) return@forEach
                    val key = line.substring(0, eq).trim().uppercase()
                    val value = line.substring(eq + 1).trim()
                    when (key) {
                        "COMMAND" -> command = value
                        "USER" -> user = value
                        "DESC" -> desc = value
                        "AUTO_RESTART" -> autoRestart = value.equals("yes", ignoreCase = true) ||
                            value.equals("true", ignoreCase = true) || value == "1"
                    }
                }
            require(command.isNotEmpty()) { "service '$name' defines no COMMAND" }
            return ServiceConfig(name, command, user, desc, autoRestart)
        }
    }
}
