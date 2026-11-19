package org.forestear.node.device

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import org.forestear.node.audio.AudioDeviceManager
import org.forestear.node.util.FLog
import org.forestear.node.util.TimeUtils
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Best-effort capability probe.
 *
 * Every query is wrapped: a maker that returns null, throws `SecurityException`
 * or omits an API must degrade to "unknown" and be listed in `missing`, never
 * crash the node. The result is written to `device_profile.json` and reused
 * until the app version changes (or the user re-runs the probe).
 */
class DeviceProbe(private val context: Context) {

    private val missing = mutableListOf<String>()
    private val warnings = mutableListOf<String>()

    fun probe(): DeviceProfile {
        missing.clear()
        warnings.clear()
        val build = probeBuild()
        val audio = probeAudio()
        val telephony = probeTelephony()
        val location = probeLocation()
        val battery = probeBattery()
        val display = probeDisplay()
        return DeviceProfile(
            generatedUtc = TimeUtils.isoUtc(),
            appVersionCode = appVersionCode(),
            appVersionName = appVersionName(),
            probeSource = "DeviceProbe",
            build = build,
            audio = audio,
            telephony = telephony,
            location = location,
            battery = battery,
            display = display,
            missing = missing.distinct(),
            warnings = warnings.distinct(),
        )
    }

    private fun probeBuild(): BuildCapability {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memory = ActivityManager.MemoryInfo()
        val ramTotal: Int
        val ramAvail: Int
        if (am != null) {
            runCatching { am.getMemoryInfo(memory) }
                .onFailure { noteMissing("ram", it) }
            ramTotal = (memory.totalMem / (1024 * 1024)).toInt()
            ramAvail = (memory.availMem / (1024 * 1024)).toInt()
        } else {
            noteMissing("ram", null)
            ramTotal = 0
            ramAvail = 0
        }
        val freeStorage = runCatching {
            val stat = StatFs(context.filesDir.absolutePath)
            (stat.availableBytes / (1024 * 1024)).toInt()
        }.onFailure { noteMissing("free_storage", it) }.getOrDefault(0)

        val socModel = if (Build.VERSION.SDK_INT >= 31) {
            runCatching { Build.SOC_MODEL }.getOrNull().orEmpty()
        } else ""
        val socManufacturer = if (Build.VERSION.SDK_INT >= 31) {
            runCatching { Build.SOC_MANUFACTURER }.getOrNull().orEmpty()
        } else ""
        if (socModel.isBlank()) noteMissing("soc_model", null)
        if (socManufacturer.isBlank()) noteMissing("soc_manufacturer", null)

        return BuildCapability(
            manufacturer = Build.MANUFACTURER.orEmpty().ifBlank { "unknown" },
            brand = Build.BRAND.orEmpty().ifBlank { "unknown" },
            model = Build.MODEL.orEmpty().ifBlank { "unknown" },
            device = Build.DEVICE.orEmpty().ifBlank { "unknown" },
            product = Build.PRODUCT.orEmpty().ifBlank { "unknown" },
            hardware = Build.HARDWARE.orEmpty().ifBlank { "unknown" },
            socModel = socModel.ifBlank { "unknown" },
            socManufacturer = socManufacturer.ifBlank { "unknown" },
            androidRelease = Build.VERSION.RELEASE.orEmpty().ifBlank { "unknown" },
            sdkInt = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS?.toList() ?: emptyList(),
            cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            ramTotalMb = ramTotal,
            ramAvailableMb = ramAvail,
            freeStorageMb = freeStorage,
            isEmulator = isEmulator(),
        )
    }

