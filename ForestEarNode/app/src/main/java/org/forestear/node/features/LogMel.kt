package org.forestear.node.features

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Log-mel 64 x 98 @ 16 kHz, exactly matching forestear/ml/features.py:
 *
 *   pad/truncate the waveform to 16000 + 160 samples (16160),
 *   98 frames of 512 samples at hop 160, Hann(400) centred in the 512 window,
 *   power spectrum, mel(64, fmin=50, fmax=7800), log(x + 1e-6),
 *   zero-mean/unit-variance over the whole 64 x 98 window.
 *
 * Output layout is mel-major: out[mel * frames + frame], matching numpy's
 * (n_mels, frames) C order. The parity test compares this against the exported
 * Python log-mel within 1e-3.
 */
class LogMel(
    private val assets: FeatureAssets,
    private val config: org.forestear.node.config.FeatureConfig,
) {
    private val nFft = config.nFft
    private val nMels = config.nMels
    private val frames = config.frames
    private val hop = config.hopLength
    private val windowSamples = 16000
    private val bins = nFft / 2 + 1

    private val fft = Fft(nFft)
    private val re = DoubleArray(nFft)
    private val im = DoubleArray(nFft)
    private val power = DoubleArray(bins)
    private val mel = DoubleArray(nMels)
    private val padded = FloatArray(windowSamples + hop)

    /** Number of output floats (nMels * frames). */
    val size: Int get() = nMels * frames

    fun compute(wav: FloatArray): FloatArray {
        val n = if (wav.size < padded.size) wav.size else padded.size
        java.util.Arrays.fill(padded, 0f)
        System.arraycopy(wav, 0, padded, 0, n)

        val out = FloatArray(nMels * frames)
        val win = assets.window400
        val fb = assets.mel64
        val logOffset = config.logOffset.toDouble()

        for (f in 0 until frames) {
            val start = f * hop
            for (i in 0 until nFft) {
                re[i] = (padded[start + i] * win[i]).toDouble()
                im[i] = 0.0
            }
            fft.transform(re, im)
            for (b in 0 until bins) {
                power[b] = re[b] * re[b] + im[b] * im[b]
            }
            for (m in 0 until nMels) {
                var acc = 0.0
                val off = m * bins
                for (b in 0 until bins) acc += power[b] * fb[off + b]
                mel[m] = acc
            }
            for (m in 0 until nMels) {
                out[m * frames + f] = ln(mel[m] + logOffset).toFloat()
            }
        }

        // per-window normalisation (population mean/std, like numpy)
        var sum = 0.0
        for (v in out) sum += v
        val mean = sum / out.size
        var varSum = 0.0
        for (v in out) {
            val d = v - mean
            varSum += d * d
        }
        val sd = sqrt(varSum / out.size) + 1e-6
        for (i in out.indices) out[i] = ((out[i] - mean) / sd).toFloat()
        return out
    }

    /** (nMels, frames) -> (frames, nMels, 1) flat float array for TFLite. */
    fun toTfInput(logMel: FloatArray, out: FloatArray = FloatArray(size)): FloatArray {
        for (f in 0 until frames) {
            for (m in 0 until nMels) {
                out[f * nMels + m] = logMel[m * frames + f]
            }
        }
        return out
    }
}
