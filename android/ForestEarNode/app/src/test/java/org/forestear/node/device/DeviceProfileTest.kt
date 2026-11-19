package org.forestear.node.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {

    @Test
    fun jsonRoundTripPreservesNullBatteryFields() {
        val mock = MockCapabilities.profile()
        assertNull(mock.battery.temperatureC)
        assertNull(mock.battery.capacityMah)

        val restored = DeviceProfile.fromJson(mock.toJson())

        assertEquals(mock, restored)
        assertNull(restored.battery.temperatureC)
        assertNull(restored.battery.capacityMah)
        assertNull(restored.telephony.signalDbm)
        assertNull(restored.telephony.operator)
        assertEquals(5.0, restored.display.diagonalInches!!, 0.0)
    }

    @Test
    fun jsonTextRoundTripIsValidJson() {
        val mock = MockCapabilities.profile()
        val text = mock.toJsonText()
        val parsed = JSONObject(text)
        assertEquals(DeviceProfile.SCHEMA, parsed.getInt("schema"))
        assertEquals(mock, DeviceProfile.fromJson(text))
    }

    @Test
    fun headlineSummarisesTheHandset() {
        val headline = MockCapabilities.profile().headline()
        assertTrue(headline.contains("Mock Capability Test Device"))
        assertTrue(headline.contains("Android 8.0"))
        assertTrue(headline.contains("API 26"))
        assertTrue(headline.contains("2 cores"))
        assertTrue(headline.contains("1500 MB RAM"))
        assertTrue(headline.contains("armeabi-v7a"))
    }

    @Test
    fun profileKeyTracksVersionModelAndApi() {
        val profile = MockCapabilities.profile()
        assertEquals("0:Capability Test Device:26", profile.profileKey())

        val updated = profile.copy(appVersionCode = 7, build = profile.build.copy(sdkInt = 34))
        assertEquals("7:Capability Test Device:34", updated.profileKey())
    }

    @Test
    fun summaryJsonCarriesTheHeartbeatFields() {
        val summary = MockCapabilities.profile().summaryJson()

        assertEquals("Mock", summary.getString("manufacturer"))
        assertEquals("Capability Test Device", summary.getString("model"))
        assertEquals("unknown", summary.getString("soc"))
        assertEquals(26, summary.getInt("sdk"))
        assertEquals(2, summary.getInt("cores"))
        assertEquals(1500, summary.getInt("ram_total_mb"))
        assertEquals(600, summary.getInt("ram_available_mb"))
        assertEquals(2000, summary.getInt("free_storage_mb"))
        assertEquals(1, summary.getInt("audio_inputs"))
        assertFalse(summary.getBoolean("external_input"))
        assertEquals(48000, summary.getInt("native_sample_rate"))
        assertFalse(summary.getBoolean("unprocessed"))
        assertTrue(summary.isNull("battery_capacity_mah"))
        assertFalse(summary.getBoolean("thermal_api"))

        val effects = summary.getJSONObject("effects_available")
        assertFalse(effects.getBoolean("agc"))
        assertFalse(effects.getBoolean("ns"))
        assertFalse(effects.getBoolean("aec"))

        val location = summary.getJSONObject("location")
        assertTrue(location.getBoolean("manager"))
        assertTrue(location.getBoolean("gps"))
        assertFalse(location.getBoolean("fused"))

        assertEquals(7, summary.getJSONArray("missing").length())
        assertEquals(1, summary.getJSONArray("warnings").length())
    }

    @Test
    fun mockProfileRemovesTheAdvertisedCapabilities() {
        val profile = MockCapabilities.profile()
        assertEquals(
            setOf(
                "telephony", "sim_slots", "fused_provider", "thermal_api",
                "soc_model", "battery_capacity", "external_input",
            ),
            profile.missing.toSet(),
        )
        assertEquals(1, profile.audio.inputs.size)
        assertEquals("built-in", profile.audio.inputs.single().name)
        assertFalse(profile.audio.inputs.single().isExternal)
        assertFalse(profile.telephony.hasTelephony)
        assertEquals(0, profile.telephony.simSlots)
        assertFalse(profile.location.hasFusedProvider)
        assertFalse(profile.battery.thermalApiAvailable)
    }

    @Test
    fun mockProfileKeepsCapabilitiesWhenNotListedAsMissing() {
        val profile = MockCapabilities.profile(missing = setOf("external_input"))
        assertTrue(profile.telephony.hasTelephony)
        assertTrue(profile.telephony.hasSms)
        assertEquals(2, profile.telephony.simSlots)
        assertTrue(profile.battery.thermalApiAvailable)
        assertEquals(5000, profile.battery.capacityMah)
        assertEquals("Mock SoC", profile.build.socModel)
        assertEquals(listOf("external_input"), profile.missing)
    }

    @Test
    fun emptyJsonUsesConservativeDefaults() {
        val profile = DeviceProfile.fromJson(JSONObject())

        assertEquals(DeviceProfile.SCHEMA, profile.schema)
        assertEquals("unknown", profile.probeSource)
        assertEquals("unknown", profile.build.manufacturer)
        assertEquals("unknown", profile.build.model)
        assertEquals(0, profile.build.sdkInt)
        assertEquals(0, profile.build.cores)
        assertTrue(profile.build.abis.isEmpty())

        assertEquals(16000, profile.audio.nativeSampleRate)
        assertEquals(16000, profile.audio.requestedSampleRate)
        assertTrue(profile.audio.inputs.isEmpty())

        assertFalse(profile.telephony.hasTelephony)
        assertEquals("unknown", profile.telephony.networkType)
        assertNull(profile.telephony.signalDbm)
        assertNull(profile.telephony.operator)

        assertFalse(profile.location.hasLocationManager)
        assertTrue(profile.location.fixedCoordsAlwaysAllowed)

        assertEquals(-1, profile.battery.percent)
        assertNull(profile.battery.temperatureC)
        assertNull(profile.battery.capacityMah)
        assertEquals("unknown", profile.battery.thermalLabel)
        assertEquals("unknown", profile.battery.removable)

        assertEquals(0, profile.display.smallestWidthDp)
        assertNull(profile.display.diagonalInches)

        assertTrue(profile.missing.isEmpty())
        assertTrue(profile.warnings.isEmpty())
    }

    @Test
    fun unknownCapabilitiesNeverCrashTheHeadline() {
        val profile = DeviceProfile.fromJson(JSONObject())
        val headline = profile.headline()
        assertTrue(headline.contains("unknown unknown"))
        assertTrue(headline.contains("API 0"))
        assertEquals("0:unknown:0", profile.profileKey())
    }
}
