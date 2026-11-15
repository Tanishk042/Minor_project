package org.forestear.node.device

/**
 * Performance tiers. The phone is benchmarked once per app version / device and
 * the tier is chosen automatically; the user may override it in Settings.
 *
 *  A — all nine models, 0.5 s hop
 *  B — all nine models, 1.0 s hop, anomaly + BirdNET duty-cycled
 *  C — core models only (YAMNet, gunshot, chainsaw, weather, fusion), 1–2 s hop
 *  D — core models with a long hop plus raw streaming to a laptop/server
 */
enum class PerfTier {
    A, B, C, D;

    companion object {
        fun fromName(value: String?): PerfTier? = when (value?.trim()?.uppercase()) {
            "A" -> A
            "B" -> B
            "C" -> C
            "D" -> D
            else -> null
        }
    }
}

data class TierPlan(
    val tier: PerfTier,
    val enabledModels: Set<String>,
    val hopS: Double,
    val m6Every: Int,
    val m8Duty: Int,
    val streamOnly: Boolean,
    val gpuModels: Set<String>,
    val reason: String,
    val measuredPipelineMs: Double,
    val budgetMs: Double,
) {
    val usesAllModels: Boolean get() = enabledModels == TierSelector.ALL_MODELS
    val m6Enabled: Boolean get() = m6Every > 0 && enabledModels.contains("m6")
    val m7Enabled: Boolean get() = enabledModels.contains("m7")
    val m8Enabled: Boolean get() = m8Duty > 0 && enabledModels.contains("m8")

    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("tier", tier.name)
        .put("enabled_models", org.json.JSONArray(enabledModels.sorted()))
        .put("hop_s", hopS)
        .put("m6_every", m6Every)
        .put("m8_duty", m8Duty)
        .put("stream_only", streamOnly)
        .put("gpu_models", org.json.JSONArray(gpuModels.sorted()))
        .put("reason", reason)
        .put("measured_pipeline_ms", measuredPipelineMs)
        .put("budget_ms", budgetMs)

    companion object {
        fun fromJson(o: org.json.JSONObject): TierPlan = TierPlan(
            tier = PerfTier.fromName(o.optString("tier", "C")) ?: PerfTier.C,
            enabledModels = o.optJSONArray("enabled_models").stringsOrEmpty().toSet(),
            hopS = o.optDouble("hop_s", 1.0),
            m6Every = o.optInt("m6_every", 0),
            m8Duty = o.optInt("m8_duty", 0),
            streamOnly = o.optBoolean("stream_only", false),
            gpuModels = o.optJSONArray("gpu_models").stringsOrEmpty().toSet(),
            reason = o.optString("reason", ""),
            measuredPipelineMs = o.optDouble("measured_pipeline_ms", Double.NaN),
            budgetMs = o.optDouble("budget_ms", 400.0),
        )
    }
}

/**
 * Pure tier-selection policy so it can be unit-tested on the JVM without a phone.
 * No Android types are referenced here.
 */
object TierSelector {
    val ALL_MODELS: Set<String> = setOf("m1", "m2", "m3", "m4", "m5", "m6", "m7", "m8", "m9")
    val CORE_MODELS: Set<String> = setOf("m1", "m2", "m3", "m4", "m5", "m9")

