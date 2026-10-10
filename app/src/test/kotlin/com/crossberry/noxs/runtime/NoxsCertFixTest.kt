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
 * `nx cert-fix` invariants: the guest repair command must ship with the
 * package system (migration version 8), be dispatched by the nx CLI, and
 * keep the app-side bootstrap's reliability policy inside the guest:
 * signed-HTTP fallback (never unauthenticated installs), bounded retries
 * with identical-failure abort, single-flight package transactions, and a
 * TXT log under ~/.noxs/logs.
 */
class NoxsCertFixTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var paths: NoxsPaths

    @Before
    fun setUp() {
        paths = NoxsPaths(temporary.newFolder("rootfs"))
        RootfsConfigurator.ensureNxPackageSystem(paths)
    }

    private fun installed(rel: String): File = File(paths.rootfs, rel)

    private val certFix get() = installed("usr/local/lib/noxs-pkg/cert-fix.sh").readText()
    private val cli get() = installed("usr/local/bin/nx").readText()

    @Test
    fun `package system version is 8 - existing installs migrate without a reinstall`() {
        assertEquals("8", RootfsConfigurator.NX_PACKAGE_SYSTEM_VERSION)
        assertEquals("8\n", installed("usr/local/share/noxs-pkg/.nx-version").readText())
    }

    @Test
    fun `cert-fix lib is installed executable next to the other nx libs`() {
        val lib = installed("usr/local/lib/noxs-pkg/cert-fix.sh")
        assertTrue(lib.isFile)
        assertTrue(lib.canExecute())
    }

    @Test
    fun `nx cli dispatches cert-fix and documents it`() {
        assertTrue(cli.contains("cert-fix)"))
        assertTrue(cli.contains("cert_fix_cmd"))
        assertTrue(cli.contains("nx cert-fix"))
        assertTrue(cli.contains("cert-fix.sh"))
    }

    @Test
    fun `cert-fix lib is a POSIX shell script with an entry command`() {
        assertTrue(certFix.startsWith("#!/bin/sh\n"))
        assertTrue(certFix.contains("cert_fix_cmd()"))
        assertTrue(certFix.contains("cert_fix_help"))
        assertFalse(certFix.contains("bash"))
    }

    @Test
    fun `cert-fix never bypasses the single-flight package policy`() {
        assertTrue(certFix.contains("/run/noxs/pkg-tx"))
        assertTrue(certFix.contains("cf_pkg_busy"))
        // Arm + release the flag only for its own transaction.
        assertTrue(certFix.contains("echo cert-fix > /run/noxs/pkg-tx"))
        assertTrue(certFix.contains("cf_owns_tx"))
    }

    @Test
    fun `cert-fix falls back to signed HTTP and restores HTTPS`() {
        assertTrue(certFix.contains("https://|http://|g"))
        assertTrue(certFix.contains("http://|https://|g"))
        // The signed-HTTP fallback is explained, not silent.
        assertTrue(certFix.contains("GPG"))
    }

    @Test
    fun `cert-fix installs the certificate stack and the fetch tools`() {
        assertTrue(certFix.contains("ca-certificates debian-archive-keyring curl wget"))
        assertTrue(certFix.contains("update-ca-certificates --fresh"))
        assertTrue(certFix.contains("/etc/ssl/certs/ca-certificates.crt"))
    }

    @Test
    fun `cert-fix retries are bounded and identical failures abort`() {
        assertTrue(certFix.contains("the same failure repeated"))
        assertTrue(certFix.contains("Acquire::Retries=1"))
        assertTrue(certFix.contains("Acquire::https::Timeout=20"))
        // sleep between attempts exists, and the loop counter is capped
        assertTrue(certFix.contains("cf_t_max"))
    }

    @Test
    fun `cert-fix verifies the result instead of trusting exit codes`() {
        assertTrue(certFix.contains("apt-cache policy ca-certificates"))
        assertTrue(certFix.contains("/var/lib/apt/lists/*Packages*"))
        assertTrue(certFix.contains("command -v curl"))
        assertTrue(certFix.contains("command -v wget"))
    }

    @Test
    fun `cert-fix writes an auditable TXT log`() {
        assertTrue(certFix.contains(".noxs/logs"))
        assertTrue(certFix.contains("cert-fix-"))
    }

    @Test
    fun `cert-fix help is reachable and arguments are rejected`() {
        assertTrue(certFix.contains("-h|--help|help"))
        assertTrue(certFix.contains("unknown argument"))
    }
}
