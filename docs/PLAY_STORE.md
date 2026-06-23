# Play Store Submission Guide — File Shelf

## App details

| Field | Value |
|---|---|
| Category | **Tools** (or Productivity) |
| Target API | **35** (required before publishing) |
| Min API | 26 |
| Short description (80 chars) | `Stage files from any app in a floating shelf. Share anywhere, fast.` |

## Data Safety form

| Question | Answer |
|---|---|
| Collects data? | No |
| Shares data? | No |
| Data encrypted in transit? | N/A — no network access |
| Users can request deletion? | Yes — "Clear all" in the app or overlay panel (with confirmation prompt) |

## Foreground Service declaration

**Type:** `specialUse`

**`PROPERTY_SPECIAL_USE_FGS_SUBTYPE` value** (already in `AndroidManifest.xml`):
```
Persistent floating file shelf bubble for cross-app file staging
```

**Play Console declaration text:**
> File Shelf provides a persistent floating bubble overlay that lets users temporarily stage files received from other apps (e.g. WhatsApp) and share or drag-and-drop them to a destination app without switching context. The foreground service is required to keep this user-initiated overlay visible while the user multitasks. The service stops immediately when the user dismisses the shelf or clears all files. No location, microphone, camera, or background data collection occurs.

## Required assets checklist

- [ ] Privacy policy URL (host `docs/PRIVACY_POLICY.md` on GitHub Pages or similar)
- [ ] App icon — 512 × 512 px, PNG
- [ ] Feature graphic — 1024 × 500 px
- [ ] At least 2 phone screenshots

## Full description (paste into Play Console)

```
File Shelf is a lightweight file staging utility.

Share a file from WhatsApp, your gallery, or any other app → choose File Shelf → a floating bubble appears on screen. Tap the bubble to open the shelf panel, then share the file to Gmail, Google Drive, Google AI, or any other app — instantly, without navigating through your file manager.

KEY FEATURES
• Floating bubble with live file-count badge — stays on top of any app
• Cross-app drag-and-drop — long-press a file in the shelf and drag it directly into an upload box or chat field in any compatible app
• Works with any file type: PDFs, images, videos, documents, audio
• Original filenames preserved when sharing — no garbled UUID names
• SHA-256 deduplication — adding the same file twice is a no-op
• Confirmation prompts for destructive actions to prevent accidental data loss
• Files auto-delete after 24 hours — nothing stays forever
• No internet access, no ads, no data collection
• Thread-safe architecture prevents crashes during concurrent operations

HOW IT WORKS
1. Share a file from any app → tap "File Shelf"
2. A bubble appears — multitask freely
3. When ready: tap the bubble → share or drag-and-drop the file to your destination
```

## Screen recording script (30–60 s)

Record on a physical device for best results:

1. Open WhatsApp → open a chat containing a PDF
2. Tap Share → select **File Shelf** — show the transparent intake (no visible app switch)
3. Show the blue bubble appearing over WhatsApp with badge `1`
4. Open Google AI Mode (or Gmail) while the bubble remains visible
5. Tap the bubble → shelf panel expands
6. **Option A (share):** Tap the Share icon next to the file → pick Gmail → show attachment
7. **Option B (drag-and-drop):** Long-press the file → drag shadow appears → drag into the chat/upload field → release
8. Pull down notification shade → show "File shelf active" notification
9. Tap **Clear all** in the panel → bubble disappears

Attach this recording to your submission **and** any appeal.

## If rejected for specialUse FGS

See [`APPEAL_TEMPLATE.md`](APPEAL_TEMPLATE.md) for a ready-to-send appeal letter.

**Plan B** (only if rejected twice): Remove persistent FGS; auto-dismiss the bubble after 5 minutes of inactivity. UX degrades but policy risk drops.
