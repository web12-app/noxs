/*
 * Noxs — original implementation.
 * Persistent setup state machine + task model (spec §14, §15, §55, §56).
 *
 * One source of truth: SetupTask instances produced by SetupTaskManager are
 * observed by the install UI, the environment manager, the task list and the
 * notification — never separate fake progress states.
 */
package com.crossberry.noxs.environments.model

// --------------------------------------------------------------------- state

/** Persistent setup states (spec §14). Every major transition is persisted. */
enum class SetupState {
    NOT_SELECTED, SELECTED, CHECKING, PREPARING, DOWNLOADING, VERIFYING, EXTRACTING,
    CONFIGURING, CREATING_USER, CONFIGURING_SHELL, INSTALLING_OPTIONAL_COMPONENTS,
    VERIFYING_ENVIRONMENT, READY, FAILED, CANCELLED, RECOVERY_REQUIRED;

    val isTerminal: Boolean get() = this == READY || this == FAILED || this == CANCELLED
    val isWorking: Boolean
        get() = this in setOf(CHECKING, PREPARING, DOWNLOADING, VERIFYING, EXTRACTING,
            CONFIGURING, CREATING_USER, CONFIGURING_SHELL, INSTALLING_OPTIONAL_COMPONENTS,
            VERIFYING_ENVIRONMENT)
}

/**
 * Transition table (pure, unit-tested). RECOVERY_REQUIRED is entered by the
 * manager when a persisted task was mid-flight during process death; from
 * there the user chooses resume/restart/remove (spec §43, §44).
 */
object SetupStateMachine {

    private val ALLOWED: Map<SetupState, Set<SetupState>> = mapOf(
        SetupState.NOT_SELECTED to setOf(SetupState.SELECTED, SetupState.CANCELLED),
        SetupState.SELECTED to setOf(SetupState.CHECKING, SetupState.CANCELLED),
        SetupState.CHECKING to setOf(SetupState.PREPARING, SetupState.FAILED, SetupState.CANCELLED),
        SetupState.PREPARING to setOf(SetupState.DOWNLOADING, SetupState.FAILED, SetupState.CANCELLED),
        SetupState.DOWNLOADING to setOf(SetupState.VERIFYING, SetupState.FAILED, SetupState.CANCELLED,
            SetupState.RECOVERY_REQUIRED),
        SetupState.VERIFYING to setOf(SetupState.EXTRACTING, SetupState.FAILED, SetupState.CANCELLED,
            SetupState.RECOVERY_REQUIRED),
        SetupState.EXTRACTING to setOf(SetupState.CONFIGURING, SetupState.FAILED, SetupState.CANCELLED,
            SetupState.RECOVERY_REQUIRED),
        SetupState.CONFIGURING to setOf(SetupState.CREATING_USER, SetupState.FAILED, SetupState.CANCELLED,
            SetupState.RECOVERY_REQUIRED),
        SetupState.CREATING_USER to setOf(SetupState.CONFIGURING_SHELL, SetupState.FAILED, SetupState.CANCELLED),
        SetupState.CONFIGURING_SHELL to setOf(
            SetupState.INSTALLING_OPTIONAL_COMPONENTS, SetupState.VERIFYING_ENVIRONMENT, SetupState.FAILED),
        SetupState.INSTALLING_OPTIONAL_COMPONENTS to setOf(
            SetupState.VERIFYING_ENVIRONMENT, SetupState.READY, SetupState.FAILED, SetupState.CANCELLED),
        SetupState.VERIFYING_ENVIRONMENT to setOf(SetupState.READY, SetupState.FAILED),
        SetupState.READY to setOf(SetupState.NOT_SELECTED),
        SetupState.FAILED to setOf(SetupState.RECOVERY_REQUIRED, SetupState.SELECTED, SetupState.NOT_SELECTED),
        SetupState.CANCELLED to setOf(SetupState.NOT_SELECTED, SetupState.SELECTED, SetupState.RECOVERY_REQUIRED),
        SetupState.RECOVERY_REQUIRED to setOf(
            SetupState.DOWNLOADING, SetupState.EXTRACTING, SetupState.CONFIGURING,
            SetupState.VERIFYING_ENVIRONMENT, SetupState.SELECTED, SetupState.NOT_SELECTED, SetupState.FAILED)
    )

