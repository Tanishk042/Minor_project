package org.forestear.node.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The report is the honest record of what was measured on a handset. These
 * tests pin the two rules that matter: an unmeasured tier is stated as such, and
 * a recovery path that was never exercised can never appear as PASS.
 */
class CompatibilityReportTest {

    private val profile = MockCapabilities.profile()

    @Test
    fun unmeasuredTierIsStatedExplicitly() {
        val md = CompatibilityReport.build(
            profile = profile,
            tier = null,
            checks = emptyList(),
            recovery = emptyList(),
        )
        assertTrue(md.contains("## Performance tier"))
        assertTrue(md.contains("No tier has been selected yet"))
        assertTrue(md.contains("Run Diagnostics"))
    }

    @Test
    fun unexercisedRecoveryPathsAreNotRun() {
        val md = CompatibilityReport.build(
            profile = profile,
            tier = null,
            checks = emptyList(),
            recovery = emptyList(),
        )
        assertTrue(row(md, "Service killed by the system / adb").contains("NOT RUN"))
        assertTrue(row(md, "Phone rebooted").contains("NOT RUN"))
        assertTrue(md.contains("not exercised on this handset"))
    }

    @Test
    fun recordedRecoveryOutcomeIsPrintedVerbatim() {
        val md = CompatibilityReport.build(
            profile = profile,
            tier = null,
            checks = emptyList(),
            recovery = listOf(
                RecoveryResult(
                    path = "kill_service",
                    outcome = "PASS",
                    detail = "restarted by the 5-minute alarm",
                    recordedUtc = "2026-11-15T10:00:00Z",
                ),
                RecoveryResult(
                    path = "swipe_away",
                    outcome = "FAIL",
                    detail = "service stayed dead until the app was opened",
                    recordedUtc = "2026-11-15T10:05:00Z",
                ),
            ),
        )
        val killed = row(md, "Service killed by the system / adb")
        assertTrue(killed.contains("PASS"))
        assertTrue(killed.contains("restarted by the 5-minute alarm"))
        assertTrue(killed.contains("2026-11-15T10:00:00Z"))

        val swiped = row(md, "Task swiped from Recents")
        assertTrue(swiped.contains("FAIL"))
        assertFalse(row(md, "Phone rebooted").contains("PASS"))
    }

    @Test
    fun extraRecoveryPathsAreAppended() {
        val md = CompatibilityReport.build(
            profile = profile,
            tier = null,
            checks = emptyList(),
            recovery = listOf(
                RecoveryResult("custom_doze_test", "PASS", "survived 6 h", "2026-11-15T12:00:00Z"),
            ),
        )
        assertTrue(md.contains("custom_doze_test"))
        assertTrue(md.contains("survived 6 h"))
    }

    @Test
    fun checksTableEscapesPipesAndNewlines() {
        val md = CompatibilityReport.build(
            profile = profile,
            tier = null,
            checks = listOf(
                ReportCheck("mic", "Microphone", "PASS", "level -22 dBFS"),
                ReportCheck("net", "Network", "FAIL", "down | bad\nline"),
            ),
            recovery = emptyList(),
        )
        assertTrue(md.contains("| Microphone | PASS | level -22 dBFS |"))
        assertTrue(md.contains("| Network | FAIL | down \\| bad line |"))
        assertFalse(md.contains("down | bad"))
    }

    @Test
    fun measuredTierAndBenchmarkArePrinted() {
        val plan = TierSelector.select(
            measuredPipelineMs = 100.0,
            budgetMs = 400.0,
            cores = 8,
            ramTotalMb = 8000,
        )
        val md = CompatibilityReport.build(
            profile = profile,
            tier = plan,
            checks = emptyList(),
            recovery = emptyList(),
            benchmarkMs = mapOf("m1" to 12.5, "m9" to 0.4),
        )
        assertEquals(PerfTier.A, plan.tier)
        assertTrue(md.contains("- Selected tier: **A**"))
        assertTrue(md.contains("Measured pipeline:"))
        assertTrue(md.contains("| m1 |"))
        assertTrue(md.contains("| m9 |"))
        assertTrue(md.contains("m1, m2, m3, m4, m5, m6, m7, m8, m9"))
    }

    @Test
    fun missingCapabilitiesAreListedNotGuessed() {
        val md = CompatibilityReport.build(
            profile = profile,
            tier = null,
            checks = emptyList(),
            recovery = emptyList(),
        )
        assertTrue(md.contains("## Missing capabilities"))
        for (item in profile.missing) {
            assertTrue("missing section should list $item", md.contains("- $item"))
        }
        assertTrue(md.contains("## What this report does not claim"))
        assertTrue(md.contains("unknown is recorded as unknown"))
    }

    @Test
    fun oemProfileSectionIsOptional() {
        val without = CompatibilityReport.build(profile, null, emptyList(), emptyList())
        assertFalse(without.contains("## OEM profile"))

        val with = CompatibilityReport.build(
            profile = profile,
            tier = null,
            checks = emptyList(),
            recovery = emptyList(),
            oemProfile = OemProfileTable.fallback,
        )
        assertTrue(with.contains("## OEM profile"))
        assertTrue(with.contains("Other Android manufacturer"))
    }

    @Test
    fun fileNamesAreSluggedPerModel() {
        assertEquals(
            "compatibility_report_mock_capability_test_device.md",
            CompatibilityReport.fileName(profile),
        )
        assertEquals("oneplus_nord_ce_2_lite_5g", CompatibilityReport.slug("OnePlus Nord CE 2 Lite 5G"))
        assertEquals("cph2381", CompatibilityReport.slug("  CPH2381  "))
        assertEquals("unknown_device", CompatibilityReport.slug("***"))
        assertEquals("unknown_device", CompatibilityReport.slug(""))
    }

    private fun row(markdown: String, title: String): String =
        markdown.lineSequence().first { it.contains(title) }
}
