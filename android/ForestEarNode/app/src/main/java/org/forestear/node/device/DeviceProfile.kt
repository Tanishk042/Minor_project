package org.forestear.node.device

import org.json.JSONArray
import org.json.JSONObject

/**
 * Capability profile for the phone this app is running on.
 *
 * Every field is best-effort: Android exposes different subsets on different
 * makers and API levels, and a missing capability must never crash the node.
 * Anything the platform would not answer is recorded in [missing] instead of
 * being guessed. The profile is written to `device_profile.json` and a compact
 * summary is attached to every heartbeat.
 */
data class DeviceProfile(
    val schema: Int = SCHEMA,
    val generatedUtc: String,
    val appVersionCode: Long,
    val appVersionName: String,
    val probeSource: String,
    val build: BuildCapability,
    val audio: AudioCapability,
    val telephony: TelephonyCapability,
    val location: LocationCapability,
    val battery: BatteryCapability,
    val display: DisplayCapability,
    val missing: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    /** Compact object for the heartbeat; deliberately smaller than the full file. */
    fun summaryJson(): JSONObject = JSONObject()
        .put("manufacturer", build.manufacturer)
        .put("brand", build.brand)
        .put("model", build.model)
        .put("device", build.device)
        .put("soc", build.socModel)
        .put("android", build.androidRelease)
        .put("sdk", build.sdkInt)
        .put("abis", JSONArray(build.abis))
        .put("cores", build.cores)
        .put("ram_total_mb", build.ramTotalMb)
        .put("ram_available_mb", build.ramAvailableMb)
        .put("free_storage_mb", build.freeStorageMb)
        .put("audio_inputs", audio.inputs.size)
        .put("external_input", audio.inputs.any { it.isExternal })
        .put("native_sample_rate", audio.nativeSampleRate)
        .put("unprocessed", audio.unprocessedSupported)
        .put("effects_available", JSONObject()
            .put("agc", audio.agcAvailable)
            .put("ns", audio.nsAvailable)
            .put("aec", audio.aecAvailable))
        .put("telephony", telephony.hasTelephony)
        .put("sms", telephony.hasSms)
        .put("sim_slots", telephony.simSlots)
        .put("default_sms_sub", telephony.defaultSmsSubId)
        .put("location", JSONObject()
            .put("manager", location.hasLocationManager)
            .put("gps", location.hasGps)
            .put("network", location.hasNetworkProvider)
            .put("fused", location.hasFusedProvider))
        .put("battery_capacity_mah", battery.capacityMah ?: JSONObject.NULL)
        .put("thermal_api", battery.thermalApiAvailable)
        .put("missing", JSONArray(missing))
        .put("warnings", JSONArray(warnings))

    fun toJson(): JSONObject = JSONObject()
        .put("schema", schema)
        .put("generated_utc", generatedUtc)
        .put("app_version_code", appVersionCode)
        .put("app_version_name", appVersionName)
        .put("probe_source", probeSource)
        .put("build", build.toJson())
        .put("audio", audio.toJson())
        .put("telephony", telephony.toJson())
        .put("location", location.toJson())
        .put("battery", battery.toJson())
        .put("display", display.toJson())
        .put("missing", JSONArray(missing))
        .put("warnings", JSONArray(warnings))

    fun toJsonText(): String = toJson().toString(2)

    /** One-line human summary used by the wizard and diagnostics screen. */
    fun headline(): String =
        "${build.manufacturer} ${build.model} · Android ${build.androidRelease} (API ${build.sdkInt}) · " +
            "${build.cores} cores · ${build.ramTotalMb} MB RAM · ${build.abis.joinToString("/")}"

    /**
     * Identity used to decide whether a stored performance tier still applies.
     * A tier is re-measured when the app version, the model or the API changes.
     */
    fun profileKey(): String = "$appVersionCode:${build.model}:${build.sdkInt}"

    companion object {
        const val SCHEMA = 1

        fun fromJson(text: String): DeviceProfile = fromJson(JSONObject(text))

        fun fromJson(root: JSONObject): DeviceProfile = DeviceProfile(
            schema = root.optInt("schema", SCHEMA),
            generatedUtc = root.optString("generated_utc", ""),
            appVersionCode = root.optLong("app_version_code", 0L),
            appVersionName = root.optString("app_version_name", ""),
            probeSource = root.optString("probe_source", "unknown"),
            build = BuildCapability.fromJson(root.optJSONObject("build") ?: JSONObject()),
            audio = AudioCapability.fromJson(root.optJSONObject("audio") ?: JSONObject()),
            telephony = TelephonyCapability.fromJson(root.optJSONObject("telephony") ?: JSONObject()),
            location = LocationCapability.fromJson(root.optJSONObject("location") ?: JSONObject()),
            battery = BatteryCapability.fromJson(root.optJSONObject("battery") ?: JSONObject()),
            display = DisplayCapability.fromJson(root.optJSONObject("display") ?: JSONObject()),
            missing = root.optJSONArray("missing").strings(),
            warnings = root.optJSONArray("warnings").strings(),
        )
    }
}

