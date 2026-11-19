package org.forestear.node

import android.app.Application
import android.content.pm.ApplicationInfo
import org.forestear.node.config.AppConfig
import org.forestear.node.service.WatchdogAlarmReceiver
import org.forestear.node.service.WatchdogWorker
import org.forestear.node.settings.SettingsStore
import org.forestear.node.util.AndroidAssetSource
import org.forestear.node.util.FLog

/**
 * Application entry point.
 *
 * Deliberately does not start the microphone foreground service: on Android 14
 * a while-in-use FGS cannot be started from the background. BootReceiver and the
 * watchdog start the dataSync shell, and the visible activity upgrades it to a
 * microphone FGS. Here we only seed defaults and (re)arm the watchdogs when the
 * user has completed setup and left auto-start enabled.
 */
class ForestEarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FLog.verbose = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        runCatching {
            val source = AndroidAssetSource(this)
            val config = AppConfig.load(source)
            val settings = SettingsStore.get(this)
            settings.applyDefaults(config)
            if (settings.autoStart && settings.setupComplete) {
                WatchdogWorker.schedule(this)
                WatchdogAlarmReceiver.schedule(this)
            }
        }.onFailure {
            FLog.e("application initialisation failed", it)
        }
    }
}
