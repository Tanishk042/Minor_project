package org.forestear.node.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import org.forestear.node.settings.SettingsStore
import org.forestear.node.util.FLog

/**
 * Restarts the node after reboot / package update / OxygenOS quick boot.
 * On Android 14 a microphone foreground service cannot start from BOOT_COMPLETED;
 * NodeService falls back to a dataSync notification and waits for the activity.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val settings = SettingsStore.get(context)
        if (!settings.autoStart) {
            FLog.i("boot receiver: auto-start disabled")
            return
        }
        FLog.i("boot receiver: ${intent.action}")
        val svc = Intent(context, NodeService::class.java).setAction(NodeService.ACTION_BOOT)
        runCatching { ContextCompat.startForegroundService(context, svc) }
            .onFailure { FLog.e("boot start failed", it) }
        WatchdogWorker.schedule(context)
        WatchdogAlarmReceiver.schedule(context)
    }
}
