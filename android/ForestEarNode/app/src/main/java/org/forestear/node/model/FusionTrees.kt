package org.forestear.node.model

import org.forestear.node.util.AssetSource
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * Kotlin evaluator for the deployed M9 LightGBM fusion model.
 *
 * android/tools/export_android_assets.py flattens all 1500 trees into
 * config/m9_trees.bin (little-endian) and writes the class order plus the
 * per-class isotonic curves to config/m9_meta.json. The exporter validates this
 * exact evaluation order against native LightGBM `predict_proba` (max |diff| 0).
 */
class FusionTrees private constructor(
    val classes: List<String>,
    val nFeatures: Int,
    private val trees: Array<Tree>,
    private val curves: Map<String, Isotonic>,
) {
    private class Tree(
        val classIndex: Int,
        val feature: IntArray,
        val left: IntArray,
        val right: IntArray,
        val threshold: DoubleArray,
        val value: DoubleArray,
    )

    private class Isotonic(val x: DoubleArray, val y: DoubleArray) {
        fun predict(v: Double): Double {
            if (x.isEmpty()) return v
            if (v <= x[0]) return y[0]
            if (v >= x[x.size - 1]) return y[y.size - 1]
            var lo = 0
            var hi = x.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (x[mid] <= v) lo = mid else hi = mid
            }
            val span = x[hi] - x[lo]
            if (span <= 0.0) return y[lo]
            val t = (v - x[lo]) / span
            return y[lo] + t * (y[hi] - y[lo])
        }
    }

    fun predict(features: DoubleArray): DoubleArray {
        require(features.size == nFeatures) {
            "M9 expects $nFeatures features, got ${features.size}"
        }
        val raw = DoubleArray(classes.size)
        for (tree in trees) {
            var node = 0
            while (tree.left[node] >= 0) {
                node = if (features[tree.feature[node]] <= tree.threshold[node]) {
                    tree.left[node]
                } else {
                    tree.right[node]
                }
            }
            raw[tree.classIndex] += tree.value[node]
        }
        var max = raw[0]
        for (v in raw) if (v > max) max = v
        var sum = 0.0
        for (i in raw.indices) {
            raw[i] = exp(raw[i] - max)
            sum += raw[i]
        }
        if (sum > 0.0) for (i in raw.indices) raw[i] /= sum
        for (i in raw.indices) {
            val curve = curves[classes[i]]
            if (curve != null) raw[i] = curve.predict(raw[i]).coerceIn(0.0, 1.0)
        }
        val total = raw.sum()
        if (total > 0.0) for (i in raw.indices) raw[i] /= total
        return raw
    }

    companion object {
        private const val MAGIC = 0x394D4546 // "FEM9" little-endian

        fun load(source: AssetSource): FusionTrees {
            val meta = JSONObject(source.readText("config/m9_meta.json"))
            val classes = mutableListOf<String>()
            val classArr = meta.getJSONArray("classes")
            for (i in 0 until classArr.length()) classes.add(classArr.getString(i))
            val nFeatures = meta.getInt("n_features")

            val curves = mutableMapOf<String, Isotonic>()
            meta.optJSONObject("iso")?.let { iso ->
                iso.keys().forEach { key ->
                    val o = iso.getJSONObject(key)
                    val xs = o.getJSONArray("x")
                    val ys = o.getJSONArray("y")
                    curves[key] = Isotonic(
                        DoubleArray(xs.length()) { xs.getDouble(it) },
                        DoubleArray(ys.length()) { ys.getDouble(it) },
                    )
                }
            }

            val buf = ByteBuffer.wrap(source.readBytes("config/m9_trees.bin"))
                .order(ByteOrder.LITTLE_ENDIAN)
            require(buf.int == MAGIC) { "bad M9 tree magic" }
            val version = buf.int
            require(version == 1) { "unsupported M9 tree version $version" }
            val fileFeatures = buf.int
            val fileClasses = buf.int
            val nTrees = buf.int
            require(fileFeatures == nFeatures) {
                "M9 feature mismatch: meta=$nFeatures file=$fileFeatures"
            }
            require(fileClasses == classes.size) {
                "M9 class mismatch: meta=${classes.size} file=$fileClasses"
            }

            val trees = Array(nTrees) {
                val classIndex = buf.int
                val nNodes = buf.int
                val feature = IntArray(nNodes)
                val left = IntArray(nNodes)
                val right = IntArray(nNodes)
                val threshold = DoubleArray(nNodes)
                val value = DoubleArray(nNodes)
                for (i in 0 until nNodes) {
                    feature[i] = buf.int
                    left[i] = buf.int
                    right[i] = buf.int
                    threshold[i] = buf.double
                    value[i] = buf.double
                }
                Tree(classIndex, feature, left, right, threshold, value)
            }
            return FusionTrees(classes, nFeatures, trees, curves)
        }
    }
}
