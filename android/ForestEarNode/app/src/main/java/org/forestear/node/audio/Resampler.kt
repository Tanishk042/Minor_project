package org.forestear.node.audio

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2

/**
 * Streaming linear-interpolation resampler.
 *
 * The models are trained at 16 kHz. Some phones refuse a 16 kHz `AudioRecord`
 * and only expose 44.1/48 kHz, so capture negotiates the nearest supported rate
 * and converts here. Linear interpolation is not a studio resampler, but it is
 * deterministic, allocation-light and good enough for a 64-band log-mel input;
 * the alternative (silently recording at the wrong rate) would break every
 * model.
 *
 * Pure Kotlin so the conversion can be checked on the JVM.
 */
class StreamingResampler(
    val inputRate: Int,
    val outputRate: Int,
) {
    init {
        require(inputRate > 0) { "inputRate must be positive" }
        require(outputRate > 0) { "outputRate must be positive" }
    }

    private val step = inputRate.toDouble() / outputRate.toDouble()
    private var position = 0.0
    private var prevSample = 0f

    val isIdentity: Boolean get() = inputRate == outputRate

    fun reset() {
        position = 0.0
        prevSample = 0f
    }

    /**
     * Convert [count] samples from [input]. Returns a new array whose length is
     * approximately `count * outputRate / inputRate`; the exact length varies by
     * one between blocks, which is normal for a streaming resampler.
     */
    fun process(input: ShortArray, count: Int = input.size): FloatArray {
        if (count <= 0) return FloatArray(0)
        if (isIdentity) {
            val out = FloatArray(count)
            for (i in 0 until count) out[i] = input[i] / 32768.0f
            return out
        }
        val capacity = ceil(count * outputRate.toDouble() / inputRate).toInt() + 2
        val out = FloatArray(capacity)
        var oi = 0
        while (position < count - 1) {
            val idx = floor(position).toInt()
            val frac = (position - idx).toFloat()
            val a = if (idx < 0) prevSample else input[idx] / 32768.0f
            val b = input[(idx + 1).coerceIn(0, count - 1)] / 32768.0f
            out[oi++] = a + (b - a) * frac
            position += step
        }
        position -= count
        prevSample = input[count - 1] / 32768.0f
        return if (oi == out.size) out else out.copyOf(oi)
    }

    companion object {
        /** Convert a whole buffer in one call (tests, offline tools). */
        fun resample(input: ShortArray, inputRate: Int, outputRate: Int): FloatArray =
            StreamingResampler(inputRate, outputRate).process(input, input.size)

        /**
         * Pick the supported rate closest to [requested] on a log scale. Returns
         * [requested] when the device did not enumerate any rates.
         */
        fun nearestSupportedRate(requested: Int, supported: List<Int>): Int {
            val usable = supported.filter { it > 0 }
            if (usable.isEmpty()) return requested
            if (usable.contains(requested)) return requested
            return usable.minByOrNull { abs(log2(it.toDouble() / requested.toDouble())) } ?: requested
        }

        /** Candidate rates in preference order: exact first, then common rates. */
        fun candidateRates(requested: Int, supported: List<Int>): List<Int> {
            val list = mutableListOf(requested)
            list += nearestSupportedRate(requested, supported)
            list += listOf(48000, 44100, 32000, 24000, 22050, 16000, 8000)
            list += supported
            return list.filter { it > 0 }.distinct()
        }
    }
}
