package com.noxs.linux.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProotLauncherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun makeLauncher(): Pair<ProotLauncher, NoxsPaths> {
        val base = tmp.newFolder("noxs-base")
        val paths = NoxsPaths(base)
        paths.ensureBaseDirs()
        paths.prootBinary.writeText("#!/bin/sh\n")
        val resources = NoxsResources(paths)
        resources.confFile.parentFile?.mkdirs()
        resources.save(ResourceQuotas())
        return ProotLauncher(paths, resources) to paths
    }

    @Test fun `user session argv uses su to drop to noxs`() {
        val (launcher, paths) = makeLauncher()
        val argv = launcher.sessionArgv(loginAsRoot = false)

        assertEquals(paths.prootBinary.absolutePath, argv[0])
        assertTrue(argv.contains("-0"))
        assertTrue(argv.contains(paths.rootfs.absolutePath))
        val suIdx = argv.indexOf("/bin/su")
        assertTrue(suIdx > 0)
        assertEquals("noxs", argv.last())
        assertTrue(argv.contains("-w"))
        assertTrue(argv.contains("/home/noxs"))
        assertTrue(argv.contains("${paths.run.absolutePath}:/var/run/noxs/host"))
        assertTrue(argv.any { it.contains(":/etc/noxs/resources.conf") })
    }

    @Test fun `root session uses bash login in root home`() {
        val (launcher, _) = makeLauncher()
        val argv = launcher.sessionArgv(loginAsRoot = true)
        assertTrue(argv.contains("/bin/bash"))
        assertTrue(argv.contains("--login"))
        assertTrue(argv.contains("/root"))
    }

    @Test fun `one-shot wraps command for user context`() {
        val (launcher, _) = makeLauncher()
        val argv = launcher.oneShotArgv(listOf("dpkg-query", "-W"), asRoot = false)
        assertTrue(argv.contains("-c"))
        val cIdx = argv.indexOf("-c")
        assertEquals("'dpkg-query' '-W'", argv[cIdx + 1])
    }

    @Test fun `env carries TERM HOME and compatibility flags`() {
        val (launcher, paths) = makeLauncher()
        val env = launcher.buildEnv().map { e -> e.substringBefore('=') to e.substringAfter('=') }.toMap()
        assertEquals("xterm-256color", env["TERM"])
        assertEquals("/home/noxs", env["HOME"])
        assertEquals("1", env["NOXS"])
        assertEquals("1", env["PROOT_NO_SECCOMP"])
        assertEquals(paths.tmp.absolutePath, env["PROOT_TMP_DIR"])
    }

    @Test fun `missing proot binary is refused`() {
        val base = tmp.newFolder("no-proot")
        val paths = NoxsPaths(base)
        val launcher = ProotLauncher(paths, NoxsResources(paths))
        try {
            launcher.sessionArgv(false)
            assertTrue("expected failure", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("proot"))
        }
    }

    @Test fun `resource quotas round trip`() {
        val q = ResourceQuotas(maxSessions = 12, maxProcessesPerSession = 300, memorySoftMb = 1024)
        val parsed = ResourceQuotas.parse(q.serialize())
        assertEquals(12, parsed.maxSessions)
        assertEquals(300, parsed.maxProcessesPerSession)
        assertEquals(1024, parsed.memorySoftMb)
    }
}
