package com.pranav.fileshelf.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

object PermissionHelper {

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun overlaySettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isFloatingShelfEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_FLOATING_ENABLED, false)
    }

    fun setFloatingShelfEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FLOATING_ENABLED, enabled)
            .apply()
    }

    fun isOnboardingComplete(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDING_COMPLETE, false)
    }

    fun setOnboardingComplete(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ONBOARDING_COMPLETE, true)
            .apply()
    }
    
    fun clearOnboarding(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ONBOARDING_COMPLETE, false)
            .apply()
    }

    fun hasSeenDragHint(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DRAG_HINT_SEEN, false)
    }

    fun setDragHintSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DRAG_HINT_SEEN, true)
            .apply()
    }

    /**
     * True when both system-level permissions required by the overlay are granted.
     * Does NOT check isFloatingShelfEnabled — use this as the gate for start() calls
     * so that users who skipped onboarding step 3 still get the bubble.
     */
    fun hasHardPermissions(context: Context): Boolean {
        return canDrawOverlays(context) && hasNotificationPermission(context)
    }

    fun canStartOverlay(context: Context): Boolean {
        return isFloatingShelfEnabled(context) &&
            canDrawOverlays(context) &&
            hasNotificationPermission(context)
    }

    private const val PREFS = "file_shelf_prefs"
    private const val KEY_FLOATING_ENABLED = "floating_enabled"
    private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
    private const val KEY_DRAG_HINT_SEEN = "drag_hint_seen"
}
