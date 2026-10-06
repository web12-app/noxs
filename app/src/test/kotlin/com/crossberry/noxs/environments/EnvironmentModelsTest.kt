package com.crossberry.noxs.environments

import com.crossberry.noxs.environments.download.ChecksumAlgorithm
import com.crossberry.noxs.environments.download.ChecksumsFile
import com.crossberry.noxs.environments.model.CompatibilityLevel
import com.crossberry.noxs.environments.model.Environment
import com.crossberry.noxs.environments.model.EnvironmentStatus
import com.crossberry.noxs.environments.model.EnvJson
import com.crossberry.noxs.environments.model.SetupFormat
import com.crossberry.noxs.environments.model.SetupState
import com.crossberry.noxs.environments.model.SetupStateMachine
import com.crossberry.noxs.environments.model.SetupTask
import com.crossberry.noxs.environments.model.StoragePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentModelsTest {

    @Test fun `environment json round trip preserves every field`() {
        val env = Environment(
            id = "kali", providerId = "kali", displayName = "Kali NetHunter Rootless",
            version = "current", architecture = "arm64-v8a", variant = "minimal",
            status = EnvironmentStatus.READY, storagePath = "/data/noxs/environments/kali",
            createdAt = 100L, updatedAt = 200L, lastUsedAt = 300L
        )
        val restored = Environment.fromJson(env.toJson())
        assertNotNull(restored)
        assertEquals(env, restored)
    }

    @Test fun `environment json tolerates malformed input`() {
        assertNull(Environment.fromJson("not json"))
        assertNull(Environment.fromJson("{}"))
    }

    @Test fun `storage plan adds download temp extracted and margin`() {
        val plan = StoragePlan.compute(downloadBytes = 1_000L, extractedBytes = 10_000L, tempBytes = 100L)
        // margin = max(256MB, 10% of extracted) = 256MB
        assertEquals(100L + 1_000L + 10_000L + 256L * 1024 * 1024, plan.totalRequired)
    }

    @Test fun `storage plan margin grows with extraction size`() {
        val big = StoragePlan.compute(1_000L, 4_000_000_000L, 0L)
        assertEquals(400_000_000L, big.marginBytes) // 10% of extracted > 256MB
    }

    @Test fun `envjson writer escapes control characters`() {
        val text = EnvJson.write(mapOf("a" to "quote\" backslash\\ newline\n"))
        val parsed = EnvJson.readObject(text)
        assertEquals("quote\" backslash\\ newline\n", parsed["a"])
    }

    @Test fun `setup format renders elapsed and bytes`() {
        assertEquals("01:42", SetupFormat.elapsed(0L, 102_000L))
        assertEquals("00:00", SetupFormat.elapsed(0L, 0L))
        assertEquals("1.0 MB", SetupFormat.bytes(1_048_576L))
        assertEquals("1.00 GB", SetupFormat.bytes(1_073_741_824L))
    }
}

class SetupStateMachineTest {

    @Test fun `happy path transitions are legal`() {
        SetupStateMachine.transition(SetupState.SELECTED, SetupState.CHECKING)
        SetupStateMachine.transition(SetupState.CHECKING, SetupState.PREPARING)
        SetupStateMachine.transition(SetupState.PREPARING, SetupState.DOWNLOADING)
        SetupStateMachine.transition(SetupState.DOWNLOADING, SetupState.VERIFYING)
        SetupStateMachine.transition(SetupState.VERIFYING, SetupState.EXTRACTING)
        SetupStateMachine.transition(SetupState.EXTRACTING, SetupState.CONFIGURING)
        SetupStateMachine.transition(SetupState.CONFIGURING, SetupState.CREATING_USER)
        SetupStateMachine.transition(SetupState.CREATING_USER, SetupState.CONFIGURING_SHELL)
        SetupStateMachine.transition(SetupState.CONFIGURING_SHELL, SetupState.VERIFYING_ENVIRONMENT)
        SetupStateMachine.transition(SetupState.VERIFYING_ENVIRONMENT, SetupState.READY)
    }

    @Test fun `skipping stages is illegal`() {
        assertFalse(SetupStateMachine.canTransition(SetupState.SELECTED, SetupState.EXTRACTING))
        assertFalse(SetupStateMachine.canTransition(SetupState.DOWNLOADING, SetupState.READY))
    }

    @Test fun `working states after process death need recovery`() {
        assertTrue(SetupStateMachine.needsRecovery(SetupState.DOWNLOADING))
        assertTrue(SetupStateMachine.needsRecovery(SetupState.EXTRACTING))
        assertFalse(SetupStateMachine.needsRecovery(SetupState.READY))
        assertFalse(SetupStateMachine.needsRecovery(SetupState.CANCELLED))
    }

