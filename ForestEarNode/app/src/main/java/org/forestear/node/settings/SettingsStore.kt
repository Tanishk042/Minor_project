package org.forestear.node.settings

import android.content.Context
import org.forestear.node.config.AppConfig
import org.json.JSONObject

enum class InputChoice { AUTO, BUILTIN, WIRED_HEADSET }

/**
 * Small, dependency-free settings store (SharedPreferences).
 *
 * The service reads these values live, so changing a threshold or gain in the
 * UI takes effect on the next analysis hop without restarting the service.
 */
class SettingsStore private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("forestear_settings", Context.MODE_PRIVATE)

    fun applyDefaults(config: AppConfig) {
        if (prefs.getBoolean("initialized", false)) return
        prefs.edit()
            .putString("node_id", "node-01")
            .putString("backend_url", "http://127.0.0.1:8000")
            .putString("token", "forestear-dev-token")
            .putBoolean("auto_start", true)
            .putBoolean("sms_enabled", false)
            .putBoolean("collection_enabled", false)
            .putBoolean("streaming_enabled", false)
            .putBoolean("soak_enabled", false)
            .putBoolean("use_fixed_location", true)
            .putFloat("fixed_lat", 12.9716f)
            .putFloat("fixed_lon", 77.5946f)
            .putString("input_choice", InputChoice.AUTO.name)
            .putFloat("gain_builtin", 1.0f)
            .putFloat("gain_headset", 1.0f)
            .putInt("m8_duty", config.biodiversity.dutyCycle)
            .putInt("heartbeat_s", 30)
            .putInt("analysis_budget_ms", config.android.analysisBudgetMs)
            .putFloat("thermal_warm_c", config.android.thermal.warmC.toFloat())
            .putFloat("thermal_hot_c", config.android.thermal.hotC.toFloat())
            .putFloat("thermal_critical_c", config.android.thermal.criticalC.toFloat())
            .putFloat("thermal_hard_pause_c", config.android.thermal.hardPauseC.toFloat())
            .putInt("low_battery_pct", config.android.lowBattery.enablePct)
            .putInt("critical_battery_pct", config.android.lowBattery.criticalPct)
            .putString("threshold_gunshot", config.decision.thresholds["gunshot"].toString())
            .putString("threshold_chainsaw", config.decision.thresholds["chainsaw"].toString())
            .putString("threshold_vehicle", config.decision.thresholds["vehicle"].toString())
            .putString("threshold_human", config.decision.thresholds["human"].toString())
            .putString("threshold_anomaly", config.decision.thresholds["anomaly"].toString())
            .putString("streaming_url", config.android.streaming.defaultUrl)
            .putString("sms_numbers", "")
            .putInt("sms_subscription_id", -1)
            .putBoolean("initialized", true)
            .apply()
    }

    var nodeId: String
        get() = prefs.getString("node_id", "node-01") ?: "node-01"
        set(v) = prefs.edit().putString("node_id", v.trim()).apply()

    var backendUrl: String
        get() = (prefs.getString("backend_url", "http://127.0.0.1:8000") ?: "").trimEnd('/')
        set(v) = prefs.edit().putString("backend_url", v.trim().trimEnd('/')).apply()

    var token: String
        get() = prefs.getString("token", "forestear-dev-token") ?: ""
        set(v) = prefs.edit().putString("token", v.trim()).apply()

    var autoStart: Boolean
        get() = prefs.getBoolean("auto_start", true)
        set(v) = prefs.edit().putBoolean("auto_start", v).apply()

    var smsEnabled: Boolean
        get() = prefs.getBoolean("sms_enabled", false)
        set(v) = prefs.edit().putBoolean("sms_enabled", v).apply()

    var collectionEnabled: Boolean
        get() = prefs.getBoolean("collection_enabled", false)
        set(v) = prefs.edit().putBoolean("collection_enabled", v).apply()

    var streamingEnabled: Boolean
        get() = prefs.getBoolean("streaming_enabled", false)
        set(v) = prefs.edit().putBoolean("streaming_enabled", v).apply()

    var soakEnabled: Boolean
        get() = prefs.getBoolean("soak_enabled", false)
        set(v) = prefs.edit().putBoolean("soak_enabled", v).apply()

    var useFixedLocation: Boolean
        get() = prefs.getBoolean("use_fixed_location", true)
        set(v) = prefs.edit().putBoolean("use_fixed_location", v).apply()

    var fixedLat: Double
        get() = prefs.getFloat("fixed_lat", 12.9716f).toDouble()
        set(v) = prefs.edit().putFloat("fixed_lat", v.toFloat()).apply()

    var fixedLon: Double
        get() = prefs.getFloat("fixed_lon", 77.5946f).toDouble()
        set(v) = prefs.edit().putFloat("fixed_lon", v.toFloat()).apply()

    var inputChoice: InputChoice
        get() = runCatching {
            InputChoice.valueOf(prefs.getString("input_choice", InputChoice.AUTO.name)!!)
        }.getOrDefault(InputChoice.AUTO)
        set(v) = prefs.edit().putString("input_choice", v.name).apply()

    fun gainFor(isHeadset: Boolean): Float =
        prefs.getFloat(if (isHeadset) "gain_headset" else "gain_builtin", 1.0f)
            .coerceIn(0.25f, 8.0f)

    fun setGain(isHeadset: Boolean, gain: Float) {
        prefs.edit().putFloat(if (isHeadset) "gain_headset" else "gain_builtin",
            gain.coerceIn(0.25f, 8.0f)).apply()
    }

    var m8Duty: Int
        get() = prefs.getInt("m8_duty", 10).coerceAtLeast(1)
        set(v) = prefs.edit().putInt("m8_duty", v.coerceAtLeast(1)).apply()

    var heartbeatS: Int
        get() = prefs.getInt("heartbeat_s", 30).coerceAtLeast(5)
        set(v) = prefs.edit().putInt("heartbeat_s", v.coerceAtLeast(5)).apply()

    var analysisBudgetMs: Int
        get() = prefs.getInt("analysis_budget_ms", 400).coerceIn(50, 5000)
        set(v) = prefs.edit().putInt("analysis_budget_ms", v.coerceIn(50, 5000)).apply()

    var thermalWarmC: Double
        get() = prefs.getFloat("thermal_warm_c", 40f).toDouble()
        set(v) = prefs.edit().putFloat("thermal_warm_c", v.toFloat()).apply()

    var thermalHotC: Double
        get() = prefs.getFloat("thermal_hot_c", 45f).toDouble()
        set(v) = prefs.edit().putFloat("thermal_hot_c", v.toFloat()).apply()

    var thermalCriticalC: Double
        get() = prefs.getFloat("thermal_critical_c", 50f).toDouble()
        set(v) = prefs.edit().putFloat("thermal_critical_c", v.toFloat()).apply()

    var thermalHardPauseC: Double
        get() = prefs.getFloat("thermal_hard_pause_c", 55f).toDouble()
        set(v) = prefs.edit().putFloat("thermal_hard_pause_c", v.toFloat()).apply()

    var lowBatteryPct: Int
        get() = prefs.getInt("low_battery_pct", 20).coerceIn(1, 80)
        set(v) = prefs.edit().putInt("low_battery_pct", v.coerceIn(1, 80)).apply()

    var criticalBatteryPct: Int
        get() = prefs.getInt("critical_battery_pct", 10).coerceIn(1, 50)
        set(v) = prefs.edit().putInt("critical_battery_pct", v.coerceIn(1, 50)).apply()

    var streamingUrl: String
        get() = prefs.getString("streaming_url", "ws://192.168.1.10:8765/stream") ?: ""
        set(v) = prefs.edit().putString("streaming_url", v.trim()).apply()

    var smsNumbers: String
        get() = prefs.getString("sms_numbers", "") ?: ""
        set(v) = prefs.edit().putString("sms_numbers", v.trim()).apply()

    fun smsNumberList(): List<String> =
        smsNumbers.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    var smsSubscriptionId: Int
        get() = prefs.getInt("sms_subscription_id", -1)
        set(v) = prefs.edit().putInt("sms_subscription_id", v).apply()

    fun thresholdOverrides(): Map<String, Double> = mapOf(
        "gunshot" to prefs.getString("threshold_gunshot", "0.6")!!.toDoubleOrNull().orDefault(0.6),
        "chainsaw" to prefs.getString("threshold_chainsaw", "0.5")!!.toDoubleOrNull().orDefault(0.5),
        "vehicle" to prefs.getString("threshold_vehicle", "0.5")!!.toDoubleOrNull().orDefault(0.5),
        "human" to prefs.getString("threshold_human", "0.6")!!.toDoubleOrNull().orDefault(0.6),
        "anomaly" to prefs.getString("threshold_anomaly", "0.7")!!.toDoubleOrNull().orDefault(0.7),
    )

    fun setThreshold(cls: String, value: Double) {
        prefs.edit().putString("threshold_$cls", value.toString()).apply()
    }

    fun threshold(cls: String): Double = thresholdOverrides()[cls] ?: 0.5

    var setupComplete: Boolean
        get() = prefs.getBoolean("setup_complete", false)
        set(v) = prefs.edit().putBoolean("setup_complete", v).apply()

    var batteryExemptConfirmed: Boolean
        get() = prefs.getBoolean("battery_exempt_confirmed", false)
        set(v) = prefs.edit().putBoolean("battery_exempt_confirmed", v).apply()

    var backgroundActivityConfirmed: Boolean
        get() = prefs.getBoolean("background_activity_confirmed", false)
        set(v) = prefs.edit().putBoolean("background_activity_confirmed", v).apply()

    var autoLaunchConfirmed: Boolean
        get() = prefs.getBoolean("auto_launch_confirmed", false)
        set(v) = prefs.edit().putBoolean("auto_launch_confirmed", v).apply()

    var recentsLockedConfirmed: Boolean
        get() = prefs.getBoolean("recents_locked_confirmed", false)
        set(v) = prefs.edit().putBoolean("recents_locked_confirmed", v).apply()

    var restartCount: Int
        get() = prefs.getInt("restart_count", 0)
        private set(value) = prefs.edit().putInt("restart_count", value).apply()

    fun incrementRestartCount(): Int {
        val v = restartCount + 1
        restartCount = v
        return v
    }

    var totalUptimeMs: Long
        get() = prefs.getLong("total_uptime_ms", 0L)
        private set(value) = prefs.edit().putLong("total_uptime_ms", value).apply()

    fun addUptime(ms: Long) {
        totalUptimeMs = totalUptimeMs + ms.coerceAtLeast(0)
    }

    var audioRestartCount: Int
        get() = prefs.getInt("audio_restart_count", 0)
        private set(value) = prefs.edit().putInt("audio_restart_count", value).apply()

    fun incrementAudioRestartCount(): Int {
        val v = audioRestartCount + 1
        audioRestartCount = v
        return v
    }

    var lastSmsStatus: String
        get() = prefs.getString("last_sms_status", "never") ?: "never"
        set(v) = prefs.edit().putString("last_sms_status", v).apply()

    var smsSentTimes: String
        get() = prefs.getString("sms_sent_times", "[]") ?: "[]"
        set(v) = prefs.edit().putString("sms_sent_times", v).apply()

    var bestDelegates: String
        get() = prefs.getString("best_delegates", "{}") ?: "{}"
        set(v) = prefs.edit().putString("best_delegates", v).apply()

    fun bestDelegateFor(model: String): String =
        runCatching { JSONObject(bestDelegates).optString(model, "CPU_XNNPACK") }
            .getOrDefault("CPU_XNNPACK")

    companion object {
        @Volatile private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context).also { instance = it }
            }
    }
}

private fun Double?.orDefault(def: Double): Double = this ?: def
