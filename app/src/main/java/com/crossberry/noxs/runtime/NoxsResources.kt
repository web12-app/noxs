/*
 * Noxs — original implementation.
 * Resource quotas for the Noxs environment (CPU / memory / processes /
 * sessions / storage). Enforced:
 *   - sessions: hard cap in the app (NoxsSessionManager)
 *   - processes/fd/memory: ulimits applied inside the sandbox by the
 *     `noxs-resource` launcher script (children of the Noxs shell only)
 *   - storage: monitored and reported; hard limits remain Android's job
 * Noxs never attempts to override Android's own resource management.
 */
package com.crossberry.noxs.runtime

import com.crossberry.noxs.shared.NoxsLog
import java.io.File

data class ResourceQuotas(
    val maxSessions: Int = 8,
    val maxProcessesPerSession: Int = 256,
    val maxOpenFiles: Int = 512,
    val memorySoftMb: Int = 2048,
    val storageWarnMb: Long = 4096
) {
    fun serialize(): String = buildString {
        appendLine("# /etc/noxs/resources.conf — Noxs resource quotas")
        appendLine("# Applied by noxs-resource inside the Noxs userspace only.")
        appendLine("MAX_SESSIONS=$maxSessions")
        appendLine("MAX_PROCESSES=$maxProcessesPerSession")
        appendLine("MAX_OPEN_FILES=$maxOpenFiles")
        appendLine("MEMORY_SOFT_MB=$memorySoftMb")
        appendLine("STORAGE_WARN_MB=$storageWarnMb")
    }

    companion object {
        fun parse(content: String): ResourceQuotas {
            var q = ResourceQuotas()
            for (line in content.lineSequence()) {
                val l = line.substringBefore('#').trim()
                if (l.isEmpty() || !l.contains('=')) continue
                val (k, v) = l.split('=', limit = 2).map { it.trim() }
                val n = v.toIntOrNull() ?: continue
                q = when (k.uppercase()) {
                    "MAX_SESSIONS" -> q.copy(maxSessions = n.coerceIn(1, 32))
                    "MAX_PROCESSES" -> q.copy(maxProcessesPerSession = n.coerceIn(16, 4096))
                    "MAX_OPEN_FILES" -> q.copy(maxOpenFiles = n.coerceIn(64, 8192))
                    "MEMORY_SOFT_MB" -> q.copy(memorySoftMb = n.coerceIn(128, 8192))
                    "STORAGE_WARN_MB" -> q.copy(storageWarnMb = n.toLong().coerceAtLeast(64))
                    else -> q
                }
            }
            return q
        }
    }
}

class NoxsResources(private val paths: NoxsPaths) {

    val confFile: File = paths.noxsResourcesConf

    fun load(): ResourceQuotas = try {
        if (confFile.isFile) ResourceQuotas.parse(confFile.readText()) else ResourceQuotas()
    } catch (e: Exception) {
        NoxsLog.w("Resources", "quota parse failed, defaults in use", e)
        ResourceQuotas()
    }

    fun save(q: ResourceQuotas) {
        confFile.parentFile?.mkdirs()
        confFile.writeText(q.serialize())
    }

    /** Session-level snapshot for the status bar widget. */
    data class Usage(
        val cpuPercent: Double,
        val memMb: Long,
        val totalMemMb: Long,
        val storageUsedMb: Long,
        val storageWarnMb: Long
    )

    fun currentUsage(): Usage {
        val memTotal = readMemInfoLong("MemTotal:") / 1024
        val memAvailable = readMemInfoLong("MemAvailable:") / 1024
        val memUsed = (memTotal - memAvailable).coerceAtLeast(0)
        val cpu = readAppCpuPercent()
        val storageMb = paths.dirSize(paths.rootfs) / (1024 * 1024)
        return Usage(cpu, memUsed, memTotal, storageMb, load().storageWarnMb)
    }

    private fun readMemInfoLong(key: String): Long = try {
        File("/proc/meminfo").readLines()
            .firstOrNull { it.startsWith(key) }
            ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull() ?: 0
    } catch (e: Exception) { 0 }

    private var lastCpuSample: Pair<Long, Long>? = null // (totalJiffies@t, uptime)

    /** Coarse app-process CPU% from /proc/self/stat (status-bar granularity). */
    private fun readAppCpuPercent(): Double {
        return try {
            val stat = File("/proc/self/stat").readText().split(' ')
            val utime = stat[13].toLong() + stat[14].toLong()
            val uptime = File("/proc/uptime").readText().substringBefore(' ').toDouble()
            val last = lastCpuSample
            lastCpuSample = utime to (uptime * 100).toLong()
            if (last == null) 0.0
            else {
                val dJiffies = (utime - last.first).toDouble()
                val dMs = (uptime * 100 - last.second).toDouble().coerceAtLeast(1.0)
                (dJiffies / (dMs / 1000.0) / Runtime.getRuntime().availableProcessors() * 100.0)
                    .coerceIn(0.0, 100.0)
            }
        } catch (e: Exception) { 0.0 }
    }
}
