package org.forestear.node.config

import org.forestear.node.decision.DecisionConfig
import org.forestear.node.util.AssetSource
import org.json.JSONArray
import org.json.JSONObject

data class AudioConfig(
    val sampleRate: Int,
    val windowSamples: Int,
    val hopSamples: Int,
    val windowS: Double,
    val hopS: Double,
    val silenceRmsDb: Double,
    val clipRatio: Double,
)

data class FeatureConfig(
    val nMels: Int,
    val nFft: Int,
    val winLength: Int,
    val hopLength: Int,
    val fmin: Int,
    val fmax: Int,
    val frames: Int,
    val logOffset: Float,
    val perWindowNorm: Boolean,
    val selectedClasses: List<String>,
    val selectedIndices: List<Int>,
    val m7ExtraClasses: List<String>,
    val m7ExtraIndices: List<Int>,
    val m5Classes: List<String>,
    val m9FeatureOrder: List<String>,
)

data class ModelSpec(
    val file: String,
    val input: String,
    val quant: String,
    val inputSamples: Int,
    val labels: String?,
    val trees: String?,
    val nFeatures: Int,
)

data class M6Config(
    val baselineMedian: Double,
    val baselineMad: Double,
    val rollingWindow: Int,
)

data class BiodiversityConfig(val dutyCycle: Int, val topK: Int)

data class ThermalConfig(
    val warmC: Double,
    val hotC: Double,
    val criticalC: Double,
    val hardPauseC: Double,
    val hysteresisC: Double,
    val warmStride: Int,
    val hotStride: Int,
    val warmM8Duty: Int,
    val hotM8Duty: Int,
    val warmM6Every: Int,
    val hotM6Every: Int,
)

data class LowBatteryConfig(
    val enablePct: Int,
    val criticalPct: Int,
    val enabledStride: Int,
    val criticalStride: Int,
)

data class SmsConfig(val minIntervalS: Long, val maxPerHour: Int)
data class CollectionConfig(val chunkS: Int, val maxBytes: Long)
data class StreamingConfig(val defaultUrl: String)
data class WatchdogConfig(val workMinutes: Long, val alarmMinutes: Long)
data class BenchmarkConfig(val runs: Int, val warmup: Int)

data class AndroidConfig(
    val analysisBudgetMs: Int,
    val analysisHopLevelsS: List<Double>,
    val thermal: ThermalConfig,
    val lowBattery: LowBatteryConfig,
    val sms: SmsConfig,
    val collection: CollectionConfig,
    val streaming: StreamingConfig,
    val watchdog: WatchdogConfig,
    val benchmark: BenchmarkConfig,
)

