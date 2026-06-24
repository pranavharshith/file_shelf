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
import java.util.UUID

/**
 * Async wrapper around [FileCopyCore] for the **system share sheet path**
 * (`ShareReceiverActivity` → `ACTION_SEND`). Runs on the app coroutine
 * scope, surfaces progress + completion via notifications, and keeps the
 * overlay service alive while the copy is in flight.
 *
 * The byte-copy itself, dedupe, size enforcement, and repository write all
 * live in [FileCopyCore]. This file owns only the worker-specific concerns:
 * the foreground notification, pending-copy bookkeeping, and the order
 * dance with [FileShelfRepository.removePendingCopy] that prevents
 * `OverlayService.startObserving()` from auto-stopping the bubble in the
 * gap between "file landed" and "pending entry cleared".
 */
object FileCopyWorker {

    private const val PROGRESS_THRESHOLD_BYTES = 5L * 1024 * 1024

    /**
     * Anything bigger than this gets the bubble loading spinner. Picked to
     * roughly correspond to "the user would notice this isn't instant" on
     * mid-range storage — smaller files complete in well under a second
     * and don't need a spinner at all.
     */
    private const val LARGE_FILE_THRESHOLD_BYTES = 40L * 1024 * 1024

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

                // User-friendly notification for the size-limit family of errors.
                if (errorMessage.contains("MB") && errorMessage.contains("limit")) {
                    val limitMB = FileShelfRepository.MAX_FILE_BYTES / (1024 * 1024)
                    NotificationHelper.showFileSizeLimitError(context, displayName, 0, limitMB)
                } else {
                    NotificationHelper.showCopyError(context, errorMessage)
                }
            }
        }
    }

    /**
     * Thin progress-emitting wrapper over [FileCopyCore.copy]. Throttling
     * matches the previous implementation exactly:
     *  - One indeterminate notification on the first byte read.
     *  - Once `bytesCopied` crosses [PROGRESS_THRESHOLD_BYTES] AND the
     *    provider gave us a real total size, emit a determinate update
     *    each time the integer-percent value changes.
     *
     * FileCopyCore no longer dispatches to `Dispatchers.IO` internally (so
     * the drag-in path can keep the URI grant alive on the main thread).
     * We restore that behaviour here because the share-sheet path's URIs
     * come from Intent.FLAG_GRANT_READ_URI_PERMISSION, which survives
     * thread switches just fine, and 100 MB share copies must NOT block
     * whichever thread invoked us.
     */
    private suspend fun copyToShelf(
        context: Context,
        sourceUri: Uri,
        mimeType: String?,
        jobId: String,
        displayName: String
    ): Result<StagedFile> = withContext(Dispatchers.IO) {
        var lastProgressPercent = -1
        var firstProgressShown = false
        var markedLarge = false

        try {
            FileCopyCore.copy(
                context = context,
                sourceUri = sourceUri,
                displayName = displayName,
                mimeType = mimeType,
                maxBytes = FileShelfRepository.MAX_FILE_BYTES
            ) { bytesCopied, totalSize ->
                // Flip the bubble into loading mode the moment we know this
                // copy isn't going to be instant. Two trigger paths:
                //  1. Provider gave a size hint and it's already over the
                //     threshold — fires on the first chunk.
                //  2. Provider didn't, but we've physically written more
                //     than the threshold — fires mid-stream.
                if (!markedLarge) {
                    val knownLarge = totalSize != null && totalSize > LARGE_FILE_THRESHOLD_BYTES
                    val observedLarge = bytesCopied > LARGE_FILE_THRESHOLD_BYTES
                    if (knownLarge || observedLarge) {
                        markedLarge = true
                        FileShelfRepository.incrementLargeCopy()
                    }
                }

                if (bytesCopied > PROGRESS_THRESHOLD_BYTES && totalSize != null && totalSize > 0) {
                    val percent = ((bytesCopied * 100) / totalSize).toInt()
                    if (percent != lastProgressPercent) {
                        lastProgressPercent = percent
                        withContext(Dispatchers.Main) {
                            NotificationHelper.showCopyProgress(context, jobId, displayName, percent)
                        }
                    }
                } else if (!firstProgressShown && bytesCopied > 0) {
                    firstProgressShown = true
                    withContext(Dispatchers.Main) {
                        NotificationHelper.showCopyProgress(context, jobId, displayName, null)
                    }
                }
            }
        } finally {
            // Always release the refcount we took, even on failure / cancel.
            // Without this, a failed large copy would leave the spinner
            // spinning forever.
            if (markedLarge) {
                FileShelfRepository.decrementLargeCopy()
            }
        }
    }
}
