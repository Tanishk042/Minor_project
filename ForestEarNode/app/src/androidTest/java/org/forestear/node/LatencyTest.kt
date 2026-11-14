package org.forestear.node

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.forestear.node.config.AppConfig
import org.forestear.node.features.FeatureAssets
import org.forestear.node.model.ModelZoo
import org.forestear.node.util.AndroidAssetSource
import org.forestear.node.util.Binary
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * On-device latency budget. The Python reference measured 5.56 ms/hop on an M1
 * laptop; that number says nothing about this phone. This test measures the real
 * pipeline and fails only on a pathological regression (1500 ms/hop), not on a
 * specific handset target. Diagnostics → Benchmark records the actual numbers.
 */
@RunWith(AndroidJUnit4::class)
class LatencyTest {

    @Test
    fun fullPipelineStaysWithinTheAnalysisBudget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = AndroidAssetSource(context)
        val config = AppConfig.load(source)
        val assets = FeatureAssets(source, config.features)
        val wav = Binary.floats(source.readBytes("golden/input_ambience.f32"))

        ModelZoo(context, config, assets, loadBirdnet = true).use { zoo ->
            // Warm up: first calls include interpreter initialisation and page-ins.
            repeat(3) { zoo.process(wav, hour = 12.0) }

            val times = mutableListOf<Double>()
            repeat(10) {
                val r = zoo.process(wav, hour = 12.0)
                times += r.inferenceMs["total"] ?: 0.0
            }
            times.sort()
            val mean = times.average()
            val p95 = times[(times.size * 0.95).toInt().coerceIn(0, times.size - 1)]
            println(
                String.format(
                    Locale.US,
                    "on-device pipeline: mean %.1f ms, p95 %.1f ms, min %.1f ms, max %.1f ms",
                    mean, p95, times.first(), times.last(),
                )
            )
            assertTrue("mean ${mean}ms exceeds the 1500ms sanity budget", mean < 1500.0)
            assertTrue("p95 ${p95}ms exceeds the 1500ms sanity budget", p95 < 1500.0)
        }
    }
}