    private fun probeAudio(): AudioCapability {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val devices = AudioDeviceManager(context)
        val inputs = runCatching {
            devices.inputDevices().map { info ->
                AudioInputCapability(
                    id = info.id,
                    name = info.productName?.toString()?.ifBlank { null }
                        ?: devices.typeLabel(info.type),
                    type = info.type,
                    typeLabel = devices.typeLabel(info.type),
                    isExternal = devices.isExternal(info),
                    sampleRates = runCatching { info.sampleRates?.toList() ?: emptyList() }
                        .getOrDefault(emptyList()),
                    channelCounts = runCatching { info.channelCounts?.toList() ?: emptyList() }
                        .getOrDefault(emptyList()),
                )
            }
        }.onFailure { noteMissing("audio_inputs", it) }.getOrDefault(emptyList())

        val nativeRate = runCatching {
            manager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        }.getOrNull() ?: 0
        if (nativeRate <= 0) noteMissing("native_sample_rate", null)

        val unprocessed = runCatching { devices.unprocessedSupported() }.getOrDefault(false)
        val effectTypes = runCatching { AudioEffect.queryEffects()?.map { it.type } ?: emptyList() }
            .getOrDefault(emptyList())
        val agc = effectTypes.any { it == AudioEffect.EFFECT_TYPE_AGC }
        val ns = effectTypes.any { it == AudioEffect.EFFECT_TYPE_NS }
        val aec = effectTypes.any { it == AudioEffect.EFFECT_TYPE_AEC }
        if (!agc && !ns && !aec) {
            warnings += "no AGC/NS/AEC effect descriptors exposed; capture will still try to disable them per session"
        }
        val sco = inputs.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
        if (inputs.none { it.isExternal }) {
            warnings += "no external microphone currently attached; built-in mic will be used"
        }
        return AudioCapability(
            inputs = inputs,
            nativeSampleRate = nativeRate,
            requestedSampleRate = 16000,
            unprocessedSupported = unprocessed,
            agcAvailable = agc,
            nsAvailable = ns,
            aecAvailable = aec,
            bluetoothScoPresent = sco,
        )
    }

    private fun probeTelephony(): TelephonyCapability {
        val pm = context.packageManager
        val hasTelephony = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val phoneState = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        val sms = hasTelephony && runCatching {
            context.getSystemService(Context.TELEPHONY_SERVICE) != null
        }.getOrDefault(false)
        val slots = runCatching { tm?.phoneCount ?: 0 }.getOrDefault(0)
        if (hasTelephony && slots <= 0) noteMissing("sim_slots", null)

        val subs = mutableListOf<SimCapability>()
        if (hasTelephony) {
            runCatching {
                val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                    as? SubscriptionManager
                val defaultData = runCatching {
                    SubscriptionManager.getDefaultDataSubscriptionId()
                }.getOrDefault(-1)
                val defaultSms = runCatching {
                    SubscriptionManager.getDefaultSmsSubscriptionId()
                }.getOrDefault(-1)
                sm?.activeSubscriptionInfoList?.forEach { info ->
                    subs += SimCapability(
                        subscriptionId = info.subscriptionId,
                        slotIndex = info.simSlotIndex,
                        carrier = info.carrierName?.toString().orEmpty(),
                        displayName = info.displayName?.toString().orEmpty(),
                        isDefaultSms = info.subscriptionId == defaultSms,
                        isDefaultData = info.subscriptionId == defaultData,
                    )
                }
            }.onFailure {
                if (it is SecurityException) {
                    warnings += "SIM details need READ_PHONE_STATE; capability recorded without them"
                } else {
                    noteMissing("subscriptions", it)
                }
            }
        }
        val defaultData = runCatching {
            SubscriptionManager.getDefaultDataSubscriptionId()
        }.getOrNull() ?: -1
        val defaultSms = runCatching {
            SubscriptionManager.getDefaultSmsSubscriptionId()
        }.getOrNull() ?: -1

        var networkType = "unknown"
        var signalDbm: Int? = null
        var operator: String? = null
        if (hasTelephony && tm != null) {
            networkType = runCatching {
                when (tm.dataNetworkType) {
                    TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                    TelephonyManager.NETWORK_TYPE_NR -> "NR"
                    TelephonyManager.NETWORK_TYPE_HSPA,
                    TelephonyManager.NETWORK_TYPE_HSDPA,
                    TelephonyManager.NETWORK_TYPE_HSUPA,
                    TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
                    TelephonyManager.NETWORK_TYPE_EDGE,
                    TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
                    TelephonyManager.NETWORK_TYPE_UNKNOWN -> "unknown"
                    else -> "type-${tm.dataNetworkType}"
                }
            }.onFailure {
                if (it is SecurityException) noteMissing("network_type_permission", it)
                else noteMissing("network_type", it)
            }.getOrDefault("unknown")
            signalDbm = runCatching {
                if (Build.VERSION.SDK_INT >= 29) {
                    tm.signalStrength?.cellSignalStrengths?.mapNotNull { it.dbm }?.maxOrNull()
                } else null
            }.onFailure { noteMissing("signal_strength", it) }.getOrNull()
            operator = runCatching { tm.networkOperatorName?.ifBlank { null } }
                .onFailure { noteMissing("operator", it) }.getOrNull()
        }
        return TelephonyCapability(
            hasTelephony = hasTelephony,
            hasSms = sms,
            simSlots = slots.coerceAtLeast(subs.size),
            subscriptions = subs,
            defaultDataSubId = defaultData,
            defaultSmsSubId = defaultSms,
            networkType = networkType,
            signalDbm = signalDbm,
            operator = operator,
            phoneStateGranted = phoneState,
        )
    }

