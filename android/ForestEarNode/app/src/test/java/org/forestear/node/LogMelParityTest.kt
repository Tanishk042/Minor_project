package org.forestear.node

import org.forestear.node.config.AppConfig
import org.forestear.node.features.FeatureAssets
import org.forestear.node.features.LogMel
import org.forestear.node.util.Binary
import org.forestear.node.util.ClasspathAssetSource
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Kotlin log-mel must match the Python implementation bit-for-bit enough that a
 * TFLite int8 model sees the same input. The exporter writes both the input PCM
 * and the expected 64x98 log-mel; this test recomputes the features on the JVM.
 */
class LogMelParityTest {
    private val source = ClasspathAssetSource()
    private val config = AppConfig.load(source)
    private val assets = FeatureAssets(source, config.features)
    private val logMel = LogMel(assets, config.features)

    @Test
    fun logMelMatchesPythonGoldenVectors() {
        val pipeline = JSONObject(source.readText("golden/pipeline.json"))
        val windows = pipeline.getJSONArray("windows")
        val frames = config.features.frames
        val mels = config.features.nMels
        var worst = 0.0
        for (i in 0 until windows.length()) {
            val w = windows.getJSONObject(i)
            val name = w.getString("name")
            val wav = Binary.floats(source.readBytes("golden/input_$name.f32"))
            val expected = Binary.floats(source.readBytes(w.getString("logmel_file")))
            val actual = logMel.compute(wav)
            assertEquals("$name length", mels * frames, actual.size)
            assertEquals("$name expected length", expected.size, actual.size)
            var maxDiff = 0.0
            for (j in actual.indices) {
                maxDiff = maxOf(maxDiff, abs(actual[j] - expected[j]).toDouble())
            }
            worst = maxOf(worst, maxDiff)
            assertTrue("$name log-mel max diff $maxDiff exceeds 1e-5", maxDiff < 1e-5)
        }
        println("log-mel worst absolute difference across windows: $worst")
    }
}
