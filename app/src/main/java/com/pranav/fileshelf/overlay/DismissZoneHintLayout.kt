package com.pranav.fileshelf.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.pranav.fileshelf.R

/** Bottom hint shown while the bubble is dragged into the PiP-style dismiss zone. */
@SuppressLint("ViewConstructor")
class DismissZoneHintLayout(context: Context) : FrameLayout(context) {

    private val label: TextView

    init {
        setBackgroundResource(R.drawable.bg_dismiss_zone)
        alpha = 0f

        label = TextView(context).apply {
            text = context.getString(R.string.dismiss_zone_hint)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(20), dp(24), dp(36))
        }
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        })
    }

    fun show(active: Boolean) {
        animate().cancel()
        animate()
            .alpha(if (active) 1f else 0f)
            .setDuration(if (active) 160 else 120)
            .start()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
