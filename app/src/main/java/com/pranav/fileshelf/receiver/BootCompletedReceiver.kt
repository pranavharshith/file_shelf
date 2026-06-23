package com.pranav.fileshelf.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pranav.fileshelf.data.FileShelfRepository
import com.pranav.fileshelf.service.OverlayService
import com.pranav.fileshelf.util.PermissionHelper

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val items = FileShelfRepository.loadSync(context)
        if (items.isEmpty()) return
        if (!PermissionHelper.hasHardPermissions(context)) return

        OverlayService.start(context)
    }
}
