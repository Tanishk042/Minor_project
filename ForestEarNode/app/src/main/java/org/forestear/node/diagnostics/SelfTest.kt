package org.forestear.node.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.OkHttpClient
import okhttp3.Request
import org.forestear.node.audio.AudioDeviceManager
import org.forestear.node.audio.MicLevels
import org.forestear.node.audio.MicProbe
import org.forestear.node.config.AppConfig
import org.forestear.node.features.FeatureAssets
import org.forestear.node.model.ModelZoo
import org.forestear.node.power.BatteryMonitor
import org.forestear.node.service.ProtectionChecker
import org.forestear.node.settings.SettingsStore
import org.forestear.node.transport.SignalMonitor
import org.forestear.node.transport.SmsSender
import org.forestear.node.util.AndroidAssetSource
import org.forestear.node.util.Binary
import org.forestear.node.util.FLog
import org.forestear.node.util.TimeUtils
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

enum class TestStatus { PASS, WARN, FAIL }

data class SelfTestItem(
    val id: String,
    val title: String,
    val status: TestStatus,
    val detail: String,
)

data class SelfTestReport(
    val generatedUtc: String,
    val items: List<SelfTestItem>,
) {
    val failed: Int get() = items.count { it.status == TestStatus.FAIL }
    val warned: Int get() = items.count { it.status == TestStatus.WARN }
    val allPass: Boolean get() = failed == 0
}

/**
 * Hardware self-test covering exactly the things that break a phone node:
 * microphone + clipping, headset detection, permissions, background
 * protections, network, backend reachability, model loading, SMS capability and
 * thermal readings.
 */
class SelfTest(private val context: Context, private val settings: SettingsStore) {

    fun run(progress: (String) -> Unit = {}): SelfTestReport {
        val items = mutableListOf<SelfTestItem>()
        val devices = AudioDeviceManager(context)

        progress("microphone")
        items += micTest(devices)

        val headset = devices.wiredHeadset()
        items += SelfTestItem(
            "headset", "Wired headset detection",
            if (headset != null) TestStatus.PASS else TestStatus.WARN,
            if (headset != null) "detected: ${devices.describe(headset).name}"
            else "no TRRS headset detected (built-in mic will be used)"
        )

        progress("permissions")
        items += permission("mic_permission", "Microphone permission", Manifest.permission.RECORD_AUDIO, required = true)
        items += permission("phone_state", "Phone state (signal/SIM)", Manifest.permission.READ_PHONE_STATE, required = false)
        if (settings.smsEnabled) {
            items += permission("sms", "SMS permission", Manifest.permission.SEND_SMS, required = true)
        }

        progress("background protections")
        val protection = ProtectionChecker(context, settings).check()
        items += SelfTestItem(
            "protections", "OxygenOS background protections",
            if (protection.allOk) TestStatus.PASS else TestStatus.WARN,
            if (protection.allOk) "all checks confirmed"
            else "missing: ${protection.missing.joinToString { it.title }}"
        )

        progress("network")
        items += networkTest()

        progress("backend")
        items += backendTest()

        progress("models")
        items += modelTest()

        progress("SMS")
        val sms = SmsSender(context, settings, org.forestear.node.transport.Transport(
            context, settings, org.forestear.node.transport.OfflineQueue(context)
        ))
        val sims = sms.subscriptions()
        items += SelfTestItem(
            "sms_capability", "SMS capability",
            when {
                !sms.permissionGranted() -> TestStatus.WARN
                !sms.smsCapable() -> TestStatus.FAIL
                else -> TestStatus.PASS
            },
            when {
                !sms.permissionGranted() -> "SEND_SMS not granted"
                !sms.smsCapable() -> "device reports no telephony"
                else -> "SIMs: " + sims.joinToString { "${it.displayName} (sub ${it.subscriptionId})" }
                    .ifBlank { "telephony present, no active subscription" }
            }
        )

        progress("thermal")
        val battery = BatteryMonitor(context).snapshot()
        items += SelfTestItem(
            "thermal", "Battery / thermal readings",
            if (battery.temperatureValid || battery.thermalStatus > 0) TestStatus.PASS else TestStatus.WARN,
            "level=${battery.percent}% temp=" +
                (if (battery.temperatureValid) "%.1f C".format(battery.temperatureC) else "n/a") +
                " thermal=${battery.thermalLabel} current=" +
                (battery.currentMa?.let { "%.0f mA".format(it) } ?: "n/a")
        )

        progress("signal")
        val signal = SignalMonitor(context, settings).sample()
        items += SelfTestItem(
            "signal", "Cellular signal",
            if (signal.networkType.startsWith("UNKNOWN") || signal.networkType == "permission denied") {
                TestStatus.WARN
            } else TestStatus.PASS,
            "type=${signal.networkType} dbm=${signal.dbm ?: "n/a"} operator=${signal.operator ?: "n/a"}"
        )

        return SelfTestReport(TimeUtils.isoUtc(), items)
    }

