package com.pranav.fileshelf.overlay.dragin

import android.content.Context
import android.util.Log

/**
 * State orchestrator for the foreign-drag (drag-IN) flow.
 *
 * Responsibilities:
 *  - State machine: `IDLE → RECEIVING → HOVER → IDLE`
 *  - Auto-collapse the shelf panel on drag start (plan §8)
 *  - Tell `BubbleLayout` to render each state
 *
 * The actual byte copy is handled by [android.view.OnReceiveContentListener]
 * attached to the bubble in `OverlayService.attachReceiveContentListener`.
 * `OverlayService` owns one instance of this controller for its lifetime.
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
