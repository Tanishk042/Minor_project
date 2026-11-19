package org.forestear.node

import org.forestear.node.model.FusionTrees
import org.forestear.node.util.ClasspathAssetSource
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The deployed Android fusion path is a generated tree ensemble exported as a
 * compact little-endian binary, not a TFLite MLP. This test is the contract that
 * the Kotlin traversal reproduces Python `predict_proba` on 64 scripted inputs.
 */
class FusionTreesParityTest {
    private val source = ClasspathAssetSource()

    @Test
    fun treeEnsembleMatchesPythonPredictProba() {
        val trees = FusionTrees.load(source)
        val meta = JSONObject(source.readText("config/m9_meta.json"))
        val classes = meta.getJSONArray("classes").let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }
        assertEquals(47, trees.nFeatures)
        assertEquals(classes.size, trees.classes.size)

        val vectors = JSONObject(source.readText("golden/m9_vectors.json"))
            .getJSONArray("vectors")
        var worst = 0.0
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val x = v.getJSONArray("x")
            val p = v.getJSONArray("p")
            val features = DoubleArray(x.length()) { x.getDouble(it) }
            val actual = trees.predict(features)
            assertEquals("vector $i output size", p.length(), actual.size)
            var sum = 0.0
            for (c in actual.indices) {
                val diff = abs(actual[c] - p.getDouble(c))
                worst = maxOf(worst, diff)
                assertTrue(
                    "vector $i class ${classes[c]} Kotlin=${actual[c]} Python=${p.getDouble(c)}",
                    diff < 1e-6,
                )
                sum += actual[c]
            }
            assertEquals("vector $i probabilities sum to 1", 1.0, sum, 1e-6)
        }
        println("M9 tree worst absolute probability difference: $worst")
    }
}
