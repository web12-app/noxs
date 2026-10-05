/*
 * Noxs — original implementation.
 * Parsing and atomic editing of /etc/passwd, /etc/group and /etc/shadow inside
 * the Debian rootfs. The app edits these files during user management; all
 * updates go through [updateUserFiles] which backs up and renames atomically.
 */
package com.crossberry.noxs.shared

import java.io.File

data class NoxsUser(
    val name: String,
    val uid: Int,
    val gid: Int,
    val gecos: String,
    val home: String,
    val shell: String
)

data class NoxsGroup(
    val name: String,
    val gid: Int,
    val members: List<String>
)

data class NoxsShadow(
    val name: String,
    val passwordHash: String
)

object PasswdDb {

    /** Marker stored in /etc/shadow for accounts without a usable password. */
    const val SHADOW_LOCKED = "!"

    // Debian username conventions: start with lowercase letter or underscore,
    // then lowercase/digits/underscore/hyphen, at most 32 characters.
    private val USERNAME_RE = Regex("^[a-z_][a-z0-9_-]{0,31}$")

    fun isValidUsername(name: String): Boolean = USERNAME_RE.matches(name)

    fun parsePasswd(text: String): List<NoxsUser> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val f = line.split(':')
                if (f.size < 7) return@mapNotNull null
                val uid = f[2].toIntOrNull() ?: return@mapNotNull null
                val gid = f[3].toIntOrNull() ?: return@mapNotNull null
                NoxsUser(f[0], uid, gid, f[4], f[5], f[6])
            }
            .toList()

    fun parseGroup(text: String): List<NoxsGroup> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val f = line.split(':')
                if (f.size < 4) return@mapNotNull null
                val gid = f[2].toIntOrNull() ?: return@mapNotNull null
                NoxsGroup(f[0], gid, if (f[3].isEmpty()) emptyList() else f[3].split(','))
            }
            .toList()

    fun parseShadow(text: String): List<NoxsShadow> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val f = line.split(':')
                if (f.size < 2) return@mapNotNull null
                NoxsShadow(f[0], f[1])
            }
            .toList()

    fun serializeUser(u: NoxsUser): String =
        "${u.name}:x:${u.uid}:${u.gid}:${u.gecos}:${u.home}:${u.shell}"

    fun serializeGroup(g: NoxsGroup): String =
        "${g.name}:x:${g.gid}:" + g.members.joinToString(",")

    fun defaultShadowLineFor(name: String): String =
        "$name:$SHADOW_LOCKED::0:99999:7:::"

    fun nextFreeUid(users: List<NoxsUser>, start: Int = 1000): Int {
        val used = users.map { it.uid }.toHashSet()
        var candidate = start
        while (candidate in used) candidate++
        return candidate
    }

    fun nextFreeGid(groups: List<NoxsGroup>, start: Int = 1000): Int {
        val used = groups.map { it.gid }.toHashSet()
        var candidate = start
        while (candidate in used) candidate++
        return candidate
    }

    /**
     * Replaces passwd/group/shadow under [etcDir]. Each existing file is copied
     * to "<name>.bak" first; new content is written to a temp file and renamed
     * over the target so readers never observe a partially written file.
     */
    fun updateUserFiles(etcDir: File, passwd: String, group: String, shadow: String) {
        updateOne(File(etcDir, "passwd"), passwd, private_ = false)
        updateOne(File(etcDir, "group"), group, private_ = false)
        updateOne(File(etcDir, "shadow"), shadow, private_ = true)
    }

    private fun updateOne(target: File, content: String, private_: Boolean) {
        val dir = target.parentFile ?: throw IllegalArgumentException("no parent for $target")
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw java.io.IOException("cannot create ${dir.absolutePath}")
        }
        if (target.isFile) {
            target.copyTo(File(dir, target.name + ".bak"), overwrite = true)
        }
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(content)
        if (private_) {
            tmp.setReadable(false, false)
            tmp.setReadable(true, true)
            tmp.setWritable(false, false)
            tmp.setWritable(true, true)
            tmp.setExecutable(false, false)
        }
        if (!tmp.renameTo(target)) {
            if (target.exists()) target.delete()
            check(tmp.renameTo(target)) { "rename failed for ${target.absolutePath}" }
        }
    }
}