data class BuildCapability(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val product: String,
    val hardware: String,
    val socModel: String,
    val socManufacturer: String,
    val androidRelease: String,
    val sdkInt: Int,
    val abis: List<String>,
    val cores: Int,
    val ramTotalMb: Int,
    val ramAvailableMb: Int,
    val freeStorageMb: Int,
    val isEmulator: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("manufacturer", manufacturer)
        .put("brand", brand)
        .put("model", model)
        .put("device", device)
        .put("product", product)
        .put("hardware", hardware)
        .put("soc_model", socModel)
        .put("soc_manufacturer", socManufacturer)
        .put("android_release", androidRelease)
        .put("sdk_int", sdkInt)
        .put("abis", JSONArray(abis))
        .put("cores", cores)
        .put("ram_total_mb", ramTotalMb)
        .put("ram_available_mb", ramAvailableMb)
        .put("free_storage_mb", freeStorageMb)
        .put("is_emulator", isEmulator)

    companion object {
        fun fromJson(o: JSONObject): BuildCapability = BuildCapability(
            manufacturer = o.optString("manufacturer", "unknown"),
            brand = o.optString("brand", "unknown"),
            model = o.optString("model", "unknown"),
            device = o.optString("device", "unknown"),
            product = o.optString("product", "unknown"),
            hardware = o.optString("hardware", "unknown"),
            socModel = o.optString("soc_model", "unknown"),
            socManufacturer = o.optString("soc_manufacturer", "unknown"),
            androidRelease = o.optString("android_release", "unknown"),
            sdkInt = o.optInt("sdk_int", 0),
            abis = o.optJSONArray("abis").strings(),
            cores = o.optInt("cores", 0),
            ramTotalMb = o.optInt("ram_total_mb", 0),
            ramAvailableMb = o.optInt("ram_available_mb", 0),
            freeStorageMb = o.optInt("free_storage_mb", 0),
            isEmulator = o.optBoolean("is_emulator", false),
        )
    }
}

data class AudioInputCapability(
    val id: Int,
    val name: String,
    val type: Int,
    val typeLabel: String,
    val isExternal: Boolean,
    val sampleRates: List<Int>,
    val channelCounts: List<Int>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("type", type)
        .put("type_label", typeLabel)
        .put("external", isExternal)
        .put("sample_rates", JSONArray(sampleRates))
        .put("channel_counts", JSONArray(channelCounts))

    companion object {
        fun fromJson(o: JSONObject): AudioInputCapability = AudioInputCapability(
            id = o.optInt("id", -1),
            name = o.optString("name", "unknown"),
            type = o.optInt("type", 0),
            typeLabel = o.optString("type_label", "unknown"),
            isExternal = o.optBoolean("external", false),
            sampleRates = o.optJSONArray("sample_rates").ints(),
            channelCounts = o.optJSONArray("channel_counts").ints(),
        )
    }
}

