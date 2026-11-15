package org.forestear.node.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TierSelectorTest {

    private fun select(
        measured: Double = 100.0,
        budget: Double = 400.0,
        available: Set<String> = TierSelector.ALL_MODELS,
        cores: Int = 8,
        ram: Int = 8000,
        manual: PerfTier? = null,
    ) = TierSelector.select(
        measuredPipelineMs = measured,
        budgetMs = budget,
        availableModels = available,
        cores = cores,
        ramTotalMb = ram,
        manualTier = manual,
    )

    @Test
    fun fastPhoneWithAllModelsGetsTierA() {
        val plan = select(measured = 100.0)
        assertEquals(PerfTier.A, plan.tier)
        assertEquals(TierSelector.ALL_MODELS, plan.enabledModels)
        assertEquals(0.5, plan.hopS, 1e-9)
        assertFalse(plan.streamOnly)
    }

    @Test
    fun withinBudgetGetsTierBWithDutyCycling() {
        val plan = select(measured = 300.0)
        assertEquals(PerfTier.B, plan.tier)
        assertEquals(1.0, plan.hopS, 1e-9)
        assertTrue(plan.m6Enabled)
        assertTrue(plan.m8Enabled)
    }

    @Test
    fun doubleBudgetGetsTierCWithCoreModelsOnly() {
        val plan = select(measured = 700.0)
        assertEquals(PerfTier.C, plan.tier)
        assertEquals(TierSelector.CORE_MODELS, plan.enabledModels)
        assertFalse(plan.m6Enabled)
        assertFalse(plan.m8Enabled)
    }

    @Test
    fun farOverBudgetGetsTierDStreamingOnly() {
        val plan = select(measured = 1200.0)
        assertEquals(PerfTier.D, plan.tier)
        assertTrue(plan.streamOnly)
        assertEquals(2.0, plan.hopS, 1e-9)
    }

    @Test
    fun tierAGuardFallsBackToBOnSmallPhones() {
        val plan = select(measured = 100.0, cores = 2, ram = 2000)
        assertEquals(PerfTier.B, plan.tier)
        assertTrue(plan.reason.contains("cores"))
    }

    @Test
    fun missingModelFallsBackToCoreOnlyTierC() {
        // Tier B is defined as all nine models, so a model that failed to load
        // drops the phone to the core-model tier instead of pretending B applies.
        val plan = select(
            measured = 100.0,
            available = TierSelector.ALL_MODELS - "m8",
        )
        assertEquals(PerfTier.C, plan.tier)
        assertEquals(TierSelector.CORE_MODELS, plan.enabledModels)
        assertTrue(plan.reason.contains("m8"))
    }

    @Test
    fun unmeasuredBenchmarkStartsAtTierC() {
        val plan = select(measured = Double.NaN)
        assertEquals(PerfTier.C, plan.tier)
        assertTrue(plan.reason.contains("benchmark"))
    }

    @Test
    fun manualOverrideWins() {
        val plan = select(measured = 100.0, manual = PerfTier.C)
        assertEquals(PerfTier.C, plan.tier)
        assertTrue(plan.reason.contains("manual override"))
        assertFalse(plan.reason.contains("exceeds"))
    }

    @Test
    fun manualOverrideNotesBudgetViolation() {
        val plan = select(measured = 1200.0, manual = PerfTier.A)
        assertEquals(PerfTier.A, plan.tier)
        assertTrue(plan.reason.contains("exceeds"))
    }

    @Test
    fun tierPlanJsonRoundTrips() {
        val plan = select(measured = 300.0)
        val restored = TierPlan.fromJson(plan.toJson())
        assertEquals(plan.tier, restored.tier)
        assertEquals(plan.enabledModels, restored.enabledModels)
        assertEquals(plan.hopS, restored.hopS, 1e-9)
        assertEquals(plan.m6Every, restored.m6Every)
        assertEquals(plan.m8Duty, restored.m8Duty)
        assertEquals(plan.streamOnly, restored.streamOnly)
        assertEquals(plan.measuredPipelineMs, restored.measuredPipelineMs, 1e-9)
    }

    @Test
    fun tierPlanJsonDefaultsAreConservative() {
        val restored = TierPlan.fromJson(org.json.JSONObject())
        assertEquals(PerfTier.C, restored.tier)
        assertEquals(1.0, restored.hopS, 1e-9)
        assertFalse(restored.streamOnly)
    }
}
