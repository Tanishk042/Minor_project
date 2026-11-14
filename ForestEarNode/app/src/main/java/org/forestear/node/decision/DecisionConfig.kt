package org.forestear.node.decision

import org.json.JSONObject

data class PersistenceSpec(val k: Int, val n: Int, val highConf: Double? = null)

data class DecisionConfig(
    val severity: Map<String, String>,
    val persistence: Map<String, PersistenceSpec>,
    val cooldownS: Map<String, Double>,
    val chainsawSustain: PersistenceSpec,
    val thresholds: MutableMap<String, Double>,
    val vetoEnable: Boolean,
    val vetoWeatherProb: Double,
    val vetoGunshotConf: Double,
) {
    fun withThresholdOverrides(overrides: Map<String, Double>): DecisionConfig =
        copy(thresholds = (thresholds + overrides).toMutableMap())

    companion object {
        fun fromJson(o: JSONObject): DecisionConfig {
            val sev = mutableMapOf<String, String>()
            o.optJSONObject("severity")?.let { s ->
                s.keys().forEach { k -> sev[k] = s.optString(k) }
            }
            val persistence = mutableMapOf<String, PersistenceSpec>()
            o.optJSONObject("persistence")?.let { p ->
                p.keys().forEach { k ->
                    val v = p.getJSONObject(k)
                    val hc = v.optDouble("high_conf", Double.NaN)
                    persistence[k] = PersistenceSpec(
                        v.optInt("k", 1), v.optInt("n", 1),
                        if (hc.isNaN()) null else hc
                    )
                }
            }
            val cooldown = mutableMapOf<String, Double>()
            o.optJSONObject("cooldown_s")?.let { c ->
                c.keys().forEach { k -> cooldown[k] = c.optDouble(k, 0.0) }
            }
            val sustainObj = o.optJSONObject("chainsaw_sustain")
            val sustain = PersistenceSpec(
                sustainObj?.optInt("k", 8) ?: 8,
                sustainObj?.optInt("n", 30) ?: 30
            )
            val thresholds = mutableMapOf<String, Double>()
            o.optJSONObject("thresholds")?.let { th ->
                th.keys().forEach { k -> thresholds[k] = th.optDouble(k, 0.0) }
            }
            val veto = o.optJSONObject("weather_veto")
            return DecisionConfig(
                severity = sev,
                persistence = persistence,
                cooldownS = cooldown,
                chainsawSustain = sustain,
                thresholds = thresholds,
                vetoEnable = veto?.optBoolean("enable", true) ?: true,
                vetoWeatherProb = veto?.optDouble("weather_prob", 0.6) ?: 0.6,
                vetoGunshotConf = veto?.optDouble("gunshot_conf", 0.80) ?: 0.80,
            )
        }
    }
}
