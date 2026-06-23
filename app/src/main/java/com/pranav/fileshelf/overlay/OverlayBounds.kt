package com.pranav.fileshelf.overlay

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.WindowInsets
import android.view.WindowManager

object OverlayBounds {

    private const val EDGE_MARGIN_DP = 8

    data class UsableArea(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top

        fun clampX(x: Int, viewWidth: Int): Int =
            x.coerceIn(left, (right - viewWidth).coerceAtLeast(left))

        fun clampY(y: Int, viewHeight: Int): Int =
            y.coerceIn(top, (bottom - viewHeight).coerceAtLeast(top))

        fun isOnScreen(x: Int, y: Int, viewWidth: Int, viewHeight: Int): Boolean {
            return x >= left && y >= top &&
                x + viewWidth <= right && y + viewHeight <= bottom
        }

        /** Bottom ~18% of usable area; PiP-style dismiss zone. */
        fun dismissZoneTop(): Int = top + (height * 0.82f).toInt()

        fun isInDismissZone(centerY: Int): Boolean = centerY >= dismissZoneTop()
    }

    fun usableArea(context: Context): UsableArea {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val margin = dp(context, EDGE_MARGIN_DP)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            val insets = metrics.windowInsets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            return UsableArea(
                left = insets.left + margin,
                top = insets.top + margin,
                right = metrics.bounds.width() - insets.right - margin,
                bottom = metrics.bounds.height() - insets.bottom - margin
            )
        }

        @Suppress("DEPRECATION")
        val dm = context.resources.displayMetrics
        return UsableArea(
            left = margin,
            top = margin,
            right = dm.widthPixels - margin,
            bottom = dm.heightPixels - margin
        )
    }

    fun clampPosition(context: Context, x: Int, y: Int, viewWidth: Int, viewHeight: Int): Pair<Int, Int> {
        val area = usableArea(context)
        return area.clampX(x, viewWidth) to area.clampY(y, viewHeight)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
