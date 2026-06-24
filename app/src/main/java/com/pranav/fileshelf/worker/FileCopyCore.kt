package com.pranav.fileshelf.worker

import android.content.Context
import android.net.Uri
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

        // 7. Register. FileShelfRepository.add takes the write mutex and
        //    atomically persists the JSON before updating the state flow.
        val staged = StagedFile(
            id = UUID.randomUUID().toString(),
            displayName = displayName,
            mimeType = mimeType ?: resolver.getType(sourceUri) ?: "application/octet-stream",
            localPath = destFile.absolutePath,
            sizeBytes = bytesCopied,
            sha256 = hash,
            addedAt = System.currentTimeMillis()
        )
        FileShelfRepository.add(context, staged)
        return Result.success(staged)
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