data class AudioCapability(
    val inputs: List<AudioInputCapability>,
    val nativeSampleRate: Int,
    val requestedSampleRate: Int,
    val unprocessedSupported: Boolean,
    val agcAvailable: Boolean,
    val nsAvailable: Boolean,
    val aecAvailable: Boolean,
    val bluetoothScoPresent: Boolean,
) {
    fun toJson(): JSONObject {
        val arr = JSONArray()
        inputs.forEach { arr.put(it.toJson()) }
        return JSONObject()
            .put("inputs", arr)
            .put("native_sample_rate", nativeSampleRate)
            .put("requested_sample_rate", requestedSampleRate)
            .put("unprocessed_supported", unprocessedSupported)
            .put("agc_available", agcAvailable)
            .put("ns_available", nsAvailable)
            .put("aec_available", aecAvailable)
            .put("bluetooth_sco_present", bluetoothScoPresent)
    }

    companion object {
        fun fromJson(o: JSONObject): AudioCapability {
            val inputs = mutableListOf<AudioInputCapability>()
            val arr = o.optJSONArray("inputs") ?: JSONArray()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { inputs += AudioInputCapability.fromJson(it) }
            }
            return AudioCapability(
                inputs = inputs,
                nativeSampleRate = o.optInt("native_sample_rate", 16000),
                requestedSampleRate = o.optInt("requested_sample_rate", 16000),
                unprocessedSupported = o.optBoolean("unprocessed_supported", false),
                agcAvailable = o.optBoolean("agc_available", false),
                nsAvailable = o.optBoolean("ns_available", false),
                aecAvailable = o.optBoolean("aec_available", false),
                bluetoothScoPresent = o.optBoolean("bluetooth_sco_present", false),
            )
        }
    }
}

data class SimCapability(
    val subscriptionId: Int,
    val slotIndex: Int,
    val carrier: String,
    val displayName: String,
    val isDefaultSms: Boolean,
    val isDefaultData: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("subscription_id", subscriptionId)
        .put("slot_index", slotIndex)
        .put("carrier", carrier)
        .put("display_name", displayName)
        .put("default_sms", isDefaultSms)
        .put("default_data", isDefaultData)

    companion object {
        fun fromJson(o: JSONObject): SimCapability = SimCapability(
            subscriptionId = o.optInt("subscription_id", -1),
            slotIndex = o.optInt("slot_index", -1),
            carrier = o.optString("carrier", ""),
            displayName = o.optString("display_name", ""),
            isDefaultSms = o.optBoolean("default_sms", false),
            isDefaultData = o.optBoolean("default_data", false),
        )
    }
}

data class TelephonyCapability(
    val hasTelephony: Boolean,
    val hasSms: Boolean,
    val simSlots: Int,
    val subscriptions: List<SimCapability>,
    val defaultDataSubId: Int,
    val defaultSmsSubId: Int,
    val networkType: String,
    val signalDbm: Int?,
    val operator: String?,
    val phoneStateGranted: Boolean,
) {
    fun toJson(): JSONObject {
        val arr = JSONArray()
        subscriptions.forEach { arr.put(it.toJson()) }
        return JSONObject()
            .put("has_telephony", hasTelephony)
            .put("has_sms", hasSms)
            .put("sim_slots", simSlots)
            .put("subscriptions", arr)
            .put("default_data_sub_id", defaultDataSubId)
            .put("default_sms_sub_id", defaultSmsSubId)
            .put("network_type", networkType)
            .put("signal_dbm", signalDbm ?: JSONObject.NULL)
            .put("operator", operator ?: JSONObject.NULL)
            .put("phone_state_granted", phoneStateGranted)
    }

    companion object {
        fun fromJson(o: JSONObject): TelephonyCapability {
            val subs = mutableListOf<SimCapability>()
            val arr = o.optJSONArray("subscriptions") ?: JSONArray()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { subs += SimCapability.fromJson(it) }
            }
            return TelephonyCapability(
                hasTelephony = o.optBoolean("has_telephony", false),
                hasSms = o.optBoolean("has_sms", false),
                simSlots = o.optInt("sim_slots", 0),
                subscriptions = subs,
                defaultDataSubId = o.optInt("default_data_sub_id", -1),
                defaultSmsSubId = o.optInt("default_sms_sub_id", -1),
                networkType = o.optString("network_type", "unknown"),
                signalDbm = if (o.isNull("signal_dbm")) null else o.optInt("signal_dbm"),
                // Android's org.json.optString() returns the literal "null"
                // for JSONObject.NULL (newer desktop org.json returns the
                // fallback). Without the isNull guard a missing operator
                // silently becomes the string "null" on every save/load.
                operator = if (o.isNull("operator")) null
                else o.optString("operator", "").ifBlank { null },
                phoneStateGranted = o.optBoolean("phone_state_granted", false),
            )
        }
    }
}

