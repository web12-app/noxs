package com.crossberry.noxs.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * NoxsPermissionCenter policy (Noxs API spec §6-§10): real Android probes,
 * explicit-decision-only access, feature-dependent restrictions and honest
 * persistence with a tamper guard.
 */
class NoxsPermissionCenterTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun center(
        granted: Set<String> = emptySet(),
        features: Map<String, Boolean> = emptyMap(),
        dir: File = temporary.newFolder()
    ): NoxsPermissionCenter = NoxsPermissionCenter(
        stateDir = dir,
        androidProbe = { permission -> permission in granted },
        featureProbe = { id -> features[id] == true }
    )

    @Test fun `absence of a decision is not access`() {
        val c = center()
        assertEquals(NoxsPermissionCenter.State.NOT_GRANTED, c.stateOf("window.create"))
        assertFalse(c.isAllowed("window.create"))
    }

    @Test fun `android permission gates the noxs state`() {
        // POST_NOTIFICATIONS not granted by the OS: user grant cannot fake it.
        val c = center(granted = emptySet())
        assertFalse(c.grant("notifications.show"))
        assertEquals(NoxsPermissionCenter.State.NOT_GRANTED, c.stateOf("notifications.show"))

        val c2 = center(granted = setOf("android.permission.POST_NOTIFICATIONS"))
        assertTrue(c2.grant("notifications.show"))
        assertTrue(c2.isAllowed("notifications.show"))
    }

    @Test fun `deny always wins for software permissions`() {
        val c = center()
        assertTrue(c.deny("terminal.execute"))
        assertEquals(NoxsPermissionCenter.State.DENIED, c.stateOf("terminal.execute"))
        assertFalse(c.isAllowed("terminal.execute"))
    }

    @Test fun `terminal execution is never granted by default (spec §13)`() {
        val c = center()
        assertFalse(c.isAllowed("terminal.write"))
        assertFalse(c.isAllowed("terminal.execute"))
        // Default package access is read/subscribe only.
        assertTrue(c.grant("terminal.read"))
        assertTrue(c.grant("terminal.subscribe"))
        assertTrue(c.isAllowed("terminal.read"))
        assertTrue(c.isAllowed("terminal.subscribe"))
    }

    @Test fun `feature dependent permissions follow the real feature`() {
        // WAKE_LOCK is an install-time permission: on a real device the OS
        // probe reports granted, and the feature toggle decides the rest.
        val wake = setOf("android.permission.WAKE_LOCK")
        val on = center(granted = wake, features = mapOf("background.keepawake" to true))
        assertEquals(NoxsPermissionCenter.State.ALLOWED, on.stateOf("background.keepawake"))
        val off = center(granted = wake, features = mapOf("background.keepawake" to false))
        assertEquals(NoxsPermissionCenter.State.RESTRICTED, off.stateOf("background.keepawake"))
    }

    @Test fun `unknown ids are not supported and grants fail`() {
        val c = center()
        assertEquals(NoxsPermissionCenter.State.NOT_SUPPORTED, c.stateOf("not.a.permission"))
        assertFalse(c.grant("not.a.permission"))
        assertFalse(c.deny("not.a.permission"))
        assertFalse(c.revoke("not.a.permission"))
    }

    @Test fun `revocation returns to not granted`() {
        val c = center()
        c.grant("web.open")
        assertTrue(c.isAllowed("web.open"))
        c.revoke("web.open")
        assertEquals(NoxsPermissionCenter.State.NOT_GRANTED, c.stateOf("web.open"))
    }

    @Test fun `android result callback records the truth`() {
        val c = center()
        c.onAndroidPermissionResult("notifications.show", granted = true)
        assertEquals(NoxsPermissionCenter.State.NOT_GRANTED, c.stateOf("notifications.show"))
        val c2 = center(granted = setOf("android.permission.POST_NOTIFICATIONS"))
        c2.onAndroidPermissionResult("notifications.show", granted = true)
        assertEquals(NoxsPermissionCenter.State.ALLOWED, c2.stateOf("notifications.show"))
        c2.onAndroidPermissionResult("notifications.show", granted = false)
        assertEquals(NoxsPermissionCenter.State.NOT_GRANTED, c2.stateOf("notifications.show"))
    }

    @Test fun `state persists and survives a reload`() {
        val dir = temporary.newFolder("state")
        val c = center(dir = dir)
        c.grant("system.info")
        c.deny("logs.read")

        val reloaded = center(dir = dir)
        assertTrue(reloaded.isAllowed("system.info"))
        assertEquals(NoxsPermissionCenter.State.DENIED, reloaded.stateOf("logs.read"))
    }

    @Test fun `tampered state files never grant unknown ids`() {
        val dir = temporary.newFolder("state2")
        File(dir, "noxs-permissions.json").writeText("""{"../evil":"ALLOWED","web.open":"ALLOWED"}""")
        val c = center(dir = dir)
        // Unknown key dropped; known key still honored.
        assertTrue(c.isAllowed("web.open"))
        assertEquals(NoxsPermissionCenter.State.NOT_SUPPORTED, c.stateOf("../evil"))
    }

    @Test fun `pending android permissions are listed for verification`() {
        val c = center(granted = setOf("android.permission.POST_NOTIFICATIONS"))
        val pending = c.pendingAndroidPermissions().map { it.id }
        assertTrue("notifications.show" in pending)
    }
}
