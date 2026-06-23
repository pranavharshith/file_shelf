package com.pranav.fileshelf.worker

import android.content.Context
import android.net.Uri
import com.pranav.fileshelf.data.FileShelfRepository
import com.pranav.fileshelf.data.StagedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID

/**
 * The single byte-copy primitive used by every path that adds a file to the
 * shelf:
 *
 *  - [FileCopyWorker]            — system share sheet (`ACTION_SEND`), async,
 *                                  surfaces progress notifications, capped at
 *                                  [FileShelfRepository.MAX_FILE_BYTES].
 *  - `DroppedClipImporter` (Phase 2) — drag-in from another app, synchronous
 *                                      inside the `ACTION_DROP` handler so the
 *                                      cross-app URI permission window stays
 *                                      open until the bytes are on disk,
 *                                      capped at `MAX_DRAG_IMPORT_BYTES`.
 *
 * Responsibilities:
 *  - Open the source `InputStream` and translate platform errors into Result.
 *  - Enforce the caller-supplied byte cap **both** pre-stream (via the
 *    provider's `_SIZE`/`statSize` hint when present) **and** mid-stream
 *    (mandatory — many providers, Drive in particular, return null for
 *    `_SIZE`; the only honest check is to count bytes as we write).
 *  - Dedupe by `(displayName, size)` before copying and by SHA-256 after.
 *  - Atomically register the resulting [StagedFile] via [FileShelfRepository].
 *
 * Notifications, foreground-service interaction, and user-facing toasts are
 * **caller-specific** and live in wrappers, not here. Progress is surfaced
 * only via [onProgress]; the caller decides whether to render anything.
 */
internal object FileCopyCore {

    private const val BUFFER_SIZE = 8192
    private const val TAG = "FileCopyCore"

    suspend fun copy(
        context: Context,
        sourceUri: Uri,
        displayName: String,
        mimeType: String?,
        maxBytes: Long,
        onProgress: suspend (bytesCopied: Long, totalSize: Long?) -> Unit = { _, _ -> }
    ): Result<StagedFile> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        val input = openSourceStream(resolver, sourceUri)
            ?: return@withContext Result.failure(Exception("Cannot open URI"))

        val totalSize = probeFileSize(resolver, sourceUri)

        checkPreCopySizeLimit(totalSize, maxBytes, input)?.let { return@withContext it }

        val existing = FileShelfRepository.findByNameAndSize(context, displayName, totalSize)
        if (existing != null) {
            input.close()
            return@withContext Result.success(existing)
        }

        val destFile = FileShelfRepository.createDestFile(context, displayName)
        val copyResult = performCopy(input, destFile, maxBytes, totalSize, onProgress)

        copyResult.onFailure { return@withContext Result.failure(it) }
        val bytesCopied = copyResult.getOrThrow()

        validateSize(totalSize, bytesCopied, destFile)?.let { return@withContext it }

        val hash = computeHashAndDedupe(destFile)
        if (hash.isFailure) {
            return@withContext performRegistration(
                context, destFile, displayName, mimeType, resolver, sourceUri, bytesCopied, ""
            )
        }
        val hashStr = hash.getOrThrow()

        val hashDupe = FileShelfRepository.findByHash(context, hashStr)
        if (hashDupe != null) {
            destFile.delete()
            return@withContext Result.success(hashDupe)
        }

        performRegistration(context, destFile, displayName, mimeType, resolver, sourceUri, bytesCopied, hashStr)
    }

    private fun openSourceStream(
        resolver: android.content.ContentResolver,
        sourceUri: Uri
    ): java.io.InputStream? {
        return try {
            resolver.openInputStream(sourceUri)
        } catch (e: SecurityException) {
            android.util.Log.e(TAG, "Permission denied for URI: $sourceUri", e)
            null
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Cannot open URI: $sourceUri", e)
            null
        }
    }

    private fun probeFileSize(
        resolver: android.content.ContentResolver,
        sourceUri: Uri
    ): Long? {
        return try {
            resolver.openFileDescriptor(sourceUri, "r")?.use { fd ->
                fd.statSize.takeIf { it > 0 }
            }
        } catch (e: Exception) {
            android.util.Log.d(TAG, "openFileDescriptor probe failed: ${e.message}")
            null
        }
    }

    private fun checkPreCopySizeLimit(
        totalSize: Long?,
        maxBytes: Long,
        input: java.io.InputStream
    ): Result<StagedFile>? {
        if (totalSize != null && totalSize > maxBytes) {
            val limitMB = maxBytes / (1024 * 1024)
            val fileSizeMB = totalSize / (1024 * 1024)
            input.close()
            return Result.failure(
                Exception("File size ($fileSizeMB MB) exceeds the $limitMB MB limit")
            )
        }
        return null
    }

    private suspend fun performCopy(
        input: java.io.InputStream,
        destFile: java.io.File,
        maxBytes: Long,
        totalSize: Long?,
        onProgress: suspend (bytesCopied: Long, totalSize: Long?) -> Unit
    ): Result<Long> {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            val bytesCopied: Long
            input.use { ins ->
                destFile.outputStream().use { out ->
                    bytesCopied = copyStream(
                        ins, out, buffer, maxBytes, totalSize, onProgress
                    )
                }
            }
            return Result.success(bytesCopied)
        } catch (e: FileSizeLimitException) {
            destFile.delete()
            return Result.failure(e)
        } catch (e: Exception) {
            if (destFile.exists()) {
                destFile.delete()
                android.util.Log.w(TAG, "Deleted incomplete file: ${destFile.name}", e)
            }
            return Result.failure(e)
        }
    }

    private class FileSizeLimitException(message: String) : Exception(message)

    private suspend fun copyStream(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        buffer: ByteArray,
        maxBytes: Long,
        totalSize: Long?,
        onProgress: suspend (bytesCopied: Long, totalSize: Long?) -> Unit
    ): Long {
        var bytesCopied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
            bytesCopied += read
            if (bytesCopied > maxBytes) {
                val limitMB = maxBytes / (1024 * 1024)
                throw FileSizeLimitException(
                    "File exceeded $limitMB MB limit during copy"
                )
            }
            onProgress(bytesCopied, totalSize)
        }
        return bytesCopied
    }

    private fun validateSize(
        totalSize: Long?,
        bytesCopied: Long,
        destFile: java.io.File
    ): Result<StagedFile>? {
        if (totalSize != null && bytesCopied != totalSize) {
            destFile.delete()
            android.util.Log.w(TAG, "File size mismatch: expected $totalSize, got $bytesCopied")
            return Result.failure(Exception("File copy incomplete (size mismatch)"))
        }
        return null
    }

    private fun computeHashAndDedupe(
        destFile: java.io.File
    ): Result<String> {
        val digest = MessageDigest.getInstance("SHA-256")
        destFile.inputStream().use { fis ->
            val buf = ByteArray(BUFFER_SIZE)
            var read: Int
            while (fis.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        return Result.success(digest.digest().joinToString("") { "%02x".format(it) })
    }

    @Suppress("detekt:LongParameterList")
    private suspend fun performRegistration(
        context: Context,
        destFile: java.io.File,
        displayName: String,
        mimeType: String?,
        resolver: android.content.ContentResolver,
        sourceUri: Uri,
        bytesCopied: Long,
        hash: String
    ): Result<StagedFile> {
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
}
