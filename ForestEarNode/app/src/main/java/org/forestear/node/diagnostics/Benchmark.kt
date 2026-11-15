package org.forestear.node.diagnostics

import android.content.Context
import android.os.Debug
import android.os.Process
import org.forestear.node.config.AppConfig
import org.forestear.node.device.DeviceProfileStore
import org.forestear.node.device.TierSelector
import org.forestear.node.features.FeatureAssets
import org.forestear.node.model.DelegateKind
import org.forestear.node.model.FusionTrees
import org.forestear.node.model.ModelZoo
import org.forestear.node.model.TfliteModel
import org.forestear.node.model.YamnetModel
import org.forestear.node.settings.SettingsStore
import org.forestear.node.util.FLog
import org.forestear.node.util.TimeUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.ceil
import kotlin.random.Random

data class ModelBenchmark(
    val model: String,
    val delegate: String,
    val meanMs: Double,
    val p95Ms: Double,
    val runs: Int,
    val ok: Boolean,
    val note: String = "",
)

data class BenchmarkReport(
    val generatedUtc: String,
    val perModel: List<ModelBenchmark>,
    val totalMeanMs: Double,
    val cpuPct: Double,
    val ramMb: Double,
    val chosenDelegates: Map<String, String>,
    val note: String = "",
    val tier: String = "",
    val tierReason: String = "",
)

/**
 * Per-model CPU (XNNPACK) vs GPU benchmark, then a full-pipeline timing run.
 * The faster stable delegate is persisted and used by the service. Results are
 * written to logs/benchmark.json.
 */
