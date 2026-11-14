package org.forestear.node.service

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import org.forestear.node.audio.AudioCapture
import org.forestear.node.audio.AudioDeviceManager
import org.forestear.node.audio.CollectionRecorder
import org.forestear.node.config.AppConfig
import org.forestear.node.decision.Decision
import org.forestear.node.decision.DecisionEngine
import org.forestear.node.decision.WindowScores
import org.forestear.node.diagnostics.SoakTracker
import org.forestear.node.features.FeatureAssets
import org.forestear.node.location.LocationProvider
import org.forestear.node.model.DelegateKind
import org.forestear.node.model.ModelZoo
import org.forestear.node.model.Species
import org.forestear.node.model.WindowResult
import org.forestear.node.power.BatteryCsvLogger
import org.forestear.node.power.BatteryMonitor
import org.forestear.node.power.BatterySnapshot
import org.forestear.node.power.GovernorAlert
import org.forestear.node.power.GovernorLevel
import org.forestear.node.power.GovernorOutput
import org.forestear.node.power.ThermalGovernor
import org.forestear.node.settings.SettingsStore
import org.forestear.node.transport.OfflineQueue
import org.forestear.node.transport.SignalInfo
import org.forestear.node.transport.SignalMonitor
import org.forestear.node.transport.SmsSender
import org.forestear.node.transport.StreamingClient
import org.forestear.node.transport.Transport
import org.forestear.node.util.AndroidAssetSource
import org.forestear.node.util.FLog
import org.forestear.node.util.TimeUtils
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Foreground service that owns the microphone, the 9-model pipeline, the
 * decision engine and all transports.
 *
 * Android 14: microphone FGS cannot be started from the background. The service
 * always starts with the dataSync type first and upgrades to microphone when the
 * activity is in the foreground (or immediately on older releases). If the
 * upgrade is refused, capture does not start and the notification says so.
 */
class NodeService : Service(), AudioCapture.Listener {

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val processing = AtomicBoolean(false)
    private val pipelineStarted = AtomicBoolean(false)
    private val audioLock = Any()

    private lateinit var settings: SettingsStore
    private lateinit var config: AppConfig
    private lateinit var assets: FeatureAssets
    private lateinit var notification: NotificationHelper
    private lateinit var transport: Transport
    private lateinit var queue: OfflineQueue
    private lateinit var sms: SmsSender
    private lateinit var signalMonitor: SignalMonitor
    private lateinit var streaming: StreamingClient
    private lateinit var collection: CollectionRecorder
    private lateinit var location: LocationProvider
    private lateinit var batteryMonitor: BatteryMonitor
    private lateinit var governor: ThermalGovernor
    private lateinit var batteryCsv: BatteryCsvLogger
    private lateinit var soak: SoakTracker
    private lateinit var protection: ProtectionChecker
    private lateinit var decision: DecisionEngine

    private var zoo: ModelZoo? = null
    private var capture: AudioCapture? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var micForeground = false
    @Volatile private var explicitStop = false
    @Volatile private var lastGovernor: GovernorOutput? = null
    @Volatile private var lastWindow: WindowResult? = null
    @Volatile private var lastBattery: BatterySnapshot? = null
    @Volatile private var lastSignal: SignalInfo? = null
    @Volatile private var collectionSuspended = false

    private var serviceStartMs = 0L
    private var lastUptimePersistMs = 0L
    private var lastHeartbeatMs = 0L
    private var lastProtectionMs = 0L
    private var lastCpuMs = 0L
    private var lastCpuWallMs = 0L
    private var windowCounter = 0L
    private var analysedCounter = 0L
    private var droppedFrames = 0L
    private var skippedStride = 0L
    private var silentWindows = 0L
    private var adaptiveStride = 1
    private var fastWindows = 0
    private var audioRestartScheduled = false
    private var lastDeviceId = Int.MIN_VALUE
    private var soakCompletedNotified = false

