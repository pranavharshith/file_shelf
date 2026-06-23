package com.pranav.fileshelf.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pranav.fileshelf.FileShelfApp
import com.pranav.fileshelf.data.FileShelfRepository
import com.pranav.fileshelf.service.OverlayService
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DISMISS_ALL) return

        val app = context.applicationContext as FileShelfApp
        app.appScope.launch {
            FileShelfRepository.clearAll(context)
            OverlayService.stop(context)
        }
    }

    companion object {
        const val ACTION_DISMISS_ALL = "com.pranav.fileshelf.DISMISS_ALL"
    }
}
