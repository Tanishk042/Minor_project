package org.forestear.node.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyBudgetTest {

    private val hopLevels = listOf(0.5, 1.0, 2.0, 4.0)

    private fun base(tier: PerfTier = PerfTier.B) = TierSelector.select(
        measuredPipelineMs = 300.0,
        budgetMs = 400.0,
        manualTier = tier,
    )

    private fun adjust(
        current: RuntimePlan,
        base: TierPlan,
        measured: Double,
        fast: Int = 0,
        budget: Double = 400.0,
    ) = LatencyBudget.adjust(current, base, measured, budget, hopLevels, fast)

    @Test
    fun initialMirrorsTierPlan() {
        val plan = LatencyBudget.initial(base(PerfTier.B))
        assertEquals(1.0, plan.hopS, 1e-9)
        assertEquals(2, plan.m6Every)
        assertEquals(2, plan.m8Duty)
        assertFalse(plan.streamOnly)
    }

    @Test
    fun overBudgetLengthensHopFirst() {
        val base = base()
        val current = LatencyBudget.initial(base).copy(hopS = 0.5)
        val next = adjust(current, base, measured = 500.0)
        assertEquals(1.0, next.hopS, 1e-9)
        assertEquals(current.m6Every, next.m6Every)
        assertTrue(next.reason.contains("hop"))
    }

    @Test
    fun overBudgetAtMaxHopDropsAnomalyFirst() {
        val base = base()
        val current = LatencyBudget.initial(base).copy(hopS = 4.0, m6Every = 2, m8Duty = 2)
        val next = adjust(current, base, measured = 900.0)
        assertEquals(0, next.m6Every)
        assertEquals(2, next.m8Duty)
        assertTrue(next.reason.contains("M6"))
    }

    @Test
    fun anomalyAlreadyOffDropsBirdnet() {
        val base = base()
        val current = LatencyBudget.initial(base).copy(hopS = 4.0, m6Every = 0, m8Duty = 2)
        val next = adjust(current, base, measured = 900.0)
        assertEquals(0, next.m8Duty)
        assertTrue(next.reason.contains("M8"))
    }

    @Test
    fun everythingDroppedFallsBackToStreamingOnly() {
        val base = base()
        val current = LatencyBudget.initial(base)
            .copy(hopS = 4.0, m6Every = 0, m8Duty = 0)
        val next = adjust(current, base, measured = 900.0)
        assertTrue(next.streamOnly)
        assertTrue(next.reason.contains("streaming-only"))
    }

    @Test
    fun noRelaxBeforeFastWindowRun() {
        val base = base()
        val current = LatencyBudget.initial(base).copy(hopS = 2.0)
        val next = adjust(current, base, measured = 100.0, fast = 11)
        assertEquals(2.0, next.hopS, 1e-9)
    }

    @Test
    fun sustainedFastWindowsRelaxHop() {
        val base = base()
        val current = LatencyBudget.initial(base).copy(hopS = 2.0)
        val next = adjust(current, base, measured = 100.0, fast = 12)
        assertEquals(1.0, next.hopS, 1e-9)
        assertTrue(next.reason.contains("sustained"))
    }

    @Test
    fun relaxationNeverGoesBelowTierBaseline() {
        val base = base()
        val current = LatencyBudget.initial(base).copy(hopS = 1.0)
        val next = adjust(current, base, measured = 100.0, fast = 20)
        assertEquals(1.0, next.hopS, 1e-9)
    }

    @Test
    fun sustainedFastLeavesStreamingOnlyFirst() {
        val base = base()
        val current = LatencyBudget.initial(base)
            .copy(hopS = 4.0, m6Every = 0, m8Duty = 0, streamOnly = true)
        val next = adjust(current, base, measured = 100.0, fast = 12)
        assertFalse(next.streamOnly)
        assertEquals(0, next.m6Every)
    }

    @Test
    fun invalidMeasurementLeavesPlanUntouched() {
        val base = base()
        val current = LatencyBudget.initial(base)
        assertEquals(current, adjust(current, base, measured = Double.NaN))
        assertEquals(current, adjust(current, base, measured = -5.0))
    }
}
