package org.forestear.node.model

import android.content.Context
import org.forestear.node.config.AppConfig
import org.forestear.node.features.FeatureAssets
import org.forestear.node.features.Handcrafted
import org.forestear.node.features.LogMel
import org.forestear.node.util.FLog
import java.io.Closeable
import kotlin.math.cos
import kotlin.math.sin

data class Species(val species: String, val confidence: Float)

data class WindowResult(
    val m1Scores: Map<String, Float>,
    val m1Embedding: FloatArray,
    val m2: Float,
    val m3: Float,
    val m4: Float,
    val m5: Map<String, Float>,
    val m6Err: Float,
    val m6Z: Float,
    val m7: Float,
    val m9: Map<String, Float>,
    val handcrafted: Handcrafted.Values,
    val logMel: FloatArray,
    val inferenceMs: Map<String, Double>,
    val m8Species: List<Species>?,
    val rmsDb: Double,
) {
    val threatScore: Float
        get() = m9.entries.filter { it.key != "benign" }.maxOfOrNull { it.value } ?: 0f
}

/**
 * The 9-model ensemble on-device. Same model files, input layouts, quantisation,
 * rolling baselines and 47-feature M9 contract as forestear/edge_agent/zoo.py.
 *
 * Models are loaded once and reused for the lifetime of the foreground service.
 * M8 is optional: if BirdNET cannot be loaded the rest of the pipeline continues
 * and biodiversity is reported as unavailable (no fabricated species).
 */
