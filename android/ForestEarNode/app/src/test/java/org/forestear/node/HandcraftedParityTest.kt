package org.forestear.node

import org.forestear.node.config.AppConfig
import org.forestear.node.features.FeatureAssets
import org.forestear.node.features.Handcrafted
import org.forestear.node.util.Binary
import org.forestear.node.util.ClasspathAssetSource
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Handcrafted descriptors are the 6 features the M9 fusion model receives
 * alongside model scores. They replicate librosa's quirks (see Handcrafted.kt),
 * so a tiny drift here silently degrades the deployed classifier.
 */
class HandcraftedParityTest {
    private val source = ClasspathAssetSource()
    private val config = AppConfig.load(source)
    private val assets = FeatureAssets(source, config.features)
    private val handcrafted = Handcrafted(assets, config.features)

    @Test
    fun handcraftedMatchesPythonGoldenVectors() {
        val pipeline = JSONObject(source.readText("golden/pipeline.json"))
        val windows = pipeline.getJSONArray("windows")
        var worst = 0.0
        for (i in 0 until windows.length()) {
            val w = windows.getJSONObject(i)
            val name = w.getString("name")
            val wav = Binary.floats(source.readBytes("golden/input_$name.f32"))
            val actual = handcrafted.compute(wav)
            val expected = w.getJSONObject("handcrafted")
            val diffs = mapOf(
                "rms_db" to (actual.rmsDb to expected.getDouble("rms_db")),
                "crest" to (actual.crest to expected.getDouble("crest")),
                "spectral_flux" to (actual.spectralFlux to expected.getDouble("spectral_flux")),
                "spectral_flatness" to
                    (actual.spectralFlatness to expected.getDouble("spectral_flatness")),
                "onset_strength" to
                    (actual.onsetStrength to expected.getDouble("onset_strength")),
                "zcr" to (actual.zcr to expected.getDouble("zcr")),
            )
            for ((key, pair) in diffs) {
                val diff = abs(pair.first - pair.second)
                worst = maxOf(worst, diff)
                assertTrue(
                    "$name/$key Kotlin=${pair.first} Python=${pair.second} diff=$diff",
                    diff < 1e-5,
                )
            }
            assertEquals("$name rms_db", expected.getDouble("rms_db"), actual.rmsDb, 1e-6)
        }
        println("handcrafted worst absolute difference across windows: $worst")
    }
}
