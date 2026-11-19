package org.forestear.node.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import org.forestear.node.settings.SettingsStore
import org.forestear.node.util.FLog
import java.io.Closeable
import java.util.concurrent.Executor
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
    val captureRate: Int = 16000,
    val resampled: Boolean = false,
) {
    fun summary(): String = buildString {
        append("source=").append(audioSource)
        append(" rate=").append(captureRate)
        if (resampled) append("->16000")
        append(" agc=").append(if (agcAvailable) (if (agcDisabled) "off" else "ON") else "n/a")
        append(" ns=").append(if (nsAvailable) (if (nsDisabled) "off" else "ON") else "n/a")
        append(" aec=").append(if (aecAvailable) (if (aecDisabled) "off" else "ON") else "n/a")
    }
}

/**
 * 16 kHz mono PCM capture with a 1.0 s sliding window and 0.5 s hop.
 *
 * Device-agnostic behaviour:
 *  - requests 16 kHz; if the phone refuses it, negotiates the nearest supported
 *    rate and resamples to 16 kHz (linear interpolation, see [StreamingResampler]);
 *  - prefers UNPROCESSED and falls back to MIC per rate, not globally;
 *  - disables AGC/NS/AEC where they can be created and logs what is actually on;
 *  - registers `AudioRecordingCallback` on API 29+ to notice route changes;
 *  - restarts capture on read errors, a 10 s all-zero signal, or a stalled read;
 *  - reports the gap length so the service can log it and mark the node degraded.
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

        /** Audio was not delivered for [gapMs]; the service logs the hole. */
        fun onGap(gapMs: Long, reason: String) {}

        /** The input produced digital silence for [durationMs]. */
        fun onZeroSignal(durationMs: Long) {}

        /** The routed input changed (headset plugged, USB attached, etc.). */
        fun onRouteChanged(input: String) {}
    }

    enum class State { IDLE, STARTING, RUNNING, STOPPING, ERROR }

    private val appContext = context.applicationContext
    private val devices = AudioDeviceManager(appContext)
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val targetRate = 16000
    private val windowSamples = 16000
    private val hopSamples = 8000

    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var effects: List<Any> = emptyList()
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null
    private val callbackExecutor = Executor { command -> command.run() }

    @Volatile private var activeInputName: String = "none"
    @Volatile private var effectStatus: EffectStatus? = null
    @Volatile private var gain: Float = 1.0f
    @Volatile private var lastRmsDb: Double = -100.0
    @Volatile private var lastPeakDb: Double = -100.0
    @Volatile private var lastClipRatio: Double = 0.0
    @Volatile private var captureRate: Int = 16000
    @Volatile private var resampled: Boolean = false

    fun activeInput(): String = activeInputName
    fun lastEffects(): EffectStatus? = effectStatus
    fun lastLevels(): Triple<Double, Double, Double> = Triple(lastRmsDb, lastPeakDb, lastClipRatio)
    fun captureSampleRate(): Int = captureRate
    fun isResampling(): Boolean = resampled

    /** Applied to subsequent samples; used by the calibration screen. */
    fun setGain(value: Float) {
        gain = value.coerceIn(0.25f, 8.0f)
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val chosen = devices.choose(settings.inputChoice)
        gain = settings.gainFor(devices.inputKey(chosen))
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

    private data class Negotiated(
        val record: AudioRecord,
        val status: EffectStatus,
        val device: android.media.AudioDeviceInfo?,
    )

    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val negotiated = negotiate()
        if (negotiated == null) {
            running.set(false)
            listener.onState(State.ERROR, "cannot open microphone")
            listener.onError("cannot open microphone at any supported rate", true)
            return
        }
        val rec = negotiated.record
        record = rec
        effectStatus = negotiated.status
        captureRate = negotiated.status.captureRate
        resampled = negotiated.status.resampled
        FLog.i("audio capture: ${negotiated.status.summary()}")
        listener.onState(State.RUNNING, negotiated.status.summary())

        val resampler = StreamingResampler(captureRate, targetRate)
        val blockSamples = max(160, captureRate / 10) // 100 ms
        val ring = FloatRing(windowSamples)
        val shortBuf = ShortArray(blockSamples)
        val startEpochMs = System.currentTimeMillis()
        var totalOut = 0L
        var sinceHop = 0
        var zeroReads = 0
        var zeroRunSamples = 0L
        var lastReadWallMs = startEpochMs
        val zeroSignalLimit = captureRate.toLong() * 10L

        try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord did not enter RECORDING state")
            }
            activeInputName = describeRouted(rec)
            FLog.i("active input: $activeInputName")
            registerCallback(rec)

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

                val nowWall = System.currentTimeMillis()
                val gap = nowWall - lastReadWallMs
                lastReadWallMs = nowWall
                if (gap > max(2_000L, (hopSamples * 1000L / targetRate) * 4)) {
                    listener.onGap(gap, "audio read stalled")
                }

                var nonZero = false
                var clip = 0
                for (i in 0 until n) {
                    val raw = shortBuf[i].toInt()
                    if (abs(raw) > 1) nonZero = true
                    if (abs(raw) >= 32000) clip++
                }
                if (nonZero) zeroRunSamples = 0 else zeroRunSamples += n
                if (zeroRunSamples >= zeroSignalLimit) {
                    listener.onZeroSignal(10_000)
                    listener.onError("input produced 10 s of digital silence", true)
                    break
                }

                val out = if (resampler.isIdentity) {
                    FloatArray(n) { shortBuf[it] / 32768.0f }
                } else {
                    resampler.process(shortBuf, n)
                }
                if (out.isEmpty()) continue

                val pcm = ShortArray(out.size) {
                    (out[it] * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
                }
                val chunkTs = startEpochMs + totalOut * 1000L / targetRate
                listener.onRawChunk(pcm, chunkTs)

                for (v in out) ring.add(v * gain)
                totalOut += out.size
                sinceHop += out.size
                lastClipRatio = clip.toDouble() / n

                while (sinceHop >= hopSamples) {
                    sinceHop -= hopSamples
                    val window = ring.lastWindow()
                    val windowStart = (totalOut - windowSamples).coerceAtLeast(0L)
                    val ts = startEpochMs + windowStart * 1000L / targetRate
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
            unregisterCallback(rec)
            runCatching { rec.stop() }
            release()
            running.set(false)
            listener.onState(State.IDLE, "capture stopped")
        }
    }

    /**
     * Try the target rate first, then the native rate and every rate the chosen
     * input advertises, then common rates. At each rate, UNPROCESSED is tried
     * before MIC. A failure at one rate never poisons the next attempt.
     */
    private fun negotiate(): Negotiated? {
        val chosen = devices.choose(settings.inputChoice)
        val advertised = chosen?.sampleRates?.toList() ?: emptyList()
        val native = runCatching {
            audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        }.getOrNull()
        val rates = StreamingResampler.candidateRates(
            targetRate,
            advertised + listOfNotNull(native),
        )
        var lastError: Throwable? = null
        for (rate in rates) {
            for (source in candidateSources()) {
                try {
                    val opened = open(source, rate, chosen)
                    return Negotiated(opened.first, opened.second, chosen)
                } catch (e: Throwable) {
                    lastError = e
                    FLog.w("audio open failed at ${rate}Hz source=$source: ${e.message}")
                }
            }
        }
        if (lastError != null) FLog.e("no working audio configuration", lastError)
        return null
    }

    private fun candidateSources(): List<Int> {
        val unprocessed = if (Build.VERSION.SDK_INT >= 24 && devices.unprocessedSupported()) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else null
        return listOfNotNull(unprocessed, MediaRecorder.AudioSource.MIC).distinct()
    }

    private fun open(
        source: Int,
        rate: Int,
        device: android.media.AudioDeviceInfo?,
    ): Pair<AudioRecord, EffectStatus> {
        val minBuf = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBuf > 0) { "getMinBufferSize=$minBuf at ${rate}Hz" }
        val blockSamples = max(160, rate / 10)
        val bufferBytes = max(minBuf, blockSamples * 2 * 4)
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val rec = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("AudioRecord not initialized (source=$source rate=$rate)")
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
            captureRate = rate,
            resampled = rate != targetRate,
        )
        return rec to status
    }

    private fun registerCallback(rec: AudioRecord) {
        if (Build.VERSION.SDK_INT < 29) return
        // API 29's recording callback lives on AudioManager, not AudioRecord.
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                val routed = runCatching { rec.routedDevice }.getOrNull()
                val name = routed?.let { devices.describe(it).name } ?: "default"
                if (name != activeInputName) {
                    FLog.i("input route changed: $activeInputName -> $name")
                    activeInputName = name
                    listener.onRouteChanged(name)
                }
            }
        }
        runCatching { rec.registerAudioRecordingCallback(callbackExecutor, callback) }
            .onFailure { FLog.w("recording callback registration failed: ${it.message}") }
        recordingCallback = callback
    }

    private fun unregisterCallback(rec: AudioRecord) {
        val callback = recordingCallback ?: return
        recordingCallback = null
        runCatching { rec.unregisterAudioRecordingCallback(callback) }
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
