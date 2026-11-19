package org.forestear.node.settings

import android.content.Context
import org.forestear.node.config.AppConfig
import org.json.JSONObject

enum class InputChoice { AUTO, BUILTIN, WIRED_HEADSET, USB }

/**
 * Small, dependency-free settings store (SharedPreferences).
 *
 * The service reads these values live, so changing a threshold or gain in the
 * UI takes effect on the next analysis hop without restarting the service.
 */
class SettingsStore private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("forestear_settings", Context.MODE_PRIVATE)
    private val tokenStore = TokenStore(PrefsKeyValueStore(prefs), KeystoreTokenCipher())

    /** "android-keystore-aes-gcm", "plaintext-fallback", or "unset". */
    val tokenStorageMode: String get() = tokenStore.storageMode

    /** True only when the token is encrypted at rest. */
    val tokenEncrypted: Boolean get() = tokenStore.isEncrypted()

    fun applyDefaults(config: AppConfig) {
        if (prefs.getBoolean("initialized", false)) return
        prefs.edit()
            .putString("node_id", "node-01")
            .putString("backend_url", "http://127.0.0.1:8000")
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
            .putString("tier_override", "AUTO")
            .putString("tier_plan", "")
            .putString("tier_profile_key", "")
            .putBoolean("wizard_completed", false)
            .putBoolean("charge_limit_confirmed", false)
            .putBoolean("battery_health_inspected", false)
            .putBoolean("overlay_confirmed", false)
            .putBoolean("overlay_helped", false)
            .putBoolean("mock_capabilities", false)
            .putString("continuity_result", "")
            .putInt("continuity_minutes", 5)
            .putBoolean("consent_accepted", false)
            .putString("consent_accepted_utc", "")
            .putInt("consent_version", 0)
            .putInt("retention_days", 30)
            .putBoolean("initialized", true)
            .apply()
        tokenStore.set("forestear-dev-token")
    }

    /** Consent gate: false until the operator accepts the current notice. */
    var consentAccepted: Boolean
        get() = prefs.getBoolean("consent_accepted", false)
        set(v) = prefs.edit().putBoolean("consent_accepted", v).apply()

    var consentAcceptedUtc: String
        get() = prefs.getString("consent_accepted_utc", "") ?: ""
        set(v) = prefs.edit().putString("consent_accepted_utc", v).apply()

    var consentVersion: Int
        get() = prefs.getInt("consent_version", 0)
        set(v) = prefs.edit().putInt("consent_version", v).apply()

    /** Operator's stated retention for events/clips; shown, not enforced. */
    var retentionDays: Int
        get() = prefs.getInt("retention_days", 30).coerceIn(1, 3650)
        set(v) = prefs.edit().putInt("retention_days", v.coerceIn(1, 3650)).apply()

    fun acceptConsent(version: Int = CONSENT_VERSION) {
        prefs.edit()
            .putBoolean("consent_accepted", true)
            .putString("consent_accepted_utc", java.time.Instant.now().toString())
            .putInt("consent_version", version)
            .apply()
    }

    fun revokeConsent() {
        prefs.edit().putBoolean("consent_accepted", false).apply()
    }

    var nodeId: String
        get() = prefs.getString("node_id", "node-01") ?: "node-01"
        set(v) = prefs.edit().putString("node_id", v.trim()).apply()

    var backendUrl: String
        get() = (prefs.getString("backend_url", "http://127.0.0.1:8000") ?: "").trimEnd('/')
        set(v) = prefs.edit().putString("backend_url", v.trim().trimEnd('/')).apply()

    var token: String
        get() = tokenStore.get()
        set(v) = tokenStore.set(v)

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

    /** Per-input gain key: builtin / headset / usb. */
    fun gainFor(inputKey: String): Float =
        prefs.getFloat("gain_${inputKey.replace(Regex("[^a-z0-9_]"), "_")}", 1.0f)
            .coerceIn(0.25f, 8.0f)

    fun setGain(inputKey: String, gain: Float) {
        prefs.edit()
            .putFloat("gain_${inputKey.replace(Regex("[^a-z0-9_]"), "_")}", gain.coerceIn(0.25f, 8.0f))
            .apply()
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

    /** Mean full-pipeline time from the last benchmark; NaN when never measured. */
    var lastBenchmarkTotalMs: Double
        get() = prefs.getFloat("benchmark_total_ms", Float.NaN).toDouble()
        set(v) = prefs.edit()
            .putFloat("benchmark_total_ms", if (v.isFinite()) v.toFloat() else Float.NaN)
            .apply()

    /** Models whose GPU delegate was both faster and stable on this handset. */
    var gpuStableModels: Set<String>
        get() = (prefs.getString("gpu_stable_models", "") ?: "")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        set(v) = prefs.edit().putString("gpu_stable_models", v.joinToString(",")).apply()

    var continuityMinutes: Int
        get() = prefs.getInt("continuity_minutes", 5).coerceIn(1, 120)
        set(v) = prefs.edit().putInt("continuity_minutes", v.coerceIn(1, 120)).apply()

    // ---------------------------------------------------------------- tiers
    var tierOverride: org.forestear.node.device.PerfTier?
        get() = org.forestear.node.device.PerfTier.fromName(
            prefs.getString("tier_override", "AUTO")
        )
        set(v) = prefs.edit().putString("tier_override", v?.name ?: "AUTO").apply()

    var tierPlanJson: String
        get() = prefs.getString("tier_plan", "") ?: ""
        private set(v) = prefs.edit().putString("tier_plan", v).apply()

    fun tierPlan(): org.forestear.node.device.TierPlan? = runCatching {
        val text = tierPlanJson
        if (text.isBlank()) null
        else org.forestear.node.device.TierPlan.fromJson(JSONObject(text))
    }.getOrNull()

    fun setTierPlan(plan: org.forestear.node.device.TierPlan, profileKey: String) {
        prefs.edit()
            .putString("tier_plan", plan.toJson().toString())
            .putString("tier_profile_key", profileKey)
            .apply()
    }

    var tierProfileKey: String
        get() = prefs.getString("tier_profile_key", "") ?: ""
        private set(v) = prefs.edit().putString("tier_profile_key", v).apply()

    /** True when the stored tier was chosen for a different app version/device. */
    fun tierIsStale(currentProfileKey: String): Boolean =
        tierPlan() == null || tierProfileKey != currentProfileKey

    // ------------------------------------------------------------- wizard
    var wizardCompleted: Boolean
        get() = prefs.getBoolean("wizard_completed", false)
        set(v) = prefs.edit().putBoolean("wizard_completed", v).apply()

    var chargeLimitConfirmed: Boolean
        get() = prefs.getBoolean("charge_limit_confirmed", false)
        set(v) = prefs.edit().putBoolean("charge_limit_confirmed", v).apply()

    var batteryHealthInspected: Boolean
        get() = prefs.getBoolean("battery_health_inspected", false)
        set(v) = prefs.edit().putBoolean("battery_health_inspected", v).apply()

    var overlayConfirmed: Boolean
        get() = prefs.getBoolean("overlay_confirmed", false)
        set(v) = prefs.edit().putBoolean("overlay_confirmed", v).apply()

    var overlayHelped: Boolean
        get() = prefs.getBoolean("overlay_helped", false)
        set(v) = prefs.edit().putBoolean("overlay_helped", v).apply()

    var mockCapabilities: Boolean
        get() = prefs.getBoolean("mock_capabilities", false)
        set(v) = prefs.edit().putBoolean("mock_capabilities", v).apply()

    var continuityResultJson: String
        get() = prefs.getString("continuity_result", "") ?: ""
        set(v) = prefs.edit().putString("continuity_result", v).apply()

    // ------------------------------------------------------- export/import
    /**
     * Settings export. Deliberately excludes counters, logs and the device
     * profile: those describe this handset, not the configuration the user
     * wants to copy to another node.
     */
    fun exportJson(): String = JSONObject()
        .put("schema", EXPORT_SCHEMA)
        .put("node_id", nodeId)
        .put("backend_url", backendUrl)
        // The backend token is intentionally NOT exported: exports are shared
        // as plain files. Re-enter the token after importing on a new phone.
        .put("token_included", false)
        .put("token_storage_mode", tokenStorageMode)
        .put("auto_start", autoStart)
        .put("sms_enabled", smsEnabled)
        .put("sms_numbers", smsNumbers)
        .put("sms_subscription_id", smsSubscriptionId)
        .put("collection_enabled", collectionEnabled)
        .put("streaming_enabled", streamingEnabled)
        .put("streaming_url", streamingUrl)
        .put("use_fixed_location", useFixedLocation)
        .put("fixed_lat", fixedLat)
        .put("fixed_lon", fixedLon)
        .put("input_choice", inputChoice.name)
        .put("tier_override", tierOverride?.name ?: "AUTO")
        .put("heartbeat_s", heartbeatS)
        .put("analysis_budget_ms", analysisBudgetMs)
        .put("m8_duty", m8Duty)
        .put("thermal", JSONObject()
            .put("warm_c", thermalWarmC)
            .put("hot_c", thermalHotC)
            .put("critical_c", thermalCriticalC)
            .put("hard_pause_c", thermalHardPauseC))
        .put("low_battery_pct", lowBatteryPct)
        .put("critical_battery_pct", criticalBatteryPct)
        .put("thresholds", JSONObject(thresholdOverrides() as Map<*, *>))
        .put("gains", JSONObject()
            .put("builtin", gainFor("builtin"))
            .put("headset", gainFor("headset"))
            .put("usb", gainFor("usb")))
        .put("wizard_completed", wizardCompleted)
        .put("charge_limit_confirmed", chargeLimitConfirmed)
        .put("battery_health_inspected", batteryHealthInspected)
        .put("overlay_confirmed", overlayConfirmed)
        .toString(2)

    /** Returns false when the JSON is not a ForestEar settings export. */
    fun importJson(text: String): Boolean = runCatching {
        val o = JSONObject(text)
        if (o.optInt("schema", -1) != EXPORT_SCHEMA) return false
        // Legacy exports may contain a token; new exports never do. Accept it
        // if present and store it through the encrypted path.
        val importedToken = if (o.has("token")) o.optString("token").trim() else ""
        val editor = prefs.edit()
        if (o.has("node_id")) editor.putString("node_id", o.optString("node_id").trim())
        if (o.has("backend_url")) editor.putString("backend_url", o.optString("backend_url").trim().trimEnd('/'))
        if (o.has("auto_start")) editor.putBoolean("auto_start", o.optBoolean("auto_start"))
        if (o.has("sms_enabled")) editor.putBoolean("sms_enabled", o.optBoolean("sms_enabled"))
        if (o.has("sms_numbers")) editor.putString("sms_numbers", o.optString("sms_numbers"))
        if (o.has("sms_subscription_id")) editor.putInt("sms_subscription_id", o.optInt("sms_subscription_id", -1))
        if (o.has("collection_enabled")) editor.putBoolean("collection_enabled", o.optBoolean("collection_enabled"))
        if (o.has("streaming_enabled")) editor.putBoolean("streaming_enabled", o.optBoolean("streaming_enabled"))
        if (o.has("streaming_url")) editor.putString("streaming_url", o.optString("streaming_url"))
        if (o.has("use_fixed_location")) editor.putBoolean("use_fixed_location", o.optBoolean("use_fixed_location"))
        if (o.has("fixed_lat")) editor.putFloat("fixed_lat", o.optDouble("fixed_lat", 12.9716).toFloat())
        if (o.has("fixed_lon")) editor.putFloat("fixed_lon", o.optDouble("fixed_lon", 77.5946).toFloat())
        if (o.has("input_choice")) {
            val choice = runCatching { InputChoice.valueOf(o.optString("input_choice")) }
                .getOrDefault(InputChoice.AUTO)
            editor.putString("input_choice", choice.name)
        }
        if (o.has("tier_override")) {
            val tier = org.forestear.node.device.PerfTier.fromName(o.optString("tier_override"))
            editor.putString("tier_override", tier?.name ?: "AUTO")
        }
        if (o.has("heartbeat_s")) editor.putInt("heartbeat_s", o.optInt("heartbeat_s", 30).coerceAtLeast(5))
        if (o.has("analysis_budget_ms")) editor.putInt("analysis_budget_ms", o.optInt("analysis_budget_ms", 400).coerceIn(50, 5000))
        if (o.has("m8_duty")) editor.putInt("m8_duty", o.optInt("m8_duty", 10).coerceAtLeast(1))
        o.optJSONObject("thermal")?.let { t ->
            if (t.has("warm_c")) editor.putFloat("thermal_warm_c", t.optDouble("warm_c").toFloat())
            if (t.has("hot_c")) editor.putFloat("thermal_hot_c", t.optDouble("hot_c").toFloat())
            if (t.has("critical_c")) editor.putFloat("thermal_critical_c", t.optDouble("critical_c").toFloat())
            if (t.has("hard_pause_c")) editor.putFloat("thermal_hard_pause_c", t.optDouble("hard_pause_c").toFloat())
        }
        if (o.has("low_battery_pct")) editor.putInt("low_battery_pct", o.optInt("low_battery_pct", 20).coerceIn(1, 80))
        if (o.has("critical_battery_pct")) editor.putInt("critical_battery_pct", o.optInt("critical_battery_pct", 10).coerceIn(1, 50))
        o.optJSONObject("thresholds")?.let { t ->
            t.keys().forEach { cls -> editor.putString("threshold_$cls", t.optDouble(cls).toString()) }
        }
        o.optJSONObject("gains")?.let { g ->
            g.keys().forEach { key -> editor.putFloat("gain_$key", g.optDouble(key, 1.0).toFloat().coerceIn(0.25f, 8.0f)) }
        }
        if (o.has("wizard_completed")) editor.putBoolean("wizard_completed", o.optBoolean("wizard_completed"))
        if (o.has("charge_limit_confirmed")) editor.putBoolean("charge_limit_confirmed", o.optBoolean("charge_limit_confirmed"))
        if (o.has("battery_health_inspected")) editor.putBoolean("battery_health_inspected", o.optBoolean("battery_health_inspected"))
        if (o.has("overlay_confirmed")) editor.putBoolean("overlay_confirmed", o.optBoolean("overlay_confirmed"))
        editor.apply()
        if (importedToken.isNotEmpty()) tokenStore.set(importedToken)
        true
    }.getOrDefault(false)

    companion object {
        const val EXPORT_SCHEMA = 1
        const val CONSENT_VERSION = 1

        @Volatile private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context).also { instance = it }
            }
    }
}

private fun Double?.orDefault(def: Double): Double = this ?: def
