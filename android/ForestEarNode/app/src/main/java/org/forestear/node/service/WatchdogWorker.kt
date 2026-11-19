package org.forestear.node.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.forestear.node.settings.SettingsStore
import org.forestear.node.util.FLog
import java.util.concurrent.TimeUnit

/** WorkManager watchdog: if the foreground service died, restart it. */
class WatchdogWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val settings = SettingsStore.get(applicationContext)
        if (!settings.autoStart) return Result.success()
        if (!NodeService.isRunning) {
            FLog.w("watchdog: service not running, restarting")
            WatchdogAlarmReceiver.startService(applicationContext)
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "forestear-watchdog"

        fun schedule(context: Context) {
            val minutes = SettingsStore.get(context).let { 15L }
            val request = PeriodicWorkRequestBuilder<WatchdogWorker>(minutes, TimeUnit.MINUTES)
                .setConstraints(Constraints.NONE)
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    NAME, ExistingPeriodicWorkPolicy.UPDATE, request
                )
            }.onFailure { FLog.w("watchdog schedule failed: ${it.message}") }
        }
    }
}