    private fun probeLocation(): LocationCapability {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        val providers = runCatching { lm?.allProviders?.toSet() ?: emptySet() }
            .onFailure { noteMissing("location_providers", it) }.getOrDefault(emptySet())
        val gps = providers.contains(android.location.LocationManager.GPS_PROVIDER) ||
            runCatching { lm?.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) }
                .getOrNull() == true
        val network = providers.contains(android.location.LocationManager.NETWORK_PROVIDER) ||
            runCatching { lm?.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) }
                .getOrNull() == true
        val fused = providers.contains("fused") ||
            runCatching {
                Class.forName("com.google.android.gms.location.FusedLocationProviderClient")
                true
            }.getOrDefault(false)
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (lm == null) noteMissing("location_manager", null)
        if (!fused) {
            warnings += "no fused location provider; platform LocationManager is used (fixed coordinates remain the default)"
        }
        return LocationCapability(
            hasLocationManager = lm != null,
            hasGps = gps,
            hasNetworkProvider = network,
            hasFusedProvider = fused,
            fineGranted = fine,
            coarseGranted = coarse,
        )
    }

    private fun probeBattery(): BatteryCapability {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val percent = runCatching {
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        }.getOrDefault(-1).coerceIn(-1, 100)
        if (percent < 0) noteMissing("battery_percent", null)
        val currentUa = runCatching {
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        }.getOrNull()
        val currentMa = currentUa?.takeIf { it != Int.MIN_VALUE && it != 0 }?.let { it / 1000.0 }
        val sticky = runCatching {
            ContextCompat.registerReceiver(
                context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.getOrNull()
        val tempTenths = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        val temperature = if (tempTenths == Int.MIN_VALUE || tempTenths == 0) null
        else tempTenths / 10.0
        val voltage = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val plugged = when (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "unplugged"
        }
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL || plugged != "unplugged"

        // No public API exposes design capacity. Recording null is honest; the
        // charge-counter property is remaining charge, not capacity.
        val capacityMah: Int? = null
        warnings += "battery design capacity is not exposed by Android; recorded as unknown"

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val thermalApi = Build.VERSION.SDK_INT >= 29 && pm != null
        val thermalStatus = if (thermalApi) {
            runCatching { pm!!.currentThermalStatus }.getOrDefault(0)
        } else 0
        if (!thermalApi) noteMissing("thermal_api", null)

        return BatteryCapability(
            percent = percent,
            charging = charging,
            plugged = plugged,
            temperatureC = temperature,
            currentMa = currentMa,
            voltageMv = voltage,
            capacityMah = capacityMah,
            thermalApiAvailable = thermalApi,
            thermalStatus = thermalStatus,
            thermalLabel = thermalLabel(thermalStatus),
            removable = "unknown",
        )
    }

    private fun probeDisplay(): DisplayCapability {
        val res = context.resources
        val metrics = res.displayMetrics
        val smallest = runCatching { res.configuration.smallestScreenWidthDp }.getOrDefault(0)
        val diagonal = runCatching {
            if (metrics.xdpi > 0 && metrics.ydpi > 0) {
                val w = metrics.widthPixels / metrics.xdpi.toDouble()
                val h = metrics.heightPixels / metrics.ydpi.toDouble()
                sqrt(w * w + h * h)
            } else null
        }.getOrNull()
        return DisplayCapability(
            smallestWidthDp = smallest,
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
            diagonalInches = diagonal,
        )
    }

    private fun thermalLabel(status: Int): String = when (status) {
        0 -> "NONE"
        1 -> "LIGHT"
        2 -> "MODERATE"
        3 -> "SEVERE"
        4 -> "CRITICAL"
        5 -> "EMERGENCY"
        6 -> "SHUTDOWN"
        else -> "unknown($status)"
    }

    private fun appVersionCode(): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(0L)

    private fun appVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("unknown")

    private fun isEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.orEmpty()
        return fingerprint.startsWith("generic") ||
            fingerprint.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.contains("Android SDK", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk", ignoreCase = true) ||
            Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true)
    }

    private fun noteMissing(what: String, error: Throwable?) {
        missing += if (error == null) what else "$what (${error.javaClass.simpleName})"
        FLog.d("capability unavailable: $what ${error?.message ?: ""}")
    }
}

