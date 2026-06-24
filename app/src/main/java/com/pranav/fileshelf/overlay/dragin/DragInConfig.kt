package com.pranav.fileshelf.overlay.dragin

/**
 * Per-item byte cap for the drag-in import path.
 *
 * The drag-in import runs via [android.view.OnReceiveContentListener] on
 * the bubble, which handles the URI permission grant at the platform level.
 * The copy then runs on `Dispatchers.IO` via the app coroutine scope.
 *
 * Cap is matched to the share-sheet path's
 * [com.pranav.fileshelf.data.FileShelfRepository.MAX_FILE_BYTES] (500 MB).
 * Held as a separate constant so we can dial drag-in down independently
 * if a specific cloud-stub scenario produces pathological timings.
 */
internal const val MAX_DRAG_IMPORT_BYTES: Long = 500L * 1024 * 1024

/**
 * Single shared logcat tag prefix for every drag-in component. Filter with
 * `adb logcat -s FileShelfDragIn:V FileShelfDragIn.Tgt:V FileShelfDragIn.Ctl:V FileShelfDragIn.Recv:V`
 * to see only this subsystem during field debugging.
 */
internal const val DRAG_IN_TAG = "FileShelfDragIn"
