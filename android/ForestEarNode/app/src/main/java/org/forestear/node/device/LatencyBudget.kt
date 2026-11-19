package org.forestear.node.device

/**
 * Runtime latency governor, separate from the thermal governor.
 *
 * The thermal governor reacts to temperature and battery; this one reacts to the
 * measured pipeline time. Escalation order matches the requirement:
 *   1. lengthen the analysis hop,
 *   2. drop duty-cycled models (anomaly M6, then BirdNET M8),
 *   3. fall back to streaming-only mode.
 * Recovery is hysteretic: the service only asks for an upgrade after a run of
 * fast windows, so a single scheduling spike does not oscillate the plan.
 *
 * Pure Kotlin: unit-tested on the JVM.
 */
data class RuntimePlan(
    val hopS: Double,
    val m6Every: Int,
    val m8Duty: Int,
    val streamOnly: Boolean,
    val reason: String,
)

object LatencyBudget {
    /** Number of consecutive fast windows before the plan is relaxed. */
    const val FAST_WINDOWS_TO_RELAX = 12

    fun initial(base: TierPlan): RuntimePlan = RuntimePlan(
        hopS = base.hopS,
        m6Every = if (base.m6Enabled) base.m6Every else 0,
        m8Duty = if (base.m8Enabled) base.m8Duty else 0,
        streamOnly = base.streamOnly,
        reason = "tier ${base.tier.name} baseline",
    )

    /**
     * Apply one measured window. Returns the (possibly) new plan. The caller
     * passes [consecutiveFast] so recovery can be hysteretic; the counter is
     * managed by the service.
     */
    fun adjust(
        current: RuntimePlan,
        base: TierPlan,
        measuredMs: Double,
        budgetMs: Double,
        hopLevels: List<Double>,
        consecutiveFast: Int,
    ): RuntimePlan {
        if (!measuredMs.isFinite() || measuredMs <= 0) return current
        val budget = if (budgetMs.isFinite() && budgetMs > 0) budgetMs else 400.0
        val levels = (hopLevels + base.hopS).filter { it > 0 }.distinct().sorted()
        val baseHop = base.hopS

        if (measuredMs > budget) {
            // 1. Lengthen the hop.
            val nextHop = levels.firstOrNull { it > current.hopS + 1e-6 }
            if (nextHop != null) {
                return current.copy(
                    hopS = nextHop,
                    reason = "measured %.0f ms > %.0f ms budget: hop %.1fs -> %.1fs"
                        .format(measuredMs, budget, current.hopS, nextHop),
                )
            }
            // 2. Drop duty-cycled models.
            if (current.m6Every > 0) {
                return current.copy(
                    m6Every = 0,
                    reason = "measured %.0f ms over budget at max hop: anomaly M6 disabled"
                        .format(measuredMs),
                )
            }
            if (current.m8Duty > 0) {
                return current.copy(
                    m8Duty = 0,
                    reason = "measured %.0f ms over budget at max hop: BirdNET M8 disabled"
                        .format(measuredMs),
                )
            }
            // 3. Streaming-only fallback.
            if (!current.streamOnly) {
                return current.copy(
                    streamOnly = true,
                    reason = "measured %.0f ms over budget: streaming-only fallback"
                        .format(measuredMs),
                )
            }
            return current
        }

        if (measuredMs < budget * 0.5 && consecutiveFast >= FAST_WINDOWS_TO_RELAX) {
            // Relax in the reverse order.
            if (current.streamOnly) {
                return current.copy(
                    streamOnly = false,
                    reason = "%.0f ms for %d windows: leaving streaming-only mode"
                        .format(measuredMs, consecutiveFast),
                )
            }
            if (base.m8Enabled && current.m8Duty == 0) {
                return current.copy(
                    m8Duty = base.m8Duty,
                    reason = "%.0f ms sustained: BirdNET re-enabled".format(measuredMs),
                )
            }
            if (base.m6Enabled && current.m6Every == 0) {
                return current.copy(
                    m6Every = base.m6Every,
                    reason = "%.0f ms sustained: anomaly M6 re-enabled".format(measuredMs),
                )
            }
            val previousHop = levels.lastOrNull { it < current.hopS - 1e-6 }
            if (previousHop != null && previousHop >= baseHop - 1e-6) {
                return current.copy(
                    hopS = previousHop,
                    reason = "%.0f ms sustained: hop %.1fs -> %.1fs"
                        .format(measuredMs, current.hopS, previousHop),
                )
            }
        }
        return current
    }
}
