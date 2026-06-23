# Drag-In to Bubble (Smart Hub style)

Status: draft / planning
Branch: `feat/drag-in-to-bubble`

## 1. Goal

Let the user drag files from any app (Gallery, Drive, Files, Chrome, etc.) and drop them onto the floating FileShelf bubble. The bubble accepts the payload, copies it into the existing shelf storage, and the files immediately become normal `StagedFile`s — so the existing drag-OUT flow can dump them into any other app later.

This is the symmetric counterpart of what we already have. We already do `shelf → drag → other app`. We're adding `other app → drag → shelf`. No new storage layer. No new persistence model. The shelf is the hub.

## 2. Scope

In scope:
- Bubble becomes a drop target during any cross-app drag with `DRAG_FLAG_GLOBAL`.
- Visual "drop zone" affordance on the bubble while a global drag is in flight.
- On drop: extract URIs from `ClipData`, copy bytes into `cacheDir/shelf/`, register the new files as `StagedFile`s via `FileShelfRepository`.
- Support both single-item and multi-item drops (`ClipData` with multiple `Item`s).
- Reuse existing `FileCopyWorker` / repository APIs.
- Reuse existing 24-hour expiry, dedupe, and shelf-cap rules.

Out of scope (deliberately — see §11):
- True Samsung-Smart-Hub-style continuous drag (drag → park → keep dragging into a different app without releasing). We can't do that without system privileges; we will instead "park" on drop and let the user re-initiate a drag from the shelf.
- Receiving drags from apps that do **not** set `DRAG_FLAG_GLOBAL` (WhatsApp internal drags, etc.). Not technically possible.
- Receiving non-URI drag payloads as files (plain-text, intents). Plain text could be saved as a `.txt`, but that's a follow-up.

## 3. Current architecture, in one paragraph

`OverlayService` hosts a `TYPE_APPLICATION_OVERLAY` window managed by `OverlayWindowManager`. The window contains `BubbleLayout` (idle) and `ShelfPanelLayout` (expanded). Files arrive today only through `ShareReceiverActivity` (system share sheet → `ACTION_SEND`), which enqueues `FileCopyWorker` to stream bytes from the source `content://` URI into `cacheDir/shelf/` and updates `FileShelfRepository`'s state flow. `DragHelper` in `ShareIntentHelper.kt` handles the drag-OUT side: it builds a `ClipData` of `FileProvider` URIs and calls `View.startDragAndDrop` with `DRAG_FLAG_GLOBAL | DRAG_FLAG_GLOBAL_URI_READ`.

The drag-IN feature inserts a second entry point that feeds into the **same** `FileCopyWorker → FileShelfRepository` pipeline.

## 4. New components

All new code lives under `app/src/main/java/com/pranav/fileshelf/overlay/dragin/`:

- `DragInController.kt` — single source of truth for the drag-in lifecycle. Tracks "is a global drag active right now?", exposes a small state flow to the UI, and owns the drop-handling logic. Held by `OverlayService` for the service lifetime.
- `BubbleDropTarget.kt` — the `View.OnDragListener` attached to the bubble's root view. Translates raw `DragEvent` callbacks into `DragInController` calls. Thin glue.
- `DroppedClipImporter.kt` — given a `ClipData` + `DragEvent`, walks every `ClipData.Item`, resolves each URI's display name + MIME, copies the stream into `cacheDir/shelf/`, and registers the resulting `StagedFile`. This is the only file that touches `FileCopyWorker` / `FileShelfRepository` from the drag-in side.

No new data class. Drag-in produces the existing `StagedFile`. No schema change.

## 5. Drag event lifecycle on the bubble

The bubble's root view registers an `OnDragListener`. With `DRAG_FLAG_GLOBAL` set by the source, the overlay window receives events from drags originating in other apps. Mapping:

- `ACTION_DRAG_STARTED`
  - Inspect `event.clipDescription` and accept the drag if **any** of these match:
    - `hasMimeType("*/*")`
    - `hasMimeType("image/*")`
    - `hasMimeType("video/*")`
    - `hasMimeType("audio/*")`
    - `hasMimeType("application/*")`
  - Rationale: `ClipDescription` MIME info from source apps is frequently incomplete or wrong (e.g. a Drive drag advertising only `*/*`, an app omitting types entirely). MIME filtering at this stage is unreliable; we cast a wide net here and do the **real** payload validation in `ACTION_DROP` by walking `ClipData.Item`s. Plain-text-only drags (description that has only `text/plain` and no other types) are still ignored in v1.
  - On match: tell `DragInController` "global drag active". Controller flips state to `RECEIVING`.
  - Bubble switches to drop-zone UI (see §8).
- `ACTION_DRAG_ENTERED`
  - Highlight the bubble (scale up + accent ring + haptic tick).