class ModelZoo(
    context: Context,
    private val config: AppConfig,
    private val assets: FeatureAssets,
    delegateFor: (String) -> DelegateKind = { DelegateKind.CPU_XNNPACK },
    private val loadBirdnet: Boolean = true,
    private val enabledModels: Set<String> = ALL_MODELS,
) : Closeable {

    private val logMel = LogMel(assets, config.features)
    private val handcrafted = Handcrafted(assets, config.features)
    private val yamnet = YamnetModel(context, config.model("m1").file, delegateFor("m1"))
    private val m2: TfliteModel? = if (enabled("m2")) {
        TfliteModel.load(context, config.model("m2").file, "m2", delegateFor("m2"))
    } else null
    private val m3: TfliteModel? = if (enabled("m3")) {
        TfliteModel.load(context, config.model("m3").file, "m3", delegateFor("m3"))
    } else null
    private val m4: TfliteModel? = if (enabled("m4")) {
        TfliteModel.load(context, config.model("m4").file, "m4", delegateFor("m4"))
    } else null
    private val m5: TfliteModel? = if (enabled("m5")) {
        TfliteModel.load(context, config.model("m5").file, "m5", delegateFor("m5"))
    } else null
    private val m6: TfliteModel? = if (enabled("m6")) {
        TfliteModel.load(context, config.model("m6").file, "m6", delegateFor("m6"))
    } else null
    private val m7: TfliteModel? = if (enabled("m7")) {
        TfliteModel.load(context, config.model("m7").file, "m7", delegateFor("m7"))
    } else null
    private val m8: TfliteModel? = if (loadBirdnet && enabled("m8")) {
        runCatching {
            TfliteModel.load(context, config.model("m8").file, "m8", delegateFor("m8"))
        }.onFailure { FLog.e("BirdNET unavailable; biodiversity disabled", it) }.getOrNull()
    } else null
    private val m8Labels: List<String> = if (m8 != null) {
        context.assets.open(config.model("m8").labels ?: "models/birdnet_labels.txt")
            .bufferedReader().use { it.readLines() }
    } else emptyList()

    private val fusion = FusionTrees.load(assets.source)

    private val selectedNames = config.features.selectedClasses
    private val selectedIndices = config.features.selectedIndices
    private val m7ExtraNames = config.features.m7ExtraClasses
    private val m7ExtraIndices = config.features.m7ExtraIndices
    private val m5Classes = config.features.m5Classes
    private val m7Norm = assets.norms["m7_human"]
    private val m3Norm = assets.norms["m3_chainsaw"]
    private val m4Norm = assets.norms["m4_vehicle"]

    private val tfInput = FloatArray(logMel.size)
    private val rolling = ArrayDeque<Float>()
    private val history = ArrayDeque<DoubleArray>()

    private var m8Counter = 0
    private var lastM6Z = 0.0

    @Volatile
    var birdnetAvailable: Boolean = m8 != null
        private set

    val m9Classes: List<String> get() = fusion.classes

    fun process(
        wav: FloatArray,
        hour: Double? = null,
        m6Enabled: Boolean = true,
        m8Duty: Int = config.biodiversity.dutyCycle,
    ): WindowResult {
        val totalStart = System.nanoTime()
        val timings = LinkedHashMap<String, Double>()

        var t = System.nanoTime()
        val logMelValues = logMel.compute(wav)
        val hc = handcrafted.compute(wav)
        logMel.toTfInput(logMelValues, tfInput)
        timings["features"] = ms(t)

        t = System.nanoTime()
        val (scores, embedding) = yamnet.run(wav)
        val selected = LinkedHashMap<String, Float>()
        for (i in selectedNames.indices) {
            val idx = selectedIndices[i]
            selected[selectedNames[i]] = if (idx in scores.indices) scores[idx] else 0f
        }
        timings["m1"] = ms(t)

        t = System.nanoTime()
        val m2Value = m2?.run(tfInput)?.get(0) ?: 0f
        timings["m2"] = ms(t)

        t = System.nanoTime()
        val m5Values = m5?.run(tfInput) ?: FloatArray(0)
        val m5Map = LinkedHashMap<String, Float>()
        for (i in m5Classes.indices) {
            m5Map[m5Classes[i]] = if (i < m5Values.size) m5Values[i] else 0f
        }
        timings["m5"] = ms(t)

        t = System.nanoTime()
        val embNorm3 = m3Norm?.apply(embedding) ?: embedding
        val m3Value = m3?.run(embNorm3)?.get(0) ?: 0f
        timings["m3"] = ms(t)

        t = System.nanoTime()
        val embNorm4 = m4Norm?.apply(embedding) ?: embedding
        val m4Value = m4?.run(embNorm4)?.get(0) ?: 0f
        timings["m4"] = ms(t)

        t = System.nanoTime()
        var m6Err = 0f
        var m6Z = lastM6Z.toFloat()
        if (m6Enabled && m6 != null) {
            val rec = m6.run(tfInput)
            var err = 0.0
            for (i in tfInput.indices) {
                val d = (tfInput[i] - rec[i]).toDouble()
                err += d * d
            }
            m6Err = (err / tfInput.size).toFloat()
            rolling.addLast(m6Err)
            while (rolling.size > config.m6.rollingWindow) rolling.removeFirst()
            val med: Double
            val mad: Double
            if (rolling.size >= 10) {
                // Python uses np.median(np.abs(arr - med)): median absolute
                // deviation, not the mean absolute deviation.
                med = median(rolling.map { it.toDouble() })
                val deviations = rolling.map { kotlin.math.abs(it - med) }
                mad = median(deviations) + 1e-9
            } else {
                med = config.m6.baselineMedian
                mad = config.m6.baselineMad
            }
            m6Z = ((m6Err - med) / mad).toFloat()
            lastM6Z = m6Z.toDouble()
        }
        timings["m6"] = ms(t)

        t = System.nanoTime()
        val m7Value = if (m7 != null) {
            val m7Input = FloatArray(1024 + m7ExtraNames.size)
            System.arraycopy(embedding, 0, m7Input, 0, 1024)
            for (i in m7ExtraNames.indices) {
                val idx = m7ExtraIndices[i]
                m7Input[1024 + i] = if (idx in scores.indices) scores[idx] else 0f
            }
            m7.run(m7Norm?.apply(m7Input) ?: m7Input)[0]
        } else 0f
        timings["m7"] = ms(t)

        t = System.nanoTime()
        var species: List<Species>? = null
        if (m8 != null && m8Duty > 0) {
            m8Counter++
            if (m8Counter % m8Duty == 1) {
                species = runBirdnet(wav)
                timings["m8"] = ms(t)
            }
        }

        t = System.nanoTime()
        val m5Max = maxOf(
            m5Map["rain"] ?: 0f,
            m5Map["wind"] ?: 0f,
            m5Map["thunder"] ?: 0f,
        )
        history.addLast(doubleArrayOf(
            m2Value.toDouble(), m3Value.toDouble(), m4Value.toDouble(),
            m5Max.toDouble(), m6Z.toDouble(), m7Value.toDouble(),
        ))
        while (history.size > 5) history.removeFirst()
        val features = buildM9Features(
            selected, m2Value, m3Value, m4Value, m5Map, m6Z, m7Value, hc,
            hour ?: currentUtcHour(),
        )
        val probs = fusion.predict(features)
        val m9Map = LinkedHashMap<String, Float>()
        for (i in fusion.classes.indices) m9Map[fusion.classes[i]] = probs[i].toFloat()
        timings["m9"] = ms(t)
        timings["total"] = (System.nanoTime() - totalStart) / 1e6

        return WindowResult(
            m1Scores = selected,
            m1Embedding = embedding,
            m2 = m2Value,
            m3 = m3Value,
            m4 = m4Value,
            m5 = m5Map,
            m6Err = m6Err,
            m6Z = m6Z,
            m7 = m7Value,
            m9 = m9Map,
            handcrafted = hc,
            logMel = logMelValues,
            inferenceMs = timings,
            m8Species = species,
            rmsDb = hc.rmsDb,
        )
    }

    private fun buildM9Features(
        selected: Map<String, Float>,
        m2: Float,
        m3: Float,
        m4: Float,
        m5: Map<String, Float>,
        m6Z: Float,
        m7: Float,
        hc: Handcrafted.Values,
        hour: Double,
    ): DoubleArray {
        val out = DoubleArray(fusion.nFeatures)
        var i = 0
        for (name in selectedNames) out[i++] = (selected[name] ?: 0f).toDouble()
        out[i++] = m2.toDouble()
        out[i++] = m3.toDouble()
        out[i++] = m4.toDouble()
        for (cls in m5Classes) out[i++] = (m5[cls] ?: 0f).toDouble()
        out[i++] = m6Z.toDouble()
        out[i++] = m7.toDouble()
        out[i++] = hc.rmsDb
        out[i++] = hc.crest
        out[i++] = hc.spectralFlux
        out[i++] = hc.spectralFlatness
        out[i++] = hc.onsetStrength
        out[i++] = hc.zcr

        for (model in 0 until 6) {
            val vals = history.map { it[model] }
            out[i++] = if (vals.isEmpty()) 0.0 else vals.average()
            out[i++] = vals.maxOrNull() ?: 0.0
            out[i++] = if (vals.size < 2) 0.0 else (vals.last() - vals.first()) / (vals.size - 1)
        }
        out[i++] = sin(2.0 * Math.PI * hour / 24.0)
        out[i++] = cos(2.0 * Math.PI * hour / 24.0)
        check(i == fusion.nFeatures) { "M9 feature count $i != ${fusion.nFeatures}" }
        return out
    }

    private fun runBirdnet(wav: FloatArray): List<Species> {
        val model = m8 ?: return emptyList()
        val need = model.input.elements
        val x = FloatArray(need)
        if (wav.isNotEmpty()) {
            var pos = 0
            while (pos < need) {
                val n = minOf(wav.size, need - pos)
                System.arraycopy(wav, 0, x, pos, n)
                pos += n
            }
        }
        val probs = model.run(x)
        val top = probs.indices.sortedByDescending { probs[it] }
            .take(config.biodiversity.topK)
        return top.mapNotNull { idx ->
            if (probs[idx] > 0.03f && idx < m8Labels.size) {
                Species(m8Labels[idx], probs[idx])
            } else null
        }
    }

    private fun median(values: Collection<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2]
        else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    private fun enabled(id: String): Boolean = enabledModels.contains(id)

    /** Models actually loaded on this phone/tier. */
    fun availableModels(): Set<String> = buildSet {
        add("m1")
        if (m2 != null) add("m2")
        if (m3 != null) add("m3")
        if (m4 != null) add("m4")
        if (m5 != null) add("m5")
        if (m6 != null) add("m6")
        if (m7 != null) add("m7")
        if (m8 != null) add("m8")
        add("m9")
    }

    fun currentEnabledModels(): Set<String> = enabledModels.toSet()

    private fun currentUtcHour(): Double {
        val now = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
        return now.hour + now.minute / 60.0
    }

    private fun ms(startNanos: Long): Double = (System.nanoTime() - startNanos) / 1e6

    override fun close() {
        yamnet.close()
        for (m in listOfNotNull(m2, m3, m4, m5, m6, m7)) m.close()
        m8?.close()
    }

    companion object {
        val ALL_MODELS: Set<String> = setOf("m1", "m2", "m3", "m4", "m5", "m6", "m7", "m8", "m9")
    }
}
