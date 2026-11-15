package org.forestear.node.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The OEM table is data, not code, so these tests check the loader and the
 * matching rules (known device -> manufacturer -> generic) rather than any
 * particular vendor's screen names.
 */
class OemProfilesTest {

    private val inline = """
        {
          "default_profile": "generic",
          "profiles": [
            {
              "id": "acme",
              "display_name": "Acme",
              "manufacturer_keys": ["acme"],
              "dontkillmyapp_url": "https://dontkillmyapp.com/acme",
              "notes": "acme notes",
              "steps": {
                "battery": ["step one", "step two"],
                "autostart": ["launch"]
              },
              "intents": {
                "battery": [
                  {"action": "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
                   "data": "package:{package}"}
                ]
              }
            },
            {
              "id": "globex",
              "display_name": "Globex",
              "manufacturer_keys": ["globex"],
              "steps": {},
              "intents": {}
            },
            {
              "id": "generic",
              "display_name": "Generic",
              "manufacturer_keys": ["generic"],
              "steps": {"battery": ["generic battery step"]},
              "intents": {}
            }
          ],
          "known_devices": [
            {
              "model_pattern": "ACME-1|ACME-2",
              "manufacturer": "globex",
              "display_name": "Acme Known",
              "notes": "tested elsewhere",
              "tested": true
            }
          ]
        }
    """.trimIndent()

    private fun table(): OemProfileTable = OemProfileTable.fromJson(inline)

    @Test
    fun parsesProfilesAndKnownDevices() {
        val table = table()
        assertEquals(3, table.profiles.size)
        assertEquals("generic", table.defaultProfileId)
        assertEquals(1, table.knownDevices.size)

        val known = table.knownDevices.single()
        assertEquals("globex", known.manufacturer)
        assertTrue(known.tested)
        assertTrue(known.matches("ACME-2"))
        assertTrue(known.matches("acme-1 build"))
        assertTrue(!known.matches("ACME-3"))
    }

    @Test
    fun matchesManufacturerIgnoringCase() {
        assertEquals("acme", table().forManufacturer("ACME").id)
        assertEquals("acme", table().forManufacturer("Acme Corporation").id)
    }

    @Test
    fun matchesBrandWhenManufacturerIsGeneric() {
        // Some Chinese ROMs report the real maker in BRAND but "unknown" in
        // MANUFACTURER, so the brand string has to be matched too.
        assertEquals("globex", table().forManufacturer("Foo", "Globex Corp").id)
    }

    @Test
    fun knownDeviceTakesPrecedenceOverManufacturer() {
        // The known-device entry deliberately points at a different profile than
        // the reported manufacturer, which is how a rebranded handset is handled.
        val profile = table().forDevice("Acme", "Acme", "ACME-2")
        assertEquals("globex", profile.id)
        assertNotNull(table().knownDevice("ACME-2"))
    }

    @Test
    fun fallsBackToDefaultProfileForUnknownMaker() {
        val profile = table().forDevice("Unknown", "Unknown", "ZZZ-9")
        assertEquals("generic", profile.id)
        assertEquals(listOf("generic battery step"), profile.stepsFor("battery"))
    }

    @Test
    fun emptyTableReturnsDocumentedFallback() {
        val table = OemProfileTable(emptyList(), emptyList(), "generic")
        val profile = table.forManufacturer("Acme")
        assertEquals("generic", profile.id)
        assertEquals("Other Android manufacturer", profile.displayName)
        assertTrue(profile.stepsFor("battery").isNotEmpty())
        assertTrue(profile.intentsFor("battery").isEmpty())
    }

    @Test
    fun stepsAndIntentsDefaultToEmptyLists() {
        val profile = table().profiles.first { it.id == "globex" }
        assertEquals(emptyList<String>(), profile.stepsFor("autostart"))
        assertEquals(emptyList<OemIntentSpec>(), profile.intentsFor("battery"))
        assertEquals(emptyList<String>(), OemProfileTable.fallback.stepsFor("autostart"))
    }

    @Test
    fun expandsPackagePlaceholder() {
        val profile = table().profiles.first { it.id == "acme" }
        val spec = profile.intentsFor("battery").single()
        val expanded = spec.expand("org.forestear.node")
        assertEquals("package:org.forestear.node", expanded.data)
        assertEquals("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", expanded.action)
        assertNull(expanded.component)

        val component = OemIntentSpec(
            action = "com.example.{package}.ACTION",
            component = "com.example.{package}/.Main",
            data = null,
        ).expand("org.forestear.node")
        assertEquals("com.example.org.forestear.node.ACTION", component.action)
        assertEquals("com.example.org.forestear.node/.Main", component.component)
        assertNull(component.data)
    }

    @Test
    fun shippedTableCoversCommonMakers() {
        val table = shippedTable()
        assertTrue("expected at least 10 OEM profiles", table.profiles.size >= 10)

        val ids = table.profiles.map { it.id }
        assertTrue(ids.contains("oneplus"))
        assertTrue(ids.contains("xiaomi"))
        assertTrue(ids.contains("samsung"))

        assertEquals("oneplus", table.forManufacturer("OnePlus", "OnePlus").id)
        assertEquals("oneplus", table.forManufacturer("OPPO", "OPPO").id)
        assertEquals("oneplus", table.forManufacturer("realme", "realme").id)
        assertEquals("xiaomi", table.forManufacturer("Xiaomi", "Redmi").id)
        assertEquals("samsung", table.forManufacturer("samsung", "samsung").id)
        assertEquals("generic", table.forDevice("Acme", "Acme", "Unknown Model").id)
    }

    @Test
    fun shippedTableKnowsTheNordCe2Lite() {
        val table = shippedTable()
        val known = table.knownDevice("CPH2381")
        assertNotNull("Nord CE 2 Lite CPH2381 should be a known device", known)
        assertEquals("oneplus", known!!.manufacturer)
        assertEquals("oneplus", table.forDevice("OnePlus", "OnePlus", "CPH2381").id)
        assertEquals("oneplus", table.forDevice("OnePlus", "OnePlus", "Nord CE 2 Lite 5G").id)
    }

    @Test
    fun shippedTableStepsAreNonEmptyForEveryRequiredStep() {
        val table = shippedTable()
        val required = listOf("battery", "background_activity", "autostart")
        for (profile in table.profiles) {
            if (profile.id == "generic") continue
            for (step in required) {
                assertTrue(
                    "${profile.id} is missing plain-language steps for '$step'",
                    profile.stepsFor(step).isNotEmpty(),
                )
            }
        }
    }

    private fun shippedTable(): OemProfileTable {
        val candidates = listOf(
            "src/main/assets/config/oem_profiles.json",
            "app/src/main/assets/config/oem_profiles.json",
            "../app/src/main/assets/config/oem_profiles.json",
        )
        val file = candidates.map(::File).firstOrNull { it.exists() }
        assertNotNull(
            "shipped oem_profiles.json not found from ${System.getProperty("user.dir")}",
            file,
        )
        return OemProfileTable.fromJson(file!!.readText())
    }
}