data class AppConfig(
    val audio: AudioConfig,
    val features: FeatureConfig,
    val models: Map<String, ModelSpec>,
    val m6: M6Config,
    val decision: DecisionConfig,
    val biodiversity: BiodiversityConfig,
    val android: AndroidConfig,
    val raw: JSONObject,
) {
    fun model(name: String): ModelSpec =
        models[name] ?: throw IllegalArgumentException("model $name missing from config")

    companion object {
        fun load(source: AssetSource): AppConfig =
            fromJson(source.readText("config/forestear.json"))

        fun fromJson(text: String): AppConfig {
            val root = JSONObject(text)
            val audioO = root.getJSONObject("audio")
            val audio = AudioConfig(
                sampleRate = audioO.optInt("sample_rate", 16000),
                windowSamples = audioO.optInt("window_samples", 16000),
                hopSamples = audioO.optInt("hop_samples", 8000),
                windowS = audioO.optDouble("window_s", 1.0),
                hopS = audioO.optDouble("hop_s", 0.5),
                silenceRmsDb = audioO.optDouble("silence_rms_db", -60.0),
                clipRatio = audioO.optDouble("clip_ratio", 0.995),
            )

            val f = root.getJSONObject("features")
            val features = FeatureConfig(
                nMels = f.optInt("n_mels", 64),
                nFft = f.optInt("n_fft", 512),
                winLength = f.optInt("win_length", 400),
                hopLength = f.optInt("hop_length", 160),
                fmin = f.optInt("fmin", 50),
                fmax = f.optInt("fmax", 7800),
                frames = f.optInt("frames", 98),
                logOffset = f.optDouble("log_offset", 1e-6).toFloat(),
                perWindowNorm = f.optBoolean("per_window_norm", true),
                selectedClasses = f.getJSONArray("yamnet_selected_classes").strings(),
                selectedIndices = f.getJSONArray("yamnet_selected_indices").ints(),
                m7ExtraClasses = f.getJSONArray("m7_extra_classes").strings(),
                m7ExtraIndices = f.getJSONArray("m7_extra_indices").ints(),
                m5Classes = f.getJSONArray("m5_classes").strings(),
                m9FeatureOrder = f.getJSONArray("m9_feature_order").strings(),
            )

            val modelsO = root.getJSONObject("models")
            val models = mutableMapOf<String, ModelSpec>()
            modelsO.keys().forEach { key ->
                val o = modelsO.getJSONObject(key)
                models[key] = ModelSpec(
                    file = o.optString("file"),
                    input = o.optString("input", ""),
                    quant = o.optString("quant", "float"),
                    inputSamples = o.optInt("input_samples", 0),
                    labels = o.optString("labels", "").ifBlank { null },
                    trees = o.optString("trees", "").ifBlank { null },
                    nFeatures = o.optInt("n_features", 0),
                )
            }

            val m6O = root.getJSONObject("m6")
            val m6 = M6Config(
                baselineMedian = m6O.optDouble("baseline_median", 0.0),
                baselineMad = m6O.optDouble("baseline_mad", 1.0),
                rollingWindow = m6O.optInt("rolling_window", 200),
            )

            val bioO = root.getJSONObject("biodiversity")
            val bio = BiodiversityConfig(
                dutyCycle = bioO.optInt("duty_cycle", 10).coerceAtLeast(1),
                topK = bioO.optInt("top_k", 5),
            )

            val a = root.getJSONObject("android")
            val th = a.getJSONObject("thermal")
            val thermal = ThermalConfig(
                warmC = th.optDouble("warm_c", 40.0),
                hotC = th.optDouble("hot_c", 45.0),
                criticalC = th.optDouble("critical_c", 50.0),
                hardPauseC = th.optDouble("hard_pause_c", 55.0),
                hysteresisC = th.optDouble("hysteresis_c", 3.0),
                warmStride = th.optInt("warm_stride", 2),
                hotStride = th.optInt("hot_stride", 4),
                warmM8Duty = th.optInt("warm_m8_duty", 20),
                hotM8Duty = th.optInt("hot_m8_duty", 0),
                warmM6Every = th.optInt("warm_m6_every", 2),
                hotM6Every = th.optInt("hot_m6_every", 0),
            )
            val lb = a.getJSONObject("low_battery")
            val lowBattery = LowBatteryConfig(
                enablePct = lb.optInt("enable_pct", 20),
                criticalPct = lb.optInt("critical_pct", 10),
                enabledStride = lb.optInt("enabled_stride", 2),
                criticalStride = lb.optInt("critical_stride", 4),
            )
            val smsO = a.getJSONObject("sms")
            val sms = SmsConfig(
                minIntervalS = smsO.optLong("min_interval_s", 300),
                maxPerHour = smsO.optInt("max_per_hour", 6),
            )
            val col = a.getJSONObject("collection")
            val collection = CollectionConfig(
                chunkS = col.optInt("chunk_s", 60),
                maxBytes = col.optLong("max_bytes", 2_000_000_000L),
            )
            val stream = StreamingConfig(
                defaultUrl = a.getJSONObject("streaming")
                    .optString("default_url", "ws://192.168.1.10:8765/stream")
            )
            val wd = a.getJSONObject("watchdog")
            val watchdog = WatchdogConfig(
                workMinutes = wd.optLong("work_minutes", 15),
                alarmMinutes = wd.optLong("alarm_minutes", 5),
            )
            val bm = a.getJSONObject("benchmark")
            val benchmark = BenchmarkConfig(
                runs = bm.optInt("runs", 20),
                warmup = bm.optInt("warmup", 3),
            )
            val android = AndroidConfig(
                analysisBudgetMs = a.optInt("analysis_budget_ms", 400),
                analysisHopLevelsS = a.getJSONArray("analysis_hop_levels_s").doubles(),
                thermal = thermal, lowBattery = lowBattery, sms = sms,
                collection = collection, streaming = stream,
                watchdog = watchdog, benchmark = benchmark,
            )

            return AppConfig(
                audio = audio, features = features, models = models, m6 = m6,
                decision = DecisionConfig.fromJson(root.getJSONObject("decision")),
                biodiversity = bio, android = android, raw = root,
            )
        }
    }
}

private fun JSONArray.strings(): List<String> =
    (0 until length()).map { optString(it) }

private fun JSONArray.ints(): List<Int> =
    (0 until length()).map { optInt(it) }

private fun JSONArray.doubles(): List<Double> =
    (0 until length()).map { optDouble(it) }
