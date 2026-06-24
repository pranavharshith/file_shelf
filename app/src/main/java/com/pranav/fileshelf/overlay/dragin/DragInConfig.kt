package com.pranav.fileshelf.overlay.dragin

/**
 * Per-item byte cap for the drag-in import path.
 *
 * Now that drag-in runs inside [DropTrampolineActivity] (Intent-scoped URI
 * grants, off-thread copy on `Dispatchers.IO`) the source app no longer
 * pays for our I/O time, so the cap is matched to the share-sheet path's
 * [com.pranav.fileshelf.data.FileShelfRepository.MAX_FILE_BYTES] (500 MB).
 *
 * Held as a separate constant so we can dial drag-in down independently
 * of the share-sheet path if a specific cloud-stub scenario starts
 * producing pathological timings.
 */
internal const val MAX_DRAG_IMPORT_BYTES: Long = 500L * 1024 * 1024

/**
 * Single shared logcat tag prefix for every drag-in component. Filter with
 * `adb logcat -s FileShelfDragIn:V FileShelfDragIn.Tgt:V FileShelfDragIn.Ctl:V FileShelfDragIn.Imp:V`
 * to see only this subsystem during field debugging.
 */
internal const val DRAG_IN_TAG = "FileShelfDragIn"
