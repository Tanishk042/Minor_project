package org.forestear.node

import org.forestear.node.config.AppConfig
import org.forestear.node.model.FusionTrees
import org.forestear.node.util.ClasspathAssetSource
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the asset contract the service depends on at startup. */
class ConfigTest {
    private val source = ClasspathAssetSource()
    private val config = AppConfig.load(source)

    @Test
    fun featureContractMatchesTrainedModels() {
        assertEquals(16000, config.audio.windowSamples)
        assertEquals(8000, config.audio.hopSamples)
        assertEquals(16000, config.audio.sampleRate)
        assertEquals(64, config.features.nMels)
        assertEquals(512, config.features.nFft)
        assertEquals(98, config.features.frames)
        assertEquals(12, config.features.selectedIndices.size)
        assertEquals(47, config.features.m9FeatureOrder.size)
    }

    @Test
    fun allNineModelsAreConfigured() {
        val required = listOf("m1", "m2", "m3", "m4", "m5", "m6", "m7", "m8", "m9")
        for (id in required) {
            assertTrue("model $id missing", config.models.containsKey(id))
            assertTrue("model $id has no file", config.model(id).file.isNotBlank())
        }
    }

    @Test
    fun m9TreeAssetMatchesMeta() {
        val trees = FusionTrees.load(source)
        val meta = JSONObject(source.readText("config/m9_meta.json"))
        val classes = meta.getJSONArray("classes").let { a ->
            (0 until a.length()).map { a.getString(it) }
        }
        assertEquals(classes, trees.classes)
        assertEquals(meta.getInt("n_features"), trees.nFeatures)
        assertEquals(listOf("chainsaw", "gunshot", "vehicle", "human", "benign"), trees.classes)
    }

    @Test
    fun safetyThresholdsAndDutyCyclesArePresent() {
        assertEquals(0.6, config.decision.thresholds["gunshot"] ?: -1.0, 1e-9)
        assertTrue(config.decision.vetoEnable)
        assertTrue(config.decision.vetoGunshotConf >= 0.8)
        assertTrue(config.decision.persistence.containsKey("gunshot"))
        assertTrue(config.decision.persistence.containsKey("vehicle"))
        assertTrue(config.biodiversity.dutyCycle >= 1)
        assertTrue(config.android.collection.chunkS in 10..600)
        assertTrue(config.android.collection.maxBytes > 0)
        assertTrue(config.android.thermal.warmC < config.android.thermal.hotC)
        assertTrue(config.android.thermal.hotC < config.android.thermal.criticalC)
        assertTrue(config.android.thermal.criticalC < config.android.thermal.hardPauseC)
    }

    @Test
    fun decisionThresholdOverridesAreHonoured() {
        val overridden = config.decision.withThresholdOverrides(mapOf("gunshot" to 0.9))
        assertEquals(0.9, overridden.thresholds["gunshot"] ?: -1.0, 1e-9)
        // The original config must not be mutated.
        assertEquals(0.6, config.decision.thresholds["gunshot"] ?: -1.0, 1e-9)
    }
}
