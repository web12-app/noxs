/*
 * Noxs — original implementation.
 * JVM tests for /proc sampling and process-tree ownership attribution.
 */
package com.crossberry.noxs.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoxsProcSamplerTest {

    /** Deterministic fake /proc tree (spec 12/19: ownership layer). */
    private class FakeSource : NoxsProcSource {
        val procs = LinkedHashMap<Int, NoxsProcInfo>()

        fun add(pid: Int, ppid: Int, cpuTicks: Long = 0, rssKb: Long = 0, comm: String = "sh") {
            procs[pid] = NoxsProcInfo(pid, ppid, cpuTicks, rssKb, startTimeTicks = 0, comm = comm)
        }

        override fun scan(): List<NoxsProcInfo> = procs.values.toList()
    }

    private fun sampler(source: FakeSource, clock: () -> Long) = NoxsProcSampler(source, clock)

    @Test
    fun `descendants are attributed to their session root`() {
        val source = FakeSource()
        // Android init (1) is NOT ours; session shell 100 owns 101 (npm) and 102 (esbuild).
        source.add(1, 0, comm = "init")
        source.add(100, 1, comm = "proot")
        source.add(101, 100, comm = "npm")
        source.add(102, 101, comm = "node")
        // Unrelated second session root
        source.add(200, 1, comm = "proot")
        source.add(201, 200, comm = "python")

        var now = 0L
        val s = sampler(source, { now })
        val usage = s.sampleTree(setOf(100))!!

        assertEquals(3, usage.processCount)
        // The root (proot) itself is excluded; only descendants are reported.
        assertEquals(setOf("npm", "node"), s.runningCommands(setOf(100)).toSet())
        assertNull(s.sampleTree(setOf(999)))
    }

    @Test
    fun `cpu percent is computed from tick delta between samples`() {
        val source = FakeSource()
        source.add(100, 0, cpuTicks = 0)
        source.add(101, 100, cpuTicks = 0)
        var now = 0L
        val s = sampler(source, { now })

        val first = s.sampleTree(setOf(100))!!
        assertEquals(0.0, first.cpuPercent, 0.0001) // baseline sample

        // 200 ticks in 2.0 s with HZ=100 → 1.0 CPU-second per second of wall
        // time; the sampler normalizes per core, so expected = 100 / cores.
        source.procs[100] = source.procs[100]!!.copy(cpuTicks = 150)
        source.procs[101] = source.procs[101]!!.copy(cpuTicks = 50)
        now = 2_000L
        val second = s.sampleTree(setOf(100))!!
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        assertEquals(100.0 / cores, second.cpuPercent, 0.5)
    }

    @Test
    fun `rss sums across the owned tree`() {
        val source = FakeSource()
        source.add(100, 0, rssKb = 4096)
        source.add(101, 100, rssKb = 262144) // 256 MB
        var now = 0L
        val usage = sampler(source, { now }).sampleTree(setOf(100))!!
        assertEquals(266_240L, usage.rssKb)
    }

    @Test
    fun `dead root yields null usage`() {
        val source = FakeSource()
        var now = 0L
        val s = sampler(source, { now })
        source.add(100, 0)
        assertNotNull(s.sampleTree(setOf(100)))
        source.procs.remove(100)
        assertNull(s.sampleTree(setOf(100)))
    }

    private fun assertNotNull(any: Any?) {
        org.junit.Assert.assertNotNull(any)
    }
}
