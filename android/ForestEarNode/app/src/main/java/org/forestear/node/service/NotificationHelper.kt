package org.forestear.node.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import org.forestear.node.MainActivity
import org.forestear.node.R

class NotificationHelper(private val context: Context) {
    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SERVICE, "ForestEar monitoring",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Persistent foreground-service status"
                    setShowBadge(false)
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERTS, "ForestEar warnings",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Thermal, battery and service warnings" }
            )
        }
    }

    fun ongoing(text: String, paused: Boolean = false): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, ControlReceiver::class.java).setAction(ControlReceiver.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_forestear)
            .setContentTitle(
                if (paused) context.getString(R.string.notification_mic_paused)
                else context.getString(R.string.notification_title)
            )
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, context.getString(R.string.notification_stop), stop)
            .build()
    }

    fun update(notificationId: Int, text: String, paused: Boolean = false) {
        runCatching { manager.notify(notificationId, ongoing(text, paused)) }
    }

    fun warning(title: String, text: String) {
        val open = PendingIntent.getActivity(
            context, 2,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_forestear)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { manager.notify(WARNING_ID, n) }
    }

    /**
     * High-priority re-entry notification used when the OS refused to restart
     * the microphone in the background. Uses a full-screen intent only when the
     * platform says it is permitted; otherwise it is a normal heads-up alert.
     */
    fun reentry(title: String, text: String) {
        val open = PendingIntent.getActivity(
            context, 3,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_forestear)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.notification_open), open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
        val allowed = if (Build.VERSION.SDK_INT >= 34) {
            runCatching { manager.canUseFullScreenIntent() }.getOrDefault(false)
        } else {
            true
        }
        if (allowed) builder.setFullScreenIntent(open, true)
        runCatching { manager.notify(REENTRY_ID, builder.build()) }
    }

    companion object {
        const val CHANNEL_SERVICE = "forestear_service"
        const val CHANNEL_ALERTS = "forestear_alerts"
        const val SERVICE_ID = 1001
        const val WARNING_ID = 1002
        const val REENTRY_ID = 1003
    }
}
