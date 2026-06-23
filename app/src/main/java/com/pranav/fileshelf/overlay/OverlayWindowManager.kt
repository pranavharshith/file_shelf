package com.pranav.fileshelf.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

class OverlayWindowManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val attachedViews = mutableMapOf<String, View>()

    data class ViewConfig(
        val key: String,
        val view: View,
        val width: Int,
        val height: Int,
        val x: Int,
        val y: Int,
        val blurBehind: Boolean = false,
        val watchOutsideTouch: Boolean = false
    )

    fun addView(config: ViewConfig) {
        removeView(config.key)
        val params = createLayoutParams(
            config.width, config.height, config.x, config.y,
            config.blurBehind, touchable = true,
            watchOutsideTouch = config.watchOutsideTouch
        )
        windowManager.addView(config.view, params)
        config.view.tag = params
        attachedViews[config.key] = config.view
    }

    @Suppress("detekt:LongParameterList")
    fun addView(
        key: String,
        view: View,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        blurBehind: Boolean = false,
        watchOutsideTouch: Boolean = false
    ) {
        addView(
            ViewConfig(key, view, width, height, x, y, blurBehind, watchOutsideTouch)
        )
    }

    /** Non-interactive overlay (visual only; touches pass through). */
    fun addDecorView(key: String, view: View, width: Int, height: Int, x: Int, y: Int) {
        removeView(key)
        val params = createLayoutParams(width, height, x, y, blurBehind = false, touchable = false)
        windowManager.addView(view, params)
        view.tag = params
        attachedViews[key] = view
    }

    /** Full-screen transparent layer that captures outside taps. */
    @SuppressLint("ClickableViewAccessibility")
    fun addScrim(key: String, onTap: () -> Unit) {
        removeView(key)
        val scrim = View(context).apply {
            setBackgroundColor(0x00000000)
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    onTap()
                }
                true
            }
        }
        val area = OverlayBounds.usableArea(context)
        val params = createLayoutParams(
            width = area.width,
            height = area.height,
            x = area.left,
            y = area.top,
            blurBehind = false,
            touchable = true
        )
        windowManager.addView(scrim, params)
        scrim.tag = params
        attachedViews[key] = scrim
    }

    fun updateViewPosition(key: String, x: Int, y: Int) {
        val view = attachedViews[key] ?: return
        val params = view.tag as? WindowManager.LayoutParams ?: return
        params.x = x
        params.y = y
        windowManager.updateViewLayout(view, params)
    }

    fun updateViewSize(key: String, width: Int, height: Int) {
        val view = attachedViews[key] ?: return
        val params = view.tag as? WindowManager.LayoutParams ?: return
        params.width = width
        params.height = height
        windowManager.updateViewLayout(view, params)
    }

    fun removeView(key: String) {
        val view = attachedViews.remove(key) ?: return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            android.util.Log.w("OverlayWindowManager", "Failed to remove view: $key", e)
        }
    }

    fun removeAll() {
        attachedViews.keys.toList().forEach { removeView(it) }
    }

    fun setPassthrough(key: String, passthrough: Boolean) {
        val view = attachedViews[key] ?: return
        val params = view.tag as? WindowManager.LayoutParams ?: return
        if (passthrough) {
            params.flags = params.flags or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        } else {
            params.flags = params.flags and
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() and
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        try { windowManager.updateViewLayout(view, params) } catch (e: Exception) {
            android.util.Log.w("OverlayWindowManager", "Failed to update view passthrough", e)
        }
    }

    fun getParams(key: String): WindowManager.LayoutParams? {
        return attachedViews[key]?.tag as? WindowManager.LayoutParams
    }

    fun hasView(key: String): Boolean = attachedViews.containsKey(key)

    private fun createLayoutParams(
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        blurBehind: Boolean = false,
        touchable: Boolean = true,
        watchOutsideTouch: Boolean = false
    ): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

        if (!touchable) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        if (blurBehind) {
            flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        }

        if (watchOutsideTouch) {
            // Lets us receive ACTION_OUTSIDE motion events when the user
            // touches anywhere on screen outside this window. We use it on
            // the bubble window as a reliable drop-release detector: after
            // a system drag-and-drop, the user's next touch (anywhere) fires
            // ACTION_OUTSIDE here, so we can recover state even when the
            // platform fails to deliver ACTION_DRAG_ENDED to our listener.
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }

        return WindowManager.LayoutParams(
            width,
            height,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            if (blurBehind && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                blurBehindRadius = 60
            }
        }
    }
}