    override fun onCreate() {
        super.onCreate()
        running.set(true)
        isRunning = true
        serviceStartMs = System.currentTimeMillis()
        lastUptimePersistMs = serviceStartMs
        lastCpuMs = Process.getElapsedCpuTime()
        lastCpuWallMs = serviceStartMs
        notification = NotificationHelper(this)
        // Must be foreground within a few seconds; dataSync is always allowed.
        startForegroundCompat(mic = false, text = "starting models…")

        runCatching {
            val source = AndroidAssetSource(this)
            config = AppConfig.load(source)
            settings = SettingsStore.get(this)
            settings.applyDefaults(config)
            assets = FeatureAssets(source, config.features)
        }.onFailure {
            FLog.e("config load failed", it)
            notification.warning("ForestEar configuration error", it.message ?: "cannot read assets")
            stopSelf()
            return
        }

        // First-ever launch is not a restart; later starts (watchdog/boot/process
        // death) are counted and reported in every heartbeat.
        val restarts = if (settings.restartCount > 0 || settings.totalUptimeMs > 0) {
            settings.incrementRestartCount()
        } else {
            0
        }
        queue = OfflineQueue(this)
        transport = Transport(this, settings, queue)
        sms = SmsSender(this, settings, transport)
        signalMonitor = SignalMonitor(this, settings)
        streaming = StreamingClient { settings.nodeId }
        collection = CollectionRecorder(this, settings, config)
        location = LocationProvider(this, settings)
        batteryMonitor = BatteryMonitor(this)
        governor = ThermalGovernor(config, settings)
        batteryCsv = BatteryCsvLogger(this)
        soak = SoakTracker(this, settings)
        protection = ProtectionChecker(this, settings)
        decision = DecisionEngine(
            config.decision.withThresholdOverrides(settings.thresholdOverrides())
        )
        lastGovernor = GovernorOutput(
            GovernorLevel.NORMAL, 1, settings.m8Duty, 1, false,
            "normal", false, false
        )

        acquireWakeLock()
        WatchdogWorker.schedule(this)
        WatchdogAlarmReceiver.schedule(this)
        NodeState.update {
            it.copy(
                running = true, starting = true, restartCount = restarts,
                serviceStartedAtMs = serviceStartMs, uptimeMs = settings.totalUptimeMs,
                protection = protection.check(), smsStatus = settings.lastSmsStatus,
            )
        }
        handler.post(heartbeatRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!::settings.isInitialized) {
            // onCreate failed (assets/config missing); never touch lateinit fields.
            stopSelf()
            return START_NOT_STICKY
        }
        val action = intent?.action
        when (action) {
            ACTION_STOP -> {
                explicitStop = true
                // A user stop must not be undone by the watchdog or boot receiver.
                settings.autoStart = false
                WatchdogAlarmReceiver.cancel(this)
                runCatching { WorkManager.getInstance(this).cancelAllWork() }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SOAK_ON -> {
                settings.soakEnabled = true
                soak.start()
                soakCompletedNotified = false
                NodeState.update { it.copy(soak = true) }
                sendSystem("soak", "INFO", "24 h soak test started")
            }
            ACTION_SOAK_OFF -> {
                val report = soak.finish()
                NodeState.update { it.copy(soak = false) }
                sendSystem(
                    "soak", "INFO",
                    report?.let {
                        "soak stopped after ${it.durationS}s: restarts=${it.restartsEnd - it.restartsStart}, " +
                            "dropped=${it.droppedFrames}, maxTemp=${it.maxTempC}"
                    } ?: "soak stopped"
                )
            }
            ACTION_COLLECT_ON -> {
                settings.collectionEnabled = true
                if (zoo != null && !(lastGovernor?.pauseInference ?: false)) {
                    collectionSuspended = false
                    collection.start()
                }
                NodeState.update { it.copy(collection = collection.stats()) }
            }
            ACTION_COLLECT_OFF -> {
                settings.collectionEnabled = false
                collection.stop()
                NodeState.update { it.copy(collection = collection.stats()) }
            }
            ACTION_STREAM_ON -> {
                settings.streamingEnabled = true
                streaming.start(settings.streamingUrl)
                NodeState.update { it.copy(streaming = true) }
                sendSystem("streaming", "INFO", "raw PCM streaming enabled")
            }
            ACTION_STREAM_OFF -> {
                settings.streamingEnabled = false
                streaming.stop()
                NodeState.update { it.copy(streaming = false) }
                sendSystem("streaming", "INFO", "raw PCM streaming disabled")
            }
            ACTION_ACTIVITY_STARTED -> {
                upgradeToMicForeground()
                startPipeline()
            }
            ACTION_BOOT, ACTION_WATCHDOG -> {
                if (!settings.autoStart) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (Build.VERSION.SDK_INT >= 34) {
                    // While-in-use FGS cannot start from the background on 14+.
                    notification.update(
                        NotificationHelper.SERVICE_ID,
                        "open the app once to start the microphone"
                    )
                } else {
                    upgradeToMicForeground()
                    startPipeline()
                }
            }
            ACTION_START -> {
                settings.autoStart = true
                upgradeToMicForeground()
                startPipeline()
            }
            else -> {
                upgradeToMicForeground()
                startPipeline()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        FLog.w("task removed: scheduling restart")
        runCatching {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                this, 992,
                Intent(this, WatchdogAlarmReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 2_000L, pi)
        }
        WatchdogAlarmReceiver.schedule(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running.set(false)
        isRunning = false
        pipelineStarted.set(false)
        handler.removeCallbacksAndMessages(null)
        persistUptime()
        runCatching { capture?.stop() }
        capture = null
        runCatching { streaming.shutdown() }
        runCatching { collection.stop() }
        runCatching { zoo?.close() }
        zoo = null
        releaseWakeLock()
        if (explicitStop) {
            WatchdogAlarmReceiver.cancel(this)
            runCatching { WorkManager.getInstance(this).cancelAllWork() }
        }
        if (::settings.isInitialized) {
            NodeState.update {
                it.copy(
                    running = false, starting = false, modelLoaded = false,
                    uptimeMs = settings.totalUptimeMs,
                )
            }
        } else {
            NodeState.update { it.copy(running = false, starting = false) }
        }
        executor.shutdownNow()
        val restarts = if (::settings.isInitialized) settings.restartCount else -1
        FLog.i("service destroyed (restarts=$restarts)")
        super.onDestroy()
    }

    // ------------------------------------------------------------ foreground
    private fun startForegroundCompat(mic: Boolean, text: String) {
        val n = notification.ongoing(text, paused = !mic && Build.VERSION.SDK_INT >= 34)
        when {
            // Microphone FGS type only exists from API 30. On API 29 the service
            // still records audio; the platform simply has no per-type flag.
            Build.VERSION.SDK_INT >= 30 -> {
                val type = if (mic) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
                startForeground(NotificationHelper.SERVICE_ID, n, type)
            }
            Build.VERSION.SDK_INT >= 29 ->
                startForeground(
                    NotificationHelper.SERVICE_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            else -> startForeground(NotificationHelper.SERVICE_ID, n)
        }
    }

    private fun upgradeToMicForeground() {
        if (micForeground) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notification.warning(
                "Microphone permission required",
                "Open ForestEar Node and grant the microphone permission."
            )
            return
        }
        try {
            startForegroundCompat(mic = true, text = "microphone active")
            micForeground = true
        } catch (e: Throwable) {
            // Android 14 while-in-use restriction: keep dataSync alive and wait.
            micForeground = false
            FLog.w("microphone FGS upgrade refused: ${e.message}")
            notification.update(
                NotificationHelper.SERVICE_ID,
                "open the app once to start the microphone"
            )
        }
    }

    // ------------------------------------------------------------ pipeline
    private fun startPipeline() {
        if (!micForeground) {
            FLog.w("startPipeline waiting for microphone foreground type")
            return
        }
        if (!pipelineStarted.compareAndSet(false, true)) return
        NodeState.update { it.copy(starting = true) }
        executor.execute {
            try {
                if (zoo == null) {
                    zoo = ModelZoo(
                        this, config, assets,
                        delegateFor = { id -> DelegateKind.fromName(settings.bestDelegateFor(id)) },
                        loadBirdnet = true,
                    )
                }
                NodeState.update {
                    it.copy(
                        starting = false, modelLoaded = true,
                        protection = protection.check(),
                    )
                }
                if (settings.collectionEnabled && !(lastGovernor?.pauseInference ?: false)) {
                    collection.start()
                }
                if (settings.streamingEnabled) streaming.start(settings.streamingUrl)
                if (settings.soakEnabled && !soak.isActive()) soak.start()
                NodeState.update {
                    it.copy(
                        soak = soak.isActive(),
                        collection = collection.stats(),
                        streaming = settings.streamingEnabled,
                    )
                }
                startCapture()
            } catch (e: Throwable) {
                FLog.e("pipeline start failed", e)
                NodeState.update { it.copy(starting = false, lastError = e.message) }
                notification.warning("Model loading failed", e.message ?: "unknown error")
                pipelineStarted.set(false)
                handler.postDelayed({ startPipeline() }, 60_000L)
            }
        }
    }

    private fun startCapture() {
        if (!running.get() || !micForeground) return
        synchronized(audioLock) {
            if (capture != null) return
            val c = AudioCapture(
                this, settings, this,
                silenceRmsDb = config.audio.silenceRmsDb,
                clipRatioThreshold = config.audio.clipRatio,
            )
            capture = c
            lastDeviceId = AudioDeviceManager(this).choose(settings.inputChoice)?.id ?: -1
            try {
                c.start()
            } catch (e: Throwable) {
                capture = null
                FLog.e("capture start failed", e)
                onError("capture start failed: ${e.message}", fatal = true)
            }
        }
    }

    // ------------------------------------------------------------ audio callbacks
    override fun onWindow(window: FloatArray, timestampMs: Long, silent: Boolean, clipping: Boolean) {
        if (!running.get()) return
        if (clipping) FLog.w("input clipping detected")
        windowCounter++
        val gov = lastGovernor ?: return
        if (gov.pauseInference) return
        val stride = max(gov.stride, adaptiveStride)
        if (windowCounter % stride != 0L) {
            skippedStride++
            return
        }
        if (silent) {
            silentWindows++
            return
        }
        if (!processing.compareAndSet(false, true)) {
            droppedFrames++
            return
        }
        try {
            executor.execute {
                try {
                    processWindow(window, timestampMs)
                } catch (e: Throwable) {
                    FLog.e("pipeline error", e)
                    NodeState.update { it.copy(lastError = e.message) }
                } finally {
                    processing.set(false)
                }
            }
        } catch (e: RejectedExecutionException) {
            processing.set(false)
            droppedFrames++
        }
    }

    override fun onRawChunk(pcm: ShortArray, timestampMs: Long) {
        if (settings.streamingEnabled) streaming.send(pcm)
        if (settings.collectionEnabled && !collectionSuspended) {
            collection.append(pcm, timestampMs)
        }
    }

    override fun onState(state: AudioCapture.State, detail: String) {
        if (state == AudioCapture.State.RUNNING || state == AudioCapture.State.ERROR) {
            NodeState.update {
                it.copy(
                    activeInput = capture?.activeInput() ?: detail,
                    effects = capture?.lastEffects()?.summary() ?: detail,
                )
            }
        }
    }

    override fun onError(message: String, fatal: Boolean) {
        FLog.e("audio error: $message")
        NodeState.update { it.copy(lastError = message) }
        if (fatal) handler.post { restartAudio(message) }
    }

    private fun restartAudio(reason: String) {
        if (!running.get()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notification.warning("Microphone permission lost", "Grant RECORD_AUDIO to resume monitoring.")
            return
        }
        synchronized(audioLock) {
            runCatching { capture?.stop() }
            capture = null
            if (audioRestartScheduled) return
            audioRestartScheduled = true
            val count = settings.incrementAudioRestartCount()
            val delay = min(30_000L, 1_000L shl min(count, 5))
            FLog.w("restarting audio in ${delay}ms ($reason)")
            handler.postDelayed({
                audioRestartScheduled = false
                if (running.get()) startCapture()
            }, delay)
        }
    }

    private fun checkAudioRoute() {
        val chosen = AudioDeviceManager(this).choose(settings.inputChoice)
        val id = chosen?.id ?: -1
        if (id != lastDeviceId) {
            restartAudio("input device changed to ${chosen?.productName ?: "default"}")
            lastDeviceId = id
        }
    }

    // ------------------------------------------------------------ inference
    private fun processWindow(wav: FloatArray, timestampMs: Long) {
        val z = zoo ?: return
        val gov = lastGovernor ?: GovernorOutput(
            GovernorLevel.NORMAL, 1, settings.m8Duty, 1, false, "", false, false
        )
        val m6Every = gov.m6Every
        val m6Enabled = m6Every > 0 && (analysedCounter % m6Every == 0L)
        val m8Duty = gov.m8Duty
        analysedCounter++
        val hour = TimeUtils.utcHour(timestampMs)
        val result = z.process(wav, hour = hour, m6Enabled = m6Enabled, m8Duty = m8Duty)
        lastWindow = result

        val scores = WindowScores(
            m2 = result.m2.toDouble(),
            m3 = result.m3.toDouble(),
            m4 = result.m4.toDouble(),
            m7 = result.m7.toDouble(),
            m6Z = result.m6Z.toDouble(),
            m5Rain = (result.m5["rain"] ?: 0f).toDouble(),
            m5Wind = (result.m5["wind"] ?: 0f).toDouble(),
            m5Thunder = (result.m5["thunder"] ?: 0f).toDouble(),
            m9 = result.m9.mapValues { it.value.toDouble() },
            rmsDb = result.rmsDb,
        )
        decision.config = config.decision.withThresholdOverrides(settings.thresholdOverrides())
        val decisions = decision.update(scores, timestampMs / 1000.0)
        val (lat, lon) = location.current()
        val human = (result.m7 >= 0.5f) || ((result.m9["human"] ?: 0f) >= 0.5f)
        collection.onHumanPresence(human)

        for (d in decisions) {
            if (d.fired) {
                val eventTs = if (d.cls == "gunshot") onsetTimestamp(wav, timestampMs) else timestampMs
                val payload = buildEventPayload(eventTs, d, result, lat, lon)
                val sent = transport.sendEvent(payload)
                val smsSent = maybeSms(payload, d)
                val summary = "${d.cls}/${d.severity} conf=${"%.2f".format(d.confidence)}" +
                    (if (d.escalated) " ESCALATED" else "") +
                    (if (sent) "" else " [queued]") +
                    (if (smsSent) " [sms]" else "")
                NodeState.update { it.copy(lastEvent = summary) }
                if (d.severity == "CRITICAL") {
                    notification.warning("ForestEar alert: ${d.cls}", summary)
                }
            } else if (d.suppressedBy != null) {
                transport.sendSuppressed(JSONObject()
                    .put("node_id", settings.nodeId)
                    .put("timestamp_utc", TimeUtils.isoUtc(timestampMs))
                    .put("class", d.cls)
                    .put("confidence", round(d.confidence, 3))
                    .put("suppressed_by", d.suppressedBy)
                    .put("rms_db", round(result.rmsDb, 1)))
            }
        }

        if (!result.m8Species.isNullOrEmpty()) {
            transport.sendBiodiversity(JSONObject()
                .put("node_id", settings.nodeId)
                .put("timestamp_utc", TimeUtils.isoUtc(timestampMs))
                .put("detections", speciesJson(result.m8Species!!)))
        }

        transport.sendScores(buildScoresPayload(timestampMs, result))

        val total = result.inferenceMs["total"] ?: 0.0
        adaptStride(total)
        NodeState.update {
            it.copy(
                lastWindow = result,
                lastDecisions = decisions,
                inferenceMs = total,
                droppedFrames = droppedFrames,
                queueSize = transport.queueSize(),
                alertsSent = transport.sentEvents,
                smsStatus = settings.lastSmsStatus,
                adaptiveStride = adaptiveStride,
                collection = collection.stats(),
                backendOnline = transport.online,
            )
        }
    }

    private fun adaptStride(totalMs: Double) {
        val budget = settings.analysisBudgetMs.toDouble()
        if (totalMs > budget) {
            adaptiveStride = min(8, adaptiveStride + 1)
            fastWindows = 0
        } else if (totalMs < budget * 0.5) {
            fastWindows++
            if (fastWindows >= 10) {
                adaptiveStride = max(1, adaptiveStride - 1)
                fastWindows = 0
            }
        }
    }

    private fun onsetTimestamp(wav: FloatArray, windowStartMs: Long): Long {
        var peak = 0f
        for (v in wav) {
            val a = abs(v)
            if (a > peak) peak = a
        }
        if (peak < 1e-4f) return windowStartMs
        var idx = 0
        val threshold = 0.25f * peak
        for (i in wav.indices) {
            if (abs(wav[i]) >= threshold) {
                idx = i
                break
            }
        }
        return windowStartMs + (idx * 1000L / 16000L)
    }

    private fun buildEventPayload(
        timestampMs: Long,
        d: Decision,
        result: WindowResult,
        lat: Double,
        lon: Double,
    ): JSONObject {
        val battery = lastBattery
        val perModel = JSONObject()
            .put("m1_selected", rounded(result.m1Scores))
            .put("m2_gunshot", round(result.m2.toDouble(), 3))
            .put("m3_chainsaw", round(result.m3.toDouble(), 3))
            .put("m4_vehicle", round(result.m4.toDouble(), 3))
            .put("m5_weather", rounded(result.m5))
            .put("m6_anomaly_z", round(result.m6Z.toDouble(), 2))
            .put("m7_human", round(result.m7.toDouble(), 3))
            .put("m9_fusion", rounded(result.m9))
            .put("device", JSONObject()
                .put("model", Build.MODEL)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("input", capture?.activeInput() ?: "unknown")
                .put("governor", lastGovernor?.level?.name ?: "NORMAL"))
        val payload = JSONObject()
            .put("node_id", settings.nodeId)
            .put("timestamp_utc", TimeUtils.isoUtc(timestampMs))
            .put("lat", lat)
            .put("lon", lon)
            .put("class", d.cls)
            .put("severity", d.severity ?: "INFO")
            .put("confidence", round(d.confidence, 3))
            .put("per_model_scores", perModel)
            .put("rms_db", round(result.rmsDb, 1))
            .put("persistence", d.kOfN)
            .put("escalated", d.escalated)
            .put("battery", battery?.percent ?: JSONObject.NULL)
            .put("cpu", JSONObject.NULL)
            .put("inference_ms", JSONObject(result.inferenceMs))
            .put("suppressed_by", JSONObject.NULL)
            .put("clip_path", JSONObject.NULL)
        payload.put("compact_bytes", transport.compactPayload(payload).toByteArray().size)
        return payload
    }

    private fun maybeSms(payload: JSONObject, d: Decision): Boolean {
        if (!settings.smsEnabled || d.severity != "CRITICAL") return false
        val now = System.currentTimeMillis()
        if (!sms.canSend(now, config.android.sms.minIntervalS, config.android.sms.maxPerHour)) {
            return false
        }
        return sms.sendAlert(
            payload,
            config.android.sms.minIntervalS,
            config.android.sms.maxPerHour,
        )
    }

    private fun buildScoresPayload(timestampMs: Long, result: WindowResult): JSONObject {
        val scores = JSONObject()
            .put("m2", round(result.m2.toDouble(), 3))
            .put("m3", round(result.m3.toDouble(), 3))
            .put("m4", round(result.m4.toDouble(), 3))
            .put("m7", round(result.m7.toDouble(), 3))
            .put("m6_z", round(result.m6Z.toDouble(), 2))
            .put("m5_rain", round((result.m5["rain"] ?: 0f).toDouble(), 3))
            .put("m5_wind", round((result.m5["wind"] ?: 0f).toDouble(), 3))
            .put("m5_thunder", round((result.m5["thunder"] ?: 0f).toDouble(), 3))
            .put("m9", rounded(result.m9))
            .put("rms_db", round(result.rmsDb, 1))
        val spect = IntArray(64)
        val frames = config.features.frames
        for (m in 0 until 64) {
            var sum = 0.0
            for (f in 0 until frames) sum += result.logMel[m * frames + f]
            spect[m] = sum.toInt()
        }
        val lo = spect.minOrNull() ?: 0
        val hi = spect.maxOrNull() ?: 0
        val range = max(1, hi - lo)
        val q = JSONArray()
        for (v in spect) q.put(((v - lo) * 255 / range).coerceIn(0, 255))
        return JSONObject()
            .put("node_id", settings.nodeId)
            .put("ts", TimeUtils.isoUtc(timestampMs))
            .put("scores", scores)
            .put("spect", q)
            .put("rms_db", round(result.rmsDb, 1))
            .put("inference_ms", JSONObject(result.inferenceMs))
    }

    // ------------------------------------------------------------ heartbeat
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            runCatching { heartbeat() }
                .onFailure { FLog.e("heartbeat failed", it) }
            handler.postDelayed(this, 5_000L)
        }
    }

    private fun heartbeat() {
        val now = System.currentTimeMillis()
        val battery = batteryMonitor.snapshot()
        lastBattery = battery
        val signal = signalMonitor.sample()
        lastSignal = signal
        val (gov, alerts) = governor.update(
            battery.temperatureC, battery.thermalStatus, battery.percent, battery.charging
        )
        handleGovernor(gov, alerts, battery)
        persistUptime()
        NodeState.update {
            it.copy(
                battery = battery,
                signalDbm = signal.dbm,
                networkType = signal.networkType,
                governorLevel = gov.level.name,
                governorReason = gov.reason,
                restartCount = settings.restartCount,
                audioRestartCount = settings.audioRestartCount,
                uptimeMs = settings.totalUptimeMs,
                smsStatus = settings.lastSmsStatus,
                soak = soak.isActive(),
                collection = collection.stats(),
                streaming = streaming.isConnected(),
            )
        }

        if (now - lastProtectionMs >= 60_000L) {
            lastProtectionMs = now
            NodeState.update { it.copy(protection = protection.check()) }
        }
        batteryCsv.maybeLog(battery, gov.level.name)
        soak.sample(battery, gov.level.name, droppedFrames, transport.queueSize(), transport.sentEvents)
        checkSoakComplete()
        checkAudioRoute()

        if (now - lastHeartbeatMs < settings.heartbeatS * 1000L) return
        lastHeartbeatMs = now
        val (lat, lon) = location.current()
        val health = JSONObject()
            .put("cpu_pct", cpuPct(now))
            .put("ram_mb", ramMb())
            .put("temp_c", if (battery.temperatureValid) battery.temperatureC else JSONObject.NULL)
            .put("battery", JSONObject()
                .put("percent", battery.percent)
                .put("charging", battery.charging)
                .put("plugged", battery.plugged)
                .put("temperature_c", if (battery.temperatureValid) battery.temperatureC else JSONObject.NULL)
                .put("current_ma", battery.currentMa ?: JSONObject.NULL)
                .put("voltage_mv", battery.voltageMv))
            .put("thermal", JSONObject()
                .put("status", battery.thermalStatus)
                .put("label", battery.thermalLabel)
                .put("governor", gov.level.name)
                .put("reason", gov.reason))
            .put("uptime_s", settings.totalUptimeMs / 1000L)
            .put("restart_count", settings.restartCount)
            .put("audio_restart_count", settings.audioRestartCount)
            .put("dropped_frames", droppedFrames)
            .put("skipped_stride", skippedStride)
            .put("silent_windows", silentWindows)
            .put("service_started_utc", TimeUtils.isoUtc(serviceStartMs))
            .put("active_input", capture?.activeInput() ?: "inactive")
            .put("effects", capture?.lastEffects()?.summary() ?: "")
            .put("mic_foreground", micForeground)
            .put("model_loaded", zoo != null)
            .put("signal", JSONObject()
                .put("dbm", signal.dbm ?: JSONObject.NULL)
                .put("network", signal.networkType)
                .put("operator", signal.operator ?: JSONObject.NULL))
            .put("queue_len", transport.queueSize())
            .put("backend_online", transport.online)
            .put("collection", JSONObject()
                .put("enabled", settings.collectionEnabled)
                .put("active", collection.stats().active)
                .put("chunks", collection.stats().chunks)
                .put("bytes", collection.stats().bytes)
                .put("dropped_for_human", collection.stats().droppedForHuman))
            .put("streaming", streaming.isConnected())
            .put("soak", soak.isActive())
            .put("sms", settings.lastSmsStatus)
            .put("adaptive_stride", adaptiveStride)
            .put("protections_ok", protection.check().allOk)
            .put("android_sdk", Build.VERSION.SDK_INT)
            .put("device", Build.MODEL)
        val scores = lastWindow?.let { result ->
            JSONObject()
                .put("m2", round(result.m2.toDouble(), 3))
                .put("m3", round(result.m3.toDouble(), 3))
                .put("m4", round(result.m4.toDouble(), 3))
                .put("m7", round(result.m7.toDouble(), 3))
                .put("m6_z", round(result.m6Z.toDouble(), 2))
                .put("m9", rounded(result.m9))
                .put("rms_db", round(result.rmsDb, 1))
        } ?: JSONObject()
        transport.sendHeartbeat(health, scores, lat, lon)

        notification.update(
            NotificationHelper.SERVICE_ID,
            "%s | %d%% %s | %s | %s".format(
                TimeUtils.humanDuration(settings.totalUptimeMs / 1000),
                battery.percent,
                if (battery.temperatureValid) "%.0f°C".format(battery.temperatureC) else "temp n/a",
                capture?.activeInput() ?: "no mic",
                gov.level.name,
            )
        )
    }

    private fun handleGovernor(gov: GovernorOutput, alerts: List<GovernorAlert>, battery: BatterySnapshot) {
        val previous = lastGovernor
        lastGovernor = gov
        if (gov.pauseInference && !collectionSuspended) {
            collectionSuspended = true
            collection.stop()
            FLog.w("inference paused: collection suspended to avoid unguarded speech capture")
        } else if (!gov.pauseInference && collectionSuspended) {
            collectionSuspended = false
            if (settings.collectionEnabled) collection.start()
        }
        for (alert in alerts) {
            FLog.w("governor ${alert.kind}: ${alert.message}")
            notification.warning("ForestEar ${alert.severity.lowercase()}", alert.message)
            val (lat, lon) = location.current()
            transport.sendSystemEvent(alert.kind, alert.severity, alert.message, lat, lon)
        }
        if (previous?.level != gov.level) {
            FLog.i("governor ${previous?.level} -> ${gov.level} (${gov.reason})")
        }
    }

    private fun checkSoakComplete() {
        if (!soak.isActive() || soakCompletedNotified) return
        val report = soak.report()
        if (!report.completed24h) return
        soakCompletedNotified = true
        val finished = soak.finish()
        NodeState.update { it.copy(soak = false) }
        val text = finished?.let {
            "24 h soak complete: duration=${it.durationS}s restarts=${it.restartsEnd - it.restartsStart} " +
                "dropped=${it.droppedFrames} maxTemp=${it.maxTempC} alerts=${it.alertsSent}"
        } ?: "24 h soak complete"
        notification.warning("ForestEar soak test complete", text)
        sendSystem("soak_complete", "INFO", text)
    }

    private fun sendSystem(cls: String, severity: String, message: String) {
        val (lat, lon) = location.current()
        transport.sendSystemEvent(cls, severity, message, lat, lon)
    }

    // ------------------------------------------------------------ helpers
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ForestEar::NodeService").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.release() }
        wakeLock = null
    }

    private fun persistUptime() {
        if (!::settings.isInitialized) return
        val now = System.currentTimeMillis()
        val delta = now - lastUptimePersistMs
        if (delta > 0) {
            settings.addUptime(delta)
            lastUptimePersistMs = now
        }
    }

    private fun cpuPct(now: Long): Double {
        val cpu = Process.getElapsedCpuTime()
        val wall = now - lastCpuWallMs
        val delta = cpu - lastCpuMs
        lastCpuMs = cpu
        lastCpuWallMs = now
        return if (wall > 0) (delta.toDouble() / wall * 100.0) else 0.0
    }

    private fun ramMb(): Double {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss / 1024.0
    }

    private fun speciesJson(list: List<Species>): JSONArray {
        val arr = JSONArray()
        for (s in list) {
            arr.put(JSONObject().put("species", s.species).put("confidence", round(s.confidence.toDouble(), 3)))
        }
        return arr
    }

    private fun rounded(map: Map<String, Float>): JSONObject {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, round(v.toDouble(), 3))
        return o
    }

