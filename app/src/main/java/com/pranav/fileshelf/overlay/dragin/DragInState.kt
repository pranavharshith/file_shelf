package com.pranav.fileshelf.overlay.dragin

/**
 * Visual / behavioural state during a foreign (cross-app) drag session.
 *
 * Our own drag-OUT sessions do NOT drive this enum — `BubbleDropTarget`
 * distinguishes own vs foreign via `DragEvent.localState` and ignores own
 * drags for state purposes. So [IDLE] genuinely means "no foreign drag in
 * progress", regardless of whether the user is currently dragging
 * something out of the shelf.
 */
internal enum class DragInState {
    /** No foreign drag active. Bubble looks normal. */
    IDLE,

    /** Foreign drag is active but the finger is not over the bubble. */
    RECEIVING,

    /** Foreign drag's finger is currently over the bubble. */
    HOVER,
}
