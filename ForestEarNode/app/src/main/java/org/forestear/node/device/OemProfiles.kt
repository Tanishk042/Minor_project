package org.forestear.node.device

import org.forestear.node.util.AssetSource
import org.json.JSONArray
import org.json.JSONObject

/** One deep-link candidate from the OEM table. */
data class OemIntentSpec(
    val action: String?,
    val component: String?,
    val data: String?,
) {
    /** Expand `{package}` and similar placeholders. */
    fun expand(packageName: String): OemIntentSpec = copy(
        action = action?.replace("{package}", packageName),
        component = component?.replace("{package}", packageName),
        data = data?.replace("{package}", packageName),
    )

    companion object {
        fun fromJson(o: JSONObject): OemIntentSpec = OemIntentSpec(
            action = o.optString("action", "").ifBlank { null },
            component = o.optString("component", "").ifBlank { null },
            data = o.optString("data", "").ifBlank { null },
        )
    }
}

data class OemProfile(
    val id: String,
    val displayName: String,
    val manufacturerKeys: List<String>,
    val dontkillmyappUrl: String,
    val notes: String,
    val steps: Map<String, List<String>>,
    val intents: Map<String, List<OemIntentSpec>>,
) {
    fun stepsFor(step: String): List<String> = steps[step] ?: emptyList()
    fun intentsFor(step: String): List<OemIntentSpec> = intents[step] ?: emptyList()

    companion object {
        val STEP_ORDER = listOf(
            "autostart", "battery", "background_activity", "recents", "overlay",
            "charge_limit", "battery_health",
        )

        fun fromJson(o: JSONObject): OemProfile {
            val steps = mutableMapOf<String, List<String>>()
            o.optJSONObject("steps")?.let { s ->
                s.keys().forEach { key ->
                    val arr = s.optJSONArray(key) ?: JSONArray()
                    steps[key] = (0 until arr.length()).map { arr.optString(it) }
                        .filter { it.isNotBlank() }
                }
            }
            val intents = mutableMapOf<String, List<OemIntentSpec>>()
            o.optJSONObject("intents")?.let { s ->
                s.keys().forEach { key ->
                    val arr = s.optJSONArray(key) ?: JSONArray()
                    intents[key] = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                        .map { OemIntentSpec.fromJson(it) }
                }
            }
            return OemProfile(
                id = o.optString("id", "generic"),
                displayName = o.optString("display_name", "Other Android manufacturer"),
                manufacturerKeys = o.optJSONArray("manufacturer_keys").stringList(),
                dontkillmyappUrl = o.optString("dontkillmyapp_url", "https://dontkillmyapp.com/"),
                notes = o.optString("notes", ""),
                steps = steps,
                intents = intents,
            )
        }
    }
}

data class KnownDevice(
    val modelPattern: String,
    val manufacturer: String,
    val displayName: String,
    val notes: String,
    val tested: Boolean,
) {
    fun matches(model: String): Boolean =
        runCatching { Regex(modelPattern, RegexOption.IGNORE_CASE).containsMatchIn(model) }
            .getOrDefault(false)

    companion object {
        fun fromJson(o: JSONObject): KnownDevice = KnownDevice(
            modelPattern = o.optString("model_pattern", ""),
            manufacturer = o.optString("manufacturer", "generic"),
            displayName = o.optString("display_name", ""),
            notes = o.optString("notes", ""),
            tested = o.optBoolean("tested", false),
        )
    }
}

/**
 * OEM kill-protection table, loaded from `config/oem_profiles.json`.
 *
 * The table is data, not code: adding a maker only requires a JSON entry. The
 * plain-language steps are the source of truth; deep links are best-effort and
 * are always resolved before launch (see `OemIntents`).
 */
class OemProfileTable(
    val profiles: List<OemProfile>,
    val knownDevices: List<KnownDevice>,
    val defaultProfileId: String,
) {
    private val byId = profiles.associateBy { it.id }

    fun forManufacturer(manufacturer: String, brand: String = ""): OemProfile {
        val keys = listOf(manufacturer, brand)
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        for (profile in profiles) {
            if (profile.manufacturerKeys.any { key -> keys.any { it.contains(key) } }) {
                return profile
            }
        }
        return byId[defaultProfileId] ?: profiles.firstOrNull() ?: fallback
    }

    /** Known-device match first, then manufacturer, then the generic profile. */
    fun forDevice(manufacturer: String, brand: String, model: String): OemProfile {
        val known = knownDevices.firstOrNull { it.matches(model) }
        if (known != null) byId[known.manufacturer]?.let { return it }
        return forManufacturer(manufacturer, brand)
    }

    fun knownDevice(model: String): KnownDevice? = knownDevices.firstOrNull { it.matches(model) }

    companion object {
        val fallback = OemProfile(
            id = "generic",
            displayName = "Other Android manufacturer",
            manufacturerKeys = listOf("generic"),
            dontkillmyappUrl = "https://dontkillmyapp.com/",
            notes = "No OEM profile matched; use the standard Android settings.",
            steps = mapOf(
                "battery" to listOf(
                    "Open Settings → Battery → Battery optimisation → All apps → ForestEar Node → Don't optimise."
                ),
                "background_activity" to listOf(
                    "Open Settings → Apps → ForestEar Node → Battery → Unrestricted."
                ),
            ),
            intents = emptyMap(),
        )

        fun load(source: AssetSource): OemProfileTable =
            fromJson(source.readText("config/oem_profiles.json"))

        fun fromJson(text: String): OemProfileTable = fromJson(JSONObject(text))

        fun fromJson(root: JSONObject): OemProfileTable {
            val profiles = mutableListOf<OemProfile>()
            val arr = root.optJSONArray("profiles") ?: JSONArray()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { profiles += OemProfile.fromJson(it) }
            }
            val devices = mutableListOf<KnownDevice>()
            val devArr = root.optJSONArray("known_devices") ?: JSONArray()
            for (i in 0 until devArr.length()) {
                devArr.optJSONObject(i)?.let { devices += KnownDevice.fromJson(it) }
            }
            return OemProfileTable(
                profiles = profiles,
                knownDevices = devices,
                defaultProfileId = root.optString("default_profile", "generic"),
            )
        }
    }
}

private fun JSONArray?.stringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optString(it, "").ifBlank { null } }
}
