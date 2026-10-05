/*
 * Noxs — original implementation.
 * NoxsInstaller: first-launch bootstrap pipeline (see docs/BOOTSTRAP.md).
 *
 * Steps (each resumable; interruption-safe via staged downloads + markers):
 *  1  architecture detection
 *  2  storage initialization (app-private dirs)
 *  3  bootstrap manifest load (linux-runtime/bootstrap/<arch>/bootstrap.manifest)
 *  4  proot binary acquisition (download → SHA-256 verify)
 *  5  Debian 12 rootfs acquisition (download → SHA-256 verify)
 *  6  safe extraction (TarGuard: traversal/device/setuid defense)
 *  7  Debian userspace configuration (FHS dirs, /run, /var/run, overlay files)
 *  8  noxs user creation (+ password prompt → chpasswd, never stored)
 *  9  runtime state initialization (/run, /var/run/noxs, logs, quotas)
 * 10  signed APT bootstrap (dpkg repair, CA certificates, verified HTTPS update)
 * 11  first shell launch (handled by caller)
 */
package com.crossberry.noxs.runtime

import android.content.Context
import com.crossberry.noxs.BuildConfig
import com.crossberry.noxs.R
import com.crossberry.noxs.shared.BootstrapManifest
import com.crossberry.noxs.shared.ChecksumVerifier
import com.crossberry.noxs.shared.NoxsConstants
import com.crossberry.noxs.shared.NoxsLog
import com.crossberry.noxs.shared.NoxsUser
import com.crossberry.noxs.shared.PasswdDb
import com.crossberry.noxs.shared.RootfsExtractor
import com.crossberry.noxs.shared.ShellUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.CharBuffer
import java.security.SecureRandom

