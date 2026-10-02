package com.noxs.linux

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device smoke tests (integration layer). Run with:
 *   ./gradlew connectedDebugAndroidTest   (emulator or device required)
 *
 * These verify sandbox invariants on real Android: all Noxs paths live under
 * the app-private dir and never point at the Android system filesystem.
 */
@RunWith(AndroidJUnit4::class)
class SandboxInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun packageName_isNoxs() {
        assertTrue("package must be com.noxs.linux*", context.packageName.startsWith("com.noxs.linux"))
    }

    @Test fun baseDir_staysInsideAppSandbox() {
        val paths = com.noxs.linux.runtime.NoxsPaths(context)
        val appDir = context.filesDir.absolutePath
        assertTrue(paths.base.absolutePath.startsWith(appDir))
        assertTrue(paths.rootfs.absolutePath.startsWith(appDir))
        // The app must never point at the Android system filesystem
        assertTrue(!paths.rootfs.absolutePath.startsWith("/system"))
        assertTrue(!paths.rootfs.absolutePath.startsWith("/data/data/com.termux"))
    }

    @Test fun quotaDefaults_areSane() {
        val q = com.noxs.linux.runtime.ResourceQuotas()
        assertTrue(q.maxSessions in 1..32)
        assertTrue(q.maxProcessesPerSession in 16..4096)
    }
}
