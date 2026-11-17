package org.forestear.node

import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.forestear.node.device.CompatibilityReport
import org.forestear.node.device.DeviceProbe
import org.forestear.node.device.DeviceProfile
import org.forestear.node.device.DeviceProfileStore
import org.forestear.node.device.MockCapabilities
import org.forestear.node.device.OemProfileTable
import org.forestear.node.device.RecoveryLog
import org.forestear.node.service.ControlReceiver
import org.forestear.node.util.AndroidAssetSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Device Adaptation Layer on real hardware: the probe must never throw, a
 * deliberately incomplete profile must persist and round-trip, the shipped OEM
 * table must load, the report must keep unexercised recovery paths as NOT RUN,
 * and the recovery recorder must work without the service process.
 *
 * This is instrumented because the probe and the asset loader use Android APIs.
 * It is written but has **not** been run in this repository (no device).
 */
@RunWith(AndroidJUnit4::class)
class DeviceAdaptationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun realProbeNeverThrowsAndReportsWhatItFound() {
        val profile = DeviceProbe(context).probe()

        assertEquals(DeviceProfile.SCHEMA, profile.schema)
        assertTrue("generatedUtc must be set", profile.generatedUtc.isNotBlank())
        assertEquals(Build.VERSION.SDK_INT, profile.build.sdkInt)
        assertEquals(Build.MODEL, profile.build.model)
        assertTrue("a running device must report at least one core", profile.build.cores >= 1)
        assertTrue("probeSource must be set", profile.probeSource.isNotBlank())

        // A capability may be missing, but it must be recorded, not guessed.
        for (item in profile.missing) assertTrue(item.isNotBlank())
        val summary = profile.summaryJson()
        assertEquals(Build.MODEL, summary.getString("model"))
        assertEquals(Build.VERSION.SDK_INT, summary.getInt("sdk"))
    }

    @Test
    fun mockProfilePersistsAndRoundTripsWithoutTouchingRealProfile() {
        val store = DeviceProfileStore(context)
        val original = store.load()
        try {
            val mock = store.current(force = true, mock = true)
            assertEquals(MockCapabilities.profile(), mock)
            assertTrue("profile file should exist", File(store.path()).exists())

            val reloaded = DeviceProfileStore(context).load()
            assertNotNull(reloaded)
            assertEquals(mock, reloaded)
            // Android's org.json.optString() turns JSONObject.NULL into the
            // literal "null" unless the isNull guard is present. This used to
            // make the two profiles unequal while printing identically.
            assertNull(reloaded!!.telephony.operator)
            assertNull(reloaded.telephony.signalDbm)
            assertNull(reloaded.battery.temperatureC)
            assertNull(reloaded.battery.capacityMah)
            assertTrue(reloaded.missing.contains("telephony"))
        } finally {
            if (original != null) store.save(original) else File(store.path()).delete()
        }
    }

    @Test
    fun shippedOemTableLoadsOnDevice() {
        val text = AndroidAssetSource(context).readText("config/oem_profiles.json")
        val table = OemProfileTable.fromJson(text)

        assertTrue("expected at least 10 OEM profiles", table.profiles.size >= 10)
        assertEquals("generic", table.defaultProfileId)
        assertNotNull(table.knownDevice("CPH2381"))
        assertEquals("oneplus", table.forDevice("OnePlus", "OnePlus", "CPH2381").id)
        assertTrue(table.forManufacturer("Xiaomi").stepsFor("autostart").isNotEmpty())
    }

    @Test
    fun reportKeepsUnexercisedRecoveryPathsAsNotRun() {
        val md = CompatibilityReport.build(
            profile = MockCapabilities.profile(),
            tier = null,
            checks = emptyList(),
            recovery = emptyList(),
        )
        assertTrue(md.contains("NOT RUN"))
        assertFalse(
            "an unexercised path must never print PASS",
            md.lineSequence()
                .filter { it.contains("Phone rebooted") }
                .any { it.contains("PASS") },
        )
        assertTrue(md.contains("## Missing capabilities"))
    }

    @Test
    fun recoveryRecorderWorksWithoutTheService() {
        val log = RecoveryLog(context)
        log.clear()
        try {
            val receiver = ControlReceiver()
            receiver.onReceive(
                context,
                Intent(context, ControlReceiver::class.java)
                    .setAction(ControlReceiver.ACTION_RECORD_RECOVERY)
                    .putExtra(ControlReceiver.EXTRA_PATH, "kill_service")
                    .putExtra(ControlReceiver.EXTRA_OUTCOME, "pass")
                    .putExtra(ControlReceiver.EXTRA_DETAIL, "instrumented test"),
            )

            val recorded = RecoveryLog(context).load().single { it.path == "kill_service" }
            assertEquals("PASS", recorded.outcome)
            assertEquals("instrumented test", recorded.detail)

            // An invalid outcome must be ignored rather than stored.
            receiver.onReceive(
                context,
                Intent(context, ControlReceiver::class.java)
                    .setAction(ControlReceiver.ACTION_RECORD_RECOVERY)
                    .putExtra(ControlReceiver.EXTRA_PATH, "swipe_away")
                    .putExtra(ControlReceiver.EXTRA_OUTCOME, "probably fine"),
            )
            assertTrue(RecoveryLog(context).load().none { it.path == "swipe_away" })

            receiver.onReceive(
                context,
                Intent(context, ControlReceiver::class.java)
                    .setAction(ControlReceiver.ACTION_CLEAR_RECOVERY),
            )
            assertTrue(RecoveryLog(context).load().isEmpty())
        } finally {
            log.clear()
        }
    }
}
