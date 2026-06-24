package com.pranav.fileshelf.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.pranav.fileshelf.R
import com.pranav.fileshelf.overlay.dragin.DragInState
import kotlin.math.abs

@SuppressLint("ViewConstructor")
class BubbleLayout(
    context: Context,
    private val onTap: () -> Unit,
    private val onPositionChanged: (x: Int, y: Int) -> Unit,
    private val onSnapComplete: (x: Int, y: Int) -> Unit,
    private val onDismissRequested: () -> Unit,
    private val onInDismissZoneChanged: (Boolean) -> Unit,
    /**
     * Fires synchronously at the very start of every ACTION_DOWN on the
     * bubble. Used by OverlayService to detect a desynced drag-active state
     * and snap the bubble back to its ready visuals BEFORE the touch-down
     * scale animation runs — so the user never sees the grey/contracted
     * state once their finger lands on the bubble.
     */
    private val onTouchInteraction: () -> Unit = {}
) : FrameLayout(context) {

    private val badgeView: TextView
    private var initialX = 0
    private var initialY = 0
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isDragging = false
    private var inDismissZone = false
    private val dragThreshold = 12
    private var lastClickTime = 0L
    private val clickDebounceMs = 250L  // Reduced from 450ms to prevent long unresponsive periods

    private val scaleXSpring = SpringAnimation(this, DynamicAnimation.SCALE_X, 1f)
    private val scaleYSpring = SpringAnimation(this, DynamicAnimation.SCALE_Y, 1f)

    // ── Loading spinner state ────────────────────────────────────────────
    //
    // Driven by FileShelfRepository.largeCopiesActive via OverlayService.
    // `loadingAlpha` cross-fades the spinner in/out, `loadingSweepStart`
    // is the rotating arc angle. The badge text fades to (1 - loadingAlpha)
    // so they share the bubble face without overlapping at full strength.
    //
    // The rotator keeps running for the full fade-out; only stopped after
    // loadingAlpha lands at 0. Otherwise the arc would visibly freeze
    // mid-fade.
    private var isLoading = false
    private var loadingAlpha = 0f
    private var loadingSweepStart = 0f
    private var loadingRotator: ValueAnimator? = null
    private var loadingFader: ValueAnimator? = null
    private val loadingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#0056CC")
    }
    private val loadingRect = RectF()

    // ── Odometer state ───────────────────────────────────────────────────
    //
    // `displayedCount` is what the badge text currently shows.
    // `pendingTargetCount` is the latest value we've been told to display.
    // Roll transitions move displayed → pending; if more updates arrive
    // mid-roll, only the latest pending matters — we never tick through
    // intermediates.
    //
    // While `isLoading` is true the badge is faded out behind the spinner
    // and we deliberately don't animate; the roll is deferred until the
    // loading fader returns the badge to full opacity.
    private var displayedCount = 0
    private var pendingTargetCount = 0
    private var isCountTransitioning = false
    private var isFirstCountUpdate = true

    private var snapXSpring: SpringAnimation? = null
    private var snapYSpring: SpringAnimation? = null
    private var snapXDone = true
    private var snapYDone = true

    init {
        setBackgroundResource(R.drawable.bg_bubble)
        // FrameLayout suppresses onDraw by default; we need it for the
        // rotating arc spinner.
        setWillNotDraw(false)
        val size = dp(64)
        layoutParams = LayoutParams(size, size)

        outlineProvider = ViewOutlineProvider.BOUNDS
        translationZ = dp(20).toFloat()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            outlineAmbientShadowColor = Color.parseColor("#33000000")
            outlineSpotShadowColor = Color.parseColor("#4D000000")
        }

        badgeView = TextView(context).apply {
            setTextColor(Color.parseColor("#0056CC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            text = "0"
        }
        addView(
            badgeView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    fun updateCount(count: Int) {
        pendingTargetCount = count
        contentDescription = context.getString(R.string.file_count_badge, count)

        // First display after the bubble appears: snap to the count directly
        // so the user doesn't see a roll on the initial render.
        if (isFirstCountUpdate) {
            isFirstCountUpdate = false
            displayedCount = count
            badgeView.text = count.toString()
            return
        }

        maybeStartCountRoll()
    }

    /**
     * Kicks off the odometer roll if all preconditions are met:
     *  - We're not showing the loading spinner (badge is faded out behind it).
     *  - A previous roll isn't still in flight.
     *  - There's actually a delta to animate.
     *
     * Safe to call from anywhere; it self-debounces. The setLoading fader's
     * end listener calls this so a deferred roll fires once the badge is
     * visible again.
     */
    private fun maybeStartCountRoll() {
        if (isLoading || loadingAlpha > 0f) return
        if (isCountTransitioning) return
        if (displayedCount == pendingTargetCount) return
        rollCountTo(pendingTargetCount)
    }

    /**
     * Vertical flip-clock roll: the old digit accelerates up and fades out,
     * we swap the text, the new digit decelerates in from below. ~500 ms
     * total — deliberately unhurried per design intent. Skips intermediates
     * regardless of delta size: 2 → 18 is a single roll, not 16 ticks.
     *
     * If `pendingTargetCount` changes during the roll (more files land
     * mid-animation), the chain re-fires on completion to chase the new
     * target. We never queue intermediate rolls.
     */
    private fun rollCountTo(target: Int) {
        isCountTransitioning = true

        // Roll distance is just over half the badge height — far enough that
        // the text visibly clears the bubble face before alpha hits 0, but
        // not so far that the animation looks like a jump.
        val measuredHeight = badgeView.height
        val rollDistance = if (measuredHeight > 0) {
            measuredHeight * 0.55f
        } else {
            dp(18).toFloat()
        }

        // Cancel any in-flight ViewPropertyAnimator on the badge so we don't
        // race the loading fader (which also touches alpha). The fader is
        // controlled by setLoading; if it becomes active mid-roll, setLoading
        // resets isCountTransitioning so we don't get stuck.
        badgeView.animate().cancel()

        badgeView.animate()
            .translationY(-rollDistance)
            .alpha(0f)
            .setDuration(220L)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                // The roll may have been pre-empted by a setLoading(true)
                // before this end-action ran. If so, just bail; the new
                // count will be picked up when loading ends.
                if (isLoading) {
                    isCountTransitioning = false
                    return@withEndAction
                }
                badgeView.text = target.toString()
                badgeView.translationY = rollDistance
                badgeView.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(280L)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        displayedCount = target
                        isCountTransitioning = false
                        // Caught up? Done. Still behind? Chase the latest.
                        if (displayedCount != pendingTargetCount && !isLoading) {
                            rollCountTo(pendingTargetCount)
                        }
                    }
                    .start()
            }
            .start()
    }

    /**
     * Toggle the bubble's loading spinner. Idempotent — calling with the
     * same value twice is a no-op, so the OverlayService observer can
     * spam updates without causing flicker.
     *
     * When `active` is true the badge text gracefully fades out and a
     * slow-rotating arc takes its place. When false it cross-fades back.
     * Multiple concurrent large copies all share one spinner: the refcount
     * in FileShelfRepository keeps `active` true until the last one finishes.
     */
    fun setLoading(active: Boolean) {
        if (active == isLoading) return
        isLoading = active

        if (active) {
            // The odometer roll fights the fader for badge.alpha. Cancel any
            // in-flight roll so the fader owns the alpha cleanly; the pending
            // target is preserved and replayed when the spinner clears.
            badgeView.animate().cancel()
            isCountTransitioning = false
        }

        loadingFader?.cancel()
        loadingFader = ValueAnimator.ofFloat(loadingAlpha, if (active) 1f else 0f).apply {
            duration = 320L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                loadingAlpha = anim.animatedValue as Float
                // Inverse cross-fade: badge number disappears as arc appears.
                badgeView.alpha = (1f - loadingAlpha).coerceIn(0f, 1f)
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // Stop the rotator only after the fade-out lands at 0.
                    if (!isLoading && loadingAlpha <= 0f) {
                        loadingRotator?.cancel()
                        loadingRotator = null
                        // Now that the badge is fully visible again, replay
                        // whatever count updates piled up while it was
                        // hidden. From the user's POV: spinner stops, the
                        // count rolls from the old value to the new one.
                        maybeStartCountRoll()
                    }
                }
            })
            start()
        }

        if (active && loadingRotator == null) {
            // 1400ms per revolution is the "graceful, not hurried" sweet spot.
            // Linear interpolator keeps the angular velocity constant — an
            // ease curve would make the spinner visibly hesitate, which
            // reads as "stuck" to the user.
            loadingRotator = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 1400L
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { anim ->
                    loadingSweepStart = anim.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (loadingAlpha <= 0f) return

        // Slightly thinner stroke + larger inset so the ring reads as a
        // delicate accent inside the bubble, not a chunky border on it.
        val strokeWidth = resources.displayMetrics.density * 2.5f
        loadingPaint.strokeWidth = strokeWidth
        loadingPaint.alpha = (loadingAlpha * 255f).toInt().coerceIn(0, 255)

        val inset = dp(13).toFloat() + strokeWidth / 2f
        loadingRect.set(inset, inset, width - inset, height - inset)
        // Fixed 90° sweep, rotating start angle = classic continuous loader.
        canvas.drawArc(loadingRect, loadingSweepStart, 90f, false, loadingPaint)
    }

    fun setBubbleVisible(visible: Boolean, animate: Boolean = true) {
        // Always ensure bubble is never completely hidden if it should be visible
        val targetAlpha = if (visible) 1f else 0f
        if (animate) {
            animate()
                .alpha(targetAlpha)
                .setDuration(180)
                .withStartAction {
                    // Ensure view is visible before animating
                    if (targetAlpha > 0f) {
                        this.visibility = View.VISIBLE
                    }
                }
                .withEndAction {
                    // Hide view only after alpha reaches 0
                    if (targetAlpha == 0f) {
                        this.visibility = View.GONE
                    }
                }
                .start()
        } else {
            alpha = targetAlpha
            visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    fun onDragStart() {
        scaleXSpring.spring.stiffness = SpringForce.STIFFNESS_MEDIUM
        scaleXSpring.spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        scaleYSpring.spring.stiffness = SpringForce.STIFFNESS_MEDIUM
        scaleYSpring.spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        scaleXSpring.animateToFinalPosition(0.85f)
        scaleYSpring.animateToFinalPosition(0.85f)
    }

    fun onDragEnd() {
        scaleXSpring.spring.stiffness = SpringForce.STIFFNESS_LOW
        scaleXSpring.spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
        scaleYSpring.spring.stiffness = SpringForce.STIFFNESS_LOW
        scaleYSpring.spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
        scaleXSpring.animateToFinalPosition(1f)
        scaleYSpring.animateToFinalPosition(1f)
        
        // CRITICAL FIX: Ensure bubble is fully visible and responsive after drag
        // This prevents the bubble from staying grey/dim after drag operations
        post {
            visibility = View.VISIBLE
            if (alpha < 0.9f) {
                alpha = 1f  // Force full opacity if still dimmed
            }
        }
    }

    /**
     * Apply the foreign-drag-in visual mode. Decoupled from the
     * touch-feedback springs in onTouchEvent because the Android platform
     * does NOT deliver touch events to onTouchEvent during an active drag
     * session — the two code paths therefore can't collide on
     * [scaleXSpring] / [scaleYSpring].
     *
     * Intentionally minimal in v1: just scale + alpha + a haptic tick on
     * hover. Plan §8 calls out accent ring / count-pill swap / green flash
     * as polish work; deferred until the spike confirms the underlying
     * platform behaviour. Cheap to add later once we know which surfaces
     * actually deliver drag events.
     */
    internal fun setDragInState(state: DragInState) {
        when (state) {
            DragInState.IDLE -> {
                scaleXSpring.spring.stiffness = SpringForce.STIFFNESS_LOW
                scaleXSpring.spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
                scaleYSpring.spring.stiffness = SpringForce.STIFFNESS_LOW
                scaleYSpring.spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
                scaleXSpring.animateToFinalPosition(1f)
                scaleYSpring.animateToFinalPosition(1f)
                alpha = 1f
                visibility = View.VISIBLE
            }
            DragInState.RECEIVING -> {
                scaleXSpring.spring.stiffness = SpringForce.STIFFNESS_MEDIUM
                scaleXSpring.spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                scaleYSpring.spring.stiffness = SpringForce.STIFFNESS_MEDIUM
                scaleYSpring.spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                scaleXSpring.animateToFinalPosition(1.1f)
                scaleYSpring.animateToFinalPosition(1.1f)
                alpha = 1f
                visibility = View.VISIBLE
            }
            DragInState.HOVER -> {
                scaleXSpring.spring.stiffness = SpringForce.STIFFNESS_MEDIUM
                scaleXSpring.spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                scaleYSpring.spring.stiffness = SpringForce.STIFFNESS_MEDIUM
                scaleYSpring.spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                scaleXSpring.animateToFinalPosition(1.25f)
                scaleYSpring.animateToFinalPosition(1.25f)
                alpha = 1f
                visibility = View.VISIBLE
                // Tick once on hover-enter. CLOCK_TICK is the lightest
                // built-in haptic — same one used for dismiss-zone entry.
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
    }

    /**
     * Snaps the bubble back to its "ready / eager to expand" visual state
     * IMMEDIATELY (no animation), and resets internal touch state.
     *
     * Used as the recovery path when a system drag-and-drop ends without the
     * normal ACTION_DRAG_ENDED callback reaching us — for example when the
     * originating panel window's flags were toggled to FLAG_NOT_TOUCHABLE
     * during the drag, or when the drop happened in a third-party app that
     * swallowed the event. Without this, the bubble would be stuck at the
     * scale-0.85 / alpha-0.3 "contracted grey" state shown during a drag.
     */
    fun forceRestoreReadyState() {
        // Stop any pending ViewPropertyAnimator (alpha fades, etc.)
        animate().cancel()

        // Stop spring animations and snap to the ready scale.
        // SpringAnimation.cancel() is required because animate().cancel() does
        // NOT touch SpringAnimations.
        try { scaleXSpring.cancel() } catch (e: Throwable) {
            android.util.Log.w("BubbleLayout", "Failed to cancel scaleXSpring", e)
        }
        try { scaleYSpring.cancel() } catch (e: Throwable) {
            android.util.Log.w("BubbleLayout", "Failed to cancel scaleYSpring", e)
        }
        scaleX = 1f
        scaleY = 1f

        // Snap visuals back to fully visible and responsive
        alpha = 1f
        visibility = View.VISIBLE
        isEnabled = true
        isClickable = true

        // Clear any in-flight touch / dismiss-zone tracking that could
        // confuse the next gesture.
        isDragging = false
        if (inDismissZone) {
            inDismissZone = false
            onInDismissZoneChanged(false)
        }
        // Reset click debounce so the next tap is honored immediately.
        lastClickTime = 0L
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // FLAG_WATCH_OUTSIDE_TOUCH on the bubble window delivers ACTION_OUTSIDE
        // for the first touch of any gesture that lands outside the bubble.
        // After a system drag-and-drop, the user's next touch — anywhere on
        // screen — funnels through here and lets us recover from a stale
        // drag-active state even when ACTION_DRAG_ENDED never fires.
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            onTouchInteraction()
            return false
        }

        val params = tag as? android.view.WindowManager.LayoutParams
        val area = OverlayBounds.usableArea(context)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Notify service FIRST, before any local state mutation, so a
                // recovery snap (alpha=1, scale=1) lands cleanly before our
                // touch-feedback scale-down to 0.92 below.
                onTouchInteraction()

                isDragging = false
                setDismissZone(false)
                touchStartX = event.rawX
                touchStartY = event.rawY
                params?.let {
                    initialX = it.x
                    initialY = it.y
                }
                snapXSpring?.cancel()
                snapYSpring?.cancel()
                snapXDone = true
                snapYDone = true

                scaleXSpring.spring.stiffness = SpringForce.STIFFNESS_MEDIUM
                scaleXSpring.spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                scaleYSpring.spring.stiffness = SpringForce.STIFFNESS_MEDIUM
                scaleYSpring.spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                scaleXSpring.animateToFinalPosition(0.92f)
                scaleYSpring.animateToFinalPosition(0.92f)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - touchStartX).toInt()
                val dy = (event.rawY - touchStartY).toInt()
                if (!isDragging && (abs(dx) > dragThreshold || abs(dy) > dragThreshold)) {
                    isDragging = true
                    scaleXSpring.spring.stiffness = SpringForce.STIFFNESS_LOW
                    scaleXSpring.spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                    scaleYSpring.spring.stiffness = SpringForce.STIFFNESS_LOW
                    scaleYSpring.spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                    scaleXSpring.animateToFinalPosition(1f)
                    scaleYSpring.animateToFinalPosition(1f)
                }
                if (isDragging && params != null) {
                    val newX = area.clampX(initialX + dx, width)
                    val newY = area.clampY(initialY + dy, height)
                    onPositionChanged(newX, newY)
                    updateDismissZone(newY, area)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                scaleXSpring.spring.stiffness = SpringForce.STIFFNESS_LOW
                scaleXSpring.spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
                scaleYSpring.spring.stiffness = SpringForce.STIFFNESS_LOW
                scaleYSpring.spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
                scaleXSpring.animateToFinalPosition(1f)
                scaleYSpring.animateToFinalPosition(1f)

                val wasInDismissZone = inDismissZone
                setDismissZone(false)

                if (!isDragging && event.actionMasked == MotionEvent.ACTION_UP) {
                    // Apply click debounce to prevent animation interruption
                    val currentTime = SystemClock.elapsedRealtime()
                    if (currentTime - lastClickTime > clickDebounceMs) {
                        lastClickTime = currentTime
                        onTap()
                    }
                } else if (wasInDismissZone) {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onDismissRequested()
                } else {
                    // Always clamp position before snapping to ensure bubble stays on screen
                    val usableArea = OverlayBounds.usableArea(context)
                    val clampedX = usableArea.clampX(params?.x ?: 0, width)
                    val clampedY = usableArea.clampY(params?.y ?: 0, height)
                    snapToEdge(clampedX, clampedY)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateDismissZone(y: Int, area: OverlayBounds.UsableArea) {
        val centerY = y + height / 2
        setDismissZone(area.isInDismissZone(centerY))
    }

    private fun setDismissZone(inZone: Boolean) {
        if (inDismissZone == inZone) return
        inDismissZone = inZone
        onInDismissZoneChanged(inZone)
        if (inZone) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            scaleXSpring.spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            scaleYSpring.spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            scaleXSpring.animateToFinalPosition(0.78f)
            scaleYSpring.animateToFinalPosition(0.78f)
        } else if (isDragging) {
            scaleXSpring.animateToFinalPosition(1f)
            scaleYSpring.animateToFinalPosition(1f)
        }
    }

    private fun snapToEdge(currentX: Int, currentY: Int) {
        try {
            val area = OverlayBounds.usableArea(context)
            val mid = area.left + area.width / 2
            
            // Clamp target position to stay on screen (don't go off-screen)
            val targetX = if (currentX + width / 2 < mid) 
                area.left 
            else 
                area.right - width
            val targetY = area.clampY(currentY, height)

            val params = tag as? android.view.WindowManager.LayoutParams ?: return

            val propX = object : androidx.dynamicanimation.animation.FloatPropertyCompat<BubbleLayout>("wmX") {
                override fun getValue(obj: BubbleLayout): Float = params.x.toFloat()
                override fun setValue(obj: BubbleLayout, value: Float) {
                    onPositionChanged(area.clampX(value.toInt(), width), params.y)
                }
            }
            val propY = object : androidx.dynamicanimation.animation.FloatPropertyCompat<BubbleLayout>("wmY") {
                override fun getValue(obj: BubbleLayout): Float = params.y.toFloat()
                override fun setValue(obj: BubbleLayout, value: Float) {
                    onPositionChanged(params.x, area.clampY(value.toInt(), height))
                }
            }

            // Cancel any previous animations
            snapXSpring?.cancel()
            snapYSpring?.cancel()

            // Create fresh SpringForce objects explicitly (not lazy-initialized)
            val springForceX = SpringForce().apply {
                stiffness = SpringForce.STIFFNESS_MEDIUM
                dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            }
            val springForceY = SpringForce().apply {
                stiffness = SpringForce.STIFFNESS_MEDIUM
                dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            }

            // Initialize spring animations with explicit forces
            snapXSpring = SpringAnimation(this, propX, targetX.toFloat()).apply {
                spring = springForceX
                addEndListener { _, _, _, _ ->
                    snapXDone = true
                    maybeFinishSnap(params.x, params.y)
                }
            }
            snapYSpring = SpringAnimation(this, propY, targetY.toFloat()).apply {
                spring = springForceY
                addEndListener { _, _, _, _ ->
                    snapYDone = true
                    maybeFinishSnap(params.x, params.y)
                }
            }

            snapXDone = false
            snapYDone = false
            
            // Animate to final position (already set in constructor)
            snapXSpring?.start()
            snapYSpring?.start()
        } catch (e: Exception) {
            android.util.Log.w("BubbleLayout", "Snap animation failed, falling back to direct position", e)
            onPositionChanged(currentX, currentY)
        }
    }

    private fun maybeFinishSnap(x: Int, y: Int) {
        if (snapXDone && snapYDone) {
            val area = OverlayBounds.usableArea(context)
            val clamped = area.clampX(x, width) to area.clampY(y, height)
            onSnapComplete(clamped.first, clamped.second)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
