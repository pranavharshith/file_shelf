package com.pranav.fileshelf.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pranav.fileshelf.service.OverlayService

class ConfigurationChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_CONFIGURATION_CHANGED) {
            // Notify overlay service to reposition elements for new orientation
            OverlayService.reposition(context)
        }
    }
}