class Benchmark(
    private val context: Context,
    private val config: AppConfig,
    private val assets: FeatureAssets,
    private val settings: SettingsStore,
) {
    fun run(): BenchmarkReport {
        val runs = config.android.benchmark.runs.coerceIn(3, 50)
        val warmup = config.android.benchmark.warmup.coerceIn(1, 10)
        val cpuStart = Process.getElapsedCpuTime()
        val wallStart = System.nanoTime()
        val results = mutableListOf<ModelBenchmark>()
        val chosen = mutableMapOf<String, String>()
        val gpuStable = mutableSetOf<String>()

        val specs = listOf(
            "m1" to 15600,
            "m2" to 98 * 64,
            "m3" to 1024,
            "m4" to 1024,
            "m5" to 98 * 64,
            "m6" to 98 * 64,
            "m7" to 1032,
            "m8" to 144000,
        )
        for ((id, size) in specs) {
            val input = FloatArray(size) { Random.nextFloat() * 0.5f }
            val cpu = measure(id, input, DelegateKind.CPU_XNNPACK, warmup, runs)
            var best = cpu
            var bestKind = DelegateKind.CPU_XNNPACK
            val gpu = measure(id, input, DelegateKind.GPU, warmup, runs)
            if (gpu.ok && (!cpu.ok || gpu.meanMs < cpu.meanMs * 0.95)) {
                best = gpu
                bestKind = DelegateKind.GPU
                // "Stable" means no p95 spike larger than 2x the mean; a GPU
                // that is fast on average but periodically stalls is not used.
                if (gpu.p95Ms <= gpu.meanMs * 2.0) gpuStable += id
            }
            chosen[id] = bestKind.name
            results += best.copy(
                model = id,
                note = if (gpu.ok) {
                    String.format(Locale.US, "cpu=%.2f ms gpu=%.2f ms", cpu.meanMs, gpu.meanMs)
                } else {
                    "gpu unavailable: ${gpu.note}"
                }
            )
        }

        // M9 fusion (generated trees) is measured directly.
        val fusion = runCatching { FusionTrees.load(assets.source) }.getOrNull()
        if (fusion != null) {
            val x = DoubleArray(fusion.nFeatures) { Random.nextDouble() }
            val times = mutableListOf<Double>()
            repeat(warmup) { fusion.predict(x) }
            repeat(runs) {
                val t = System.nanoTime()
                fusion.predict(x)
                times += (System.nanoTime() - t) / 1e6
            }
            results += ModelBenchmark(
                model = "m9", delegate = "KOTLIN_TREES",
                meanMs = times.average(), p95Ms = p95(times), runs = runs, ok = true
            )
            chosen["m9"] = "KOTLIN_TREES"
        }

        // Full pipeline: features + YAMNet + all heads + fusion.
        var total = 0.0
        var totalOk = false
        runCatching {
            val zoo = org.forestear.node.model.ModelZoo(
                context, config, assets,
                delegateFor = { id -> DelegateKind.fromName(chosen[id]) },
                loadBirdnet = true,
            )
            try {
                val wav = FloatArray(16000) { Random.nextFloat() * 0.2f - 0.1f }
                repeat(warmup) { zoo.process(wav, hour = 12.0) }
                val times = mutableListOf<Double>()
                repeat(runs) {
                    val t = System.nanoTime()
                    zoo.process(wav, hour = 12.0)
                    times += (System.nanoTime() - t) / 1e6
                }
                total = times.average()
                totalOk = true
            } finally {
                zoo.close()
            }
        }.onFailure { FLog.e("full pipeline benchmark failed", it) }

        val cpuMs = Process.getElapsedCpuTime() - cpuStart
        val wallMs = (System.nanoTime() - wallStart) / 1e6
        val cpuPct = if (wallMs > 0) (cpuMs / wallMs * 100.0) else 0.0
        val ramMb = currentRamMb()

        val totalMean = if (totalOk) total else results.sumOf { it.meanMs }
        val profile = runCatching { DeviceProfileStore(context).current() }.getOrNull()
        val plan = if (profile != null) {
            TierSelector.select(
                measuredPipelineMs = totalMean,
                budgetMs = settings.analysisBudgetMs.toDouble(),
                availableModels = ModelZoo.ALL_MODELS,
                gpuModels = gpuStable,
                cores = profile.build.cores,
                ramTotalMb = profile.build.ramTotalMb,
                manualTier = settings.tierOverride,
            )
        } else null
        if (plan != null && profile != null) {
            settings.setTierPlan(plan, profile.profileKey())
        }
        settings.gpuStableModels = gpuStable
        settings.lastBenchmarkTotalMs = totalMean

        val report = BenchmarkReport(
            generatedUtc = TimeUtils.isoUtc(),
            perModel = results,
            totalMeanMs = totalMean,
            cpuPct = cpuPct,
            ramMb = ramMb,
            chosenDelegates = chosen,
            note = "GPU used only when it was both available and >5% faster and stable; " +
                "numbers are on-device and will vary with thermal state.",
            tier = plan?.tier?.name ?: "unset",
            tierReason = plan?.reason ?: "device profile unavailable",
        )
        settings.bestDelegates = JSONObject(chosen as Map<*, *>).toString()
        write(report)
        return report
    }

    private fun measure(
        id: String,
        input: FloatArray,
        kind: DelegateKind,
        warmup: Int,
        runs: Int,
    ): ModelBenchmark {
        val path = config.model(id).file
        var model: TfliteModel? = null
        var yamnet: YamnetModel? = null
        return try {
            val times = mutableListOf<Double>()
            if (id == "m1") {
                yamnet = YamnetModel(context, path, kind)
                repeat(warmup) { yamnet.run(input) }
                repeat(runs) {
                    val t = System.nanoTime()
                    yamnet.run(input)
                    times += (System.nanoTime() - t) / 1e6
                }
            } else {
                model = TfliteModel.load(context, path, id, kind)
                val expected = model.input.elements
                val x = if (input.size == expected) input else FloatArray(expected) { 0.1f }
                repeat(warmup) { model.run(x) }
                repeat(runs) {
                    val t = System.nanoTime()
                    model.run(x)
                    times += (System.nanoTime() - t) / 1e6
                }
            }
            ModelBenchmark(
                model = id, delegate = kind.name,
                meanMs = times.average(), p95Ms = p95(times), runs = runs, ok = true
            )
        } catch (e: Throwable) {
            ModelBenchmark(
                model = id, delegate = kind.name, meanMs = Double.POSITIVE_INFINITY,
                p95Ms = Double.POSITIVE_INFINITY, runs = 0, ok = false,
                note = e.message ?: e.javaClass.simpleName
            )
        } finally {
            model?.close()
            yamnet?.close()
        }
    }

    private fun p95(values: List<Double>): Double {
        if (values.isEmpty()) return Double.POSITIVE_INFINITY
        val sorted = values.sorted()
        val idx = (ceil(sorted.size * 0.95).toInt() - 1).coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    private fun currentRamMb(): Double {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss / 1024.0
    }

    private fun write(report: BenchmarkReport) {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
        dir.mkdirs()
        val o = JSONObject()
            .put("generated_utc", report.generatedUtc)
            .put("total_mean_ms", report.totalMeanMs)
            .put("cpu_pct", report.cpuPct)
            .put("ram_mb", report.ramMb)
            .put("note", report.note)
            .put("tier", report.tier)
            .put("tier_reason", report.tierReason)
            .put("chosen_delegates", JSONObject(report.chosenDelegates as Map<*, *>))
        val arr = JSONArray()
        report.perModel.forEach { m ->
            arr.put(
                JSONObject()
                    .put("model", m.model)
                    .put("delegate", m.delegate)
                    .put("mean_ms", m.meanMs)
                    .put("p95_ms", m.p95Ms)
                    .put("runs", m.runs)
                    .put("ok", m.ok)
                    .put("note", m.note)
            )
        }
        o.put("per_model", arr)
        runCatching { File(dir, "benchmark.json").writeText(o.toString(2)) }
            .onFailure { FLog.w("benchmark write failed: ${it.message}") }
    }
}