    @Test fun `recovery can resume download or restart`() {
        SetupStateMachine.transition(SetupState.RECOVERY_REQUIRED, SetupState.DOWNLOADING)
        SetupStateMachine.transition(SetupState.RECOVERY_REQUIRED, SetupState.SELECTED)
        SetupStateMachine.transition(SetupState.RECOVERY_REQUIRED, SetupState.NOT_SELECTED)
    }

    @Test fun `task json round trip survives process death`() {
        val task = SetupTask(
            id = "setup-kali-42", environmentId = "kali", title = "Kali setup",
            state = SetupState.DOWNLOADING, progress = 42, currentOperation = "Downloading",
            startedAt = 1L, updatedAt = 2L, logPath = "/logs/setup-kali.log",
            downloadUrl = "https://kali.download/x.tar.xz", downloadedBytes = 123L,
            expectedBytes = 456L, variant = "minimal"
        )
        val restored = SetupTask.fromJson(task.toJson())
        assertNotNull(restored)
        assertEquals(task, restored)
        assertEquals(42, restored!!.progress)
    }
}

class ChecksumsFileTest {

    @Test fun `parses standard sha256 sums format`() {
        val content = """
            # comment line
            a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2 *ubuntu-base-24.04.5-base-arm64.tar.gz
            7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048 *ubuntu-base-24.04.3-base-arm64.tar.gz
        """.trimIndent()
        assertEquals(
            "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2",
            ChecksumsFile.forFile(content, ChecksumAlgorithm.SHA256, "ubuntu-base-24.04.5-base-arm64.tar.gz")
        )
    }

    @Test fun `parses kali sums with two spaces and no star`() {
        val content = "d6403a5da175df325611d23af4b92330856059c45454eced7f4cdf3ca6df2e4e  kali-nethunter-rootfs-minimal-arm64.tar.xz\n"
        assertEquals(
            "d6403a5da175df325611d23af4b92330856059c45454eced7f4cdf3ca6df2e4e",
            ChecksumsFile.forFile(content, ChecksumAlgorithm.SHA256, "kali-nethunter-rootfs-minimal-arm64.tar.xz")
        )
    }

    @Test fun `parses bsd md5 style`() {
        val content = "MD5 (ArchLinuxARM-aarch64-latest.tar.gz) = 23eec86365b24f7913c403e8f4e8719b\n"
        assertEquals(
            "23eec86365b24f7913c403e8f4e8719b",
            ChecksumsFile.forFile(content, ChecksumAlgorithm.MD5, "ArchLinuxARM-aarch64-latest.tar.gz")
        )
    }

    @Test fun `returns null for unknown file and rejects bad hex`() {
        assertNull(ChecksumsFile.forFile("nope\n", ChecksumAlgorithm.SHA256, "missing.tar"))
        assertTrue(ChecksumsFile.parse("zzzz  file.tar", ChecksumAlgorithm.SHA256).isEmpty())
    }

    @Test fun `hash length validation matches algorithm`() {
        assertTrue(ChecksumsFile.validate(
            com.crossberry.noxs.environments.download.ChecksumEntry(ChecksumAlgorithm.SHA256, "a".repeat(64), "f")))
        assertFalse(ChecksumsFile.validate(
            com.crossberry.noxs.environments.download.ChecksumEntry(ChecksumAlgorithm.SHA256, "a".repeat(32), "f")))
    }
}

class CompatibilityEngineTest {

    private fun device(
        abi: String = "arm64-v8a",
        sdk: Int = 29,
        ram: Long = 4L * 1024 * 1024 * 1024,
        storage: Long = 8L * 1024 * 1024 * 1024,
        network: Boolean = true
    ) = DeviceProfile(abi, listOf(abi), sdk, ram, storage, network)

    @Test fun `arm64 device with space is supported`() {
        val report = CompatibilityEngine.report(device(), requiredStorageBytes = 4L * 1024 * 1024 * 1024)
        assertEquals(CompatibilityLevel.SUPPORTED, report.level)
    }

    @Test fun `wrong architecture is unsupported with an honest reason`() {
        val report = CompatibilityEngine.report(device(abi = "x86"), requiredStorageBytes = 1024L)
        assertEquals(CompatibilityLevel.UNSUPPORTED, report.level)
        assertTrue(report.reasons.isNotEmpty())
    }

    @Test fun `insufficient storage is unsupported`() {
        val report = CompatibilityEngine.report(
            device(storage = 100L), requiredStorageBytes = 4L * 1024 * 1024 * 1024)
        assertEquals(CompatibilityLevel.UNSUPPORTED, report.level)
    }

    @Test fun `low ram is limited not unsupported`() {
        val report = CompatibilityEngine.report(
            device(ram = 300L * 1024 * 1024), requiredStorageBytes = 1024L)
        assertEquals(CompatibilityLevel.LIMITED, report.level)
    }

    @Test fun `no network is unsupported for downloads`() {
        val report = CompatibilityEngine.report(device(network = false), requiredStorageBytes = 1024L)
        assertEquals(CompatibilityLevel.UNSUPPORTED, report.level)
    }
}
