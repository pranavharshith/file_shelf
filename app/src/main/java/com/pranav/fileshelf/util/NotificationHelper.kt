package com.pranav.fileshelf.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pranav.fileshelf.MainActivity
import com.pranav.fileshelf.R
import com.pranav.fileshelf.receiver.NotificationActionReceiver
import com.pranav.fileshelf.service.OverlayService

object NotificationHelper {

    const val CHANNEL_OVERLAY = "file_shelf_overlay"
    const val CHANNEL_COPY = "file_shelf_copy"

    const val NOTIFICATION_OVERLAY_ID = 1001


    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_OVERLAY,
                context.getString(R.string.channel_overlay),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the floating file shelf visible"
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_COPY,
                context.getString(R.string.channel_copy),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while copying shared files"
            }
        )
    }

    fun buildOverlayNotification(context: Context, fileCount: Int) =
        NotificationCompat.Builder(context, CHANNEL_OVERLAY)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notification_overlay_title))
            .setContentText(context.getString(R.string.notification_overlay_text, fileCount))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openShelfPendingIntent(context))
            .addAction(R.drawable.ic_launcher_foreground, context.getString(R.string.action_open_shelf), openShelfPendingIntent(context))
            .addAction(R.drawable.ic_launcher_foreground, context.getString(R.string.action_dismiss_all), dismissAllPendingIntent(context))
            .build()

    fun showCopyProgress(context: Context, jobId: String, fileName: String, progress: Int?) {
        val id = jobId.hashCode()
        val builder = NotificationCompat.Builder(context, CHANNEL_COPY)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.copying_file, fileName))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (progress != null && progress in 0..100) {
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    fun showCopyComplete(context: Context, fileName: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_COPY)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.copy_complete))
            .setContentText(fileName)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), notification)
    }

    fun showCopyError(context: Context, message: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_COPY)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.copy_failed, message))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), notification)
    }

    fun showFileSizeLimitError(context: Context, fileName: String, sizeMB: Long, limitMB: Long) {
        val notification = NotificationCompat.Builder(context, CHANNEL_COPY)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("File too large")
            .setContentText("$fileName ($sizeMB MB) exceeds $limitMB MB limit")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("$fileName is $sizeMB MB, but File Shelf only supports files up to $limitMB MB. Try sharing a smaller file."))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun openShelfPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun dismissAllPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_DISMISS_ALL
        }
        return PendingIntent.getBroadcast(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun updateOverlayNotification(context: Context, fileCount: Int) {
        if (fileCount <= 0) return
        NotificationManagerCompat.from(context).notify(
            NOTIFICATION_OVERLAY_ID,
            buildOverlayNotification(context, fileCount)
        )
    }
}
