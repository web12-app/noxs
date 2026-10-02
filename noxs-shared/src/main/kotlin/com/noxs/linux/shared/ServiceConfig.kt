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

    companion object {
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