    private fun round(v: Double, digits: Int): Double {
        val f = Math.pow(10.0, digits.toDouble())
        return (v * f).roundToInt() / f
    }

    companion object {
        @Volatile var isRunning: Boolean = false
            private set

        const val ACTION_START = "org.forestear.node.service.START"
        const val ACTION_STOP = "org.forestear.node.STOP"
        const val ACTION_BOOT = "org.forestear.node.service.BOOT"
        const val ACTION_WATCHDOG = "org.forestear.node.service.WATCHDOG"
        const val ACTION_ACTIVITY_STARTED = "org.forestear.node.service.ACTIVITY_STARTED"
        const val ACTION_SOAK_ON = "org.forestear.node.SOAK_ON"
        const val ACTION_SOAK_OFF = "org.forestear.node.SOAK_OFF"
        const val ACTION_COLLECT_ON = "org.forestear.node.COLLECT_ON"
        const val ACTION_COLLECT_OFF = "org.forestear.node.COLLECT_OFF"
        const val ACTION_STREAM_ON = "org.forestear.node.STREAM_ON"
        const val ACTION_STREAM_OFF = "org.forestear.node.STREAM_OFF"

        fun start(context: Context) {
            val intent = Intent(context, NodeService::class.java).setAction(ACTION_START)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /** Called from the visible activity so the mic FGS upgrade is permitted. */
        fun activityStarted(context: Context) {
            val intent = Intent(context, NodeService::class.java).setAction(ACTION_ACTIVITY_STARTED)
            runCatching {
                if (isRunning) context.startService(intent)
                else ContextCompat.startForegroundService(context, intent)
            }
        }

        fun stop(context: Context) {
            if (!isRunning) return
            val intent = Intent(context, NodeService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }

        fun action(context: Context, action: String) {
            val intent = Intent(context, NodeService::class.java).setAction(action)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
