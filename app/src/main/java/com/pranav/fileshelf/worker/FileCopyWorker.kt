package com.pranav.fileshelf.worker

import android.content.Context
import android.net.Uri
import com.pranav.fileshelf.FileShelfApp
import com.pranav.fileshelf.data.FileShelfRepository
import com.pranav.fileshelf.data.PendingCopy
import com.pranav.fileshelf.data.StagedFile
import com.pranav.fileshelf.service.OverlayService
import com.pranav.fileshelf.util.NotificationHelper
import com.pranav.fileshelf.util.PermissionHelper
import com.pranav.fileshelf.util.queryDisplayName
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID

object FileCopyWorker {

    private const val BUFFER_SIZE = 8192
    private const val PROGRESS_THRESHOLD_BYTES = 5L * 1024 * 1024

    fun enqueue(context: Context, sourceUri: Uri, mimeType: String?) {
        val app = context.applicationContext as FileShelfApp
        val jobId = UUID.randomUUID().toString()
        val displayName = context.contentResolver.queryDisplayName(sourceUri)
            ?: "file_${System.currentTimeMillis()}"

        FileShelfRepository.addPendingCopy(PendingCopy(jobId, displayName))

        if (PermissionHelper.hasHardPermissions(context)) {
            OverlayService.startIfNeeded(context)
        }

        app.appScope.launch {
            val result = copyToShelf(context, sourceUri, mimeType, jobId, displayName)

            // Order matters: add file to _files FIRST, then remove from _pendingCopies.
            // If we did it the other way, combine(files, pendingCopies) would briefly see
            // both empty and call stopSelfSafely(), killing the bubble before the file lands.
            result.onSuccess {
                FileShelfRepository.refresh(context)       // file now in _files
            }
            FileShelfRepository.removePendingCopy(jobId)  // safe to drop pending now

            // Cancel the in-progress copy notification (it was keyed by jobId.hashCode())
            NotificationManagerCompat.from(context).cancel(jobId.hashCode())

            result.onSuccess {
                NotificationHelper.showCopyComplete(context, it.displayName)
                if (PermissionHelper.hasHardPermissions(context)) {
                    OverlayService.startIfNeeded(context)
                    OverlayService.refreshBubble(context)
                }
            }.onFailure { error ->
                val errorMessage = error.message ?: "Unknown error"
                
                // Show user-friendly notification for file size limit errors
                if (errorMessage.contains("MB") && errorMessage.contains("limit")) {
                    // Extract size info from error message for better UX
                    val limitMB = FileShelfRepository.MAX_FILE_BYTES / (1024 * 1024)
                    NotificationHelper.showFileSizeLimitError(context, displayName, 0, limitMB)
                } else {
                    NotificationHelper.showCopyError(context, errorMessage)
                }
            }
        }
    }

    private suspend fun copyToShelf(
        context: Context,
        sourceUri: Uri,
        mimeType: String?,
        jobId: String,
        displayName: String,
        onProgress: (bytesCopied: Long, total: Long?) -> Unit = { _, _ -> }
    ): Result<StagedFile> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        // SECURITY: Validate URI can be opened before processing
        val input = try {
            resolver.openInputStream(sourceUri)
        } catch (e: SecurityException) {
            android.util.Log.e("FileCopyWorker", "Permission denied for URI: $sourceUri", e)
            return@withContext Result.failure(Exception("Permission denied to access file"))
        } catch (e: Exception) {
            android.util.Log.e("FileCopyWorker", "Cannot open URI: $sourceUri", e)
            return@withContext Result.failure(Exception("Cannot open file"))
        }
        
        if (input == null) {
            return@withContext Result.failure(Exception("Cannot open URI"))
        }

        val totalSize = resolver.openFileDescriptor(sourceUri, "r")?.use { fd ->
            fd.statSize.takeIf { it > 0 }
        }

        if (totalSize != null && totalSize > FileShelfRepository.MAX_FILE_BYTES) {
            val limitMB = FileShelfRepository.MAX_FILE_BYTES / (1024 * 1024)
            val fileSizeMB = totalSize / (1024 * 1024)
            return@withContext Result.failure(
                Exception("File size ($fileSizeMB MB) exceeds the $limitMB MB limit")
            )
        }

        val existing = FileShelfRepository.findByNameAndSize(context, displayName, totalSize)
        if (existing != null) return@withContext Result.success(existing)

        val destFile = FileShelfRepository.createDestFile(context, displayName)
        val digest = MessageDigest.getInstance("SHA-256")
        var bytesCopied = 0L
        val buffer = ByteArray(BUFFER_SIZE)
        @Suppress("UNUSED_VARIABLE")
        var lastProgressPercent = -1

        try {
            input.use { ins ->
                destFile.outputStream().use { out ->
                    while (true) {
                        val read = ins.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        bytesCopied += read

                        if (bytesCopied > FileShelfRepository.MAX_FILE_BYTES) {
                            destFile.delete()
                            val limitMB = FileShelfRepository.MAX_FILE_BYTES / (1024 * 1024)
                            return@withContext Result.failure(
                                Exception("File exceeded $limitMB MB limit during copy")
                            )
                        }

                        onProgress(bytesCopied, totalSize)

                        if (bytesCopied > PROGRESS_THRESHOLD_BYTES && totalSize != null && totalSize > 0) {
                            val percent = ((bytesCopied * 100) / totalSize).toInt()
                            if (percent != lastProgressPercent) {
                                lastProgressPercent = percent
                                withContext(Dispatchers.Main) {
                                    NotificationHelper.showCopyProgress(
                                        context,
                                        jobId,
                                        displayName,
                                        percent
                                    )
                                }
                            }
                        } else if (bytesCopied == BUFFER_SIZE.toLong()) {
                            withContext(Dispatchers.Main) {
                                NotificationHelper.showCopyProgress(context, jobId, displayName, null)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // STABILITY: Clean up incomplete file on any error (crash, storage full, etc.)
            if (destFile.exists()) {
                destFile.delete()
                android.util.Log.w("FileCopyWorker", "Deleted incomplete file after copy failure: ${destFile.name}", e)
            }
            return@withContext Result.failure(e)
        }

        // STABILITY: Verify file integrity - if size doesn't match, it's corrupted
        if (totalSize != null && bytesCopied != totalSize) {
            destFile.delete()
            android.util.Log.w("FileCopyWorker", "File size mismatch: expected $totalSize, got $bytesCopied")
            return@withContext Result.failure(Exception("File copy incomplete (size mismatch)"))
        }

        val hash = digest.digest().joinToString("") { "%02x".format(it) }

        val hashDupe = FileShelfRepository.findByHash(context, hash)
        if (hashDupe != null) {
            destFile.delete()
            return@withContext Result.success(hashDupe)
        }

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
        Result.success(staged)
    }
}
