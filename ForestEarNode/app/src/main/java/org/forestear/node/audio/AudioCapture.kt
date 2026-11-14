package org.forestear.node.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import org.forestear.node.settings.SettingsStore
import org.forestear.node.util.FLog
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

data class EffectStatus(
    val unprocessedSource: Boolean,
    val agcAvailable: Boolean,
    val nsAvailable: Boolean,
    val aecAvailable: Boolean,
    val agcDisabled: Boolean,
    val nsDisabled: Boolean,
    val aecDisabled: Boolean,
    val audioSource: String,
) {
    fun summary(): String = buildString {
        append("source=").append(audioSource)
        append(" agc=").append(if (agcAvailable) (if (agcDisabled) "off" else "ON") else "n/a")
        append(" ns=").append(if (nsAvailable) (if (nsDisabled) "off" else "ON") else "n/a")
        append(" aec=").append(if (aecAvailable) (if (aecDisabled) "off" else "ON") else "n/a")
    }
}

/**
 * 16 kHz mono PCM capture with a 1.0 s sliding window and 0.5 s hop.
 *
 * Design notes for the Nord CE 2 Lite:
 *  - UNPROCESSED is preferred when the platform advertises it; otherwise MIC.
 *  - AGC / noise suppression / echo cancellation are explicitly disabled and
 *    their availability is logged, because they change the spectral envelope
 *    the models were trained on.
 *  - The capture thread owns the AudioRecord. All callbacks run on that thread;
 *    the service hands heavy inference to its own executor.
 *  - read errors surface through [Listener.onError]; the service restarts the
 *    capture with backoff (OxygenOS can revoke the mic path when the app is
 *    moved to the background without the right protections).
 */
