package com.noxs.linux.shared

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PasswdDbTest {

    private val passwd = """
        root:x:0:0:root:/root:/bin/bash
        daemon:x:1:1:daemon:/usr/sbin:/usr/sbin/nologin
        noxs:x:1000:1000:Noxs User,,,:/home/noxs:/bin/bash
    """.trimIndent()

    private val group = """
        root:x:0:
        sudo:x:27:noxs
        noxs:x:1000:
    """.trimIndent()

    private val shadow = """
        root:!:19700:0:99999:7:::
        noxs:${'$'}y${'$'}j9${'$'}salt${'$'}hash:19700:0:99999:7:::
    """.trimIndent()

    @Test fun `parses passwd lines`() {
        val users = PasswdDb.parsePasswd(passwd)
        assertEquals(3, users.size)
        val noxs = users[2]
        assertEquals("noxs", noxs.name)
        assertEquals(1000, noxs.uid)
        assertEquals("/home/noxs", noxs.home)
        assertEquals("/bin/bash", noxs.shell)
    }

    @Test fun `parses group and shadow`() {
        val groups = PasswdDb.parseGroup(group)
        assertEquals(27, groups[1].gid)
        assertEquals(listOf("noxs"), groups[1].members)
        val shadows = PasswdDb.parseShadow(shadow)
        assertTrue(shadows[1].passwordHash.startsWith("$"))
        assertEquals(PasswdDb.SHADOW_LOCKED, shadows[0].passwordHash)
    }

    @Test fun `username validation per Debian conventions`() {
        assertTrue(PasswdDb.isValidUsername("noxs"))
        assertTrue(PasswdDb.isValidUsername("noxs_user-2"))
        assertFalse(PasswdDb.isValidUsername("Noxs"))
        assertFalse(PasswdDb.isValidUsername("1noxs"))
        assertFalse(PasswdDb.isValidUsername("no xs"))
        assertFalse(PasswdDb.isValidUsername("x".repeat(33)))
    }

    @Test fun `next free uid and gid skip used ids`() {
        val users = PasswdDb.parsePasswd(passwd)
        assertEquals(1001, PasswdDb.nextFreeUid(users))
        val groups = PasswdDb.parseGroup(group)
        assertEquals(1001, PasswdDb.nextFreeGid(groups))
    }

    @Test fun `round trip serialize`() {
        val u = NoxsUser("dev", 1002, 1002, "", "/home/dev", "/bin/bash")
        assertEquals("dev:x:1002:1002::/home/dev:/bin/bash", PasswdDb.serializeUser(u))
        assertTrue(PasswdDb.parsePasswd(PasswdDb.serializeUser(u)).contains(u))
        assertEquals(PasswdDb.SHADOW_LOCKED, PasswdDb.parseShadow(PasswdDb.defaultShadowLineFor("dev"))[0].passwordHash)
    }

    @Test fun `updateUserFiles is atomic enough and backs up`() {
        val etc = TemporaryFolder(); etc.create()
        val dir: File = etc.root
        File(dir, "passwd").writeText("old\n")
        PasswdDb.updateUserFiles(dir, "newp\n", "newg\n", "news\n")
        assertEquals("newp\n", File(dir, "passwd").readText())
        assertEquals("old\n", File(dir, "passwd.bak").readText())
        assertEquals("news\n", File(dir, "shadow").readText())
        etc.delete()
    }
}

class ServiceConfigTest {

    @Test fun `parses service definition`() {
        val def = ServiceConfig.parse(
            "code",
            """
            # noxs service
            COMMAND=/opt/code-server/bin/code-server --bind-addr 127.0.0.1:8080 /home/noxs
            USER=noxs
            DESC=code-server (VS Code in browser)
            AUTO_RESTART=no
            """.trimIndent()
        )
        assertEquals("code", def.name)
        assertTrue(def.command.contains("--bind-addr 127.0.0.1:8080"))
        assertEquals("noxs", def.user)
        assertFalse(def.autoRestart)
        assertEquals("/var/run/noxs/code.pid", def.pidFilePath)
    }

    @Test fun `requires command`() {
        try {
            ServiceConfig.parse("empty", "USER=noxs\n")
            assertFalse(true)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("COMMAND"))
        }
    }

    @Test fun `service name safety`() {
        assertTrue(ServiceConfig.isServiceNameSafe("code"))
        assertFalse(ServiceConfig.isServiceNameSafe("../evil"))
        assertFalse(ServiceConfig.isServiceNameSafe("Code Server"))
    }
}
