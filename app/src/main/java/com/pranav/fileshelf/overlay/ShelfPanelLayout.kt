package com.pranav.fileshelf.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.pranav.fileshelf.R
import com.pranav.fileshelf.data.StagedFile
import com.pranav.fileshelf.util.DragHelper
import com.pranav.fileshelf.util.MimeIconResolver
import com.pranav.fileshelf.util.ShareIntentHelper
import com.pranav.fileshelf.util.formatFileSize

@SuppressLint("ViewConstructor")
class ShelfPanelLayout(
    context: Context,
    private val onDismiss: () -> Unit,
    private val onClearAll: () -> Unit,
    private val onRemove: (StagedFile) -> Unit,
    private val onOpenApp: () -> Unit,
    private val onShowDragHint: () -> Unit,
    private val onDragStarted: () -> Unit = {},
    private val onDragEnded: (accepted: Boolean) -> Unit = {}
) : FrameLayout(context) {

    private val fileListPage: LinearLayout
    private val bundlePage: LinearLayout
    private val listContainer: LinearLayout
    private val bundleListContainer: LinearLayout
    private val headerCount: TextView
    private val bundleHeaderTitle: TextView
    private val dragHintLabel: TextView
    private val shareSelectedAction: TextView
    private val dragSelectedAction: ImageButton
    private val selectionToggleAction: TextView
    private var scrollView: ScrollView? = null
    private var bundleScrollView: ScrollView? = null
    private var isBundlePageVisible = false
    private var isBundlePageTransitioning = false  // Track animation state

    private val colorTextPrimary = Color.parseColor("#FFFFFFFF")
    private val colorTextSecondary = Color.parseColor("#CCF2F2F7")
    private val colorTextTertiary = Color.parseColor("#99EBEBF5")
    private val colorSeparator = Color.parseColor("#33FFFFFF")
    private val colorTint = Color.parseColor("#4FC3F7")
    private val colorTintStrong = Color.parseColor("#332C9EFF")
    private val colorDestructive = Color.parseColor("#FF6B6B")
    private val colorSelectedFill = Color.parseColor("#2C9EFF")

    private var alphaSpring: SpringAnimation? = null
    private var scaleXSpring: SpringAnimation? = null
    private var scaleYSpring: SpringAnimation? = null
    private var isClosing = false
    private var selectionMode = false
    private var boundFiles: List<StagedFile> = emptyList()
    private val selectedIds = linkedSetOf<String>()

    private val closeEndListener = DynamicAnimation.OnAnimationEndListener { _, _, _, _ ->
        // Force invisible immediately to prevent any 1-frame flash before removeView
        alpha = 0f
        visibility = View.GONE
        isClosing = false
        onDismiss()
    }

    init {
        // Main container is now a FrameLayout holding two pages
        setBackgroundResource(R.drawable.bg_shelf_panel)
        outlineProvider = ViewOutlineProvider.BOUNDS
        translationZ = dp(24f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            outlineAmbientShadowColor = Color.parseColor("#33000000")
            outlineSpotShadowColor = Color.parseColor("#66000000")
        }
        
        // Start invisible to prevent flash before animation
        alpha = 0f
        scaleX = 0.14f
        scaleY = 0.14f

        // === FILE LIST PAGE (Page 1) ===
        fileListPage = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpInt(16f), dpInt(14f), dpInt(12f), dpInt(12f))
        }

        val titleView = TextView(context).apply {
            text = context.getString(R.string.app_name)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorTextPrimary)
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        headerCount = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(colorTint)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6f)
                setColor(colorTintStrong)
            }
            setPadding(dpInt(8f), dpInt(3f), dpInt(8f), dpInt(3f))
        }

        val dismissBtn = TextView(context).apply {
            text = "X"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(colorTextSecondary)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_dismiss_circle)
            contentDescription = context.getString(R.string.dismiss_panel)
            val size = dp(28f).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = dp(8f).toInt() }
            setOnClickListener { requestClose() }
        }

        header.addView(titleView)
        header.addView(headerCount)
        header.addView(dismissBtn)
        fileListPage.addView(header)

        fileListPage.addView(separatorView())

        listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(listContainer)
        }
        fileListPage.addView(scrollView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(248f).toInt()))

        dragHintLabel = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(colorTextSecondary)
            gravity = Gravity.CENTER
            setPadding(dpInt(12f), dpInt(6f), dpInt(12f), dpInt(6f))
            setBackgroundResource(R.drawable.bg_drag_hint)
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(4f).toInt()
                bottomMargin = dp(4f).toInt()
            }
        }
        fileListPage.addView(FrameLayout(context).apply {
            addView(dragHintLabel)
            setPadding(0, dp(4f).toInt(), 0, dp(4f).toInt())
        })

        fileListPage.addView(separatorView())

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpInt(4f), dpInt(2f), dpInt(4f), dpInt(2f))
            minimumHeight = dpInt(48f)
        }
        actions.addView(
            bottomAction(context.getString(R.string.action_open_shelf), colorTint) {
                requestClose()
                onOpenApp()
            }
        )

        selectionToggleAction = bottomAction(context.getString(R.string.selection_toggle), colorTint) {
            if (selectionMode) {
                // Already in selection mode, do nothing (button should be hidden)
            } else {
                // Select all files at once
                selectionMode = true
                selectedIds.clear()
                selectedIds.addAll(boundFiles.map { it.id })
                bindFiles(boundFiles)
            }
        }
        actions.addView(selectionToggleAction)

        shareSelectedAction = bottomAction(context.getString(R.string.share_selected), colorTint) {
            val selectedFiles = selectedFiles()
            if (selectedFiles.isNotEmpty()) {
                ShareIntentHelper.launchChooser(context, selectedFiles)
            }
            clearSelection(exitMode = true)
        }
        actions.addView(shareSelectedAction)

        dragSelectedAction = iconActionPill(R.drawable.ic_drag, R.drawable.bg_action_drag) {
            val selectedFiles = selectedFiles()
            if (selectedFiles.size >= 2) {
                showBundlePage(selectedFiles)
            }
        }
        dragSelectedAction.contentDescription = context.getString(R.string.drag_selected)
        actions.addView(dragSelectedAction)

        actions.addView(
            bottomAction(context.getString(R.string.clear_all), colorDestructive) {
                // Show confirmation dialog before clearing all files
                showClearAllConfirmationDialog()
            }
        )
        fileListPage.addView(actions, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // === BUNDLE PAGE (Page 2) ===
        bundlePage = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            // Start at same position as fileListPage (no translation)
            alpha = 0f
        }

        // Bundle page header
        val bundleHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpInt(16f), dpInt(14f), dpInt(12f), dpInt(12f))
        }

        val backBtn = TextView(context).apply {
            text = "←"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(colorTextPrimary)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            val size = dp(32f).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(8f).toInt() }
            setOnClickListener { hideBundlePage() }
            contentDescription = "Back to file list"
        }

        bundleHeaderTitle = TextView(context).apply {
            text = "Bundle"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorTextPrimary)
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        bundleHeader.addView(backBtn)
        bundleHeader.addView(bundleHeaderTitle)
        bundlePage.addView(bundleHeader)

        bundlePage.addView(separatorView())

        // Bundle file list
        bundleListContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpInt(12f), dpInt(12f), dpInt(12f), dpInt(12f))
        }
        bundleScrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(bundleListContainer)
        }
        bundlePage.addView(bundleScrollView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(248f).toInt()))

        // Bundle hint
        val bundleHint = TextView(context).apply {
            text = context.getString(R.string.bundle_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(colorTextSecondary)
            gravity = Gravity.CENTER
            setPadding(dpInt(16f), dpInt(12f), dpInt(16f), dpInt(12f))
        }
        bundlePage.addView(bundleHint)

        bundlePage.addView(separatorView())

        // Bundle page footer
        val bundleFooter = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpInt(12f), dpInt(12f), dpInt(12f), dpInt(12f))
            minimumHeight = dpInt(48f)
        }
        bundleFooter.addView(
            bottomAction("← Back", colorTint) {
                hideBundlePage()
            }
        )
        bundlePage.addView(bundleFooter, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Add both pages to the FrameLayout root
        addView(fileListPage)
        addView(bundlePage)
        
        // Hide bundle page completely on init to prevent leaking
        bundlePage.visibility = View.GONE

        syncFooterState()
    }

    fun setScrollHeight(height: Int) {
        scrollView?.layoutParams = scrollView?.layoutParams?.apply {
            this.height = height
        }
        bundleScrollView?.layoutParams = bundleScrollView?.layoutParams?.apply {
            this.height = height
        }
    }

    fun setExpandOrigin(bubbleX: Int, bubbleY: Int, bubbleSize: Int, panelX: Int, panelY: Int) {
        // Apply pivot to the root panel (this) for smooth expansion from bubble
        this.pivotX = (bubbleX - panelX + bubbleSize / 2f).coerceAtLeast(0f)
        this.pivotY = (bubbleY - panelY + bubbleSize / 2f).coerceAtLeast(0f)
    }

    fun requestClose() {
        if (isClosing) return

        // Always flatten any bundle-page state INSTANTLY before closing.
        //
        // Previously this method would call hideBundlePage() and `return`,
        // which left the panel in an in-between state (cross-fading pages
        // but never running closeWithSpring()). That deadlocked the close
        // path: OverlayService.isPanelAnimating stayed true forever, so
        // every subsequent bubble tap was swallowed by toggleShelfPanel()'s
        // `if (isPanelAnimating) return`. The user only recovered by
        // dragging to the dismiss zone (which tears down the whole service).
        //
        // forceHideBundlePageIfNeeded() handles both the "currently visible"
        // and "currently transitioning" cases, so we no longer need the
        // separate forceCompleteBundleTransition() call.
        forceHideBundlePageIfNeeded()

        closeWithSpring()
    }
    
    /**
     * Forces bundle page transition to complete immediately without animation.
     * Used to prevent race conditions when drag operations complete during transitions.
     */
    fun forceHideBundlePageIfNeeded() {
        if (!isBundlePageVisible && !isBundlePageTransitioning) return
        
        // Cancel all animations
        bundlePage.animate().cancel()
        fileListPage.animate().cancel()
        
        // Force final state
        bundlePage.setOnLongClickListener(null)
        bundleListContainer.removeAllViews()
        bundlePage.visibility = View.GONE
        bundlePage.alpha = 0f
        
        fileListPage.visibility = View.VISIBLE
        fileListPage.alpha = 1f
        
        isBundlePageVisible = false
        isBundlePageTransitioning = false
    }
    
    fun bindFiles(files: List<StagedFile>) {
        boundFiles = files
        selectedIds.retainAll(files.map { it.id }.toSet())
        if (files.isEmpty()) {
            selectionMode = false
            selectedIds.clear()
        }
        
        // Exit selection mode if all files deselected
        if (selectionMode && selectedIds.isEmpty()) {
            selectionMode = false
        }
        
        // Auto-hide bundle page if selection drops below 2 files
        if (isBundlePageVisible && selectedIds.size < 2) {
            hideBundlePage()
        }

        listContainer.removeAllViews()
        headerCount.text = if (selectionMode) {
            resources.getQuantityString(R.plurals.selected_count_label, selectedIds.size, selectedIds.size)
        } else {
            resources.getQuantityString(R.plurals.file_count_label, files.size, files.size)
        }
        headerCount.visibility = if (files.isEmpty()) GONE else VISIBLE
        dragHintLabel.text = if (selectionMode) {
            context.getString(R.string.selection_active_hint)
        } else {
            context.getString(R.string.drag_file_hint)
        }
        syncFooterState()

        if (files.isEmpty()) {
            listContainer.addView(emptyRow())
            return
        }

        files.forEachIndexed { index, file ->
            if (index > 0) listContainer.addView(separatorView())
            listContainer.addView(createFileRow(file))
        }
    }

    fun enterDragMode() {
        animate().alpha(0f).setDuration(200).start()
        dragHintLabel.animate().alpha(1f).setDuration(200).start()
    }

    fun exitDragMode() {
        animate()
            .alpha(1f)
            .setDuration(220)
            .withEndAction { resetFileRowState() }
            .start()
        dragHintLabel.animate().alpha(0f).setDuration(200).start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        // Enforce child-page state synchronously BEFORE the springs start,
        // so the very first frame rendered is correct. A post{} block here
        // would defer this by one frame while springs are already ticking —
        // exactly the kind of one-frame race that causes visible flicker in
        // an OverlayWindow environment.
        fileListPage.visibility = View.VISIBLE
        fileListPage.alpha = 1f
        bundlePage.visibility = View.GONE
        bundlePage.alpha = 0f

        scaleXSpring = SpringAnimation(this, DynamicAnimation.SCALE_X, 1f).apply {
            spring.stiffness = SpringForce.STIFFNESS_LOW
            spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            start()
        }
        scaleYSpring = SpringAnimation(this, DynamicAnimation.SCALE_Y, 1f).apply {
            spring.stiffness = SpringForce.STIFFNESS_LOW
            spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            start()
        }
        alphaSpring = SpringAnimation(this, DynamicAnimation.ALPHA, 1f).apply {
            spring.stiffness = SpringForce.STIFFNESS_LOW
            spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            start()
        }
    }

    private fun createFileRow(file: StagedFile): View {
        val selected = selectedIds.contains(file.id)
        val row = createRowContainer(selected)

        row.addView(createSelectionBox(file, selected))
        row.addView(createFileChip(file))
        row.addView(createFileInfo(file))
        row.addView(createShareButton(file))
        row.addView(createRemoveButton(file))

        row.setOnLongClickListener { startDragForRow(it, file) }
        row.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> animateRowScale(
                    view, 0.96f,
                    SpringForce.STIFFNESS_MEDIUM,
                    SpringForce.DAMPING_RATIO_NO_BOUNCY
                )
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animateRowScale(
                    view, 1f,
                    SpringForce.STIFFNESS_LOW,
                    SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
                )
            }
            false
        }
        return row
    }

    private fun createRowContainer(selected: Boolean): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpInt(8f), dpInt(10f), dpInt(10f), dpInt(10f))
            minimumHeight = dpInt(56f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14f)
                setColor(
                    if (selected) Color.parseColor("#182C9EFF")
                    else Color.TRANSPARENT
                )
                if (selected) {
                    setStroke(dpInt(1f), Color.parseColor("#554FC3F7"))
                }
            }
            isLongClickable = true
        }
    }

    private fun createSelectionBox(file: StagedFile, selected: Boolean): View {
        return SelectionBoxView(context).apply {
            isChecked = selected
            accentColor = colorSelectedFill
            borderColor = Color.parseColor("#E6FFFFFF")
            checkColor = Color.WHITE
            contentDescription = context.getString(
                R.string.selection_file, file.displayName
            )
            layoutParams = LinearLayout.LayoutParams(dpInt(40f), dpInt(40f)).apply {
                marginEnd = dpInt(8f)
            }
            setOnClickListener {
                if (!selectionMode) {
                    selectionMode = true
                }
                toggleSelection(file)
            }
        }
    }

    private fun createFileChip(file: StagedFile): View {
        val chipSize = dp(36f).toInt()
        val chipBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(9f)
            setColor(
                ContextCompat.getColor(
                    context, MimeIconResolver.chipColorRes(file.mimeType)
                )
            )
        }
        return TextView(context).apply {
            text = MimeIconResolver.emojiFor(file.mimeType)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            gravity = Gravity.CENTER
            background = chipBg
            layoutParams = LinearLayout.LayoutParams(chipSize, chipSize).apply {
                marginEnd = dp(10f).toInt()
            }
        }
    }

    private fun createFileInfo(file: StagedFile): View {
        val info = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f
            ).apply {
                marginEnd = dp(8f).toInt()
            }
        }
        val nameView = TextView(context).apply {
            text = file.displayName
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorTextPrimary)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val sizeView = TextView(context).apply {
            text = formatFileSize(file.sizeBytes)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(colorTextSecondary)
        }
        info.addView(nameView)
        info.addView(sizeView)
        return info
    }

    private fun createShareButton(file: StagedFile): View {
        return iconActionPill(
            R.drawable.ic_share_diagonal, R.drawable.bg_action_share
        ) {
            ShareIntentHelper.launchChooser(context, file)
            if (selectionMode) clearSelection(exitMode = true)
        }.apply {
            contentDescription = context.getString(R.string.share_file)
        }
    }

    private fun createRemoveButton(file: StagedFile): View {
        return iconActionPill(
            R.drawable.ic_remove_x, R.drawable.bg_action_remove
        ) {
            onRemove(file)
            selectedIds.remove(file.id)
        }.apply {
            contentDescription = context.getString(R.string.remove_file)
        }
    }

    private fun startDragForRow(view: View, file: StagedFile): Boolean {
        val filesToDrag = if (selectionMode) {
            if (!selectedIds.contains(file.id)) {
                selectedIds.add(file.id)
                bindFiles(boundFiles)
            }
            selectedFiles()
        } else {
            listOf(file)
        }
        if (filesToDrag.isEmpty()) return false

        return DragHelper.startDrag(
            view = view,
            context = context,
            files = filesToDrag,
            onDragStarted = {
                onDragStarted()
                enterDragMode()
            },
            onDragEnded = { accepted ->
                // Show the drag hint AFTER the drop completes, not before/during.
                // Showing it mid-drag creates a TYPE_APPLICATION_OVERLAY dialog that
                // covers the drop target and prevents the receiving app from getting
                // the drop event.
                onShowDragHint()
                // Forward only. Visual cleanup (exitDragMode, panel teardown,
                // selection reset) is centralized in OverlayService.endActiveDragSession,
                // which is reached via this callback AND independently via the
                // bubble's backup OnDragListener. Re-running exitDragMode() here
                // would race the recovery and cause a brief panel-flash on drop.
                onDragEnded(accepted)
            }
        )
    }

    private fun showBundlePage(files: List<StagedFile>) {
        if (isBundlePageVisible || isBundlePageTransitioning) return
        
        isBundlePageVisible = true
        isBundlePageTransitioning = true
        
        // Make bundle page visible before animation
        bundlePage.visibility = View.VISIBLE
        bundlePage.alpha = 0f  // Start invisible for fade-in
        
        // Update bundle title
        bundleHeaderTitle.text = resources.getQuantityString(R.plurals.file_count_label, files.size, files.size)
        
        // Populate bundle list
        bundleListContainer.removeAllViews()
        files.forEach { file ->
            bundleListContainer.addView(createBundleFileStrip(file))
        }
        
        // Enable long-press drag on the entire bundle page
        bundlePage.isLongClickable = true
        bundlePage.setOnLongClickListener {
            startBundleDrag(files)
            true
        }
        
        // Cross-fade transition: hide file list page, show bundle page
        // Both pages stay in the SAME position (no sliding)
        fileListPage.animate()
            .alpha(0f)
            .setDuration(200)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                fileListPage.visibility = View.GONE
            }
            .start()
        
        bundlePage.animate()
            .alpha(1f)
            .setDuration(200)
            .setStartDelay(100)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                isBundlePageTransitioning = false
            }
            .start()
    }
    
    private fun hideBundlePage() {
        if (!isBundlePageVisible || isBundlePageTransitioning) return
        isBundlePageVisible = false
        isBundlePageTransitioning = true
        
        // Make file list page visible before fading in
        fileListPage.visibility = View.VISIBLE
        fileListPage.alpha = 0f
        
        // Cross-fade transition: hide bundle page, show file list page
        bundlePage.animate()
            .alpha(0f)
            .setDuration(200)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                bundlePage.setOnLongClickListener(null)
                bundleListContainer.removeAllViews()
                bundlePage.visibility = View.GONE
            }
            .start()
        
        fileListPage.animate()
            .alpha(1f)
            .setDuration(200)
            .setStartDelay(100)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                isBundlePageTransitioning = false
            }
            .start()
    }
    
    private fun createBundleFileStrip(file: StagedFile): View {
        val strip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpInt(8f), dpInt(8f), dpInt(8f), dpInt(8f))
            minimumHeight = dpInt(42f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10f)
                setColor(Color.parseColor("#1A4FC3F7"))
            }
            val params = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            params.bottomMargin = dpInt(6f)
            layoutParams = params
        }

        val emoji = TextView(context).apply {
            text = MimeIconResolver.emojiFor(file.mimeType)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            layoutParams = LinearLayout.LayoutParams(dpInt(32f), dpInt(32f)).apply {
                marginEnd = dpInt(10f)
            }
            gravity = Gravity.CENTER
        }

        val nameView = TextView(context).apply {
            text = file.displayName
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(colorTextPrimary)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.BOLD)
        }

        strip.addView(emoji)
        strip.addView(nameView)
        return strip
    }
    
    private fun startBundleDrag(files: List<StagedFile>): Boolean {
        if (files.isEmpty()) return false
        
        return DragHelper.startDrag(
            view = bundlePage,
            context = context,
            files = files,
            onDragStarted = {
                onDragStarted()
                enterDragMode()
            },
            onDragEnded = { accepted ->
                // Show hint AFTER drop completes, same reason as startDragForRow.
                onShowDragHint()
                // User-facing feedback only. Visual cleanup (exitDragMode,
                // panel teardown, selection reset, bundle-page hide) is
                // centralized in OverlayService.endActiveDragSession, which
                // is reached via this callback AND independently via the
                // bubble's backup OnDragListener. Re-running exitDragMode()
                // or hideBundlePage() here would race the recovery and cause
                // a brief panel-flash on drop, because the two listeners fire
                // in undefined order on this dispatch.
                if (!accepted && files.size > 1) {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.drop_not_accepted),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                onDragEnded(accepted)
            }
        )
    }

    private fun emptyRow(): View = TextView(context).apply {
        text = context.getString(R.string.shelf_empty)
        setTextColor(colorTextTertiary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        setPadding(dp(16f).toInt(), dp(24f).toInt(), dp(16f).toInt(), dp(24f).toInt())
    }

    private fun toggleSelection(file: StagedFile) {
        if (!selectionMode) return
        if (!selectedIds.add(file.id)) {
            selectedIds.remove(file.id)
        }
        bindFiles(boundFiles)
    }

    private fun clearSelection(exitMode: Boolean) {
        selectedIds.clear()
        if (exitMode) selectionMode = false
        bindFiles(boundFiles)
    }

    private fun selectedFiles(): List<StagedFile> =
        boundFiles.filter { selectedIds.contains(it.id) }

    private fun syncFooterState() {
        // Show "Select all" only when NOT in selection mode
        selectionToggleAction.visibility = if (!selectionMode) VISIBLE else GONE
        
        // Show drag button only when 2+ files selected
        val hasMultipleSelected = selectedIds.size >= 2
        dragSelectedAction.visibility = if (selectionMode && hasMultipleSelected) VISIBLE else GONE
        
        // Show share button only when 1+ files selected
        val hasSelection = selectedIds.isNotEmpty()
        shareSelectedAction.visibility = if (selectionMode && hasSelection) VISIBLE else GONE
    }

    private fun resetFileRowState() {
        for (index in 0 until listContainer.childCount) {
            listContainer.getChildAt(index)?.apply {
                isEnabled = true
                isFocusable = true
                invalidate()
                requestLayout()
            }
        }
    }

    private fun animateRowScale(view: View, finalValue: Float, stiffness: Float, dampingRatio: Float) {
        SpringAnimation(view, DynamicAnimation.SCALE_X, finalValue).apply {
            spring.stiffness = stiffness
            spring.dampingRatio = dampingRatio
            start()
        }
        SpringAnimation(view, DynamicAnimation.SCALE_Y, finalValue).apply {
            spring.stiffness = stiffness
            spring.dampingRatio = dampingRatio
            start()
        }
    }

    private fun closeWithSpring() {
        if (isClosing) return
        isClosing = true

        // Animate the entire root panel (this) for smooth closing
        scaleXSpring?.apply {
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            animateToFinalPosition(0.14f)
        }
        scaleYSpring?.apply {
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            animateToFinalPosition(0.14f)
        }
        alphaSpring?.apply {
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            animateToFinalPosition(0f)
            // Remove any previous listener to avoid stacking (which would cause
            // the callback to run N times if the panel was opened/closed N times).
            // SpringAnimation does not dedupe listeners internally.
            removeEndListener(closeEndListener)
            addEndListener(closeEndListener)
        }
    }

    /**
     * Shows a confirmation dialog before clearing all files.
     * Prevents accidental data loss from the panel.
     */
    private fun showClearAllConfirmationDialog() {
        val fileCount = boundFiles.size
        if (fileCount == 0) {
            // No files to clear, just close
            requestClose()
            return
        }

        val dialog = android.app.AlertDialog.Builder(context)
            .setTitle("Clear All Files?")
            .setMessage("This will remove all $fileCount staged files. This action cannot be undone.")
            .setPositiveButton("Clear All") { _, _ ->
                requestClose()
                onClearAll()
            }
            .setNegativeButton("Cancel", null)
            .setCancelable(true)
            .create()

        // Important: Set window type for overlay display
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        } else {
            @Suppress("DEPRECATION")
            dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)
        }

        dialog.show()
    }

    private fun separatorView(): View = View(context).apply {
        setBackgroundColor(colorSeparator)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(0.5f).toInt().coerceAtLeast(1))
    }

    private fun iconActionPill(iconRes: Int, bgRes: Int, onClick: () -> Unit): ImageButton {
        return ImageButton(context).apply {
            setImageResource(iconRes)
            setBackgroundResource(bgRes)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setPadding(dpInt(7f), dpInt(7f), dpInt(7f), dpInt(7f))
            val size = dp(32f).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = dp(6f).toInt() }
            setOnClickListener { onClick() }
        }
    }

    private fun bottomAction(label: String, textColor: Int, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12f).toInt(), dp(12f).toInt(), dp(12f).toInt(), dp(12f).toInt())
            setOnClickListener { onClick() }
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun dpInt(value: Float): Int = dp(value).toInt()

    private class SelectionBoxView(context: Context) : View(context) {
        var isChecked: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                invalidate()
                // Tell TalkBack the checked state changed so it announces
                // "checked" / "not checked" without the user having to ask.
                sendAccessibilityEvent(
                    android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED
                )
            }
        var accentColor: Int = Color.parseColor("#2C9EFF")
        var borderColor: Int = Color.WHITE
        var checkColor: Int = Color.WHITE

        private val density = resources.displayMetrics.density
        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val checkPath = Path()

        init {
            isClickable = true
            // Mark as important so the accessibility framework includes this
            // view in the accessibility tree even though it has no text.
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        /**
         * Declare this view as a CheckBox to TalkBack. Without this, the
         * framework sees an opaque custom View and cannot announce its role
         * or checked state. Setting className to CheckBox causes TalkBack to
         * read e.g. "unchecked, double-tap to toggle" automatically.
         */
        override fun onInitializeAccessibilityNodeInfo(
            info: android.view.accessibility.AccessibilityNodeInfo
        ) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = android.widget.CheckBox::class.java.name
            info.isCheckable = true
            info.isChecked = isChecked
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val boxSize = 20f * density
            val left = (width - boxSize) / 2f
            val top = (height - boxSize) / 2f
            val right = left + boxSize
            val bottom = top + boxSize
            val radius = 4f * density

            boxPaint.style = Paint.Style.FILL
            boxPaint.color = if (isChecked) accentColor else Color.TRANSPARENT
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, boxPaint)

            boxPaint.style = Paint.Style.STROKE
            boxPaint.strokeWidth = 1.6f * density
            boxPaint.color = borderColor
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, boxPaint)

            if (isChecked) {
                checkPath.reset()
                checkPath.moveTo(left + boxSize * 0.26f, top + boxSize * 0.53f)
                checkPath.lineTo(left + boxSize * 0.43f, top + boxSize * 0.70f)
                checkPath.lineTo(left + boxSize * 0.75f, top + boxSize * 0.32f)
                checkPaint.color = checkColor
                checkPaint.strokeWidth = 2.4f * density
                canvas.drawPath(checkPath, checkPaint)
            }
        }
    }
}
