package org.forestear.node.features

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Handcrafted descriptors, ported from forestear/ml/features.py.
 *
 * Librosa quirks replicated deliberately (verified numerically against the
 * Python implementation to < 1e-8 except flatness ~1e-9):
 *
 *  - spectral flux / flatness: centre=False STFT, full Hann(512), 97 frames.
 *  - onset strength: librosa's onset_strength does NOT forward `center` to
 *    melspectrogram, so it actually uses centre=True (101 frames), full
 *    Hann(512), 128 mels with fmin=0/fmax=8000, power_to_db(top_db=80),
 *    positive first difference, then mean over all 101 frames.
 *  - ZCR: 98 frames of 400 samples, denominator 400 per frame (not 399).
 */
class Handcrafted(
    private val assets: FeatureAssets,
    private val config: org.forestear.node.config.FeatureConfig,
) {
    data class Values(
        val rmsDb: Double,
        val crest: Double,
        val spectralFlux: Double,
        val spectralFlatness: Double,
        val onsetStrength: Double,
        val zcr: Double,
    )

    private val nFft = config.nFft
    private val hop = config.hopLength
    private val bins = nFft / 2 + 1
    private val fft = Fft(nFft)
    private val re = DoubleArray(nFft)
    private val im = DoubleArray(nFft)
    private val magnitude = DoubleArray(bins)
    private val previous = DoubleArray(bins)
    private val mel128 = DoubleArray(128)

    fun compute(wav: FloatArray): Values {
        val n = wav.size
        if (n == 0) return Values(-100.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        val x = DoubleArray(n) { wav[it].toDouble() }
        var sumSq = 0.0
        var peak = 0.0
        for (v in x) {
            sumSq += v * v
            val a = abs(v)
            if (a > peak) peak = a
        }
        val rms = sqrt(sumSq / n + 1e-20)
        val rmsDb = 20.0 * log10(rms + 1e-10)
        val crest = (peak + 1e-10) / (rms + 1e-10)

        // ---- flux + flatness (centre=False, full Hann 512) -----------------
        val win512 = assets.window512
        val nFrames = if (n >= nFft) 1 + (n - nFft) / hop else 0
        var flux = 0.0
        var flatnessSum = 0.0
        var fluxPairs = 0
        for (f in 0 until nFrames) {
            val start = f * hop
            for (i in 0 until nFft) {
                re[i] = x[start + i] * win512[i]
                im[i] = 0.0
            }
            fft.transform(re, im)
            var logMean = 0.0
            var powerMean = 0.0
            for (b in 0 until bins) {
                val p = re[b] * re[b] + im[b] * im[b]
                val mag = sqrt(p)
                magnitude[b] = mag
                if (f > 0) {
                    val d = mag - previous[b]
                    if (d > 0.0) flux += d
                }
                logMean += ln(max(1e-10, p))
                powerMean += max(1e-10, p)
            }
            if (f > 0) fluxPairs += bins
            flatnessSum += exp(logMean / bins) / (powerMean / bins)
            System.arraycopy(magnitude, 0, previous, 0, bins)
        }
        val fluxMean = if (fluxPairs > 0) flux / fluxPairs else 0.0
        val flatness = if (nFrames > 0) flatnessSum / nFrames else 0.0

        // ---- onset strength (centre=True, 128 mels, fmin=0) ----------------
        val onsetFrames = n / hop + 1
        val onsetDb = Array(128) { DoubleArray(onsetFrames) }
        val winOnset = win512
        val fb = assets.mel128
        for (f in 0 until onsetFrames) {
            val start = f * hop - nFft / 2
            for (i in 0 until nFft) {
                val idx = start + i
                val sample = if (idx in 0 until n) x[idx] else 0.0
                re[i] = sample * winOnset[i]
                im[i] = 0.0
            }
            fft.transform(re, im)
            for (m in 0 until 128) {
                var acc = 0.0
                val off = m * bins
                for (b in 0 until bins) {
                    val p = re[b] * re[b] + im[b] * im[b]
                    acc += p * fb[off + b]
                }
                mel128[m] = acc
            }
            for (m in 0 until 128) onsetDb[m][f] = 10.0 * log10(max(1e-10, mel128[m]))
        }
        var dbMax = -Double.MAX_VALUE
        for (m in 0 until 128) for (f in 0 until onsetFrames) {
            if (onsetDb[m][f] > dbMax) dbMax = onsetDb[m][f]
        }
        val floor = dbMax - 80.0
        var onsetSum = 0.0
        for (m in 0 until 128) {
            val row = onsetDb[m]
            for (f in 0 until onsetFrames) if (row[f] < floor) row[f] = floor
            for (f in 1 until onsetFrames) {
                val d = row[f] - row[f - 1]
                if (d > 0.0) onsetSum += d
            }
        }
        // 101 env values: first is zero, then 100 positive-diff means.
        val onset = onsetSum / (128.0 * onsetFrames)

        // ---- zero-crossing rate (98 frames of 400, denominator 400) --------
        val zcrFrame = 400
        val zcrFrames = if (n >= zcrFrame) 1 + (n - zcrFrame) / hop else 0
        var crossings = 0
        for (f in 0 until zcrFrames) {
            val start = f * hop
            var prevPositive = thresholdedSign(x[start])
            for (i in 1 until zcrFrame) {
                val positive = thresholdedSign(x[start + i])
                if (positive != prevPositive) crossings++
                prevPositive = positive
            }
        }
        val zcr = if (zcrFrames > 0) crossings.toDouble() / (zcrFrame.toDouble() * zcrFrames) else 0.0

        return Values(
            rmsDb = rmsDb,
            crest = crest,
            spectralFlux = fluxMean,
            spectralFlatness = flatness,
            onsetStrength = onset,
            zcr = zcr,
        )
    }

    private fun thresholdedSign(v: Double): Boolean {
        if (abs(v) <= 1e-10) return true // librosa clips to zero, zero is positive
        return v > 0.0
    }
}
