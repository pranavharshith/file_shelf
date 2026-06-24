package com.pranav.fileshelf.worker

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.pranav.fileshelf.data.FileShelfRepository
import com.pranav.fileshelf.data.StagedFile
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * The single byte-copy primitive used by every path that adds a file to the
 * shelf:
 *
 *  - [FileCopyWorker]                      — system share sheet (`ACTION_SEND`),
 *                                            async, surfaces progress notifications.
 *  - `OverlayService.handleReceivedContent` — drag-in from another app via
 *                                            [android.view.OnReceiveContentListener].
 *
 * Responsibilities:
 *  - Open the source `InputStream` and translate platform errors into
 *    `Result.failure(...)` with a descriptive `Exception.message`.
 *  - Enforce the caller-supplied byte cap **both** pre-stream (via the
 *    provider's `_SIZE`/`statSize` hint when present) **and** mid-stream
 *    (mandatory — many providers, Drive in particular, return null for
 *    `_SIZE`; the only honest check is counting bytes as we write).
 *  - Compute SHA-256 in a single pass while writing (no second disk read).
 *  - Dedupe by `(displayName, size)` before copying and by SHA-256 after.
 *  - Atomically register the resulting [StagedFile] via [FileShelfRepository].
 *
 * Notifications, foreground-service interaction, and user-facing toasts are
 * caller-specific and live in the wrappers, not here. Progress is surfaced
 * only via [onProgress]; the caller decides whether to render anything.
 *
 * Error message conventions (callers parse these by substring):
 *  - `"Permission denied to access file"`     — source URI not readable.
 *  - `"Cannot open file"` / `"Cannot open URI"` — open failed otherwise.
 *  - `"File size (X MB) exceeds the Y MB limit"` — caught pre-stream.
 *  - `"File exceeded Y MB limit during copy"`    — caught mid-stream.
 *  - `"File copy incomplete (size mismatch)"`    — provider misreported size.
 */
internal object FileCopyCore {

    private const val BUFFER_SIZE = 8192
    private const val TAG = "FileCopyCore"

    /**
     * Runs on the caller's coroutine context. **Does NOT switch to
     * `Dispatchers.IO` internally** — that decision is delegated to the
     * caller. Both current callers wrap their call appropriately:
     *
     *  - [FileCopyWorker] wraps in `withContext(Dispatchers.IO)` for the
     *    async share-sheet path.
     *  - `OverlayService.handleReceivedContent` runs inside an
     *    `appScope.launch(Dispatchers.IO)` block — the URI permission is
     *    managed by the platform's [android.view.OnReceiveContentListener]
     *    and survives thread switches because the grant is issued at the
     *    process level when the content is delivered via that API.
     */
    suspend fun copy(
        context: Context,
        sourceUri: Uri,
        displayName: String,
        mimeType: String?,
        maxBytes: Long,
        onProgress: suspend (bytesCopied: Long, totalSize: Long?) -> Unit = { _, _ -> }
    ): Result<StagedFile> {
        val resolver = context.contentResolver

        // 1. Open the source. Translate platform errors so every caller
        //    observes the same error vocabulary.
        val input = try {
            resolver.openInputStream(sourceUri)
        } catch (e: SecurityException) {
            android.util.Log.e(TAG, "Permission denied for URI: $sourceUri", e)
            return Result.failure(Exception("Permission denied to access file"))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Cannot open URI: $sourceUri", e)
            return Result.failure(Exception("Cannot open file"))
        }
        if (input == null) {
            return Result.failure(Exception("Cannot open URI"))
        }

        // 2. Probe size. `_SIZE`/`statSize` is advisory — some providers
        //    (Drive, Chrome) return null/0. Used only to fail fast on
        //    oversize; real cap is the mid-stream check below.
        val totalSize = try {
            resolver.openFileDescriptor(sourceUri, "r")?.use { fd ->
                fd.statSize.takeIf { it > 0 }
            }
        } catch (e: Exception) {
            android.util.Log.d(TAG, "openFileDescriptor probe failed: ${e.message}")
            null
        }

        if (totalSize != null && totalSize > maxBytes) {
            val limitMB = maxBytes / (1024 * 1024)
            val fileSizeMB = totalSize / (1024 * 1024)
            input.close()
            return Result.failure(
                Exception("File size ($fileSizeMB MB) exceeds the $limitMB MB limit")
            )
        }

        // 3. Cheapest dedupe: same name + same size. Skips both the copy
        //    and the hash work.
        val existing = FileShelfRepository.findByNameAndSize(context, displayName, totalSize)
        if (existing != null) {
            input.close()
            return Result.success(existing)
        }

        // 4. Stream + hash in one pass.
        val destFile = FileShelfRepository.createDestFile(context, displayName)
        val digest = MessageDigest.getInstance("SHA-256")
        val bytesCopied = try {
            streamToDisk(input, destFile, digest, maxBytes, totalSize, onProgress)
        } catch (e: Exception) {
            // Single cleanup site for all stream failures. `use { }` already
            // closed the streams; we only need to drop the partial dest so
            // cacheDir/shelf/ never accumulates corrupt entries.
            if (destFile.exists()) destFile.delete()
            android.util.Log.w(TAG, "stream failed for ${destFile.name}: ${e.message}", e)
            return Result.failure(e)
        }

        // 5. Integrity check: if the provider claimed a size, it must match.
        if (totalSize != null && bytesCopied != totalSize) {
            destFile.delete()
            android.util.Log.w(TAG, "Size mismatch: expected $totalSize, got $bytesCopied")
            return Result.failure(Exception("File copy incomplete (size mismatch)"))
        }

        // 6. Content-hash dedupe. Same bytes under a different name / size
        //    metadata still collapse to one shelf entry.
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val hashDupe = FileShelfRepository.findByHash(context, hash)
        if (hashDupe != null) {
            destFile.delete()
            return Result.success(hashDupe)
        }

        // 7. Refuse to stage anything provably broken. Catches:
        //    • Zero-byte payloads (the interrupted-share case — looks fine
        //      on disk, silently rejected by every downstream receiver).
        //    • Files whose magic bytes don't match the declared MIME
        //      (corrupt download claiming to be a JPEG, partial PDF, etc.).
        //   The drop into another app would otherwise just "do nothing" and
        //   the user would have no idea why. Better to fail at the gate.
        val resolvedMime = mimeType
            ?: resolver.getType(sourceUri)
            ?: mimeFromExtension(displayName)
            ?: "application/octet-stream"

        detectCorruption(destFile, resolvedMime, bytesCopied)?.let { reason ->
            destFile.delete()
            android.util.Log.w(TAG, "Rejected ${destFile.name}: $reason ($resolvedMime)")
            return Result.failure(Exception("File appears to be corrupt"))
        }

        // 8. Register. FileShelfRepository.add takes the write mutex and
        //    atomically persists the JSON before updating the state flow.
        //
        // Mime resolution order (computed above for the corruption check):
        //   1. Caller-supplied (intent's mime, drag-in clip mime)
        //   2. ContentResolver.getType(uri)
        //   3. Filename extension via MimeTypeMap
        //   4. application/octet-stream
        //
        // Step 3 is the important one — without it, anything shared from
        // an app that emits a wildcard mime (file managers, some browsers)
        // lands as application/octet-stream and shows as a generic "FILE"
        // chip even when the name is obviously `report.pdf`.
        val staged = StagedFile(
            id = UUID.randomUUID().toString(),
            displayName = displayName,
            mimeType = resolvedMime,
            localPath = destFile.absolutePath,
            sizeBytes = bytesCopied,
            sha256 = hash,
            addedAt = System.currentTimeMillis()
        )
        FileShelfRepository.add(context, staged)
        return Result.success(staged)
    }

    private fun mimeFromExtension(displayName: String): String? {
        val ext = displayName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .takeIf { it.isNotEmpty() } ?: return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    // ── Corruption detection ─────────────────────────────────────────────
    //
    // Format sniff is intentionally conservative: only a handful of
    // high-confidence magic-byte signatures, no decode probes. The point
    // is to catch the obvious "0 bytes" and "downloaded HTML page named
    // photo.jpg" cases, not to validate every format on earth. Anything
    // we can't confidently classify, we pass through.

    private class Magic(val offset: Int, val bytes: ByteArray)

    /**
     * @return a non-null human-readable reason if the file is corrupt,
     *         null if it passes (or if we have no signature for this mime).
     */
    private fun detectCorruption(destFile: File, resolvedMime: String, bytesCopied: Long): String? {
        if (bytesCopied == 0L) return "empty"

        val magics = magicForMime(resolvedMime) ?: return null
        val needed = magics.maxOf { it.offset + it.bytes.size }
        if (bytesCopied < needed) return "truncated"

        val header = ByteArray(needed)
        val read = try {
            destFile.inputStream().use { it.read(header, 0, needed) }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Header read failed for ${destFile.name}: ${e.message}")
            return "unreadable"
        }
        if (read < needed) return "truncated"

        val ok = magics.all { m ->
            m.bytes.indices.all { i -> header[m.offset + i] == m.bytes[i] }
        }
        return if (ok) null else "signature mismatch"
    }

    private fun magicForMime(mime: String): List<Magic>? = when (mime) {
        "image/jpeg", "image/jpg" -> listOf(
            Magic(0, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
        )
        "image/png" -> listOf(
            Magic(0, byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
            ))
        )
        "image/gif" -> listOf(
            // "GIF8" — covers both GIF87a and GIF89a.
            Magic(0, byteArrayOf(0x47, 0x49, 0x46, 0x38))
        )
        "image/webp" -> listOf(
            Magic(0, byteArrayOf(0x52, 0x49, 0x46, 0x46)), // RIFF
            Magic(8, byteArrayOf(0x57, 0x45, 0x42, 0x50))  // WEBP
        )
        "image/bmp" -> listOf(
            Magic(0, byteArrayOf(0x42, 0x4D)) // BM
        )
        "application/pdf" -> listOf(
            Magic(0, byteArrayOf(0x25, 0x50, 0x44, 0x46)) // %PDF
        )
        // ISO base-media file format family. The brand at offset 8 varies
        // (heic, heix, mif1, msf1, mp41, mp42, isom, qt, …) so we only
        // verify the "ftyp" box marker at offset 4. False negatives are
        // negligible vs the value of catching 0-byte/HTML-pretending-to-be-
        // video corruption.
        "video/mp4", "video/quicktime", "video/3gpp",
        "image/heic", "image/heif" -> listOf(
            Magic(4, byteArrayOf(0x66, 0x74, 0x79, 0x70)) // "ftyp"
        )
        else -> null
    }

    /**
     * Streams `input` into `destFile`, feeding `digest` along the way and
     * throwing if the running byte total ever exceeds [maxBytes].
     *
     * Always closes the input and output via `use { }`, including the
     * mid-stream cap path. Caller is responsible for deleting the partial
     * `destFile` on failure.
     *
     * @throws Exception with message `"File exceeded N MB limit during copy"`
     *         when the cap is breached, or the underlying I/O exception.
     */
    private suspend fun streamToDisk(
        input: InputStream,
        destFile: File,
        digest: MessageDigest,
        maxBytes: Long,
        totalSize: Long?,
        onProgress: suspend (bytesCopied: Long, totalSize: Long?) -> Unit
    ): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var bytesCopied = 0L

        input.use { ins ->
            destFile.outputStream().use { out ->
                while (true) {
                    val read = ins.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    bytesCopied += read

                    if (bytesCopied > maxBytes) {
                        val limitMB = maxBytes / (1024 * 1024)
                        throw Exception("File exceeded $limitMB MB limit during copy")
                    }

                    onProgress(bytesCopied, totalSize)
                }
            }
        }
        return bytesCopied
    }
}