    fun canTransition(from: SetupState, to: SetupState): Boolean = ALLOWED[from]?.contains(to) ?: false

    /** @throws IllegalStateException on an illegal transition (programming error). */
    fun transition(from: SetupState, to: SetupState) {
        if (from == to && from.isTerminal) return
        require(canTransition(from, to)) {
            "Illegal setup state transition: $from -> $to"
        }
    }

    /** A task that persisted in a working state after process death needs recovery. */
    fun needsRecovery(state: SetupState): Boolean = state.isWorking
}

// ---------------------------------------------------------------------- task

data class SetupTask(
    val id: String,
    val environmentId: String,
    val title: String,
    val state: SetupState,
    /** 0..100, or -1 for honest indeterminate progress (spec §10/§16). */
    val progress: Int,
    val currentOperation: String,
    val startedAt: Long,
    val updatedAt: Long,
    val completedAt: Long = 0L,
    val error: String = "",
    val cancellable: Boolean = true,
    val logPath: String = "",
    /** Stage to resume from after process death (download/extract/configure). */
    val resumableStage: String = "",
    /** Persisted download facts for safe resume (spec §11) — no secrets. */
    val downloadUrl: String = "",
    val downloadDest: String = "",
    val downloadedBytes: Long = 0L,
    val expectedBytes: Long = 0L,
    val variant: String = ""
) {
    fun toJson(): String = EnvJson.write(
        LinkedHashMap<String, Any?>().apply {
            put("id", id); put("environmentId", environmentId); put("title", title)
            put("state", state.name); put("progress", progress)
            put("currentOperation", currentOperation)
            put("startedAt", startedAt); put("updatedAt", updatedAt); put("completedAt", completedAt)
            put("error", error); put("cancellable", cancellable); put("logPath", logPath)
            put("resumableStage", resumableStage); put("downloadUrl", downloadUrl)
            put("downloadDest", downloadDest); put("downloadedBytes", downloadedBytes)
            put("expectedBytes", expectedBytes); put("variant", variant)
        }
    )

    companion object {
        fun fromJson(text: String): SetupTask? = runCatching {
            val m = EnvJson.readObject(text)
            SetupTask(
                id = EnvJson.optString(m, "id"),
                environmentId = EnvJson.optString(m, "environmentId"),
                title = EnvJson.optString(m, "title"),
                state = SetupState.entries.firstOrNull { it.name == EnvJson.optString(m, "state") }
                    ?: SetupState.FAILED,
                progress = ((m["progress"] as? Number)?.toInt() ?: -1),
                currentOperation = EnvJson.optString(m, "currentOperation"),
                startedAt = EnvJson.optLong(m, "startedAt"),
                updatedAt = EnvJson.optLong(m, "updatedAt"),
                completedAt = EnvJson.optLong(m, "completedAt"),
                error = EnvJson.optString(m, "error"),
                cancellable = EnvJson.optBool(m, "cancellable", true),
                logPath = EnvJson.optString(m, "logPath"),
                resumableStage = EnvJson.optString(m, "resumableStage"),
                downloadUrl = EnvJson.optString(m, "downloadUrl"),
                downloadDest = EnvJson.optString(m, "downloadDest"),
                downloadedBytes = EnvJson.optLong(m, "downloadedBytes"),
                expectedBytes = EnvJson.optLong(m, "expectedBytes"),
                variant = EnvJson.optString(m, "variant")
            )
        }.getOrNull()
    }
}

// -------------------------------------------------------------------- format

object SetupFormat {
    fun elapsed(startedAt: Long, nowMs: Long): String {
        val s = ((nowMs - startedAt).coerceAtLeast(0L)) / 1000
        return "%02d:%02d".format(s / 60, s % 60)
    }

    fun bytes(b: Long): String = when {
        b >= 1L shl 30 -> "%.2f GB".format(b.toDouble() / (1L shl 30))
        b >= 1L shl 20 -> "%.1f MB".format(b.toDouble() / (1L shl 20))
        b >= 1L shl 10 -> "%.1f KB".format(b.toDouble() / (1L shl 10))
        else -> "$b B"
    }
}
