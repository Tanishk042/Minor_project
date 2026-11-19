package org.forestear.node.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import org.forestear.node.settings.SettingsStore
import org.forestear.node.util.FLog

/** AlarmManager watchdog (survives WorkManager throttling on OxygenOS). */
class WatchdogAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val settings = SettingsStore.get(context)
        if (!settings.autoStart) return
        if (!NodeService.isRunning) {
            FLog.w("alarm watchdog: service not running, restarting")
            startService(context)
        }
        schedule(context)
    }

    companion object {
        private const val REQUEST = 4101
        private const val ACTION = "org.forestear.node.WATCHDOG_ALARM"

        fun startService(context: Context) {
            val svc = Intent(context, NodeService::class.java)
                .setAction(NodeService.ACTION_WATCHDOG)
            runCatching { ContextCompat.startForegroundService(context, svc) }
                .onFailure { FLog.e("watchdog start failed", it) }
        }

        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context, REQUEST,
                Intent(context, WatchdogAlarmReceiver::class.java).setAction(ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val minutes = SettingsStore.get(context).let { 5L }
            val interval = minutes * 60_000L
            runCatching {
                if (Build.VERSION.SDK_INT >= 23) {
                    am.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + interval,
                        pi
                    )
                } else {
                    am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + interval, pi)
                }
            }.onFailure { FLog.w("alarm schedule failed: ${it.message}") }
        }

        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context, REQUEST,
                Intent(context, WatchdogAlarmReceiver::class.java).setAction(ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
        }
    }
}