data class LocationCapability(
    val hasLocationManager: Boolean,
    val hasGps: Boolean,
    val hasNetworkProvider: Boolean,
    val hasFusedProvider: Boolean,
    val fineGranted: Boolean,
    val coarseGranted: Boolean,
    val fixedCoordsAlwaysAllowed: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("location_manager", hasLocationManager)
        .put("gps", hasGps)
        .put("network_provider", hasNetworkProvider)
        .put("fused_provider", hasFusedProvider)
        .put("fine_granted", fineGranted)
        .put("coarse_granted", coarseGranted)
        .put("fixed_coords_always_allowed", fixedCoordsAlwaysAllowed)

    companion object {
        fun fromJson(o: JSONObject): LocationCapability = LocationCapability(
            hasLocationManager = o.optBoolean("location_manager", false),
            hasGps = o.optBoolean("gps", false),
            hasNetworkProvider = o.optBoolean("network_provider", false),
            hasFusedProvider = o.optBoolean("fused_provider", false),
            fineGranted = o.optBoolean("fine_granted", false),
            coarseGranted = o.optBoolean("coarse_granted", false),
            fixedCoordsAlwaysAllowed = o.optBoolean("fixed_coords_always_allowed", true),
        )
    }
}

data class BatteryCapability(
    val percent: Int,
    val charging: Boolean,
    val plugged: String,
    val temperatureC: Double?,
    val currentMa: Double?,
    val voltageMv: Int,
    val capacityMah: Int?,
    val thermalApiAvailable: Boolean,
    val thermalStatus: Int,
    val thermalLabel: String,
    /** "unknown" is the honest answer for sealed and for most removable packs. */
    val removable: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("percent", percent)
        .put("charging", charging)
        .put("plugged", plugged)
        .put("temperature_c", temperatureC ?: JSONObject.NULL)
        .put("current_ma", currentMa ?: JSONObject.NULL)
        .put("voltage_mv", voltageMv)
        .put("capacity_mah", capacityMah ?: JSONObject.NULL)
        .put("thermal_api", thermalApiAvailable)
        .put("thermal_status", thermalStatus)
        .put("thermal_label", thermalLabel)
        .put("removable", removable)

    companion object {
        fun fromJson(o: JSONObject): BatteryCapability = BatteryCapability(
            percent = o.optInt("percent", -1),
            charging = o.optBoolean("charging", false),
            plugged = o.optString("plugged", "unknown"),
            temperatureC = if (o.isNull("temperature_c")) null else o.optDouble("temperature_c"),
            currentMa = if (o.isNull("current_ma")) null else o.optDouble("current_ma"),
            voltageMv = o.optInt("voltage_mv", -1),
            capacityMah = if (o.isNull("capacity_mah")) null else o.optInt("capacity_mah"),
            thermalApiAvailable = o.optBoolean("thermal_api", false),
            thermalStatus = o.optInt("thermal_status", 0),
            thermalLabel = o.optString("thermal_label", "unknown"),
            removable = o.optString("removable", "unknown"),
        )
    }
}

data class DisplayCapability(
    val smallestWidthDp: Int,
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val diagonalInches: Double?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("smallest_width_dp", smallestWidthDp)
        .put("width_px", widthPx)
        .put("height_px", heightPx)
        .put("density_dpi", densityDpi)
        .put("diagonal_inches", diagonalInches ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): DisplayCapability = DisplayCapability(
            smallestWidthDp = o.optInt("smallest_width_dp", 0),
            widthPx = o.optInt("width_px", 0),
            heightPx = o.optInt("height_px", 0),
            densityDpi = o.optInt("density_dpi", 0),
            diagonalInches = if (o.isNull("diagonal_inches")) null else o.optDouble("diagonal_inches"),
        )
    }
}

private fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optString(it, "").ifBlank { null } }
}

private fun JSONArray?.ints(): List<Int> {
    if (this == null) return emptyList()
    return (0 until length()).map { optInt(it) }
}
