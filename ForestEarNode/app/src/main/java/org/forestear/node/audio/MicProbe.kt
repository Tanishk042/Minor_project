package org.forestear.node.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import org.forestear.node.settings.SettingsStore
import org.forestear.node.util.FLog
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

data class MicLevels(
    val rmsDbfs: Double,
    val peakDbfs: Double,
    val clipRatio: Double,
    val clipping: Boolean,
    val input: String,
    val effects: String,
)

/**
 * Calibration probe: a lightweight 16 kHz capture that reports live dBFS,
 * peak and clipping without running the model pipeline.
 */
class MicProbe(
    context: Context,
    private val settings: SettingsStore,
) : Closeable {

    fun interface Listener {
        fun onLevels(levels: MicLevels)
    }

    private val appContext = context.applicationContext
    private val devices = AudioDeviceManager(appContext)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var effects: List<Any> = emptyList()
    private var inputName: String = "none"
    private var effectSummary: String = ""

    fun start(listener: Listener) {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ loop(listener) }, "forestear-micprobe").also { it.start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { record?.stop() }
        thread?.join(1000)
        release()
    }

    override fun close() = stop()

    private fun loop(listener: Listener) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val source = if (devices.unprocessedSupported()) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.MIC
        }
        val minBuf = AudioRecord.getMinBufferSize(
            16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            FLog.e("MicProbe: getMinBufferSize=$minBuf")
            running.set(false)
            return
        }
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(16000)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val rec = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(format)
                .setBufferSizeInBytes(max(minBuf, 12800))
                .build()
        } catch (e: Throwable) {
            FLog.e("MicProbe open failed", e)
            running.set(false)
            return
        }
        record = rec
        val device = devices.choose(settings.inputChoice)
        if (device != null) rec.setPreferredDevice(device)
        disableEffects(rec.audioSessionId)
        inputName = runCatching { rec.routedDevice?.let { devices.describe(it).name } }
            .getOrNull() ?: "default"
        effectSummary = "AGC/NS/AEC disabled where available"

        val buf = ShortArray(800) // 50 ms
        try {
            rec.startRecording()
            while (running.get()) {
                val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) continue
                var sumSq = 0.0
                var peak = 0
                var clip = 0
                for (i in 0 until n) {
                    val v = buf[i].toInt()
                    val a = abs(v)
                    if (a > peak) peak = a
                    if (a >= 32000) clip++
                    val d = v / 32768.0
                    sumSq += d * d
                }
                val rms = sqrt(sumSq / n + 1e-20)
                val levels = MicLevels(
                    rmsDbfs = 20.0 * log10(rms + 1e-10),
                    peakDbfs = 20.0 * log10(peak / 32768.0 + 1e-10),
                    clipRatio = clip.toDouble() / n,
                    clipping = clip.toDouble() / n > 0.01,
                    input = inputName,
                    effects = effectSummary,
                )
                listener.onLevels(levels)
            }
        } catch (e: Throwable) {
            FLog.e("MicProbe loop failed", e)
        } finally {
            runCatching { rec.stop() }
            release()
            running.set(false)
        }
    }

    private fun disableEffects(session: Int) {
        val list = mutableListOf<Any>()
        runCatching {
            AutomaticGainControl.create(session)?.also { it.enabled = false; list.add(it) }
        }
        runCatching {
            NoiseSuppressor.create(session)?.also { it.enabled = false; list.add(it) }
        }
        runCatching {
            AcousticEchoCanceler.create(session)?.also { it.enabled = false; list.add(it) }
        }
        effects = list
    }

    private fun release() {
        effects.forEach { e ->
            runCatching {
                when (e) {
                    is AutomaticGainControl -> e.release()
                    is NoiseSuppressor -> e.release()
                    is AcousticEchoCanceler -> e.release()
                }
            }
        }
        effects = emptyList()
        runCatching { record?.release() }
        record = null
    }
}
