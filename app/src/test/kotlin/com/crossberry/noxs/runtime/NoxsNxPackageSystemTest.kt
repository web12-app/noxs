package com.crossberry.noxs.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * NX Package System (nx CLI) tests: installation into a rootfs, template
 * completeness, dispatcher wiring and shell-level security invariants
 * (spec §20, §23, §25, §31).
 */
class NoxsNxPackageSystemTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun install(): NoxsPaths {
        val paths = NoxsPaths(temporary.newFolder("rootfs"))
        RootfsConfigurator.ensureNxPackageSystem(paths)
        return paths
    }

    // ------------------------------------------------------- installation

    @Test fun `nx CLI and pkg modules are installed and executable`() {
        val paths = install()
        val nx = File(paths.rootfs, "usr/local/bin/nx")
        assertTrue(nx.isFile)
        assertTrue(nx.canExecute())
        for (module in listOf("pkg-lib.sh", "pkg-init.sh", "pkg-dev.sh", "pkg-install.sh", "plug-lib.sh")) {
            val f = File(paths.rootfs, "usr/local/lib/noxs-pkg/$module")
            assertTrue("missing $module", f.isFile)
            assertTrue("not executable: $module", f.canExecute())
        }
        assertTrue(File(paths.rootfs, "usr/local/share/noxs-pkg/.nx-version").isFile)
    }

    @Test fun `every language template is complete for nx pkg init`() {
        val paths = install()
        for (lang in listOf("nodejs", "rust", "cpp", "python", "go")) {
            val files = File(paths.rootfs, "usr/local/share/noxs-pkg/templates/$lang/files")
            for (required in listOf(
                ".github/workflows/pkg.yml", "registry.json", "VERSION",
                "README.md", "LICENSE"
            )) {
                assertTrue("$lang missing $required", File(files, required).isFile)
            }
            assertTrue("$lang missing template.conf",
                File(paths.rootfs, "usr/local/share/noxs-pkg/templates/$lang/template.conf").isFile)
        }
        // Language-specific sources (spec §3-§7; go keeps main.go at root).
        assertTrue(File(paths.rootfs, "usr/local/share/noxs-pkg/templates/nodejs/files/src/main.js").isFile)
        assertTrue(File(paths.rootfs, "usr/local/share/noxs-pkg/templates/rust/files/src/main.rs").isFile)
        assertTrue(File(paths.rootfs, "usr/local/share/noxs-pkg/templates/cpp/files/src/main.cpp").isFile)
        assertTrue(File(paths.rootfs, "usr/local/share/noxs-pkg/templates/python/files/src/main.py").isFile)
        assertTrue(File(paths.rootfs, "usr/local/share/noxs-pkg/templates/go/files/main.go").isFile)
    }

    @Test fun `installation is idempotent via the version marker`() {
        val paths = install()
        val nx = File(paths.rootfs, "usr/local/bin/nx")
        val before = nx.lastModified()
        RootfsConfigurator.ensureNxPackageSystem(paths)
        assertEquals(before, nx.lastModified())
    }

    @Test fun `configure installs nx alongside the noxs CLI`() {
        val paths = NoxsPaths(temporary.newFolder("rootfs-cfg"))
        RootfsConfigurator.configure(
            paths,
            bootstrapHttpApt = true,
            hostLabel = "test",
            banner = "Noxs test"
        )
        assertTrue(File(paths.rootfs, "usr/local/bin/noxs").isFile)
        assertTrue(File(paths.rootfs, "usr/local/bin/nx").isFile)
    }

    // ------------------------------------------------------ dispatcher shape

    @Test fun `nx dispatcher forwards unknown commands to the noxs CLI`() {
        val cli = NoxsNxTemplate.NX_CLI
        // noxs commands are allowed through nx (nx docker install == noxs docker install)
        assertTrue(cli.contains("exec noxs"))
        assertTrue(cli.contains("pkg)"))
        assertTrue(cli.contains("install)"))
        assertTrue(cli.contains("list)"))
        assertTrue(cli.contains("info)"))
        assertTrue(cli.contains("update)"))
        assertTrue(cli.contains("remove|uninstall)"))
        assertTrue(cli.contains("plug)"))
        assertTrue(cli.contains("noxs-pkg"))
    }

    @Test fun `plug-lib ships the Plugin Store CLI contract`() {
        val lib = NoxsNxPlugLib.PLUG_LIB
        assertTrue(lib.contains("nx_plug_request"))
        assertTrue(lib.contains("nx_plug_cmd"))
        assertTrue(lib.contains("NX_PLUG_HOST"))
        assertTrue(lib.contains("catalog.txt"))
        assertTrue(lib.contains("plugins.txt"))
        // The plugin bridge never runs metadata as code (spec §19).
        assertFalse("eval must not be used", lib.contains(Regex("""\beval\b""")))
    }

    // ------------------------------------------------------ security checks

    @Test fun `shell scripts never evaluate registry metadata as code`() {
        for (script in listOf(
            NoxsNxTemplate.NX_CLI, NoxsNxPkgLib.PKG_LIB, NoxsNxPkgInit.PKG_INIT,
            NoxsNxPkgDev.PKG_DEV, NoxsNxPkgInstall.PKG_INSTALL
        )) {
            assertFalse("eval must not be used", script.contains(Regex("""\beval\b""")))
        }
    }

    @Test fun `lib validates names versions repos and checksums before use`() {
        val lib = NoxsNxPkgLib.PKG_LIB
        assertTrue(lib.contains("NX_RE_NAME"))
        assertTrue(lib.contains("valid_version"))
        assertTrue(lib.contains("valid_repo"))
        assertTrue(lib.contains("valid_sha256"))
        assertTrue(lib.contains("Package checksum verification failed."))
        assertTrue(lib.contains("not safe to install"))
        assertTrue(lib.contains("Invalid registry.json"))
    }

    @Test fun `workflow validates semver rejects duplicate tags and skips ci on registry commits`() {
        val wf = NoxsNxWorkflow.WORKFLOW_YML
        assertTrue(wf.contains("MAJOR.MINOR.PATCH"))
        assertTrue(wf.contains("Release already exists"))
        assertTrue(wf.contains("[skip ci]"))
        assertTrue(wf.contains("sha256sum"))
        assertTrue(wf.contains("fromJson"))
        assertTrue(wf.contains("registry.json updated"))
        for (job in listOf("detect:", "build:", "release:")) {
            assertTrue("workflow missing job $job", wf.contains(job))
        }
        // assets follow the Noxs naming convention (spec §13)
        assertTrue(wf.contains(".nx.pkg."))
    }

    @Test fun `template placeholders are limited to the package name token`() {
        NoxsNxPackageTemplates.FILES.values.forEach { content ->
            assertFalse("literal § left in template", content.contains('§'))
        }
        // The name placeholder appears in metadata; the workflow derives the
        // name from the repository instead (single source of truth).
        assertTrue(NoxsNxPackageTemplates.FILES.getValue("templates/_shared/registry.json")
            .contains("__PKG_NAME__"))
    }
}