- `ACTION_DRAG_LOCATION`
  - Ignored. Cheap no-op.
- `ACTION_DRAG_EXITED`
  - Revert highlight, keep drop-zone UI.
- `ACTION_DROP`
  - Walk every `ClipData.Item`. For each, verify it carries a URI (skip text-only items). If zero URIs survive, return `false` — this is where the real payload check happens.
  - Otherwise call `DroppedClipImporter.import(event)` **synchronously inside the listener** (see §6 for why). Return `true` so the source learns the drop was accepted.
- `ACTION_DRAG_ENDED`
  - `DragInController` flips back to `IDLE`. Bubble restores its normal look.
  - If `event.result == true` and we imported at least one file: show a "+N to shelf" toast + count-pill flash.

## 6. URI permission strategy (the one tricky part)

When a global drag is dropped on us, each `ClipData.Item` URI is read-grantable **only for the duration of the drop event** unless we extend it. The canonical extension API is `Activity.requestDragAndDropPermissions(event)` — which requires an `Activity`. We don't have one; the bubble lives in a `Service`.

Two options, picking option A:

**Option A — synchronous import inside `ACTION_DROP` (chosen for v1):**
- During `ACTION_DROP`, open `ContentResolver.openInputStream(uri)` for every item right then.
- Copy bytes into `cacheDir/shelf/<safeName>` (reuse `FileShelfRepository.createDestFile`).
- After the bytes are on disk we no longer need the source URI, so the permission window closing is fine.
- Drawback: the listener call blocks while we stream. For tiny files (< ~5 MB) this is invisible. For big files this would stall the UI thread / risk ANR.
- **Hard size cutoff for v1: 25 MB per item**, exposed as a named constant `MAX_DRAG_IMPORT_BYTES` (not a literal) so it can be tuned without grep. Two-stage check:
  - **Stage 1, pre-stream:** read `OpenableColumns._SIZE` if the provider reports it. If `size >= MAX_DRAG_IMPORT_BYTES`, skip the item immediately and toast.
  - **Stage 2, mid-stream (mandatory):** many providers return `_SIZE = null` (Drive, some Chrome surfaces, some custom providers). For those, count bytes as we copy and **abort at `MAX_DRAG_IMPORT_BYTES + 1`**, delete the partial dest file, mark the item failed. `_SIZE` is a hint, never a contract.
  - On abort: toast "File too large for drag import", continue with the rest of the batch.
  - This keeps v1 honest: we don't pretend to handle huge drags before we've proven demand. Lifting the cap is a v2 task tied to the Option B bridge below.
- Mitigation while still under the cap: do the copy on `Dispatchers.IO` via `runBlocking`, but keep the call site synchronous so the URI permission stays alive.

**Option B — bridge Activity (v2, only if real demand for large drops):**
- On `ACTION_DROP`, immediately start a transparent `DropBridgeActivity` and forward the `ClipData` to it via `Intent` (`Intent.setClipData` + `FLAG_GRANT_READ_URI_PERMISSION` preserves grants across the launch).
- `DropBridgeActivity` calls `requestDragAndDropPermissions`, hands the URIs to `FileCopyWorker` (async), then `finish()`es.
- Drawback: launching an activity mid-drag is intrusive on some OEMs and Android 12+ background-launch rules can deny it.

Plan: ship Option A with the 25 MB cap. Keep `DroppedClipImporter` behind an interface so Option B can be slotted in once we have evidence users actually drag large files.

For each URI the importer reads:
- Display name via `ContentResolver.query(uri, [OpenableColumns.DISPLAY_NAME, _SIZE], …)`
- MIME via `ContentResolver.getType(uri)`
- Stream via `ContentResolver.openInputStream(uri)` → copy to dest file with the same 8 KB buffer `FileCopyWorker` already uses.

## 7. Reusing `FileCopyWorker` vs a direct copy

`FileCopyWorker` today is a WorkManager-style async copier driven from `ShareReceiverActivity`. For drag-in the URI permission window forces us to copy **now**, not later. So `DroppedClipImporter` will share the byte-copy primitive with `FileCopyWorker` but run it synchronously.

Refactor sketch (no code yet, just the carve-out):
- Extract the actual stream-copy + size-verify + atomic-rename logic out of `FileCopyWorker` into a `private`/`internal` helper, e.g. `FileCopyCore.copy(context, sourceUri, displayName, mime): StagedFile`.
- `FileCopyWorker.enqueue(...)` keeps its current async/WorkManager wrapper and just calls `FileCopyCore`.
- `DroppedClipImporter` calls `FileCopyCore` directly inside `ACTION_DROP`.

Zero behaviour change for the existing share-sheet path. One unit-tested helper, two call sites.

## 8. Bubble UI state machine

Add one state to whatever the bubble currently exposes:

