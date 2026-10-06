package com.crossberry.noxs.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * dpkg keeps its package database in var/lib/dpkg and rewrites it via
 * rename-based backups. Under some proot/filesystem combinations the backup
 * files (status-old, status-new) end up as symlinks, which used to hard-fail
 * the whole APT bootstrap. The repair must normalize internal symlinks into
 * real files and still refuse links that escape the rootfs.
 */
class RootfsDpkgStateRepairTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test fun `status-old symlink to status is normalized into a real file`() {
        val paths = NoxsPaths(temporary.newFolder("repair-base"))
        val dpkg = File(paths.rootfs, "var/lib/dpkg").apply { mkdirs() }
        File(dpkg, "status").apply { writeText("Package: base\nStatus: install ok installed\n") }
        Files.createSymbolicLink(File(dpkg, "status-old").toPath(), Paths.get("status"))

        RootfsConfigurator.repairDpkgPermissions(paths)

        val statusOld = File(dpkg, "status-old")
        assertFalse(Files.isSymbolicLink(statusOld.toPath()))
        assertTrue(statusOld.isFile)
        assertTrue(statusOld.readText().contains("Package: base"))
        assertTrue(statusOld.canWrite())
        assertTrue(File(dpkg, "status").readText().contains("Status: install ok installed"))
    }

    @Test fun `status symlink to backup recovers the database content`() {
        val paths = NoxsPaths(temporary.newFolder("repair-status"))
        val dpkg = File(paths.rootfs, "var/lib/dpkg").apply { mkdirs() }
        File(dpkg, "status-old").apply { writeText("Package: recovered\n") }
        Files.createSymbolicLink(File(dpkg, "status").toPath(), Paths.get("status-old"))

        RootfsConfigurator.repairDpkgPermissions(paths)

        val status = File(dpkg, "status")
        assertFalse(Files.isSymbolicLink(status.toPath()))
        assertEquals("Package: recovered\n", status.readText())
        assertFalse(Files.isSymbolicLink(File(dpkg, "status-old").toPath()))
    }

    @Test fun `dangling status-old symlink is replaced from the status sibling`() {
        val paths = NoxsPaths(temporary.newFolder("repair-dangling"))
        val dpkg = File(paths.rootfs, "var/lib/dpkg").apply { mkdirs() }
        File(dpkg, "status").apply { writeText("Package: base\n") }
        Files.createSymbolicLink(File(dpkg, "status-old").toPath(), Paths.get("does-not-exist"))

        RootfsConfigurator.repairDpkgPermissions(paths)

        val statusOld = File(dpkg, "status-old")
        assertFalse(Files.isSymbolicLink(statusOld.toPath()))
        assertEquals("Package: base\n", statusOld.readText())
    }

    @Test fun `escaping dpkg state symlink is still refused and untouched`() {
        val base = temporary.newFolder("escape-base")
        val paths = NoxsPaths(base)
        val dpkg = File(paths.rootfs, "var/lib/dpkg").apply { mkdirs() }
        val outside = File(base, "host-secret").apply { writeText("secret\n") }
        Files.createSymbolicLink(File(dpkg, "status-old").toPath(), outside.toPath())

        org.junit.Assert.assertThrows(SecurityException::class.java) {
            RootfsConfigurator.repairDpkgPermissions(paths)
        }
        assertTrue(Files.isSymbolicLink(File(dpkg, "status-old").toPath()))
        assertEquals("secret\n", outside.readText())
    }

    @Test fun `escaping status symlink is refused instead of silently copied`() {
        val base = temporary.newFolder("escape-status")
        val paths = NoxsPaths(base)
        val dpkg = File(paths.rootfs, "var/lib/dpkg").apply { mkdirs() }
        val outside = File(base, "outside-status").apply { writeText("Package: x\n") }
        Files.createSymbolicLink(File(dpkg, "status").toPath(), outside.toPath())

        org.junit.Assert.assertThrows(SecurityException::class.java) {
            RootfsConfigurator.repairDpkgPermissions(paths)
        }
        assertTrue(Files.isSymbolicLink(File(dpkg, "status").toPath()))
        assertEquals("Package: x\n", outside.readText())
    }

    @Test fun `parent symlink refusal keeps preventing writes outside the rootfs`() {
        val base = temporary.newFolder("symlink-base")
        val paths = NoxsPaths(base)
        val outside = File(base, "outside").apply { mkdirs() }
        val varDir = File(paths.rootfs, "var").apply { mkdirs() }
        Files.createSymbolicLink(File(varDir, "lib").toPath(), outside.toPath())

        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            RootfsConfigurator.repairDpkgPermissions(paths)
        }
        assertFalse(File(outside, "dpkg").exists())
    }
}
