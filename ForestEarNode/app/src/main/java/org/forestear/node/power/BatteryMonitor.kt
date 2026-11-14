package org.forestear.node.power

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

data class BatterySnapshot(
    val percent: Int,
    val charging: Boolean,
    val plugged: String,
    val temperatureC: Double,
    val currentMa: Double?,
    val voltageMv: Int,
    val thermalStatus: Int,
    val thermalLabel: String,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    val temperatureValid: Boolean get() = !temperatureC.isNaN()
}

/**
 * Battery, charging, temperature and thermal status for the Nord CE 2 Lite.
 *
 * The phone's battery is non-removable and the enclosure will get hot, so every
 * heartbeat carries this snapshot and the CSV logger writes it once a minute.
 */
class BatteryMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val batteryManager =
        appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val powerManager =
        appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    fun snapshot(): BatterySnapshot {
        val intent: Intent? = appContext.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = when {
            level >= 0 && scale > 0 -> (level * 100 / scale)
            else -> batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                .coerceIn(0, 100)
        }
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pluggedCode = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val charging = pluggedCode != 0 ||
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val plugged = when (pluggedCode) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "none"
        }
        val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        val tempC = if (tenths == Int.MIN_VALUE) Double.NaN else tenths / 10.0
        val currentUa = runCatching {
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        }.getOrDefault(Int.MIN_VALUE)
        val currentMa = if (currentUa == Int.MIN_VALUE || currentUa == 0) {
            null
        } else {
            currentUa / 1000.0
        }
        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val thermal = if (Build.VERSION.SDK_INT >= 29) {
            runCatching { powerManager.currentThermalStatus }.getOrDefault(
                PowerManager.THERMAL_STATUS_NONE
            )
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }
        return BatterySnapshot(
            percent = percent,
            charging = charging,
            plugged = plugged,
            temperatureC = tempC,
            currentMa = currentMa,
            voltageMv = voltageMv,
            thermalStatus = thermal,
            thermalLabel = thermalLabel(thermal),
        )
    }

    companion object {
        fun thermalLabel(status: Int): String = when (status) {
            0 -> "NONE"
            1 -> "LIGHT"
            2 -> "MODERATE"
            3 -> "SEVERE"
            4 -> "CRITICAL"
            5 -> "EMERGENCY"
            6 -> "SHUTDOWN"
            else -> "UNKNOWN($status)"
        }
    }
}