- `IDLE` — current default look.
- `DRAG_RECEIVING` — global drag active but finger not over bubble: bubble pulses slightly, count pill swaps to a "+" glyph, dismiss zone is hidden.
- `DRAG_HOVER` — finger over bubble: bubble scales 1.15x, accent ring appears, haptic tick on entry.
- `DRAG_ACCEPTED` (transient ~600 ms) — drop succeeded: green flash + new count pill value.

`DragInController` owns this state; `BubbleLayout` observes it. The shelf panel does **not** need to be involved during a drag-in — drops always go onto the bubble itself, panel closed.

Reasoning: opening the panel mid-drag would race the drop and most apps would deliver the drop to whichever view was under the finger when it lifted. Keeping the drop target small and stationary (the bubble) is the predictable choice.

Decided: if the panel is already open when a global drag starts, **auto-collapse it** so the bubble is the only visible drop target. Restore the panel closed after `ACTION_DRAG_ENDED`. One stationary target = predictable; leaving the panel open creates ambiguous targets, layout shifts, and missed drops.

## 9. Integration points (existing files that need to change)

Minimal, intentionally:

- `service/OverlayService.kt`
  - Instantiate `DragInController` in `onCreate`; tear down in `onDestroy`.
  - Wire it to `BubbleLayout` (push state, read drops).
- `overlay/BubbleLayout.kt`
  - Register `BubbleDropTarget` as the root view's `OnDragListener`.
  - Render the new `DRAG_*` states.
  - Important: the bubble window must be touchable (no `FLAG_NOT_TOUCHABLE`). Already is for tap/long-press, no change.
- `overlay/OverlayWindowManager.kt`
  - No code change expected. The existing window flags are compatible. Verify `FLAG_NOT_FOCUSABLE` doesn't suppress drag events (it shouldn't — drag dispatch is separate from focus).
- `worker/FileCopyWorker.kt`
  - Extract `FileCopyCore` (see §7). Pure refactor, behaviour preserved.
- `data/FileShelfRepository.kt`
  - No change. `DroppedClipImporter` uses the existing `addStagedFile`/`refresh` entry points (whichever the share-receiver path already calls).
- `AndroidManifest.xml`
  - No new permissions. `SYSTEM_ALERT_WINDOW` already covers the overlay. URI permissions come from the source app via the drag event.
- Tests
  - Unit: `FileCopyCore` (copy + rename + dedupe corner cases).
  - Instrumented: `BubbleDropTargetTest` — fabricate a `DragEvent` with a content URI and assert a `StagedFile` lands in the shelf.

## 10. Edge cases worth calling out now

- **Drops with text-only `ClipData`.** Description has no URI. We refuse in `ACTION_DRAG_STARTED` by returning `false`. Future: optionally save as `.txt`.
- **Drops from cloud apps (Drive, OneDrive).** URI is a cloud reference. `openInputStream` triggers a download — could be slow or fail offline. Wrap each item copy in try/catch; show per-file failure toast; partial success is OK.
- **Drop with N items, M succeed.** Atomic at the per-file level, not the batch. Repository gets M new `StagedFile`s. Toast: "Added M of N (K failed)".
- **Shelf cap (`MAX_ITEMS = 20`).** If the drop would overflow, accept up to the cap, drop the rest, toast the user. Don't fail the whole drop.
- **Duplicate file (same sha256).** Existing dedupe in `FileShelfRepository` handles it. The drop still counts as "accepted" for the source app.
- **Source forgot `DRAG_FLAG_GLOBAL`.** We never see the drag. Nothing we can do — by design Android scopes the drag to the source app's window.
- **Source set `DRAG_FLAG_GLOBAL` but not `DRAG_FLAG_GLOBAL_URI_READ`.** `openInputStream` throws `SecurityException`. Catch it per item, mark that item failed, continue.
- **Drop while permission overlay (`MANAGE_OVERLAY_PERMISSION`) is being asked.** Bubble isn't shown. Not applicable.
- **Bubble currently snapped to screen edge.** Drag finger hits the bubble's view bounds the same way a tap would. Should just work; verify on devices with rounded display cutouts.
- **Foldables / split-screen.** Bubble is anchored to one display surface. The drag from the other half still reaches us if it's marked global. Smoke-test on a foldable.

## 11. Honest limitations vs Samsung Smart Hub

We will not match Samsung 1:1, and that's fine. Concretely:

- Samsung Smart Hub can be **summoned during a drag** and lets the user continue dragging into a different app without releasing the finger. That requires system-level cooperation with the drag manager. As a regular app we can only **terminate** the drag by accepting the drop. Our model is "drop on bubble → park as `StagedFile` → user re-initiates a drag-OUT from the shelf later." Two gestures instead of one. This is the same trade-off our shelf is already built around.
- Smart Hub holds URI references; we copy bytes. We have to copy because we can't preserve cross-app URI grants past the drop transaction. Cost: storage + copy time. Benefit: the file survives the source app being killed or the user revoking access — which fits our 24-hour shelf contract.
- Apps that never opt into `DRAG_FLAG_GLOBAL` (some internal-only drags) are invisible to us. Same limitation Smart Hub has, though Samsung-bundled apps tend to be more cooperative on Samsung devices.

