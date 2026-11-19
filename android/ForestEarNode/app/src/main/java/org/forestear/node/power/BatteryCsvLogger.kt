package org.forestear.node.power

import android.content.Context
import org.forestear.node.util.FLog
import org.forestear.node.util.TimeUtils
import java.io.File
import java.util.Locale

/**
 * One CSV row per minute for the burn-in/soak test:
 * timestamp_utc, level_pct, charging, plugged, temp_c, current_ma, voltage_mv,
 * thermal_status, thermal_label, governor
 */
class BatteryCsvLogger(context: Context) {
    private val dir: File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "logs").also { it.mkdirs() }
    private val file = File(dir, "battery_log.csv")
    private var lastWriteMs = 0L
    private val lock = Any()

    fun maybeLog(snapshot: BatterySnapshot, governor: String, intervalMs: Long = 60_000L) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (now - lastWriteMs < intervalMs) return
            lastWriteMs = now
            val header = "timestamp_utc,level_pct,charging,plugged,temp_c,current_ma,voltage_mv,thermal_status,thermal_label,governor\n"
            if (!file.exists()) file.writeText(header)
            rotateIfNeeded()
            val temp = if (snapshot.temperatureValid) {
                String.format(Locale.US, "%.1f", snapshot.temperatureC)
            } else ""
            val current = snapshot.currentMa?.let { String.format(Locale.US, "%.0f", it) } ?: ""
            val row = listOf(
                TimeUtils.isoUtc(now),
                snapshot.percent.toString(),
                snapshot.charging.toString(),
                snapshot.plugged,
                temp,
                current,
                snapshot.voltageMv.toString(),
                snapshot.thermalStatus.toString(),
                snapshot.thermalLabel,
                governor,
            ).joinToString(",") + "\n"
            runCatching { file.appendText(row) }
                .onFailure { FLog.w("battery CSV append failed: ${it.message}") }
        }
    }

    fun file(): File = file

    fun rows(): Int = runCatching {
        file.readLines().count { it.isNotBlank() } - 1
    }.getOrDefault(0)

    private fun rotateIfNeeded() {
        if (!file.exists() || file.length() < 5_000_000L) return
        val old = File(dir, "battery_log.1.csv")
        runCatching { old.delete() }
        runCatching { file.renameTo(old) }
    }
}
