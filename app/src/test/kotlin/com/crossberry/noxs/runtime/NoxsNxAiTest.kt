package com.crossberry.noxs.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Noxs AI Agent Terminal (`nx ai`) installation and security invariants
 * (AI spec §27-§29, §31): runtime installed into the rootfs, dispatcher
 * wired, default model route, and NO provider keys ever embedded.
 */
class NoxsNxAiTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var paths: NoxsPaths

    @Before
    fun setUp() {
        paths = NoxsPaths(temporary.newFolder("rootfs"))
        RootfsConfigurator.ensureNxPackageSystem(paths)
    }

    private fun install(): NoxsPaths = paths

    private fun installed(paths: NoxsPaths, rel: String): String =
        File(paths.rootfs, rel).readText(Charsets.UTF_8)

    @Test fun `ai runtime is installed into the environment`() {
        val paths = install()
        val aiDir = File(paths.rootfs, "usr/local/lib/noxs/ai")
        assertTrue(File(aiDir, "agent.py").isFile)
        assertTrue(File(aiDir, "provider.py").isFile)
        assertTrue(File(aiDir, "tools.py").isFile)
        val aiLib = File(paths.rootfs, "usr/local/lib/noxs-pkg/ai-lib.sh")
        assertTrue(aiLib.isFile)
        assertTrue(aiLib.canExecute())
    }

    @Test fun `nx dispatcher wires the ai command`() {
        val paths = install()
        val nx = installed(paths, "usr/local/bin/nx")
        assertTrue("ai) case missing", nx.contains("ai)"))
        assertTrue(nx.contains("ai-lib.sh"))
        assertTrue(nx.contains("nx_ai_cmd"))
    }

    @Test fun `default model route is kilo-auto-free with kilo provider`() {
        install()
        val agent = installed(paths, "usr/local/lib/noxs/ai/agent.py")
        assertTrue(agent.contains("'model': 'kilo-auto/free'"))
        assertTrue(agent.contains("'provider': 'kilo'"))
        // The runtime never depends on provider-specific code paths: the
        // provider is configuration (base_url + model + key env), §26.
        assertTrue(agent.contains("base_url"))
    }

    @Test fun `no provider api keys are embedded anywhere`() {
        val paths = install()
        val files = listOf(
            "usr/local/lib/noxs/ai/agent.py",
            "usr/local/lib/noxs/ai/provider.py",
            "usr/local/lib/noxs/ai/tools.py",
            "usr/local/lib/noxs-pkg/ai-lib.sh",
            "usr/local/bin/nx"
        )
        val keyPattern = Regex("""(sk-[A-Za-z0-9_-]{20,}|ghp_[A-Za-z0-9]{20,}|Bearer\s+[A-Za-z0-9._-]{20,})""")
        files.forEach { rel ->
            val content = installed(paths, rel)
            assertFalse("embedded provider key in $rel", keyPattern.containsMatchIn(content))
            assertFalse("unexpanded section sign in $rel", content.contains('§'))
        }
    }

    @Test fun `agent enforces the documented limits and gates`() {
        val paths = install()
        val agent = installed(paths, "usr/local/lib/noxs/ai/agent.py")
        // §17 limits
        assertTrue(agent.contains("max_steps"))
        assertTrue(agent.contains("max_tool_calls"))
        assertTrue(agent.contains("max_parallel_tools"))
        assertTrue(agent.contains("Agent step limit reached"))
        // §9 confirmation gate is Noxs-controlled
        assertTrue(agent.contains("Action requires permission"))
        assertTrue(agent.contains("Allow? [y/N]"))
        // §20 shutdown releases everything the session owns
        assertTrue(agent.contains("kill_process_group"))
    }

    @Test fun `tools carry the required permission levels`() {
        val paths = install()
        val tools = installed(paths, "usr/local/lib/noxs/ai/tools.py")
        assertTrue(tools.contains("PERMISSION_READ = 'READ'"))
        assertTrue(tools.contains("PERMISSION_CONFIRM = 'CONFIRM'"))
        assertTrue(tools.contains("PERMISSION_DENY = 'DENY'"))
        // §9: read-only tools vs confirmation tools
        assertTrue(tools.contains("PERMISSION_CONFIRM, write_file"))
        assertTrue(tools.contains("PERMISSION_CONFIRM, delete_path"))
        // §15: output limits + secret filtering exist
        assertTrue(tools.contains("truncate_output"))
        assertTrue(tools.contains("redact_secrets"))
        // §16: normalized result envelope
        assertTrue(tools.contains("'duration_ms'"))
    }

    @Test fun `web and ai modules coexist after migration bump`() {
        val paths = install()
        // Marker moved with the AI install (migration 3 -> 4).
        assertEquals("4", installed(paths, "usr/local/share/noxs-pkg/.nx-version").trim())
        assertTrue(File(paths.rootfs, "usr/local/lib/noxs/nx-api/nx-api.js").isFile)
        assertTrue(File(paths.rootfs, "usr/local/lib/noxs/ai/agent.py").isFile)
    }
}
