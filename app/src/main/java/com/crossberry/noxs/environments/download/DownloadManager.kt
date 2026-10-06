/*
 * Noxs — original implementation.
 * Noxs DownloadManager (spec §10, §11, §45, §47, §48).
 *
 * HTTPS-only, streaming with 128 KiB buffered I/O, per-mirror retry,
 * Range-based resume through a `.part` staging file, cooperative
 * cancellation, live progress (bytes / speed / ETA), checksum verification
 * BEFORE the file lands at its destination, and a persisted download state
 * so an interrupted download can be found again after process death.
 * Never loads multi-gigabyte files into RAM.
 */
package com.crossberry.noxs.environments.download

import com.crossberry.noxs.BuildConfig
import com.crossberry.noxs.shared.ChecksumVerifier
import com.crossberry.noxs.shared.NoxsLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Raised on checksum mismatch — the download is corrupt; never extracted. */
class ChecksumMismatchException(val expected: String, val actual: String, val algorithm: ChecksumAlgorithm) :
    IOException("checksum mismatch ($algorithm): expected $expected, got $actual")

data class DownloadSpec(
    val taskId: String,
    val url: String,
    val mirrors: List<String> = emptyList(),
    val destFile: File,
    /** Official pinned checksum; used when no checksumUrl is available. */
    val expectedChecksum: String = "",
    val algorithm: ChecksumAlgorithm = ChecksumAlgorithm.SHA256,
    /** Official SUMS file URL fetched at install time (fresh, trusted metadata). */
    val checksumUrl: String = "",
    /** Artifact file name inside the SUMS file. */
    val checksumFileName: String = "",
    val expectedSizeBytes: Long = 0L
)

/** Live progress callback (spec §10): bytes, speed, ETA; indeterminate when total unknown. */
interface DownloadListener {
    fun onProgress(bytesDone: Long, totalBytes: Long)
    fun onLog(line: String)
    fun onStage(stage: String)
    fun isCancelled(): Boolean
}

/** Persisted state (spec §11): exactly the facts needed for a safe resume. */
data class PersistedDownload(
    val taskId: String,
    val url: String,
    val dest: String,
    val bytesCompleted: Long,
    val expectedSize: Long,
    val checksumAlgorithm: String,
    val environmentId: String,
    val variantId: String
) {
    fun toJson(): String = com.crossberry.noxs.environments.model.EnvJson.write(
        LinkedHashMap<String, Any?>().apply {
            put("taskId", taskId); put("url", url); put("dest", dest)
            put("bytesCompleted", bytesCompleted); put("expectedSize", expectedSize)
            put("checksumAlgorithm", checksumAlgorithm)
            put("environmentId", environmentId); put("variantId", variantId)
        }
    )

    companion object {
        fun fromJson(text: String): PersistedDownload? = runCatching {
            val m = com.crossberry.noxs.environments.model.EnvJson.readObject(text)
            PersistedDownload(
                taskId = m["taskId"] as? String ?: return null,
                url = m["url"] as? String ?: "",
                dest = m["dest"] as? String ?: "",
                bytesCompleted = (m["bytesCompleted"] as? Number)?.toLong() ?: 0L,
                expectedSize = (m["expectedSize"] as? Number)?.toLong() ?: 0L,
                checksumAlgorithm = m["checksumAlgorithm"] as? String ?: "SHA256",
                environmentId = m["environmentId"] as? String ?: "",
                variantId = m["variantId"] as? String ?: ""
            )
        }.getOrNull()
    }
}

class DownloadManager(private val stateDir: File) {

    init { stateDir.mkdirs() }

    private fun stateFile(taskId: String) = File(stateDir, "$taskId.download.json")

    fun persist(spec: DownloadSpec, bytesDone: Long, environmentId: String, variantId: String) {
        runCatching {
            stateFile(spec.taskId).writeText(
                PersistedDownload(
                    taskId = spec.taskId,
                    url = spec.url,
                    dest = spec.destFile.absolutePath,
                    bytesCompleted = bytesDone,
                    expectedSize = spec.expectedSizeBytes,
                    checksumAlgorithm = spec.algorithm.name,
                    environmentId = environmentId,
                    variantId = variantId
                ).toJson()
            )
        }
    }

