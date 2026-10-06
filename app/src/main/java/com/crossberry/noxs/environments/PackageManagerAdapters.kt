/*
 * Noxs — original implementation.
 * PackageManagerAdapter (spec §28): generic package operations translated to
 * the environment's real package manager. UI code NEVER constructs raw
 * package-manager commands.
 *
 *   Debian/Ubuntu/Kali/Parrot → apt
 *   Arch                      → pacman
 *   Termux                    → pkg
 *
 * Pure command translation — unit tested on the JVM.
 */
package com.crossberry.noxs.environments

import com.crossberry.noxs.environments.model.PackageManagerKind

interface PackageManagerAdapter {
    val kind: PackageManagerKind
    val binary: String
    fun update(): List<String>
    fun install(vararg packages: String): List<String>
    fun remove(vararg packages: String): List<String>
    fun search(query: String): List<String>
    fun upgrade(): List<String>
}

class AptAdapter : PackageManagerAdapter {
    override val kind = PackageManagerKind.APT
    override val binary = "/usr/bin/apt-get"
    override fun update() = listOf(binary, "update", "--yes")
    override fun install(vararg packages: String) =
        listOf(binary, "install", "--yes", "--no-install-recommends") + packages
    override fun remove(vararg packages: String) = listOf(binary, "remove", "--yes") + packages
    override fun search(query: String) = listOf("/usr/bin/apt-cache", "search", query)
    override fun upgrade() = listOf(binary, "upgrade", "--yes")
}

class PacmanAdapter : PackageManagerAdapter {
    override val kind = PackageManagerKind.PACMAN
    override val binary = "/usr/bin/pacman"
    override fun update() = listOf(binary, "-Sy", "--noconfirm")
    override fun install(vararg packages: String) =
        listOf(binary, "-S", "--noconfirm", "--needed") + packages
    override fun remove(vararg packages: String) = listOf(binary, "-R", "--noconfirm") + packages
    override fun search(query: String) = listOf(binary, "-Ss", query)
    override fun upgrade() = listOf(binary, "-Syu", "--noconfirm")
}

class TermuxPkgAdapter : PackageManagerAdapter {
    override val kind = PackageManagerKind.PKG
    override val binary = "pkg"
    override fun update() = listOf("pkg", "update", "-y")
    override fun install(vararg packages: String) = listOf("pkg", "install", "-y") + packages
    override fun remove(vararg packages: String) = listOf("pkg", "uninstall", "-y") + packages
    override fun search(query: String) = listOf("pkg", "search", query)
    override fun upgrade() = listOf("pkg", "upgrade", "-y")
}

object PackageManagerAdapters {
    fun forKind(kind: PackageManagerKind): PackageManagerAdapter? = when (kind) {
        PackageManagerKind.APT -> AptAdapter()
        PackageManagerKind.PACMAN -> PacmanAdapter()
        PackageManagerKind.PKG -> TermuxPkgAdapter()
        PackageManagerKind.NONE -> null
    }

    /** Well-known optional components (spec §36) mapped to distro package names. */
    fun optionalComponentPackage(component: String, kind: PackageManagerKind): String? = when (component) {
        "Git" -> "git"
        "Python" -> if (kind == PackageManagerKind.PACMAN) "python" else "python3"
        "Node.js" -> if (kind == PackageManagerKind.PACMAN) "nodejs" else "nodejs"
        "code-server" -> "code-server"
        else -> null
    }
}
