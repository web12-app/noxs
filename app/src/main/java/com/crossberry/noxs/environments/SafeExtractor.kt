/*
 * Noxs — original implementation.
 * SafeExtractor (spec §13, §48): secure archive extraction facade.
 *
 * Before extracting anything it validates:
 *   - the archive lives inside the Noxs-controlled boundary
 *   - the destination is inside the same boundary
 *   - every entry stays inside the destination (TarGuard: absolute paths,
 *     ../ traversal, device files, setuid bits are rejected)
 *   - symlinks never resolve outside the destination root
 * Extraction never targets arbitrary Android filesystem locations.
 */
package com.crossberry.noxs.environments

import com.crossberry.noxs.shared.ExtractedStats
import com.crossberry.noxs.shared.NoxsLog
import com.crossberry.noxs.shared.RootfsExtractor
import java.io.File
import java.io.IOException

class SafeExtractor(private val boundaryRoot: File) {

    init {
        val canonical = boundaryRoot.canonicalFile
        require(canonical.absolutePath.contains("/files") || canonical.absolutePath.contains("/noxs")) {
            "SafeExtractor boundary must be Noxs-controlled storage: ${canonical.absolutePath}"
        }
    }

    /**
     * Extracts [archive] into [destDir]. Returns the entry counts.
     * [onProgress] reports cumulative entries; [beforeEntry] is a cooperative
     * cancellation check invoked before every tar entry.
     */
    fun extract(
        archive: File,
        destDir: File,
        onProgress: (Long) -> Unit = {},
        beforeEntry: () -> Unit = {}
    ): ExtractedStats {
        assertInside(archive, "archive")
        assertInside(destDir, "destination")
        if (!archive.isFile) throw IOException("archive is missing: ${archive.name}")
        destDir.mkdirs()

        // Defense in depth: re-check the boundary right before extraction.
        val boundary = boundaryRoot.canonicalFile.toPath()
        if (!destDir.canonicalFile.toPath().startsWith(boundary)) {
            throw SecurityException("destination escapes Noxs storage boundary")
        }
        NoxsLog.i("SafeExtract", "extracting ${archive.name} into ${destDir.name} (boundary=${boundaryRoot.name})")
        val extractor = RootfsExtractor(destDir)
        return extractor.extract(
            archive,
            onProgress = onProgress,
            beforeEntry = beforeEntry
        )
    }

    /** Removes a partial extraction target directory safely (inside boundary only). */
    fun cleanPartial(destDir: File) {
        assertInside(destDir, "destination")
        if (destDir.exists()) {
            require(destDir.canonicalFile.toPath().startsWith(boundaryRoot.canonicalFile.toPath())) {
                "refusing to delete outside boundary"
            }
            destDir.deleteRecursively()
            NoxsLog.i("SafeExtract", "cleaned partial extraction ${destDir.name}")
        }
    }

    private fun assertInside(file: File, label: String) {
        val boundary = boundaryRoot.canonicalFile.toPath()
        val target = file.canonicalFile.toPath()
        if (!(target.startsWith(boundary) || target == boundary)) {
            throw SecurityException("$label is outside the Noxs storage boundary: ${file.absolutePath}")
        }
    }
}
