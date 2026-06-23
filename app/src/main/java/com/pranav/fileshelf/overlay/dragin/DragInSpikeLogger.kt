package com.pranav.fileshelf.overlay.dragin

import android.util.Log
import android.view.DragEvent
import android.view.WindowManager

/**
 * Phase 0 spike logger. Records cross-app drag dispatch behaviour for
 * `TYPE_APPLICATION_OVERLAY` windows so we have evidence (not guesses) when
 * something doesn't fire. Kept as a static logger because the spike must work
 * even before the real DragInController/BubbleDropTarget land — and stays in
 * place through Phase 2/3 since drag-in is the kind of code that benefits
 * from logs surviving the first round of field testing.
 *
 * Filter logcat with `-s FileShelfDragIn` to isolate.
 */
object DragInSpikeLogger {

    private const val TAG = "FileShelfDragIn"

    /**
     * Called once when the bubble window is created, with the WindowManager
     * params that were used. Captures `type` and `flags` so a failure-to-fire
     * is debuggable without rebuilding.
     */
    fun logWindowAttached(params: WindowManager.LayoutParams?) {
        if (params == null) {
            Log.w(TAG, "logWindowAttached: params=null (bubble window not registered yet)")
            return
        }
        Log.i(
            TAG,
            "bubble window attached: type=0x${Integer.toHexString(params.type)} " +
                "flags=0x${Integer.toHexString(params.flags)} " +
                "focusable=${(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0} " +
                "touchable=${(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0}"
        )
    }

    /**
     * Called from the bubble's OnDragListener for every DragEvent. Skips
     * `ACTION_DRAG_LOCATION` body to keep logs scannable (too noisy
     * otherwise — fires ~60×/s) but does record one initial location.
     */
    fun logEvent(event: DragEvent) {
        val action = actionName(event.action)
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                val desc = event.clipDescription
                val mimeList = if (desc == null) "<none>" else
                    (0 until desc.mimeTypeCount).joinToString(",") { desc.getMimeType(it) }
                Log.i(
                    TAG,
                    "$action label=${desc?.label} mimeCount=${desc?.mimeTypeCount ?: 0} " +
                        "mimes=[$mimeList] localState=${event.localState != null}"
                )
            }
            DragEvent.ACTION_DRAG_LOCATION -> {
                // Sample only the very first location per session to avoid spam.
                // Reset by ACTION_DRAG_STARTED would require state; instead we
                // just sample at coarse coord boundaries.
                if ((event.x.toInt() % 200 == 0) && (event.y.toInt() % 200 == 0)) {
                    Log.v(TAG, "$action x=${event.x} y=${event.y}")
                }
            }
            DragEvent.ACTION_DROP -> {
                val clip = event.clipData
                Log.i(
                    TAG,
                    "$action itemCount=${clip?.itemCount ?: 0} " +
                        "firstUri=${clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri} " +
                        "localState=${event.localState != null}"
                )
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                Log.i(TAG, "$action result=${event.result} localState=${event.localState != null}")
            }
            DragEvent.ACTION_DRAG_ENTERED,
            DragEvent.ACTION_DRAG_EXITED -> {
                Log.d(TAG, action)
            }
            else -> Log.v(TAG, "unknown action=${event.action}")
        }
    }

    private fun actionName(action: Int): String = when (action) {
        DragEvent.ACTION_DRAG_STARTED -> "ACTION_DRAG_STARTED"
        DragEvent.ACTION_DRAG_ENTERED -> "ACTION_DRAG_ENTERED"
        DragEvent.ACTION_DRAG_LOCATION -> "ACTION_DRAG_LOCATION"
        DragEvent.ACTION_DRAG_EXITED -> "ACTION_DRAG_EXITED"
        DragEvent.ACTION_DROP -> "ACTION_DROP"
        DragEvent.ACTION_DRAG_ENDED -> "ACTION_DRAG_ENDED"
        else -> "ACTION_$action"
    }
}
