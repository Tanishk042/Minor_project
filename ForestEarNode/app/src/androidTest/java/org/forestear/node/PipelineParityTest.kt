package org.forestear.node

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.forestear.node.config.AppConfig
import org.forestear.node.features.FeatureAssets
import org.forestear.node.model.ModelZoo
import org.forestear.node.util.AndroidAssetSource
import org.forestear.node.util.Binary
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * End-to-end parity on a real device/emulator: the nine exported TFLite models
 * plus the Kotlin feature code must reproduce the Python edge path's scores.
 *
 * This test needs the 63 MB of assets, so it is instrumented, not a JVM test.
 */
@RunWith(AndroidJUnit4::class)
class PipelineParityTest {

    @Test
    fun nineModelPipelineMatchesPythonGoldenVectors() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = AndroidAssetSource(context)
        val config = AppConfig.load(source)
        val assets = FeatureAssets(source, config.features)
        val zoo = ModelZoo(context, config, assets, loadBirdnet = false)
        zoo.use {
            val pipeline = JSONObject(source.readText("golden/pipeline.json"))
            val windows = pipeline.getJSONArray("windows")
            for (i in 0 until windows.length()) {
                val w = windows.getJSONObject(i)
                val name = w.getString("name")
                val wav = Binary.floats(source.readBytes("golden/input_$name.f32"))
                val r = zoo.process(wav, hour = pipeline.getDouble("hour"))

                assertEquals("$name m2", w.getDouble("m2"), r.m2.toDouble(), 5e-3)
                assertEquals("$name m3", w.getDouble("m3"), r.m3.toDouble(), 5e-3)
                assertEquals("$name m4", w.getDouble("m4"), r.m4.toDouble(), 5e-3)
                assertEquals("$name m7", w.getDouble("m7"), r.m7.toDouble(), 5e-3)
                assertEquals("$name m6_err", w.getDouble("m6_err"), r.m6Err.toDouble(), 5e-3)
                assertEquals("$name m6_z", w.getDouble("m6_z"), r.m6Z.toDouble(), 5e-2)
                assertEquals("$name rms_db", w.getDouble("rms_db"), r.rmsDb, 1e-3)

                val m5 = w.getJSONObject("m5")
                for (key in m5.keys()) {
                    assertEquals(
                        "$name m5/$key",
                        m5.getDouble(key),
                        (r.m5[key] ?: -1f).toDouble(),
                        5e-3,
                    )
                }
                val m9 = w.getJSONObject("m9_probs")
                for (key in m9.keys()) {
                    val diff = abs(m9.getDouble(key) - (r.m9[key] ?: -1f).toDouble())
                    assertTrue("$name m9/$key diff=$diff", diff < 5e-3)
                }
                val sum = r.m9.values.sum()
                assertEquals("$name m9 sums to 1", 1.0, sum.toDouble(), 1e-4)
                assertTrue("$name total latency measured", (r.inferenceMs["total"] ?: 0.0) > 0.0)
            }
        }
    }
}
