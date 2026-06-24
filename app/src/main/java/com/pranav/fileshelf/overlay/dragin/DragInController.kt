package com.pranav.fileshelf.overlay.dragin

import android.content.ClipData
import android.content.Context
import android.util.Log
import android.widget.Toast
import com.pranav.fileshelf.R

/**
 * State orchestrator for the foreign-drag (drag-IN) flow.
 *
 * Responsibilities:
 *  - State machine: `IDLE → RECEIVING → HOVER → IDLE`
 *  - Auto-collapse the shelf panel on drag start (plan §8)
 *  - Tell `BubbleLayout` to render each state
 *
 * The actual byte copy runs inside [BubbleDropReceiverActivity].
 * `OverlayService` owns one instance for its lifetime.
 */
internal class DragInController(
    private val context: Context,
    private val onStateChange: (DragInState) -> Unit,
    private val onAutoCollapseRequested: () -> Unit,
) {

    var currentState: DragInState = DragInState.IDLE
        private set

    fun onDragStarted() {
        Log.d(TAG, "onDragStarted (was=$currentState)")
        onAutoCollapseRequested()
        setState(DragInState.RECEIVING)
    }

    fun onHoverEnter() {
        if (currentState == DragInState.IDLE) {
            Log.w(TAG, "onHoverEnter while IDLE — promoting to HOVER directly")
        }
        setState(DragInState.HOVER)
    }

    fun onHoverExit() {
        if (currentState == DragInState.IDLE) return
        setState(DragInState.RECEIVING)
    }

    fun onDrop(clip: ClipData) {
        Log.i(TAG, "onDrop forwarded to trampoline, itemCount=${clip.itemCount}")
    }

    fun onDropFailed(webLinks: Int, openFailed: Int) {
        Log.w(TAG, "onDropFailed: webLinks=$webLinks, openFailed=$openFailed")
        val msg = if (webLinks > 0) {
            context.getString(R.string.drag_in_web_link)
        } else {
            context.getString(R.string.drag_in_failed)
        }
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    fun onDragEnded(accepted: Boolean) {
        Log.d(TAG, "onDragEnded accepted=$accepted (was=$currentState)")
        setState(DragInState.IDLE)
    }

    private fun setState(state: DragInState) {
        if (currentState == state) return
        Log.d(TAG, "state: $currentState -> $state")
        currentState = state
        onStateChange(state)
    }

    companion object {
        private const val TAG = "$DRAG_IN_TAG.Ctl"
    }
}
