# File Shelf

A lightweight Android utility that stages files from any app (WhatsApp, Gallery, Files, etc.) in a temporary floating shelf, then lets you share or drag-and-drop them into any destination app — without switching context.

## Features

- **Receive files** via the Android share sheet (`ACTION_SEND / SEND_MULTIPLE`) from any app
- **Floating bubble** overlay with a live file-count badge; draggable, snaps to screen edge
- **Shelf panel** — tap the bubble to expand; lists all staged files with share and remove actions
- **Cross-app drag-and-drop** — long-press a file in the shelf panel to start a global drag (`DRAG_FLAG_GLOBAL`); drop it into any compatible target (Chrome, Gmail, Google AI, etc.)
- **Original filenames preserved** — files on disk match the name they had when shared in, so receiving apps see the correct name
- **SHA-256 deduplication** — adding the same file twice is a no-op
- **Confirmation dialogs** — "Clear all" action requires explicit confirmation to prevent accidental data loss
- **Auto-expire** — staged files are deleted after 24 hours
- **Boot recovery** — bubble restores automatically if files remain after a reboot
- **500 MB per-file limit**, max 20 files in the shelf at once
- **Thread-safe** — service instance management prevents race conditions during concurrent operations

## Build

Open in **Android Studio Ladybug** or newer and let Gradle sync (SDK 35, min API 26).

Or from the command line:

```bash
./gradlew assembleDebug
# Windows:
.\gradlew.bat assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Install to a connected device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## First-run setup

1. Open the app → complete the 4-step onboarding
   - Grant **Display over other apps** (required for the floating bubble)
   - Grant **Notifications** (foreground service notification)
   - Enable **Floating Shelf** on the final screen
2. Share any file from another app → choose **File Shelf** from the share sheet
3. The bubble appears — tap it to open the shelf panel

## How to use drag-and-drop

1. Open your destination app (e.g. Google AI Mode, Gmail, Drive)
2. Tap the floating bubble → shelf panel opens
3. **Long-press** a file (~0.5 s) → haptic feedback fires → panel dims
4. A labeled drag shadow appears — drag it over the target app's upload area
5. Release to drop — panel restores to full opacity

> If the target app doesn't accept drops, tap the **Share** button instead — it works universally via the Android share sheet.

## Permissions

| Permission | Purpose |
|---|---|
| Display over other apps | Floating bubble overlay |
| Notifications | Foreground service persistent notification |
| Boot completed | Restore bubble after device reboot |

## Docs

- [Play Store submission guide](docs/PLAY_STORE.md)
- [Privacy policy](docs/PRIVACY_POLICY.md)
- [Appeal template](docs/APPEAL_TEMPLATE.md)
