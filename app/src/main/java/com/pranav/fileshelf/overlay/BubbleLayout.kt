package com.pranav.fileshelf.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
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

    private var snapXSpring: SpringAnimation? = null
    private var snapYSpring: SpringAnimation? = null
    private var snapXDone = true
    private var snapYDone = true

    init {
        setBackgroundResource(R.drawable.bg_bubble)
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

            val badgeScaleX = SpringAnimation(this, DynamicAnimation.SCALE_X, 1f)
            val badgeScaleY = SpringAnimation(this, DynamicAnimation.SCALE_Y, 1f)
            badgeScaleX.spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            badgeScaleY.spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            badgeScaleX.spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
            badgeScaleY.spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
            setTag(R.id.tag_spring_x, badgeScaleX)
            setTag(R.id.tag_spring_y, badgeScaleY)
        }
        addView(
            badgeView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    fun updateCount(count: Int) {
        badgeView.text = count.toString()
        contentDescription = context.getString(R.string.file_count_badge, count)
        animateCountPulse()
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

    private fun animateCountPulse() {
        val springX = badgeView.getTag(R.id.tag_spring_x) as? SpringAnimation
        val springY = badgeView.getTag(R.id.tag_spring_y) as? SpringAnimation
        badgeView.scaleX = 1.25f
        badgeView.scaleY = 1.25f
        springX?.animateToFinalPosition(1f)
        springY?.animateToFinalPosition(1f)
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
