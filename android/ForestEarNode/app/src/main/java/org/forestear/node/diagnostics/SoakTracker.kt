package org.forestear.node.diagnostics

import android.content.Context
import org.forestear.node.power.BatterySnapshot
import org.forestear.node.settings.SettingsStore
import org.forestear.node.util.FLog
import org.forestear.node.util.TimeUtils
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class SoakReport(
    val startedUtc: String,
    val endedUtc: String,
    val durationS: Long,
    val restartsStart: Int,
    val restartsEnd: Int,
    val droppedFrames: Long,
    val alertsSent: Long,
    val queueSize: Int,
    val minBatteryPct: Int?,
    val maxBatteryPct: Int?,
    val minTempC: Double?,
    val maxTempC: Double?,
    val samples: Int,
    val completed24h: Boolean,
)

/**
 * 24-hour soak test bookkeeping. The start time and running maxima live in
 * SharedPreferences so a service restart does not reset the test.
 */
class SoakTracker(context: Context, private val settings: SettingsStore) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("forestear_soak", Context.MODE_PRIVATE)
    private val dir = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "logs")
        .also { it.mkdirs() }
    private val csv = File(dir, "soak_samples.csv")
    private val reportFile = File(dir, "soak_report.json")

    fun start() {
        prefs.edit()
            .putLong("start_ms", System.currentTimeMillis())
            .putInt("restarts_start", settings.restartCount)
            .putLong("alerts_start", 0L)
            .putInt("min_battery", 101)
            .putInt("max_battery", -1)
            .putFloat("min_temp", Float.NaN)
            .putFloat("max_temp", Float.NaN)
            .putLong("dropped", 0L)
            .putInt("samples", 0)
            .apply()
        csv.writeText("timestamp_utc,level_pct,charging,temp_c,thermal,governor\n")
        FLog.i("soak test started")
    }

    fun isActive(): Boolean = settings.soakEnabled && prefs.getLong("start_ms", 0L) > 0L

    fun elapsedMs(): Long {
        val start = prefs.getLong("start_ms", 0L)
        return if (start > 0) System.currentTimeMillis() - start else 0L
    }

    fun sample(
        battery: BatterySnapshot,
        governor: String,
        droppedFrames: Long,
        queueSize: Int,
        alertsSent: Long,
    ) {
        if (!isActive()) return
        val editor = prefs.edit()
        val minB = prefs.getInt("min_battery", 101)
        val maxB = prefs.getInt("max_battery", -1)
        if (battery.percent in 0..100) {
            if (battery.percent < minB) editor.putInt("min_battery", battery.percent)
            if (battery.percent > maxB) editor.putInt("max_battery", battery.percent)
        }
        if (battery.temperatureValid) {
            val minT = prefs.getFloat("min_temp", Float.NaN)
            val maxT = prefs.getFloat("max_temp", Float.NaN)
            if (minT.isNaN() || battery.temperatureC < minT) {
                editor.putFloat("min_temp", battery.temperatureC.toFloat())
            }
            if (maxT.isNaN() || battery.temperatureC > maxT) {
                editor.putFloat("max_temp", battery.temperatureC.toFloat())
            }
        }
        editor.putLong("dropped", maxOf(droppedFrames, prefs.getLong("dropped", 0L)))
        editor.putInt("samples", prefs.getInt("samples", 0) + 1)
        editor.putLong("alerts_end", alertsSent)
        editor.putInt("queue_end", queueSize)
        editor.apply()

        val temp = if (battery.temperatureValid) {
            String.format(Locale.US, "%.1f", battery.temperatureC)
        } else ""
        val row = listOf(
            TimeUtils.isoUtc(),
            battery.percent.toString(),
            battery.charging.toString(),
            temp,
            battery.thermalLabel,
            governor,
        ).joinToString(",") + "\n"
        runCatching { csv.appendText(row) }
    }

    fun finish(): SoakReport? {
        if (prefs.getLong("start_ms", 0L) == 0L) return null
        val report = report()
        runCatching { reportFile.writeText(toJson(report).toString(2)) }
            .onFailure { FLog.w("soak report write failed: ${it.message}") }
        settings.soakEnabled = false
        return report
    }

    fun report(): SoakReport {
        val start = prefs.getLong("start_ms", 0L)
        val elapsed = if (start > 0) System.currentTimeMillis() - start else 0L
        val minT = prefs.getFloat("min_temp", Float.NaN)
        val maxT = prefs.getFloat("max_temp", Float.NaN)
        return SoakReport(
            startedUtc = if (start > 0) TimeUtils.isoUtc(start) else "",
            endedUtc = TimeUtils.isoUtc(),
            durationS = elapsed / 1000,
            restartsStart = prefs.getInt("restarts_start", 0),
            restartsEnd = settings.restartCount,
            droppedFrames = prefs.getLong("dropped", 0L),
            alertsSent = prefs.getLong("alerts_end", 0L),
            queueSize = prefs.getInt("queue_end", 0),
            minBatteryPct = prefs.getInt("min_battery", 101).takeIf { it <= 100 },
            maxBatteryPct = prefs.getInt("max_battery", -1).takeIf { it >= 0 },
            minTempC = if (minT.isNaN()) null else minT.toDouble(),
            maxTempC = if (maxT.isNaN()) null else maxT.toDouble(),
            samples = prefs.getInt("samples", 0),
            completed24h = elapsed >= 24 * 3600 * 1000L,
        )
    }

    fun reportPath(): String = reportFile.absolutePath
    fun csvPath(): String = csv.absolutePath

    private fun toJson(r: SoakReport): JSONObject = JSONObject()
        .put("started_utc", r.startedUtc)
        .put("ended_utc", r.endedUtc)
        .put("duration_s", r.durationS)
        .put("restarts_start", r.restartsStart)
        .put("restarts_end", r.restartsEnd)
        .put("dropped_frames", r.droppedFrames)
        .put("alerts_sent", r.alertsSent)
        .put("queue_size", r.queueSize)
        .put("min_battery_pct", r.minBatteryPct ?: JSONObject.NULL)
        .put("max_battery_pct", r.maxBatteryPct ?: JSONObject.NULL)
        .put("min_temp_c", r.minTempC ?: JSONObject.NULL)
        .put("max_temp_c", r.maxTempC ?: JSONObject.NULL)
        .put("samples", r.samples)
        .put("completed_24h", r.completed24h)
}