    /**
     * @param measuredPipelineMs mean end-to-end pipeline time for one window, or
     *   [Double.NaN]/non-finite when the benchmark could not run.
     * @param budgetMs the configured per-window latency budget.
     * @param availableModels models that actually loaded on this phone.
     * @param gpuModels models for which the GPU delegate was both faster and stable.
     * @param manualTier user override; null means automatic.
     */
    fun select(
        measuredPipelineMs: Double,
        budgetMs: Double,
        availableModels: Set<String> = ALL_MODELS,
        gpuModels: Set<String> = emptySet(),
        cores: Int = 4,
        ramTotalMb: Int = 3000,
        manualTier: PerfTier? = null,
    ): TierPlan {
        val budget = if (budgetMs.isFinite() && budgetMs > 0) budgetMs else 400.0
        val measured = if (measuredPipelineMs.isFinite() && measuredPipelineMs > 0) {
            measuredPipelineMs
        } else Double.NaN

        if (manualTier != null) {
            val plan = planFor(manualTier, measured, budget, availableModels, gpuModels)
            val over = measured.isFinite() && measured > budget * 1.5
            return plan.copy(
                reason = "manual override: ${manualTier.name}" +
                    if (over) " (measured %.0f ms exceeds the %.0f ms budget; override kept)"
                        .format(measured, budget) else ""
            )
        }

        if (!measured.isFinite()) {
            return planFor(PerfTier.C, measured, budget, availableModels, gpuModels).copy(
                reason = "benchmark did not produce a finite timing; starting conservatively at tier C"
            )
        }

        val ratio = measured / budget
        val hardwareOk = cores >= 4 && ramTotalMb >= 2500
        val allModelsOk = availableModels.containsAll(ALL_MODELS)
        val tier = when {
            ratio <= 0.60 && hardwareOk && allModelsOk -> PerfTier.A
            ratio <= 1.00 && allModelsOk -> PerfTier.B
            ratio <= 2.00 -> PerfTier.C
            else -> PerfTier.D
        }
        val plan = planFor(tier, measured, budget, availableModels, gpuModels)
        // Explain the constraint that stopped a higher tier from being chosen.
        // The hardware note is only relevant when the timing itself allowed A;
        // the model note is always relevant because B also requires all models.
        val hardwareNote = if (!hardwareOk && ratio <= 0.60) {
            "tier A needs >=4 cores and >=2500 MB RAM (this phone reports $cores cores / $ramTotalMb MB)"
        } else {
            ""
        }
        val modelsNote = if (!allModelsOk) {
            "not all models loaded; missing ${(ALL_MODELS - availableModels).sorted()}"
        } else {
            ""
        }
        val guard = listOf(hardwareNote, modelsNote)
            .filter { it.isNotBlank() }
            .joinToString("; ")
        return plan.copy(
            reason = buildString {
                append("measured %.1f ms / %.0f ms budget = %.2f×".format(measured, budget, ratio))
                if (guard.isNotBlank()) append("; ").append(guard)
            }
        )
    }

    private fun planFor(
        tier: PerfTier,
        measured: Double,
        budget: Double,
        available: Set<String>,
        gpuModels: Set<String>,
    ): TierPlan {
        val all = ALL_MODELS.intersect(available)
        val core = CORE_MODELS.intersect(available)
        return when (tier) {
            PerfTier.A -> TierPlan(
                tier = PerfTier.A, enabledModels = all, hopS = 0.5,
                m6Every = 1, m8Duty = 10, streamOnly = false,
                gpuModels = gpuModels, reason = "", measuredPipelineMs = measured, budgetMs = budget,
            )
            PerfTier.B -> TierPlan(
                tier = PerfTier.B, enabledModels = all, hopS = 1.0,
                m6Every = 2, m8Duty = 2, streamOnly = false,
                gpuModels = gpuModels, reason = "", measuredPipelineMs = measured, budgetMs = budget,
            )
            PerfTier.C -> TierPlan(
                tier = PerfTier.C, enabledModels = core, hopS = 1.0,
                m6Every = 0, m8Duty = 0, streamOnly = false,
                gpuModels = gpuModels, reason = "", measuredPipelineMs = measured, budgetMs = budget,
            )
            PerfTier.D -> TierPlan(
                tier = PerfTier.D, enabledModels = core, hopS = 2.0,
                m6Every = 0, m8Duty = 0, streamOnly = true,
                gpuModels = gpuModels, reason = "", measuredPipelineMs = measured, budgetMs = budget,
            )
        }
    }
}

private fun org.json.JSONArray?.stringsOrEmpty(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optString(it, "").ifBlank { null } }
}