## 12. Risks / things to verify before merging

- Verify `TYPE_APPLICATION_OVERLAY` windows actually receive `DragEvent` for cross-app drags on the Android versions we support (min API 26 per `build.gradle.kts`). Quick smoke test on API 26, 29, 31, 34, 35.
- Confirm the bubble's `OnDragListener` fires even when the overlay window has `FLAG_NOT_FOCUSABLE`. If not, we may need to flip focusable during a drag and restore after.
- Confirm synchronous stream copy inside `ACTION_DROP` doesn't trigger an ANR on a 50 MB drop. If it does, switch to the bridge-Activity fallback (§6 Option B).
- Confirm `OEM`s with aggressive background restrictions (Xiaomi, Vivo, Realme) don't kill the overlay during a drag.

## 13. Phased delivery on the branch

Ship in four commits on `feat/drag-in-to-bubble`. **Phase 0 is a hard gate** — if it fails, everything else is wasted work.

0. **Spike: prove the platform assumption.** ~50 LOC, throwaway listener attached to the bubble root. Logs at startup:
   - Overlay window `LayoutParams.flags` and `type` (so we have evidence, not guesses, if something doesn't fire).

   Logs on every `DragEvent`:
   - `event.action`
   - `event.clipDescription` (full toString — label, MIME list)
   - `event.clipData?.itemCount`
   - `event.result` (on `ACTION_DRAG_ENDED`)
   - Coordinates for `ACTION_DRAG_LOCATION` (sampled, not every dispatch).

   The single question to answer:

   > Does `TYPE_APPLICATION_OVERLAY` reliably receive `ACTION_DRAG_STARTED` (and subsequently `ACTION_DROP`) for cross-app global drags?

   **Primary success criteria** — these must work:
   - Google Photos
   - Google Drive
   - Files by Google
   - Samsung Gallery (on a Samsung device, if available)

   **Not pass/fail signals** — drag behaviour here is inconsistent across Android versions and surfaces, log results but don't block on them:
   - Chrome (image / link drags)
   - Third-party gallery apps
   - WebView-hosted drags

   Decision matrix:
   - All primary sources work on tested API levels → proceed to commit 1.
   - Some primary sources work → log which, decide whether the partial coverage is worth shipping, possibly trial-flag the feature.
   - No primary source works → stop. Re-evaluate (transparent Activity drop catcher? accessibility service?). Don't write commits 1–3.

   Concrete spike checklist:
   - API 26, 29, 31, 34, 35 if available.
   - Verify `FLAG_NOT_FOCUSABLE` on the overlay does **not** suppress drag dispatch. If it does, flip focusable on `ACTION_DRAG_STARTED` and restore on `ACTION_DRAG_ENDED`.

1. **Refactor.** Extract `FileCopyCore` from `FileCopyWorker`. No new behaviour, no UI change. Run existing tests.
2. **Drag-in core.** Add `DragInController`, `BubbleDropTarget`, `DroppedClipImporter`. Wire them into `OverlayService` and `BubbleLayout`. Enforce the 25 MB cutoff. Drop-zone UI is minimal (color swap on hover). Toasts on success / too-large / failure.
3. **Polish.** Hover scale + accent ring, haptic ticks, "+N added" count pill flash, panel auto-collapse on drag start, per-OEM smoke fixes.

Each commit independently buildable and reversible.

## 14. Decisions (locked)

- **Import strategy:** Option A — synchronous import inside `ACTION_DROP`, capped at 25 MB per item. Option B (bridge Activity) deferred to v2.
- **Panel behaviour on drag-in:** auto-collapse if open; bubble is the only drop target.
- **Text drops:** ignored in v1 (description must carry at least one non-text MIME wildcard). `text/plain → .txt` is a tracked follow-up, not in scope.
- **MIME gating in `ACTION_DRAG_STARTED`:** permissive wildcard match (`*/*`, `image/*`, `video/*`, `audio/*`, `application/*`); real payload validation happens in `ACTION_DROP` by walking `ClipData.Item`s.

## 15. The one assumption that has to hold

Not permissions. Not copying. Not repository integration. Just this:

```
TYPE_APPLICATION_OVERLAY  +  cross-app global drag
                  ↓
       Does ACTION_DRAG_STARTED fire?
```

Phase 0 (§13) exists solely to answer that question with ~50 LOC before we touch anything else. If yes, the rest of this plan is mechanical. If no, we redesign before any refactor.