/**
 * Deterministic profile with capabilities deliberately removed. Used by
 * instrumented tests and the "mock missing capabilities" developer toggle so the
 * no-crash contract can be checked without special hardware.
 */
object MockCapabilities {
    fun profile(
        missing: Set<String> = setOf(
            "telephony", "sim_slots", "fused_provider", "thermal_api",
            "soc_model", "battery_capacity", "external_input",
        ),
        manufacturer: String = "Mock",
        model: String = "Capability Test Device",
        sdkInt: Int = 26,
    ): DeviceProfile = DeviceProfile(
        generatedUtc = "1970-01-01T00:00:00+00:00",
        appVersionCode = 0,
        appVersionName = "mock",
        probeSource = "mock",
        build = BuildCapability(
            manufacturer = manufacturer,
            brand = manufacturer,
            model = model,
            device = "mock",
            product = "mock",
            hardware = "mock",
            socModel = if (missing.contains("soc_model")) "unknown" else "Mock SoC",
            socManufacturer = "unknown",
            androidRelease = "8.0",
            sdkInt = sdkInt,
            abis = listOf("armeabi-v7a"),
            cores = 2,
            ramTotalMb = 1500,
            ramAvailableMb = 600,
            freeStorageMb = 2000,
            isEmulator = true,
        ),
        audio = AudioCapability(
            inputs = if (missing.contains("external_input")) listOf(
                AudioInputCapability(
                    id = 1, name = "built-in", type = 15, typeLabel = "built-in mic",
                    isExternal = false, sampleRates = listOf(8000, 16000, 44100, 48000),
                    channelCounts = listOf(1, 2),
                )
            ) else emptyList(),
            nativeSampleRate = 48000,
            requestedSampleRate = 16000,
            unprocessedSupported = false,
            agcAvailable = false,
            nsAvailable = false,
            aecAvailable = false,
            bluetoothScoPresent = false,
        ),
        telephony = TelephonyCapability(
            hasTelephony = !missing.contains("telephony"),
            hasSms = !missing.contains("telephony"),
            simSlots = if (missing.contains("sim_slots")) 0 else 2,
            subscriptions = emptyList(),
            defaultDataSubId = -1,
            defaultSmsSubId = -1,
            networkType = if (missing.contains("telephony")) "unavailable" else "LTE",
            signalDbm = null,
            operator = null,
            phoneStateGranted = false,
        ),
        location = LocationCapability(
            hasLocationManager = true,
            hasGps = !missing.contains("gps"),
            hasNetworkProvider = true,
            hasFusedProvider = !missing.contains("fused_provider"),
            fineGranted = false,
            coarseGranted = false,
        ),
        battery = BatteryCapability(
            percent = 50,
            charging = false,
            plugged = "unplugged",
            temperatureC = if (missing.contains("thermal_api")) null else 30.0,
            currentMa = null,
            voltageMv = 4000,
            capacityMah = if (missing.contains("battery_capacity")) null else 5000,
            thermalApiAvailable = !missing.contains("thermal_api"),
            thermalStatus = 0,
            thermalLabel = "NONE",
            removable = "unknown",
        ),
        display = DisplayCapability(
            smallestWidthDp = 320,
            widthPx = 720,
            heightPx = 1280,
            densityDpi = 320,
            diagonalInches = 5.0,
        ),
        missing = missing.toList(),
        warnings = listOf("mock profile: capabilities intentionally removed"),
    )

    /** A tiny, valid WAV-free PCM buffer used by tests that need a UUID-ish id. */
    fun syntheticId(): String = UUID.nameUUIDFromBytes("forestear-mock".toByteArray()).toString()
}