    private fun micTest(devices: AudioDeviceManager): SelfTestItem {
        val probe = MicProbe(context, settings)
        val latch = CountDownLatch(1)
        val best = AtomicReference<MicLevels?>(null)
        val levels = mutableListOf<MicLevels>()
        probe.start { l ->
            synchronized(levels) { levels.add(l) }
            if (best.get() == null || l.peakDbfs > best.get()!!.peakDbfs) best.set(l)
            if (levels.size >= 20) latch.countDown()
        }
        val got = runCatching { latch.await(3, TimeUnit.SECONDS) }.getOrDefault(false)
        probe.stop()
        val snapshot = synchronized(levels) { levels.toList() }
        if (!got || snapshot.isEmpty()) {
            return SelfTestItem(
                "microphone", "Microphone input", TestStatus.FAIL,
                "no audio frames captured — check RECORD_AUDIO and that another app is not holding the mic"
            )
        }
        val peak = snapshot.maxOf { it.peakDbfs }
        val rms = snapshot.map { it.rmsDbfs }.average()
        val clipped = snapshot.any { it.clipping }
        val input = snapshot.last().input
        val status = when {
            clipped -> TestStatus.WARN
            peak < -50.0 -> TestStatus.WARN
            else -> TestStatus.PASS
        }
        return SelfTestItem(
            "microphone", "Microphone input", status,
            "input=$input rms=%.1f dBFS peak=%.1f dBFS%s".format(
                rms, peak, if (clipped) " CLIPPING" else ""
            ) + " | unprocessed=${devices.unprocessedSupported()}"
        )
    }

    private fun permission(id: String, title: String, perm: String, required: Boolean): SelfTestItem {
        val granted = ContextCompat.checkSelfPermission(context, perm) ==
            PackageManager.PERMISSION_GRANTED
        return SelfTestItem(
            id, title,
            when {
                granted -> TestStatus.PASS
                required -> TestStatus.FAIL
                else -> TestStatus.WARN
            },
            if (granted) "granted" else "denied"
        )
    }

    private fun networkTest(): SelfTestItem {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        return SelfTestItem(
            "network", "Internet connectivity",
            if (validated) TestStatus.PASS else TestStatus.WARN,
            when {
                wifi -> "Wi-Fi"
                cellular -> "cellular"
                caps == null -> "no active network"
                else -> "transport present"
            } + if (validated) ", validated" else ", not validated"
        )
    }

    private fun backendTest(): SelfTestItem {
        val client = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
        return try {
            val request = Request.Builder().url(settings.backendUrl + "/health").get().build()
            client.newCall(request).execute().use { r ->
                SelfTestItem(
                    "backend", "Backend reachability",
                    if (r.code in 200..299) TestStatus.PASS else TestStatus.WARN,
                    "GET /health -> ${r.code} (${settings.backendUrl})"
                )
            }
        } catch (e: Exception) {
            SelfTestItem(
                "backend", "Backend reachability", TestStatus.WARN,
                "unreachable: ${e.message} (${settings.backendUrl})"
            )
        }
    }

    private fun modelTest(): SelfTestItem {
        return try {
            val source = AndroidAssetSource(context)
            val config = AppConfig.load(source)
            val assets = FeatureAssets(source, config.features)
            val zoo = ModelZoo(context, config, assets, loadBirdnet = true)
            try {
                val wav = Binary.floats(source.readBytes("golden/input_ambience.f32"))
                val r = zoo.process(wav, hour = 12.0)
                val sum = r.m9.values.sum()
                val finite = r.m9.values.all { it.isFinite() } &&
                    r.m2.isFinite() && r.m7.isFinite()
                SelfTestItem(
                    "models", "Model loading and inference",
                    if (finite && sum > 0.9f) TestStatus.PASS else TestStatus.FAIL,
                    "9 models loaded, m9 sum=${"%.3f".format(sum)}, " +
                        "birdnet=${if (zoo.birdnetAvailable) "on" else "unavailable"}, " +
                        "latency=${"%.1f".format(r.inferenceMs["total"] ?: 0.0)} ms"
                )
            } finally {
                zoo.close()
            }
        } catch (e: Throwable) {
            FLog.e("self-test model load failed", e)
            SelfTestItem(
                "models", "Model loading and inference", TestStatus.FAIL,
                "failed: ${e.message}"
            )
        }
    }
}
