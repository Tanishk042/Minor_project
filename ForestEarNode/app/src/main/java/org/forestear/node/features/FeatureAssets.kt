package org.forestear.node.features

import org.forestear.node.config.FeatureConfig
import org.forestear.node.util.AssetSource
import org.forestear.node.util.Binary
import org.json.JSONObject

data class Norm(val mu: FloatArray, val sd: FloatArray) {
    init {
        require(mu.size == sd.size) { "norm mu/sd length mismatch" }
    }

    fun apply(x: FloatArray, offset: Int = 0): FloatArray {
        require(offset + x.size <= mu.size) { "norm vector too short" }
        val out = FloatArray(x.size)
        for (i in x.indices) out[i] = (x[i] - mu[offset + i]) / sd[offset + i]
        return out
    }
}

/**
 * Static feature artefacts exported by android/tools/export_android_assets.py.
 *
 * The filterbanks and analysis windows are shipped as float32 files so Kotlin
 * and Python use byte-identical numbers. Nothing here is recomputed at runtime.
 */
class FeatureAssets(
    val source: AssetSource,
    val config: FeatureConfig,
) {
    val mel64: FloatArray by lazy {
        checkSize("features/mel64.f32", Binary.floats(source.readBytes("features/mel64.f32")),
            config.nMels * (config.nFft / 2 + 1))
    }

    /** 128-bin mel basis used by librosa's onset strength (fmin=0, fmax=8000). */
    val mel128: FloatArray by lazy {
        checkSize("features/mel128_onset.f32",
            Binary.floats(source.readBytes("features/mel128_onset.f32")), 128 * (config.nFft / 2 + 1))
    }

    /** Hann(400) zero-padded into a 512-point FFT window (log-mel / onset). */
    val window400: FloatArray by lazy {
        checkSize("features/window400.f32",
            Binary.floats(source.readBytes("features/window400.f32")), config.nFft)
    }

    /** Full Hann(512) window used by spectral flux / flatness. */
    val window512: FloatArray by lazy {
        checkSize("features/window512.f32",
            Binary.floats(source.readBytes("features/window512.f32")), config.nFft)
    }

    val norms: Map<String, Norm> by lazy {
        val root = JSONObject(source.readText("config/norms.json"))
        val out = mutableMapOf<String, Norm>()
        root.keys().forEach { key ->
            val o = root.getJSONObject(key)
            out[key] = Norm(
                mu = o.getJSONArray("mu").toFloatArray(),
                sd = o.getJSONArray("sd").toFloatArray(),
            )
        }
        out
    }

    val yamnetClasses: List<String> by lazy {
        val arr = org.json.JSONArray(source.readText("config/yamnet_classes.json"))
        (0 until arr.length()).map { arr.getString(it) }
    }

    private fun checkSize(name: String, values: FloatArray, expected: Int): FloatArray {
        check(values.size == expected) {
            "$name has ${values.size} floats, expected $expected"
        }
        return values
    }
}

internal fun org.json.JSONArray.toFloatArray(): FloatArray =
    FloatArray(length()) { getDouble(it).toFloat() }
