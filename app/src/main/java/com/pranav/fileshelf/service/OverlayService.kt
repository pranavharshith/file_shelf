package com.pranav.fileshelf.service

import android.app.AlertDialog
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import com.pranav.fileshelf.FileShelfApp
import com.pranav.fileshelf.MainActivity
import com.pranav.fileshelf.R
import com.pranav.fileshelf.data.FileShelfRepository
import com.pranav.fileshelf.data.StagedFile
import com.pranav.fileshelf.overlay.BubbleLayout
import com.pranav.fileshelf.overlay.DismissZoneHintLayout
import com.pranav.fileshelf.overlay.OverlayBounds
import com.pranav.fileshelf.overlay.OverlayWindowManager
import com.pranav.fileshelf.overlay.ShelfPanelLayout
import com.pranav.fileshelf.overlay.dragin.BubbleDropTarget
import com.pranav.fileshelf.overlay.dragin.DragInController
import com.pranav.fileshelf.overlay.dragin.DragInSpikeLogger
import com.pranav.fileshelf.util.NotificationHelper
import com.pranav.fileshelf.util.PermissionHelper
import com.pranav.fileshelf.util.queryDisplayName
import com.pranav.fileshelf.worker.FileCopyCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OverlayService : Service() {

    private lateinit var overlayManager: OverlayWindowManager
    private var bubbleView: BubbleLayout? = null
    private var shelfPanel: ShelfPanelLayout? = null
    private var dismissHintView: DismissZoneHintLayout? = null
    private var isPanelVisible = false
    private var isPanelAnimating = false
    private var observeJob: Job? = null
    private var isManuallyActivated = false // Track if user manually activated bubble

    /**
     * Owns the foreign-drag (drag-IN) state machine. Lives for the
     * service lifetime; nulled in onDestroy. See `overlay/dragin/` package.
     * The byte-copy runs via [handleReceivedContent] on the app coroutine
     * scope, or via [tryDownloadWebLink] for Drive/Docs web links.
     */
    private var dragInController: DragInController? = null

    /**
     * True between the moment a drag-and-drop session is initiated from the
     * shelf and the moment cleanup runs. Used to make `endActiveDragSession`
     * idempotent across the two paths that can deliver drag-end:
     *  1. The OnDragListener on the originating row/bundle view (primary).
     *  2. A backup OnDragListener on the bubble (secondary, since the bubble
     *     window stays touchable throughout the drag).
     * Whichever fires first wins; the other is a no-op.
     *
     * If the platform drops both events on the floor (extremely rare given
     * the bubble listener lives in a never-passthrough window), the next
     * user interaction with the bubble — checked at the top of
     * toggleShelfPanel() — is the final safety net.
     */
    private var isDragActive = false

    private val prefs by lazy {
        getSharedPreferences(PREFS, MODE_PRIVATE)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        setInstance(this)
        overlayManager = OverlayWindowManager(this)

        // Build the drag-in controller BEFORE showBubble() (called from
        // onStartCommand) so the listener wiring inside showBubble can
        // attach a non-null target. Callbacks fire on the main thread.
        dragInController = DragInController(
            context = this,
            onStateChange = { state ->
                bubbleView?.setDragInState(state)
            },
            onAutoCollapseRequested = {
                // Plan §8: panel must collapse so the bubble is the only
                // drop target during a foreign drag. hideShelfPanel() is
                // synchronous and idempotent.
                if (isPanelVisible) hideShelfPanel()
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfSafely()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                refreshUi()
                return START_STICKY
            }
            ACTION_REPOSITION -> {
                repositionOverlays()
                return START_STICKY
            }
            ACTION_START_MANUAL -> {
                isManuallyActivated = true
            }
        }

        if (!PermissionHelper.canDrawOverlays(this)) {
            // Overlay permission was revoked between start() and onStartCommand().
            // Must satisfy Android's startForeground() contract before stopping —
            // otherwise we get ForegroundServiceDidNotStartInTimeException.
            startForeground(
                NotificationHelper.NOTIFICATION_OVERLAY_ID,
                NotificationHelper.buildOverlayNotification(this, 1)
            )
            stopSelfSafely()
            return START_NOT_STICKY
        }

        val app = application as FileShelfApp
        app.appScope.launch {
            FileShelfRepository.refresh(this@OverlayService)
        }

        ensureDismissHint()
        showBubble()
        startObserving()
        refreshUi()
        return START_STICKY
    }

    override fun onDestroy() {
        stopHandler.removeCallbacks(stopRunnable)
        isDragActive = false
        observeJob?.cancel()
        overlayManager.removeAll()
        bubbleView = null
        shelfPanel = null
        dismissHintView = null
        dragInController = null
        isPanelVisible = false
        synchronized(instanceLock) {
            if (_instance == this) setInstance(null)
        }
        super.onDestroy()
    }

    private fun bubbleSizePx(): Int = dp(BUBBLE_SIZE_DP)

    private fun showBubble() {
        if (bubbleView != null) return

        val bubbleSize = bubbleSizePx()
        val area = OverlayBounds.usableArea(this)
        val savedX = prefs.getInt(KEY_BUBBLE_X, -1)
        val savedY = prefs.getInt(KEY_BUBBLE_Y, -1)

        val defaultX = if (savedX >= 0) savedX else area.right - bubbleSize
        val defaultY = if (savedY >= 0) savedY else dp(200)
        val (startX, startY) = OverlayBounds.clampPosition(this, defaultX, defaultY, bubbleSize, bubbleSize)

        bubbleView = BubbleLayout(
            context = this,
            onTap = { toggleShelfPanel() },
            onPositionChanged = { x, y ->
                overlayManager.updateViewPosition(KEY_BUBBLE, x, y)
            },
            onSnapComplete = { x, y ->
                overlayManager.updateViewPosition(KEY_BUBBLE, x, y)
                prefs.edit().putInt(KEY_BUBBLE_X, x).putInt(KEY_BUBBLE_Y, y).apply()
            },
            onDismissRequested = { dismissBubbleOverlay() },
            onInDismissZoneChanged = { inZone ->
                dismissHintView?.show(inZone)
            },
            onTouchInteraction = {
                if (isDragActive) {
                    android.util.Log.w(
                        "OverlayService",
                        "Touch-down recovery: drag-end events were dropped. " +
                        "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
                        "Android: ${android.os.Build.VERSION.SDK_INT}"
                    )
                    endActiveDragSession(accepted = false)
                }
            }
        )

        overlayManager.addView(KEY_BUBBLE, bubbleView!!, bubbleSize, bubbleSize, startX, startY, watchOutsideTouch = true)

        bubbleView?.alpha = 1f
        bubbleView?.visibility = android.view.View.VISIBLE

        bubbleView?.setOnDragListener(
            BubbleDropTarget(
                controller = dragInController
                    ?: error("DragInController must be created in onCreate before showBubble"),
                onOwnDragEnded = { accepted ->
                    // Own drag-OUT cleanup (preserves prior behaviour: this
                    // listener was previously inline and only handled the
                    // own-drag path).
                    if (isDragActive) endActiveDragSession(accepted = accepted)
                }
            )
        )

        DragInSpikeLogger.logWindowAttached(overlayManager.getParams(KEY_BUBBLE))

        // Attach the modern (API 31+) content receiver. This is the API path
        // that lets a non-Activity window obtain the dropped URI's read
        // permission. The OnDragListener (BubbleDropTarget) returns false
        // from ACTION_DROP so the View's default onDragEvent runs and routes
        // the drop here.
        attachReceiveContentListener(bubbleView)

        // Use the StateFlow value (already in memory from the refresh() call in
        // onStartCommand) instead of loadSync(), which reads from disk on the
        // main thread and risks an ANR under storage pressure.
        val count = FileShelfRepository.files.value.size
        startForeground(
            NotificationHelper.NOTIFICATION_OVERLAY_ID,
            NotificationHelper.buildOverlayNotification(this, count.coerceAtLeast(1))
        )
    }

    /**
     * Attaches a [android.view.OnReceiveContentListener] to the bubble for
     * MIME_TYPES_ALL. This is the API-31+ mechanism by which a view (even
     * in a non-Activity window) can receive dropped content and have the
     * platform manage the URI read permission. The view's default
     * `onDragEvent` invokes this listener on ACTION_DROP when the attached
     * OnDragListener returns false for that action.
     *
     * Note: requires `setOnReceiveContentListener` is API 31+. minSdk is 26,
     * so we guard with a version check; on < 31 drag-in is unsupported and
     * users fall back to the share sheet.
     */
    private fun attachReceiveContentListener(view: android.view.View?) {
        if (view == null) return
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            android.util.Log.w("OverlayService", "OnReceiveContentListener needs API 31+; drag-in disabled")
            return
        }
        view.setOnReceiveContentListener(arrayOf(
            "image/*", "video/*", "audio/*", "application/*", "text/*"
        )) { _, payload ->
            android.util.Log.i(
                "FileShelfDragIn.Recv",
                "OnReceiveContentListener fired: source=${payload.source} clip=${payload.clip.itemCount} items"
            )
            handleReceivedContent(payload)
            // Return null = we consumed everything; nothing falls through.
            null
        }
        // The receiver only fires for views that accept content; focus flags
        // help the platform route DnD payloads to us.
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        android.util.Log.i("FileShelfDragIn.Recv", "OnReceiveContentListener attached to bubble")
    }

    /**
     * Imports every URI in a received [android.view.ContentInfo] payload.
     * Runs the copy on the app coroutine scope (IO). The platform has
     * already attached the read permission to our process for the duration
     * needed to open the stream.
     *
     * For web links (https:// from Drive/Docs), attempts an HTTP download
     * for publicly shared files. Private files that require sign-in are
     * reported to the user via toast.
     */
    private fun handleReceivedContent(payload: android.view.ContentInfo) {
        val clip = payload.clip
        val app = application as FileShelfApp
        app.appScope.launch(Dispatchers.IO) {
            var imported = 0
            var webLinkFailed = 0
            for (i in 0 until clip.itemCount) {
                val uri = clip.getItemAt(i)?.uri ?: continue
                val scheme = uri.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") {
                    android.util.Log.i("FileShelfDragIn.Recv", "item[$i]: web link detected, attempting download: $uri")
                    val result = tryDownloadWebLink(uri.toString(), i)
                    if (result) imported++ else webLinkFailed++
                    continue
                }
                val displayName = try {
                    contentResolver.queryDisplayName(uri)
                } catch (_: Exception) { null } ?: "file_${System.currentTimeMillis()}_$i"
                val mime = try { contentResolver.getType(uri) } catch (_: Exception) { null }
                android.util.Log.i("FileShelfDragIn.Recv", "item[$i]: importing uri=$uri name=$displayName")
                val result = FileCopyCore.copy(
                    context = applicationContext,
                    sourceUri = uri,
                    displayName = displayName,
                    mimeType = mime,
                    maxBytes = FileShelfRepository.MAX_FILE_BYTES
                )
                result.onSuccess {
                    imported++
                    android.util.Log.i("FileShelfDragIn.Recv", "item[$i]: SUCCESS -> ${it.localPath}")
                }.onFailure {
                    android.util.Log.e("FileShelfDragIn.Recv", "item[$i]: FAILED -> ${it.message}")
                }
            }
            withContext(Dispatchers.Main) {
                if (imported > 0) {
                    FileShelfRepository.refresh(applicationContext)
                    refreshUi()
                    android.widget.Toast.makeText(
                        this@OverlayService,
                        resources.getQuantityString(R.plurals.drag_in_added, imported, imported),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else if (webLinkFailed > 0) {
                    android.widget.Toast.makeText(
                        this@OverlayService,
                        getString(R.string.drag_in_web_link_private),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Attempts to download a Google Drive/Docs web link as a file.
     *
     * Strategy:
     *  - Drive file links (`drive.google.com/file/d/{ID}/...`) → direct download URL
     *  - Drive open links (`drive.google.com/open?id={ID}`) → direct download URL
     *  - Google Docs/Sheets/Slides (`docs.google.com/.../d/{ID}/...`) → export as PDF
     *  - Other HTTPS links → attempt direct download as-is
     *
     * Returns true if the file was successfully downloaded and added to shelf.
     * Returns false (never throws) if the download fails for any reason
     * (private file, network error, redirect to sign-in page, etc.).
     */
    private suspend fun tryDownloadWebLink(url: String, index: Int): Boolean {
        return try {
            val downloadUrl = resolveDownloadUrl(url)
            android.util.Log.d("FileShelfDragIn.Recv", "item[$index]: resolved download URL: $downloadUrl")

            val connection = java.net.URL(downloadUrl).openConnection() as java.net.HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")

            try {
                val responseCode = connection.responseCode
                android.util.Log.d("FileShelfDragIn.Recv", "item[$index]: HTTP $responseCode")

                if (responseCode != 200) {
                    android.util.Log.w("FileShelfDragIn.Recv", "item[$index]: HTTP $responseCode — file may be private or require sign-in")
                    return false
                }

                // Check if we got redirected to a sign-in page
                val contentType = connection.contentType ?: ""
                if (contentType.contains("text/html") && !url.contains("export=download")) {
                    // Drive returned an HTML page (sign-in or virus scan warning)
                    // instead of the actual file. The file is private.
                    android.util.Log.w("FileShelfDragIn.Recv", "item[$index]: got HTML instead of file — likely private/requires sign-in")
                    return false
                }

                // Extract filename from Content-Disposition or URL
                val displayName = extractFilename(connection, url)
                val mimeType = if (contentType.contains(";")) {
                    contentType.substringBefore(";").trim()
                } else {
                    contentType.ifBlank { "application/octet-stream" }
                }

                // Stream to shelf
                val destFile = FileShelfRepository.createDestFile(applicationContext, displayName)
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                var bytesCopied = 0L
                val buffer = ByteArray(8192)
                val maxBytes = FileShelfRepository.MAX_FILE_BYTES

                connection.inputStream.use { input ->
                    destFile.outputStream().use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            bytesCopied += read
                            if (bytesCopied > maxBytes) {
                                destFile.delete()
                                android.util.Log.w("FileShelfDragIn.Recv", "item[$index]: exceeded size limit")
                                return false
                            }
                        }
                    }
                }

                if (bytesCopied == 0L) {
                    destFile.delete()
                    android.util.Log.w("FileShelfDragIn.Recv", "item[$index]: zero bytes downloaded")
                    return false
                }

                // Dedupe by hash
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                val hashDupe = FileShelfRepository.findByHash(applicationContext, hash)
                if (hashDupe != null) {
                    destFile.delete()
                    android.util.Log.d("FileShelfDragIn.Recv", "item[$index]: dedupe hit (hash)")
                    return true // Already on shelf
                }

                val staged = com.pranav.fileshelf.data.StagedFile(
                    id = java.util.UUID.randomUUID().toString(),
                    displayName = displayName,
                    mimeType = mimeType,
                    localPath = destFile.absolutePath,
                    sizeBytes = bytesCopied,
                    sha256 = hash,
                    addedAt = System.currentTimeMillis()
                )
                FileShelfRepository.add(applicationContext, staged)
                android.util.Log.i("FileShelfDragIn.Recv", "item[$index]: web link SUCCESS -> ${destFile.name} ($bytesCopied bytes)")
                true
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            android.util.Log.e("FileShelfDragIn.Recv", "item[$index]: web link download failed: ${e.message}")
            false
        }
    }

    /**
     * Converts a Google Drive/Docs web link into a direct download URL.
     */
    private fun resolveDownloadUrl(url: String): String {
        // Google Drive file: https://drive.google.com/file/d/{ID}/view?...
        val driveFilePattern = Regex("drive\\.google\\.com/file/d/([^/]+)")
        driveFilePattern.find(url)?.let { match ->
            val fileId = match.groupValues[1]
            return "https://drive.google.com/uc?export=download&id=$fileId"
        }

        // Google Drive open: https://drive.google.com/open?id={ID}
        val driveOpenPattern = Regex("drive\\.google\\.com/open\\?id=([^&]+)")
        driveOpenPattern.find(url)?.let { match ->
            val fileId = match.groupValues[1]
            return "https://drive.google.com/uc?export=download&id=$fileId"
        }

        // Google Docs: https://docs.google.com/document/d/{ID}/...
        val docsPattern = Regex("docs\\.google\\.com/(document|spreadsheets|presentation)/d/([^/]+)")
        docsPattern.find(url)?.let { match ->
            val docType = match.groupValues[1]
            val docId = match.groupValues[2]
            val exportFormat = when (docType) {
                "document" -> "pdf"
                "spreadsheets" -> "xlsx"
                "presentation" -> "pptx"
                else -> "pdf"
            }
            return "https://docs.google.com/$docType/d/$docId/export?format=$exportFormat"
        }

        // Fallback: try the URL as-is
        return url
    }

    /**
     * Extracts a reasonable filename from the HTTP response or URL.
     */
    private fun extractFilename(connection: java.net.HttpURLConnection, url: String): String {
        // Try Content-Disposition header first
        val disposition = connection.getHeaderField("Content-Disposition")
        if (disposition != null) {
            val filenamePattern = Regex("filename[*]?=[\"']?(?:UTF-8'')?([^\"';]+)")
            filenamePattern.find(disposition)?.let { match ->
                val name = java.net.URLDecoder.decode(match.groupValues[1].trim(), "UTF-8")
                if (name.isNotBlank()) return name
            }
        }

        // Try to get a name from the URL path
        val path = try { java.net.URL(url).path } catch (_: Exception) { "" }
        val lastSegment = path.substringAfterLast("/").substringBefore("?")
        if (lastSegment.isNotBlank() && lastSegment.contains(".")) {
            return java.net.URLDecoder.decode(lastSegment, "UTF-8")
        }

        // Fallback with extension from content type
        val ext = when {
            connection.contentType?.contains("pdf") == true -> ".pdf"
            connection.contentType?.contains("jpeg") == true -> ".jpg"
            connection.contentType?.contains("png") == true -> ".png"
            connection.contentType?.contains("xlsx") == true -> ".xlsx"
            connection.contentType?.contains("pptx") == true -> ".pptx"
            connection.contentType?.contains("docx") == true -> ".docx"
            else -> ""
        }
        return "download_${System.currentTimeMillis()}$ext"
    }

    private fun ensureDismissHint() {
        // Remove old hint if exists (handles orientation changes)
        if (dismissHintView != null) {
            overlayManager.removeView(KEY_DISMISS_HINT)
            dismissHintView = null
        }
        
        val area = OverlayBounds.usableArea(this)
        dismissHintView = DismissZoneHintLayout(this)
        overlayManager.addDecorView(
            KEY_DISMISS_HINT,
            dismissHintView!!,
            area.width,
            area.height,
            area.left,
            area.top
        )
    }

    private fun repositionOverlays() {
        // Re-clamp bubble position for orientation changes
        bubbleView?.let {
            val params = overlayManager.getParams(KEY_BUBBLE) ?: return@let
            val bubbleSize = bubbleSizePx()
            val (clampedX, clampedY) = OverlayBounds.clampPosition(this, params.x, params.y, bubbleSize, bubbleSize)
            
            if (clampedX != params.x || clampedY != params.y) {
                overlayManager.updateViewPosition(KEY_BUBBLE, clampedX, clampedY)
                prefs.edit().putInt(KEY_BUBBLE_X, clampedX).putInt(KEY_BUBBLE_Y, clampedY).apply()
            }
        }
        
        // Re-size dismiss hint for new screen dimensions
        ensureDismissHint()
        
        // Close panel if open (will be repositioned correctly on next open)
        if (isPanelVisible) {
            hideShelfPanel()
        }
    }

    /** PiP-style dismiss: hide overlay but keep staged files in the shelf. */
    private fun dismissBubbleOverlay() {
        dismissHintView?.show(false)
        hideShelfPanel()
        // Reset manual activation flag when user dismisses via drag
        isManuallyActivated = false
        stopSelfSafely()
    }

    private val stopHandler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable { stopSelfSafely() }

    private fun startObserving() {
        if (observeJob != null) return
        val app = application as FileShelfApp
        observeJob = app.appScope.launch(Dispatchers.Main) {
            combine(
                FileShelfRepository.files,
                FileShelfRepository.pendingCopies
            ) { files, pending ->
                files to pending
            }.collect { (files, pending) ->
                val total = files.size + pending.size
                bubbleView?.updateCount(total)
                NotificationHelper.updateOverlayNotification(
                    this@OverlayService,
                    total.coerceAtLeast(1)
                )
                shelfPanel?.bindFiles(files)

                if (files.isEmpty() && pending.isEmpty()) {
                    // Only auto-stop if NOT manually activated
                    if (!isManuallyActivated) {
                        stopHandler.removeCallbacks(stopRunnable)
                        stopHandler.postDelayed(stopRunnable, 600L)
                    }
                } else {
                    stopHandler.removeCallbacks(stopRunnable)
                }
            }
        }
    }

    private fun toggleShelfPanel() {
        // Lightweight a11y/D-pad/Switch-Access fallback. ACTION_DOWN recovery
        // handles the standard touch path; this catches non-touch input
        // sources that route directly to onTap without firing ACTION_DOWN.
        if (isDragActive) {
            endActiveDragSession(accepted = false)
        }

        // Prevent rapid taps during animations
        if (isPanelAnimating) return

        if (isPanelVisible) {
            requestShelfPanelClose()
        } else {
            showShelfPanel()
        }
    }

    private fun requestShelfPanelClose() {
        if (isPanelAnimating || !isPanelVisible) return
        isPanelAnimating = true
        
        // Cancel any pending animations on bubble to ensure clean state
        bubbleView?.animate()?.cancel()
        
        shelfPanel?.requestClose()
    }

    private fun showShelfPanel() {
        if (shelfPanel != null) return

        // Keep bubble visible at reduced opacity as visual anchor
        bubbleView?.setBubbleVisible(visible = true, animate = false)
        bubbleView?.animate()?.cancel()
        bubbleView?.alpha = 0.3f

        shelfPanel = createShelfPanelLayout()

        val bubbleParams = overlayManager.getParams(KEY_BUBBLE)
        val bubbleSize = bubbleSizePx()
        val area = OverlayBounds.usableArea(this)
        
        val panelWidth = calculatePanelWidth(area)
        val maxScrollHeight = calculateScrollHeight(area)
        shelfPanel!!.setScrollHeight(maxScrollHeight)
        
        val panelHeight = WindowManager.LayoutParams.WRAP_CONTENT
        val (panelX, panelY) = calculatePanelPosition(
            bubbleParams, panelWidth, area
        )

        shelfPanel!!.setExpandOrigin(
            bubbleX = bubbleParams?.x ?: panelX,
            bubbleY = bubbleParams?.y ?: panelY,
            bubbleSize = bubbleSize,
            panelX = panelX,
            panelY = panelY
        )
        shelfPanel!!.bindFiles(FileShelfRepository.files.value)

        overlayManager.addScrim(KEY_SCRIM) { requestShelfPanelClose() }
        overlayManager.addView(
            KEY_PANEL, shelfPanel!!, panelWidth, panelHeight, panelX, panelY
        )
        isPanelVisible = true
    }

    private fun createShelfPanelLayout(): ShelfPanelLayout {
        return ShelfPanelLayout(
            context = this,
            onDismiss = { onPanelDismissed() },
            onClearAll = {
                val app = application as FileShelfApp
                app.appScope.launch(Dispatchers.Main) {
                    FileShelfRepository.clearAll(this@OverlayService)
                    hideShelfPanel()
                    stopSelfSafely()
                }
            },
            onRemove = { file -> removeFile(file) },
            onOpenApp = {
                startActivity(
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                )
            },
            onShowDragHint = { showDragHintDialog() },
            onDragStarted = {
                beginDragSession()
                overlayManager.setPassthrough(KEY_PANEL, true)
                overlayManager.setPassthrough(KEY_SCRIM, true)
                bubbleView?.onDragStart()
            },
            onDragEnded = { accepted ->
                endActiveDragSession(accepted)
            }
        )
    }

    private fun calculatePanelWidth(area: OverlayBounds.UsableArea): Int {
        val maxPanelWidthDp = dp(360)
        return minOf((area.width * 0.88f).toInt(), maxPanelWidthDp)
    }

    private fun calculateScrollHeight(area: OverlayBounds.UsableArea): Int {
        val headerFooterHeight = dp(160)
        val isPortrait = area.height > area.width
        val maxScrollHeightDp = if (isPortrait) dp(240) else dp(360)
        return (area.height - headerFooterHeight).coerceIn(
            dp(180), maxScrollHeightDp
        )
    }

    private fun calculatePanelPosition(
        bubbleParams: WindowManager.LayoutParams?,
        panelWidth: Int,
        area: OverlayBounds.UsableArea
    ): Pair<Int, Int> {
        var panelX = bubbleParams?.x ?: area.left + dp(16)
        var panelY = (bubbleParams?.y ?: dp(200)) + dp(72)

        if (panelX + panelWidth > area.right) {
            panelX = area.right - panelWidth
        }
        if (panelX < area.left) panelX = area.left

        shelfPanel!!.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(
                panelWidth, android.view.View.MeasureSpec.EXACTLY
            ),
            android.view.View.MeasureSpec.makeMeasureSpec(
                0, android.view.View.MeasureSpec.UNSPECIFIED
            )
        )
        val actualPanelHeight = shelfPanel!!.measuredHeight
        
        if (panelY + actualPanelHeight > area.bottom) {
            panelY = (bubbleParams?.y ?: dp(200)) - actualPanelHeight - dp(8)
            if (panelY < area.top) panelY = area.top + dp(8)
        }
        return panelX to panelY
    }

    private fun onPanelDismissed() {
        // Remove panel from window manager immediately to prevent flash
        hideShelfPanel()
        
        // Restore bubble to full visibility
        restoreBubbleAfterDrag()
    }
    
    /**
     * Marks the start of a drag-and-drop session. Idempotent — safe to call
     * multiple times for the same drag.
     */
    private fun beginDragSession() {
        if (isDragActive) return
        isDragActive = true
    }

    /**
     * The single end-of-drag entry point. All drag-end paths funnel through
     * here so timing and cleanup stay consistent:
     *  1. Primary `OnDragListener` on the originating row/bundle view.
     *  2. Backup `OnDragListener` on the bubble window.
     *  3. `ACTION_DOWN` recovery via `BubbleLayout.onTouchInteraction`.
     *  4. `ACTION_OUTSIDE` recovery via `FLAG_WATCH_OUTSIDE_TOUCH`.
     *  5. `ACTION_UP` / accessibility recovery via `toggleShelfPanel`.
     *
     * Runs entirely synchronously. An earlier version deferred the panel
     * teardown by 50ms to "let the receiving app's drop UI settle", but
     * that left a window in which:
     *   - We had already reset the scrim's passthrough flag (so it was
     *     touchable again).
     *   - The panel was still attached to WindowManager.
     *   - Any tap on screen — including the user reaching for the bubble —
     *     could land on the scrim, fire its `onTap`, trigger
     *     `requestShelfPanelClose` → `closeWithSpring`, and play the
     *     panel's scale-down-into-bubble animation. That was the residual
     *     "panel opens then retracts" glitch.
     * Synchronous teardown removes the scrim before any tap can reach it.
     * Idempotent: only the first call per drag session has effect.
     *
     * @param accepted Whether the receiving app accepted the drop. Reserved
     *                 for future telemetry or user-facing feedback; the
     *                 cleanup itself is identical for accepted and rejected.
     */
    private fun endActiveDragSession(@Suppress("UNUSED_PARAMETER") accepted: Boolean) {
        if (!isDragActive) return
        isDragActive = false

        // 1. Reset window passthrough flags. Safe even if windows are gone.
        overlayManager.setPassthrough(KEY_PANEL, false)
        overlayManager.setPassthrough(KEY_SCRIM, false)

        // 2. Snap the bubble to its ready state instantly (alpha=1, scale=1).
        bubbleView?.forceRestoreReadyState()

        // 3. Tear down the panel synchronously. Belt-and-braces: cancel
        //    any animator, force alpha=0 AND visibility=GONE so even if a
        //    spring or animator on the panel tries to tick on its way out,
        //    nothing renders. Then remove the WindowManager view, which
        //    also tears down the scrim and any in-flight close animations.
        if (isPanelVisible) {
            shelfPanel?.let { panel ->
                panel.animate().cancel()
                panel.alpha = 0f
                panel.visibility = android.view.View.GONE
                panel.forceHideBundlePageIfNeeded()
            }
            hideShelfPanel()
        }

        // 4. Clear the panel-animating gate so the next bubble tap is honored.
        isPanelAnimating = false
    }
    
    /**
     * Ensures bubble is fully visible and responsive after any drag operation.
     * This prevents the bubble from staying grey/dim and unresponsive.
     */
    private fun restoreBubbleAfterDrag() {
        bubbleView?.animate()?.cancel()
        bubbleView?.animate()
            ?.alpha(1f)
            ?.setDuration(200)
            ?.setInterpolator(android.view.animation.DecelerateInterpolator())
            ?.withStartAction {
                // Ensure bubble is fully visible before fading in
                bubbleView?.visibility = android.view.View.VISIBLE
            }
            ?.withEndAction {
                isPanelAnimating = false
                // Final validation: ensure bubble is fully interactive
                bubbleView?.let {
                    it.alpha = 1f
                    it.visibility = android.view.View.VISIBLE
                    it.isEnabled = true
                    it.isClickable = true
                }
            }
            ?.start()
    }

    private fun hideShelfPanel() {
        if (!isPanelVisible) return
        // Cancel any pending long-press / callback timers on the panel before
        // detaching. Without this, a CheckForLongPress Runnable that was
        // scheduled during a touch-down will fire AFTER the view is removed
        // from the window (parent = null) and crash in showContextMenu.
        // This is triggered by the auto-collapse path: foreign drag starts →
        // onAutoCollapseRequested → hideShelfPanel, but the user's finger was
        // still down on a panel child.
        shelfPanel?.cancelLongPress()
        shelfPanel?.handler?.removeCallbacksAndMessages(null)

        overlayManager.removeView(KEY_SCRIM)
        overlayManager.removeView(KEY_PANEL)
        shelfPanel = null
        isPanelVisible = false
        // Ensure passthrough flags are reset
        overlayManager.setPassthrough(KEY_SCRIM, false)
        overlayManager.setPassthrough(KEY_PANEL, false)
    }

    private fun showDragHintDialog() {
        if (PermissionHelper.hasSeenDragHint(this)) return
        PermissionHelper.setDragHintSeen(this)

        Handler(Looper.getMainLooper()).post {
            val dialog = AlertDialog.Builder(this)
                .setMessage(R.string.drag_hint)
                .setPositiveButton(R.string.got_it, null)
                .create()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            } else {
                @Suppress("DEPRECATION")
                dialog.window?.setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)
            }
            dialog.show()
        }
    }

    private fun removeFile(file: StagedFile) {
        val app = application as FileShelfApp
        app.appScope.launch(Dispatchers.Main) {
            FileShelfRepository.remove(this@OverlayService, file.id)
        }
    }

    private fun refreshUi() {
        // Use StateFlow values — already in memory, no disk read on main thread.
        val files = FileShelfRepository.files.value
        val pending = FileShelfRepository.pendingCopies.value
        bubbleView?.updateCount(files.size + pending.size)
        shelfPanel?.bindFiles(files)
    }

    internal fun stopSelfSafely() {
        isManuallyActivated = false // Reset flag when stopping
        isDragActive = false
        hideShelfPanel()
        overlayManager.removeAll()
        bubbleView = null
        dismissHintView = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFS = "overlay_prefs"
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"
        private const val BUBBLE_SIZE_DP = 64

        private const val KEY_BUBBLE = "bubble"
        private const val KEY_PANEL = "panel"
        private const val KEY_SCRIM = "scrim"
        private const val KEY_DISMISS_HINT = "dismiss_hint"

        const val ACTION_STOP = "com.pranav.fileshelf.overlay.STOP"
        const val ACTION_REFRESH = "com.pranav.fileshelf.overlay.REFRESH"
        const val ACTION_REPOSITION = "com.pranav.fileshelf.overlay.REPOSITION"
        const val ACTION_START_MANUAL = "com.pranav.fileshelf.overlay.START_MANUAL"

        // Thread-safe instance access with proper synchronization
        private val instanceLock = Any()
        @Volatile
        private var _instance: OverlayService? = null

        // StateFlow-backed running indicator. Compose callers collect this
        // directly instead of polling isRunning() on a timer, giving instant,
        // race-free UI updates when the service starts or stops.
        private val _isRunningFlow = MutableStateFlow(false)
        val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()

        val instance: OverlayService?
            get() = synchronized(instanceLock) { _instance }
        
        private fun setInstance(service: OverlayService?) {
            synchronized(instanceLock) {
                _instance = service
            }
            // Update outside the lock so collectors aren't blocked by lock
            // contention, and so emissions happen even on the fast start/stop path.
            _isRunningFlow.value = service != null
        }
        
        // Separate timestamps for start and stop so that an auto-stop triggered
        // by the observer (e.g. files emptied) does not block the very next
        // manual start() call from arriving within the debounce window.
        @Volatile private var lastStartTime = 0L
        @Volatile private var lastStopTime  = 0L
        private const val ACTION_DEBOUNCE_MS = 300L

        fun start(context: Context) {
            val now = System.currentTimeMillis()
            synchronized(instanceLock) {
                if (now - lastStartTime < ACTION_DEBOUNCE_MS) return
                lastStartTime = now
            }

            // Gate on hard system permissions only. isFloatingShelfEnabled is NOT
            // checked here because an explicit start() — from the toolbar button or
            // a file share — is the user expressing clear intent to use the bubble.
            // Skipping onboarding step 3 ("Skip for now") must not permanently lock
            // the user out of the core feature. We auto-enable the flag so that
            // startObserving()'s canStartOverlay() check also passes going forward.
            if (!PermissionHelper.hasHardPermissions(context)) return
            PermissionHelper.setFloatingShelfEnabled(context, true)

            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_START_MANUAL
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startIfNeeded(context: Context) {
            // Same rationale as start() — only hard permissions gate auto-start.
            if (!PermissionHelper.hasHardPermissions(context)) return
            val files = FileShelfRepository.files.value.ifEmpty {
                FileShelfRepository.loadSync(context)
            }
            val pending = FileShelfRepository.pendingCopies.value
            if (files.isEmpty() && pending.isEmpty()) return
            start(context)
        }

        fun refreshBubble(context: Context) {
            // Guard: only send REFRESH if the service is already running.
            // Calling startForegroundService() on a non-running service would
            // create a fresh instance that returns START_STICKY from onStartCommand
            // without ever calling startForeground() — same crash as stop().
            if (synchronized(instanceLock) { _instance } == null) return
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_REFRESH
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val now = System.currentTimeMillis()
            synchronized(instanceLock) {
                if (now - lastStopTime < ACTION_DEBOUNCE_MS) return
                lastStopTime = now
            }

            // Do NOT use startForegroundService() here. If no service instance is
            // running, Android starts a fresh one; that instance handles ACTION_STOP
            // and exits onStartCommand without ever calling startForeground() —
            // which triggers ForegroundServiceDidNotStartInTimeException and kills
            // the process. Use the live instance directly instead; stopService() is
            // a safe no-op when there is nothing running.
            val svc = synchronized(instanceLock) { _instance }
            if (svc != null) {
                Handler(Looper.getMainLooper()).post { svc.stopSelfSafely() }
            } else {
                context.stopService(Intent(context, OverlayService::class.java))
            }
        }
        
        fun reposition(context: Context) {
            // Guard: only send REPOSITION if the service is already running.
            // Same reason as refreshBubble() — ACTION_REPOSITION exits onStartCommand
            // without calling startForeground(), so startForegroundService() on a
            // non-running service would cause ForegroundServiceDidNotStartInTimeException.
            if (synchronized(instanceLock) { _instance } == null) return
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_REPOSITION
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        /**
         * Check if the overlay service is currently running.
         * Thread-safe status check that doesn't expose the service instance.
         */
        fun isRunning(): Boolean = synchronized(instanceLock) { _instance != null }
    }
}