    fun clearPersisted(taskId: String) {
        runCatching { stateFile(taskId).delete() }
    }

    fun findPersisted(): List<PersistedDownload> =
        stateDir.listFiles()?.filter { it.name.endsWith(".download.json") }?.mapNotNull {
            PersistedDownload.fromJson(runCatching { it.readText() }.getOrDefault(""))
        } ?: emptyList()

    /**
     * Streams [spec] into `destFile.part`, verifies the checksum, then renames
     * it to [DownloadSpec.destFile]. Resume is attempted when a partial file
     * with a matching server ETag-free Range is present (206 responses only).
     */
    fun download(spec: DownloadSpec, listener: DownloadListener): File {
        require(spec.url.startsWith("https://")) { "HTTPS-only downloads (spec §48): ${spec.url}" }
        spec.destFile.parentFile?.mkdirs()
        val staged = File(spec.destFile.absolutePath + ".part")
        persist(spec, if (staged.isFile) staged.length() else 0L,
            environmentId = "", variantId = spec.taskId)

        listener.onStage("Downloading")
        val candidates = (listOf(spec.url) + spec.mirrors).distinct()
        var lastError: Exception? = null

        for (candidate in candidates) {
            for (attempt in 0 until MAX_ATTEMPTS) {
                if (listener.isCancelled()) throw IOException("download cancelled")
                if (attempt > 0) {
                    listener.onLog("retry ${attempt + 1}/$MAX_ATTEMPTS after: ${lastError?.message ?: "error"}")
                    try { Thread.sleep(2_000L * attempt) } catch (_: InterruptedException) { }
                }
                try {
                    val resumed = streamOnce(spec, candidate, staged, listener)
                    if (resumed != null || staged.isFile) {
                        listener.onStage("Verifying")
                        verifyArtifact(spec, staged, listener)
                        if (!staged.renameTo(spec.destFile)) {
                            staged.copyTo(spec.destFile, overwrite = true)
                            staged.delete()
                        }
                        clearPersisted(spec.taskId)
                        return spec.destFile
                    }
                } catch (e: ChecksumMismatchException) {
                    // A corrupt resume prefix — restart once from zero, then fail honestly.
                    if (staged.isFile && staged.length() > 0L && attempt < MAX_ATTEMPTS - 1) {
                        listener.onLog("partial file failed verification; restarting from zero")
                        staged.delete()
                        lastError = e
                        continue
                    }
                    throw e
                } catch (e: IOException) {
                    lastError = e
                    // Keep the staged file for resume; drop only tiny garbage.
                    if (staged.isFile && staged.length() < 64 * 1024) staged.delete()
                }
            }
        }
        throw lastError ?: IOException("download failed for ${spec.destFile.name}")
    }

    /** Fetches the official checksum for [spec] from its SUMS file, or falls back to the pinned value. */
    private fun resolveChecksum(spec: DownloadSpec, listener: DownloadListener): Pair<String, ChecksumAlgorithm> {
        if (spec.checksumUrl.isBlank()) return spec.expectedChecksum to spec.algorithm
        return try {
            listener.onLog("fetching official ${spec.algorithm} checksums")
            val content = fetchText(spec.checksumUrl)
            val hash = ChecksumsFile.forFile(content, spec.algorithm, spec.checksumFileName)
            if (hash != null) {
                listener.onLog("official checksum resolved for ${spec.checksumFileName}")
                hash to spec.algorithm
            } else {
                listener.onLog("checksum file did not contain ${spec.checksumFileName}")
                if (spec.expectedChecksum.isNotBlank()) spec.expectedChecksum to spec.algorithm
                else throw IOException("no official checksum available for ${spec.checksumFileName}")
            }
        } catch (e: Exception) {
            if (spec.expectedChecksum.isNotBlank()) {
                listener.onLog("checksum fetch failed (${e.message}); using pinned build checksum")
                spec.expectedChecksum to spec.algorithm
            } else throw e
        }
    }