class AudioCapture(
    context: Context,
    private val settings: SettingsStore,
    private val listener: Listener,
    private val silenceRmsDb: Double = -60.0,
    private val clipRatioThreshold: Double = 0.01,
) : Closeable {

    interface Listener {
        fun onWindow(window: FloatArray, timestampMs: Long, silent: Boolean, clipping: Boolean)
        fun onRawChunk(pcm: ShortArray, timestampMs: Long)
        fun onState(state: State, detail: String)
        fun onError(message: String, fatal: Boolean)
    }

    enum class State { IDLE, STARTING, RUNNING, STOPPING, ERROR }

    private val appContext = context.applicationContext
    private val devices = AudioDeviceManager(appContext)
    private val sampleRate = 16000
    private val windowSamples = 16000
    private val hopSamples = 8000
    private val blockSamples = 1600 // 100 ms

    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var effects: List<Any> = emptyList()

    @Volatile private var activeInputName: String = "none"
    @Volatile private var effectStatus: EffectStatus? = null
    @Volatile private var gain: Float = 1.0f
    @Volatile private var lastRmsDb: Double = -100.0
    @Volatile private var lastPeakDb: Double = -100.0
    @Volatile private var lastClipRatio: Double = 0.0

    fun activeInput(): String = activeInputName
    fun lastEffects(): EffectStatus? = effectStatus
    fun lastLevels(): Triple<Double, Double, Double> = Triple(lastRmsDb, lastPeakDb, lastClipRatio)

    /** Applied to subsequent samples; used by the calibration screen. */
    fun setGain(value: Float) {
        gain = value.coerceIn(0.25f, 8.0f)
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val chosen = devices.choose(settings.inputChoice)
        val external = chosen != null && devices.isExternal(chosen)
        gain = settings.gainFor(external)
        listener.onState(State.STARTING, "opening microphone")
        thread = Thread({ captureLoop() }, "forestear-audio").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        listener.onState(State.STOPPING, "closing microphone")
        runCatching { record?.stop() }
        thread?.join(1500)
        release()
        listener.onState(State.IDLE, "microphone closed")
    }

    override fun close() = stop()

    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var opened: Pair<AudioRecord, EffectStatus>? = null
        var lastError: Throwable? = null
        for (source in candidateSources()) {
            try {
                opened = open(source)
                break
            } catch (e: Throwable) {
                lastError = e
                FLog.w("audio source $source failed: ${e.message}")
            }
        }
        val (rec, status) = opened ?: run {
            running.set(false)
            listener.onState(State.ERROR, "cannot open microphone")
            listener.onError("cannot open microphone: ${lastError?.message}", true)
            return
        }
        record = rec
        effectStatus = status
        FLog.i("audio capture: ${status.summary()}")
        listener.onState(State.RUNNING, status.summary())

        val ring = FloatRing(windowSamples)
        val shortBuf = ShortArray(blockSamples)
        val startEpochMs = System.currentTimeMillis()
        var totalSamples = 0L
        var sinceHop = 0
        var zeroReads = 0

        try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord did not enter RECORDING state")
            }
            activeInputName = describeRouted(rec)
            FLog.i("active input: $activeInputName")

            while (running.get()) {
                val n = rec.read(shortBuf, 0, shortBuf.size, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    listener.onError("AudioRecord.read error $n", true)
                    break
                }
                if (n == 0) {
                    if (++zeroReads > 100) {
                        listener.onError("microphone produced no samples for 10 s", true)
                        break
                    }
                    continue
                }
                zeroReads = 0

                val chunkTs = startEpochMs + totalSamples * 1000L / sampleRate
                listener.onRawChunk(shortBuf.copyOf(n), chunkTs)

                var clip = 0
                for (i in 0 until n) {
                    val raw = shortBuf[i].toInt()
                    if (abs(raw) >= 32000) clip++
                    ring.add((raw / 32768.0f) * gain)
                }
                totalSamples += n
                sinceHop += n
                lastClipRatio = clip.toDouble() / n

                while (sinceHop >= hopSamples) {
                    sinceHop -= hopSamples
                    val window = ring.lastWindow()
                    val windowStart = (totalSamples - windowSamples).coerceAtLeast(0L)
                    val ts = startEpochMs + windowStart * 1000L / sampleRate
                    var sumSq = 0.0
                    var peak = 0.0
                    var clipped = 0
                    for (v in window) {
                        val d = v.toDouble()
                        sumSq += d * d
                        val a = abs(d)
                        if (a > peak) peak = a
                        if (a >= 0.995) clipped++
                    }
                    val rms = sqrt(sumSq / window.size + 1e-20)
                    lastRmsDb = 20.0 * log10(rms + 1e-10)
                    lastPeakDb = 20.0 * log10(peak + 1e-10)
                    val clipRatio = clipped.toDouble() / window.size
                    val silent = lastRmsDb < silenceRmsDb
                    listener.onWindow(window, ts, silent, clipRatio > clipRatioThreshold)
                }
            }
        } catch (e: Throwable) {
            if (running.get()) {
                FLog.e("audio loop failed", e)
                listener.onError("audio loop failed: ${e.message}", true)
            }
        } finally {
            runCatching { rec.stop() }
            release()
            running.set(false)
            listener.onState(State.IDLE, "capture stopped")
        }
    }

    private fun candidateSources(): List<Int> {
        val unprocessed = if (Build.VERSION.SDK_INT >= 24 && devices.unprocessedSupported()) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else null
        return listOfNotNull(unprocessed, MediaRecorder.AudioSource.MIC).distinct()
    }

    private fun open(source: Int): Pair<AudioRecord, EffectStatus> {
        val device = devices.choose(settings.inputChoice)
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBuf > 0) { "getMinBufferSize=$minBuf" }
        val bufferBytes = max(minBuf, blockSamples * 2 * 4)
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val rec = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("AudioRecord not initialized (source=$source)")
        }
        if (device != null) {
            val ok = rec.setPreferredDevice(device)
            FLog.i("preferred input ${device.productName} (id=${device.id}) accepted=$ok")
        } else {
            FLog.w("no matching input device; using platform default")
        }

        // Explicitly disable platform DSP. These change the signal the models
        // were trained on, so we only keep them when they cannot be created.
        val session = rec.audioSessionId
        var agc: AutomaticGainControl? = null
        var ns: NoiseSuppressor? = null
        var aec: AcousticEchoCanceler? = null
        var agcOff = false
        var nsOff = false
        var aecOff = false
        runCatching {
            agc = AutomaticGainControl.create(session)
            agc?.enabled = false
            agcOff = agc?.enabled == false
        }
        runCatching {
            ns = NoiseSuppressor.create(session)
            ns?.enabled = false
            nsOff = ns?.enabled == false
        }
        runCatching {
            aec = AcousticEchoCanceler.create(session)
            aec?.enabled = false
            aecOff = aec?.enabled == false
        }
        effects = listOfNotNull(agc, ns, aec)

        val status = EffectStatus(
            unprocessedSource = source == MediaRecorder.AudioSource.UNPROCESSED,
            agcAvailable = agc != null,
            nsAvailable = ns != null,
            aecAvailable = aec != null,
            agcDisabled = agcOff,
            nsDisabled = nsOff,
            aecDisabled = aecOff,
            audioSource = if (source == MediaRecorder.AudioSource.UNPROCESSED) "UNPROCESSED" else "MIC",
        )
        return rec to status
    }

    private fun describeRouted(rec: AudioRecord): String {
        val routed = runCatching { rec.routedDevice }.getOrNull() ?: return "default"
        val info = devices.describe(routed)
        return "${info.name} (${info.typeLabel}, id=${info.id})"
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

    /** Fixed-size ring; [lastWindow] zero-pads until the first second is filled. */
    private class FloatRing(private val capacity: Int) {
        private val data = FloatArray(capacity)
        private var pos = 0
        private var count = 0

        fun add(v: Float) {
            data[pos] = v
            pos = (pos + 1) % capacity
            if (count < capacity) count++
        }

        fun lastWindow(): FloatArray {
            val out = FloatArray(capacity)
            val missing = capacity - count
            if (count < capacity) {
                // oldest data sits at index 0 when the ring has not wrapped
                System.arraycopy(data, 0, out, missing, count)
            } else {
                val first = capacity - pos
                System.arraycopy(data, pos, out, 0, first)
                System.arraycopy(data, 0, out, first, pos)
            }
            return out
        }
    }
}
