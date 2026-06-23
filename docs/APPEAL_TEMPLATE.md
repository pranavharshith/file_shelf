# Play Store Rejection Appeal — File Shelf

**Subject:** Appeal — specialUse foreground service for File Shelf overlay

---

Hello Google Play Review Team,

I am appealing the rejection of **File Shelf** (`com.yourname.fileshelf`) regarding the `specialUse` foreground service declaration.

## What the app does

File Shelf is a file staging and routing utility. Users share a file from any app (e.g. WhatsApp, Gallery) — the app copies it to temporary local cache and displays a **user-initiated floating bubble** so they can share or drag-and-drop the file to a destination app without losing their current context.

## Why specialUse FGS is necessary

Android requires a foreground service with a visible notification to keep an overlay window on screen while the user multitasks across apps. There is no alternative API that provides this capability. This is functionally analogous to picture-in-picture, but for staged files rather than video.

The service:
- **Starts only** when the user explicitly shares a file to the app, or enables the floating shelf during onboarding
- **Stops immediately** when the shelf is empty or the user taps **Clear all** / **Dismiss**
- Does **not** collect location, analytics, camera, microphone, or any personal data
- Does **not** run ads or make network requests of any kind
- Displays a **persistent notification** throughout its lifetime, as required

## User disclosure

Onboarding step 4 (the final screen before the shelf activates) explicitly discloses the foreground service to the user and requires them to tap **"Enable Floating Shelf"** to proceed. Users who skip this step do not get the overlay.

## Evidence attached

1. **Screen recording** (60 s): share from WhatsApp → transparent intake → bubble appears → shelf panel opened → file shared to Gmail → file drag-and-dropped to Google AI → Clear all → bubble dismissed
2. **Screenshot** of the onboarding disclosure screen (step 4)

## Data handling

All files remain in app cache on the user's device (`cacheDir/shelf/`). Files are automatically deleted after 24 hours or on user request. No network transmission occurs at any time. Privacy policy: **[YOUR URL]**

---

Thank you for reconsidering.

[Your name / Developer account name]
