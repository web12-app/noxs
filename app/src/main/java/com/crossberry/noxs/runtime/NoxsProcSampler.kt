/*
 * Noxs — original implementation.
 * Lightweight /proc-based process tracking and resource sampling.
 *
 * Ownership model: every host process that descends from a Noxs session's
 * PTY child belongs to that session. One /proc scan answers "what is running
 * inside this session" and "how much CPU/RAM does it use" without shelling
 * out or polling faster than the UI needs (callers drive the cadence).
 */
package com.crossberry.noxs.runtime

import java.io.File

/** One process as seen in a single /proc scan. */
data class NoxsProcInfo(
    val pid: Int,
    val ppid: Int,
    val cpuTicks: Long,        // utime + stime
    val rssKb: Long,
    val startTimeTicks: Long,
    val comm: String
)

/** Source of /proc data (abstracted for JVM tests). */
interface NoxsProcSource {
    /** Reads every readable same-UID process; returns a best-effort snapshot. */
    fun scan(): List<NoxsProcInfo>
}

/** Real /proc implementation (processes of the app UID are always readable). */
class AndroidProcSource : NoxsProcSource {

    override fun scan(): List<NoxsProcInfo> {
        val out = ArrayList<NoxsProcInfo>(64)
        val proc = File("/proc")
        val dirs = proc.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } } ?: return out
        for (dir in dirs) {
            val pid = dir.name.toIntOrNull() ?: continue
            val stat = runCatching { File(dir, "stat").readText() }.getOrNull() ?: continue
            val info = parseStat(pid, stat) ?: continue
            out.add(info)
        }
        return out
    }

    /** /proc/<pid>/stat — comm may contain spaces/parens, split after the LAST ')'. */
    internal fun parseStat(pid: Int, stat: String): NoxsProcInfo? {
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        val rest = stat.substring(close + 2).split(' ')
        if (rest.size < 22) return null
        // rest[0]=state rest[1]=ppid ... rest[10]=utime rest[11]=stime ... rest[19]=starttime rest[21]=rss
        return NoxsProcInfo(
            pid = pid,
            ppid = rest[1].toIntOrNull() ?: 0,
            cpuTicks = (rest[10].toLongOrNull() ?: 0L) + (rest[11].toLongOrNull() ?: 0L),
            rssKb = (rest[21].toLongOrNull() ?: 0L) * PAGE_KB,
            startTimeTicks = rest[19].toLongOrNull() ?: 0L,
            comm = stat.substring(stat.indexOf('(') + 1, close)
        )
    }

    companion object {
        private const val PAGE_KB = 4L
    }
}

/** Aggregated usage for a process tree. */
data class NoxsTreeUsage(
    val cpuPercent: Double,   // normalized to one core (may exceed 100 with multiple threads)
    val rssKb: Long,
    val processCount: Int
)

/**
 * Samples /proc at the caller's cadence and attributes processes to roots.
 * Keep polling ≥1 s apart — the sampler is cheap but polling exists to be
 * light, not constant.
 */
class NoxsProcSampler(
    private val source: NoxsProcSource,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private var lastCpu: Map<Int, Long> = emptyMap()  // pid -> cpuTicks
    private var lastTickMs: Long = -1L
    private val firstSeen = HashMap<Int, Long>()

    fun uptimeMs(rootPid: Int): Long {
        val start = firstSeen[rootPid] ?: run {
            firstSeen[rootPid] = clockMs()
            return 0L
        }
        return (clockMs() - start).coerceAtLeast(0L)
    }

    fun forget(rootPid: Int) { firstSeen.remove(rootPid) }

    /**
     * Sums CPU/RAM over every process whose ancestry (ppid chain) reaches one
     * of [rootPids]. Returns null when none of the roots are alive.
     */
    fun sampleTree(rootPids: Set<Int>): NoxsTreeUsage? {
        if (rootPids.isEmpty()) return null
        val snapshot = source.scan()
        if (snapshot.isEmpty()) return null

        val byPid = HashMap<Int, NoxsProcInfo>(snapshot.size * 2)
        snapshot.forEach { byPid[it.pid] = it }

        val owned = HashSet<Int>()
        for (p in snapshot) {
            var cursor = p.pid
            var depth = 0
            while (cursor > 0 && depth < MAX_DEPTH) {
                if (rootPids.contains(cursor)) { owned.add(p.pid); break }
                val parent = byPid[cursor]?.ppid ?: break
                if (parent == cursor) break
                cursor = parent
                depth++
            }
        }
        if (owned.isEmpty()) {
            lastCpu = emptyMap()
            return null
        }

        val now = clockMs()
        var rssKb = 0L
        var cpuTicksNow = 0L
        var ticksDelta = 0L
        val cpuNow = HashMap<Int, Long>(owned.size * 2)
        for (p in snapshot) {
            if (p.pid !in owned) continue
            rssKb += p.rssKb
            cpuTicksNow += p.cpuTicks
            lastCpu[p.pid]?.let { ticksDelta += (p.cpuTicks - it).coerceAtLeast(0L) }
            cpuNow[p.pid] = p.cpuTicks
        }
        lastCpu = cpuNow

        val percent = if (lastTickMs < 0L || now <= lastTickMs) {
            0.0 // first sample establishes the baseline
        } else {
            val seconds = (now - lastTickMs) / 1000.0
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            ((ticksDelta / HZ.toDouble()) / seconds) * 100.0 / cores
        }
        lastTickMs = now

        return NoxsTreeUsage(
            cpuPercent = percent.coerceIn(0.0, 100.0 * Runtime.getRuntime().availableProcessors()),
            rssKb = rssKb,
            processCount = owned.size
        )
    }

    /** Top-level command names running under [rootPids], newest-child first. */
    fun runningCommands(rootPids: Set<Int>): List<String> {
        if (rootPids.isEmpty()) return emptyList()
        val snapshot = source.scan()
        val byPid = HashMap<Int, NoxsProcInfo>(snapshot.size * 2)
        snapshot.forEach { byPid[it.pid] = it }
        val names = ArrayList<String>()
        for (p in snapshot) {
            if (p.pid in rootPids) continue
            var cursor = p.pid
            var depth = 0
            var attributed = false
            while (cursor > 0 && depth < MAX_DEPTH) {
                if (rootPids.contains(cursor)) { attributed = true; break }
                val parent = byPid[cursor]?.ppid ?: break
                if (parent == cursor) break
                cursor = parent
                depth++
            }
            if (attributed) names.add(p.comm)
        }
        return names
    }

    companion object {
        private const val HZ = 100L            // USER_HZ on Linux/Android
        private const val MAX_DEPTH = 32
    }
}