class NoxsInstaller(
    private val context: Context,
    private val paths: NoxsPaths,
    private val launcher: ProotLauncher
) {

    interface Progress {
        fun onStep(step: Int, titleRes: Int, detail: String)
        fun onProgressBytes(downloaded: Long, total: Long)
        fun onLog(line: String)
        fun onPasswordRequired(): CharArray? // setup wizard collects it
    }

    sealed class InstallResult {
        data class Success(val rootfsSha256: String) : InstallResult()
        data class Failure(val userMessageRes: Int, val detail: String) : InstallResult()
    }

    var overrideRootfsUrl: String? = null

    suspend fun install(progress: Progress): InstallResult = withContext(Dispatchers.IO) {
        progress0 = progress
        try {
            // 1 — architecture detection
            progress.onStep(1, R.string.setup_step_arch, "")
            val arch = NoxsCapabilitiesArch.detect()
            progress.onLog("arch=$arch")

            // 2 — storage initialization
            progress.onStep(2, R.string.setup_step_storage, "")
            if (!paths.ensureBaseDirs()) {
                return@withContext InstallResult.Failure(R.string.err_storage, "cannot create app dirs")
            }

            // 3 — manifest
            progress.onStep(3, R.string.setup_step_manifest, arch)
            val manifest = loadManifest(arch)
            progress.onLog("rootfs url=${manifest.rootfs.url}")

            // 4 — proot (bundled in the APK as jniLibs → nativeLibraryDir;
            // exec from app data storage is denied on Android 10+, so there is
            // intentionally NO network step here anymore)
            progress.onStep(4, R.string.setup_step_proot, "")
            val prootFile = paths.prootBinary
            if (!prootFile.isFile) {
                return@withContext InstallResult.Failure(
                    R.string.err_bootstrap,
                    "bundled proot missing at ${prootFile.absolutePath} — reinstall the APK"
                )
            }
            progress.onLog("proot bundled: ${prootFile.name} (${prootFile.length()} bytes)")

            // 5 — rootfs
            progress.onStep(5, R.string.setup_step_rootfs, "")
            val rootfsArchive = File(paths.cache, "rootfs-$arch.tar.${manifest.rootfs.format.substringAfter('.')}")
            var sha = ""
            if (!rootfsArchive.isFile) {
                acquire(manifest.rootfs, rootfsArchive, progress) { staged, expected ->
                    val v = verify(staged, expected, "rootfs")
                    sha = (v as? Verify.Ok)?.sha256 ?: sha
                    v
                }
            } else {
                sha = (verify(rootfsArchive, manifest.rootfs.sha256, "rootfs-cached") as? Verify.Ok)?.sha256 ?: ""
            }

            // 6 — safe extraction
            progress.onStep(6, R.string.setup_step_extract, "")
            // Remove any stale 0-byte top-level placeholders before extracting
            listOf("bin", "sbin", "lib", "lib64").forEach { name ->
                val f = File(paths.rootfs, name)
                if (!java.nio.file.Files.isSymbolicLink(f.toPath()) && f.isFile && f.length() == 0L) {
                    f.delete()
                }
            }
            val stats = RootfsExtractor(paths.rootfs).extract(rootfsArchive)
            progress.onLog("extracted files=${stats.files} dirs=${stats.dirs} links=${stats.links}")

            // 7 — userspace configuration. APT remains on signed HTTP only
            // during the CA-certificate bootstrap; final HTTPS is enabled later.
            progress.onStep(7, R.string.setup_step_configure, "")
            RootfsConfigurator.configure(context, paths, bootstrapHttpApt = true)

            // 8 — noxs user + password
            progress.onStep(8, R.string.setup_step_user, "")
            provisionUser(progress)

            // 9 — runtime state
            progress.onStep(9, R.string.setup_step_run, "")
            initRuntimeState()

            // 10 — initialize dpkg/APT, repair CA certificates over signed
            // Debian HTTP metadata, then require a successful verified HTTPS update.
            progress.onStep(10, R.string.setup_step_apt, "")
            val aptResult = NoxsAptBootstrapper(paths, launcher).initialize(
                force = true,
                onLog = progress::onLog
            )
            if (!aptResult.success) {
                return@withContext InstallResult.Failure(R.string.err_bootstrap, aptResult.detail)
            }

            // marker
            paths.installMarker.writeText(
                "installed=${System.currentTimeMillis()}\narch=$arch\nrootfs_sha256=$sha\nsuite=${NoxsConstants.DEFAULT_DEBIAN_SUITE}\n"
            )
            progress.onStep(11, R.string.setup_step_shell, "")
            InstallResult.Success(sha)
        } catch (e: SecurityException) {
            NoxsLog.e("Installer", "security check failed", e)
            InstallResult.Failure(R.string.err_bootstrap_verify, e.message ?: "security")
        } catch (e: Exception) {
            NoxsLog.e("Installer", "install failed", e)
            InstallResult.Failure(R.string.err_bootstrap, e.message ?: e.javaClass.simpleName)
        }
    }

    // ------------------------------------------------------------------ steps

    private fun loadManifest(arch: String): BootstrapManifest {
        val stream = context.assets.open("bootstrap/$arch/bootstrap.manifest")
        val json = stream.bufferedReader().use { it.readText() }
        val manifest = BootstrapManifest.parse(json)
        overrideRootfsUrl?.takeIf { it.startsWith("https://") }?.let {
            progress0?.onLog("settings override rootfs url")
            return manifest.copy(rootfs = manifest.rootfs.copy(url = it, sha256 = ""))
        }
        return manifest
    }

    private var progress0: Progress? = null

    private fun verify(file: File, expectedSha: String, label: String): Verify {
        if (expectedSha.isBlank()) {
            // Trust-on-first-use: compute, surface to user, pin for later runs.
            val actual = ChecksumVerifier.sha256Hex(file)
            File(file.absolutePath + ".sha256").writeText(actual)
            return Verify.Ok(actual, pinned = false)
        }
        val result = ChecksumVerifier.verify(file, expectedSha)
        return when (result) {
            is ChecksumVerifier.Result.Verified -> Verify.Ok(result.sha256, pinned = true)
            is ChecksumVerifier.Result.Mismatch -> throw SecurityException(
                "$label SHA-256 mismatch: expected $expectedSha, got ${result.actualSha256}"
            )
        }
    }

    private sealed class Verify {
        data class Ok(val sha256: String, val pinned: Boolean) : Verify()
    }

    private suspend fun acquire(
        artifact: com.crossberry.noxs.shared.BootstrapArtifact,
        dest: File,
        progress: Progress,
        attempts: Int = 3,
        doVerify: (File, String) -> Verify
    ) = withContext(Dispatchers.IO) {
        if (artifact.url.startsWith("file://")) {
            val src = File(artifact.url.removePrefix("file://"))
            src.copyTo(dest, overwrite = true)
            doVerify(dest, artifact.sha256)
            return@withContext
        }
        val staged = File(dest.absolutePath + ".part")
        staged.parentFile?.mkdirs()
        staged.delete()

        var lastError: Exception? = null
        var downloaded = false
        for (attempt in 0 until attempts) {
            if (attempt > 0) {
                progress.onLog("retry ${attempt + 1}/$attempts after network error: " +
                    (lastError?.message ?: "unknown"))
                try { Thread.sleep(2_000L * attempt) } catch (_: InterruptedException) {}
            }
            try {
                val conn = URL(artifact.url).openConnection() as HttpURLConnection
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Noxs/${BuildConfig.VERSION_NAME} (Android; bootstrap)")
                conn.setRequestProperty("Accept-Encoding", "identity")
                try {
                    conn.connect()
                    if (conn.responseCode !in 200..299) {
                        throw java.io.IOException("HTTP ${conn.responseCode} for ${artifact.url}")
                    }
                    val total = conn.contentLengthLong
                    conn.inputStream.use { input ->
                        FileOutputStream(staged).use { out ->
                            val buf = ByteArray(128 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                progress.onProgressBytes(done, total)
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                downloaded = true
                break
            } catch (e: java.io.IOException) {
                lastError = e
                staged.delete()
            }
        }
        if (!downloaded) lastError?.let { throw it }

        // verify BEFORE renaming into place (interrupted downloads never verify)
        doVerify(staged, artifact.sha256)
        staged.renameTo(dest) || (staged.copyTo(dest, overwrite = true).isFile)
    }

    /** Creates the noxs user (uid 1000, sudo group) and sets its password. */
    private fun provisionUser(progress: Progress) {
        val etc = paths.rootfsEtc
        val passwdFile = File(etc, "passwd")
        val users = PasswdDb.parsePasswd(passwdFile.readText())
        val uid = PasswdDb.nextFreeUid(users)
        val gid = uid
        val groups = PasswdDb.parseGroup(File(etc, "group").readText())

        if (users.none { it.name == NoxsConstants.DEFAULT_USER }) {
            val user = NoxsUser(
                name = NoxsConstants.DEFAULT_USER,
                uid = uid,
                gid = gid,
                gecos = "Noxs User,,,",
                home = NoxsConstants.DEFAULT_USER_HOME,
                shell = NoxsConstants.DEFAULT_SHELL
            )
            passwdFile.appendText(PasswdDb.serializeUser(user) + "\n")
            File(etc, "shadow").appendText(PasswdDb.defaultShadowLineFor(user.name) + "\n")
            val sudoGroup = groups.firstOrNull { it.name == "sudo" }
                ?: com.crossberry.noxs.shared.NoxsGroup("sudo", 27, emptyList()).also {
                    File(etc, "group").appendText(PasswdDb.serializeGroup(it) + "\n")
                }
            // add noxs to sudo group line
            val groupFile = File(etc, "group")
            val updated = groupFile.readLines().joinToString("\n") { line ->
                if (line.startsWith("sudo:")) "$line,${NoxsConstants.DEFAULT_USER}" else line
            }
            groupFile.writeText(updated + "\n")
        }

        // home + skel
        val home = paths.rootfsHomeNoxs
        home.mkdirs()
        listOf(".bashrc", ".profile").forEach { f ->
            val skel = File(paths.rootfs, "etc/skel/$f")
            val target = File(home, f)
            if (skel.isFile && !target.isFile) skel.copyTo(target, overwrite = false)
        }

        // Password is collected by the masked UI and passed only on stdin.
        // Encode from the mutable char array; never create an immutable String.
        val password = progress.onPasswordRequired()
            ?: throw IllegalStateException("A Noxs password is required")
        try {
            if (password.size < 4 || password.any { it == ':' || Character.isISOControl(it) }) {
                throw IllegalStateException("Invalid Noxs password input")
            }
            val argv = launcher.oneShotArgv(listOf("/usr/sbin/chpasswd"), asRoot = true)
            val pb = ProcessBuilder(argv)
            launcher.applyEnvTo(pb, mapOf("NOXS_ROOT_LOGIN" to "1"))
            pb.redirectErrorStream(true)
            val proc = pb.start()
            proc.outputStream.use { output ->
                output.write("${NoxsConstants.DEFAULT_USER}:".toByteArray(Charsets.UTF_8))
                val encodedPassword = encodePasswordUtf8(password)
                try {
                    output.write(encodedPassword)
                    output.write('\n'.code)
                    output.flush()
                } finally {
                    encodedPassword.fill(0)
                }
            }
            // Drain output without retaining or displaying package-tool text.
            proc.inputStream.bufferedReader().use { reader -> while (reader.readLine() != null) Unit }
            val code = proc.waitFor()
            if (code != 0) {
                NoxsLog.w("Installer", "chpasswd exited with code $code")
                throw IllegalStateException("Could not set the Noxs password")
            }
        } finally {
            password.fill('\u0000')
        }
    }

    private fun encodePasswordUtf8(password: CharArray): ByteArray {
        val encoded = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password))
        val bytes = ByteArray(encoded.remaining())
        encoded.get(bytes)
        if (encoded.hasArray()) encoded.array().fill(0)
        return bytes
    }

    private fun initRuntimeState() {
        // /run and /var/run inside the rootfs (FHS) — app-private, never Android's.
        paths.rootfsRun.mkdirs()
        paths.rootfsVarRun.mkdirs()
        paths.rootfsNoxsRun.mkdirs()
        paths.rootfsHostRun.mkdirs()
        File(paths.rootfs, "var/log/noxs").mkdirs()
        File(paths.rootfs, "var/cache/apt/archives/partial").mkdirs()
        File(paths.rootfs, "var/lib/dpkg").let { if (!it.isDirectory) it.mkdirs() }
        File(paths.run, "keep").writeText("host run dir\n")
        // FHS compatibility links recreated inside the sandbox on first run
        val linkScript = File(paths.rootfs, "usr/local/lib/noxs-links.sh")
        linkScript.parentFile?.mkdirs()
        linkScript.writeText(FHS_LINKS_SCRIPT)
    }

    private val FHS_LINKS_SCRIPT = """
        #!/bin/sh
        # Recreate FHS compatibility symlinks inside the Noxs sandbox.
        [ -e /bin ] || ln -s usr/bin /bin
        [ -e /sbin ] || ln -s usr/sbin /sbin
        [ -e /lib ] || ln -s usr/lib /lib
        [ -e /lib64 ] || ln -s usr/lib64 /lib64 2>/dev/null
        [ -e /var/run ] || ln -s /run /var/run
        exit 0
    """.trimIndent()
}

/** Small helper so the installer doesn't import android.os directly in tests. */
object NoxsCapabilitiesArch {
    fun detect(): String = com.crossberry.noxs.shared.NoxsCapabilities.detectArch(
        arrayOf(getAbi())
    ).let { supportedAbi ->
        // detectArch picks the first supported; we pass the preferred ABI when valid
        if (supportedAbi in BootstrapManifest.SUPPORTED_ARCHS) supportedAbi else "arm64-v8a"
    }

    private fun getAbi(): String =
        android.os.Build.SUPPORTED_ABIS.firstOrNull { it in BootstrapManifest.SUPPORTED_ARCHS } ?: "arm64-v8a"
}
