package com.pranav.fileshelf.overlay.dragin

import android.content.ClipDescription
import android.util.Log
import android.view.DragEvent
import android.view.View

/**
 * Single `OnDragListener` attached to the bubble window. Unifies two
 * concerns onto one listener — required because a `View` can only have one
 * `OnDragListener`:
 *
 *  1. **Drag-OUT** (our own drag, started from the shelf):
 *     `event.localState != null` because we pass the `List<StagedFile>` as
 *     `localState` when calling `startDragAndDrop` from
 *     `ShareIntentHelper.DragHelper`. We must opt into
 *     `ACTION_DRAG_STARTED` so that `ACTION_DRAG_ENDED` fires here as the
 *     backup cleanup path for `OverlayService.endActiveDragSession`.
 *     Drop-zone UI is NOT activated for own drags.
 *
 *  2. **Drag-IN** (foreign cross-app drag):
 *     `event.localState == null`. Activates drop-zone UI via
 *     [DragInController] and performs the import on `ACTION_DROP`.
 *
 * `event.localState` being null is the cleanest way to detect a cross-app
 * drag in this codebase: it is set in exactly one place (`DragHelper`) and
 * nowhere else, and the Android platform clears it for any drag whose
 * source view is not in the current process.
 */
internal class BubbleDropTarget(
    private val controller: DragInController,
    private val onOwnDragEnded: (accepted: Boolean) -> Unit,
) : View.OnDragListener {

    override fun onDrag(v: View, event: DragEvent): Boolean {
        // Log every event so any failure-to-fire is post-mortem-able from
        // logcat alone. Cheap; the logger samples noisy LOCATION events.
        DragInSpikeLogger.logEvent(event)

        val isOwnDrag = event.localState != null

        return when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> handleStarted(event, isOwnDrag)
            DragEvent.ACTION_DRAG_ENTERED -> handleEntered(isOwnDrag)
            DragEvent.ACTION_DRAG_LOCATION -> true            // accept, do nothing
            DragEvent.ACTION_DRAG_EXITED -> handleExited(isOwnDrag)
            DragEvent.ACTION_DROP -> handleDrop(event, isOwnDrag)
            DragEvent.ACTION_DRAG_ENDED -> handleEnded(event, isOwnDrag)
            else -> {
                Log.v(TAG, "ignoring unknown DragEvent action=${event.action}")
                false
            }
        }
    }

    private fun handleStarted(event: DragEvent, isOwnDrag: Boolean): Boolean {
        if (isOwnDrag) {
            return true
        }
        if (!hasAcceptableMime(event.clipDescription)) {
            Log.d(TAG, "rejecting foreign drag: no acceptable MIME")
            return false
        }
        // Opt in so this window stays in the drop dispatch path. We return
        // FALSE from ACTION_DROP (see handleDrop) so the View's default
        // onDragEvent runs and invokes the OnReceiveContentListener, which
        // is the API that handles the URI permission grant for non-Activity
        // windows (API 31+).
        controller.onDragStarted()
        return true
    }

    private fun handleEntered(isOwnDrag: Boolean): Boolean {
        if (!isOwnDrag) controller.onHoverEnter()
        return true
    }

    private fun handleExited(isOwnDrag: Boolean): Boolean {
        if (!isOwnDrag) controller.onHoverExit()
        return true
    }

    private fun handleDrop(event: DragEvent, isOwnDrag: Boolean): Boolean {
        if (isOwnDrag) {
            Log.w(TAG, "rejecting drop from own drag-out")
            return false
        }
        // Return FALSE so the View's default onDragEvent runs and triggers
        // the OnReceiveContentListener (set on the bubble in OverlayService).
        // That listener is the API path that obtains the URI permission for
        // a non-Activity window on API 31+.
        Log.d(TAG, "ACTION_DROP — deferring to OnReceiveContentListener via default onDragEvent")
        return false
    }

    private fun handleEnded(event: DragEvent, isOwnDrag: Boolean): Boolean {
        if (isOwnDrag) {
            onOwnDragEnded(event.result)
        } else {
            controller.onDragEnded(event.result)
        }
        return false
    }

    /**
     * Permissive MIME gate. Plan §5: ClipDescription from source apps is
     * often incomplete or wrong — Drive sometimes only advertises a single
     * generic wildcard, some apps omit types entirely, etc. We cast a wide
     * net here and rely on [handleDrop] walking `ClipData.Item` URIs for
     * the real check.
     *
     * Plain-text-only drags (description carrying ONLY `text/plain` and no
     * other type) are still rejected here because none of the wildcards
     * below match `text/plain`. v1 deliberately excludes text drops.
     */
    private fun hasAcceptableMime(desc: ClipDescription?): Boolean {
        if (desc == null) return false
        return desc.hasMimeType("*/*") ||
                desc.hasMimeType("image/*") ||
                desc.hasMimeType("video/*") ||
                desc.hasMimeType("audio/*") ||
                desc.hasMimeType("application/*")
    }

    companion object {
        private const val TAG = "$DRAG_IN_TAG.Tgt"
    }
}