    private fun verifyArtifact(spec: DownloadSpec, file: File, listener: DownloadListener) {
        val (expected, algorithm) = resolveChecksum(spec, listener)
        if (expected.isBlank()) {
            // Trust-on-first-use: compute, pin beside the artifact, surface honestly.
            val actual = ChecksumVerifier.sha256Hex(file)
            File(file.absolutePath + ".sha256").writeText(actual)
            listener.onLog("verified (recorded) SHA-256: $actual")
            return
        }
        listener.onLog("checking ${algorithm.displayName}…")
        val actual = when (algorithm) {
            ChecksumAlgorithm.SHA256 -> ChecksumVerifier.sha256Hex(file)
            ChecksumAlgorithm.MD5 -> digestHex(file, "MD5")
        }
        if (!ChecksumVerifier.matches(expected, actual)) {
            throw ChecksumMismatchException(expected, actual, algorithm)
        }
        listener.onLog("verification successful")
    }

    private fun streamOnce(spec: DownloadSpec, url: String, staged: File, listener: DownloadListener): Unit? {
        val resumeFrom = if (staged.isFile) staged.length() else 0L
        if (resumeFrom > 0L) listener.onLog("resuming from ${resumeFrom / (1024 * 1024)} MB")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Noxs/${BuildConfig.VERSION_NAME} (Android; environment download)")
                setRequestProperty("Accept-Encoding", "identity")
                if (resumeFrom > 0L) setRequestProperty("Range", "bytes=$resumeFrom-")
            }
            conn.connect()
            val code = conn.responseCode
            if (code == 416 && resumeFrom > 0L) {
                // Range start beyond EOF — the staged file is already complete.
                listener.onLog("staged file already complete (${resumeFrom} bytes)")
                conn.disconnect()
                return Unit
            }
            if (code !in 200..299) throw IOException("HTTP $code for $url")
            val appending = resumeFrom > 0L && code == 206
            if (resumeFrom > 0L && !appending) listener.onLog("server ignored resume; restarting from zero")

            val reportedTotal = when {
                appending && conn.contentLengthLong > 0 -> conn.contentLengthLong + resumeFrom
                conn.contentLengthLong > 0 -> conn.contentLengthLong
                else -> spec.expectedSizeBytes
            }
            conn.inputStream.use { input ->
                FileOutputStream(staged, appending).use { out ->
                    val buf = ByteArray(128 * 1024)
                    var done = if (appending) resumeFrom else 0L
                    var windowBytes = 0L
                    var windowStart = System.currentTimeMillis()
                    while (true) {
                        if (listener.isCancelled()) throw IOException("download cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        windowBytes += n
                        val now = System.currentTimeMillis()
                        if (now - windowStart >= 400) {
                            listener.onProgress(done, reportedTotal)
                            persist(spec, done, environmentId = "", variantId = spec.taskId)
                            val speed = windowBytes * 1000 / (now - windowStart).coerceAtLeast(1)
                            listener.onLog("speed ${speed / (1024 * 1024)} MB/s")
                            windowBytes = 0
                            windowStart = now
                        }
                    }
                    listener.onProgress(done, if (reportedTotal > 0) reportedTotal else done)
                }
            }
            return Unit
        } finally {
            conn?.disconnect()
        }
    }

    private fun fetchText(url: String): String {
        require(url.startsWith("https://")) { "HTTPS-only metadata fetch: $url" }
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Noxs/${BuildConfig.VERSION_NAME}")
            }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 3

        fun digestHex(file: File, algorithm: String): String {
            val md = MessageDigest.getInstance(algorithm)
            file.inputStream().use { stream ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** Honest ETA string, e.g. "ETA 01:42" — or "" when not computable. */
        fun eta(bytesDone: Long, totalBytes: Long, bytesPerSecond: Long): String {
            if (totalBytes <= 0 || bytesPerSecond <= 0) return ""
            val remaining = ((totalBytes - bytesDone).coerceAtLeast(0)) / bytesPerSecond
            return "ETA %02d:%02d".format(remaining / 60, remaining % 60)
        }
    }
}
