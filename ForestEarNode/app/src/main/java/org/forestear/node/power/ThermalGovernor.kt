package org.forestear.node.power

import android.os.PowerManager
import org.forestear.node.config.AppConfig
import org.forestear.node.settings.SettingsStore
import kotlin.math.max

enum class GovernorLevel(val rank: Int) {
    NORMAL(0), WARM(1), HOT(2), CRITICAL(3), PAUSED(4)
}

data class GovernorOutput(
    val level: GovernorLevel,
    val stride: Int,
    val m8Duty: Int,
    val m6Every: Int,
    val pauseInference: Boolean,
    val reason: String,
    val lowBattery: Boolean,
    val criticalBattery: Boolean,
)

data class GovernorAlert(
    val kind: String,
    val severity: String,
    val message: String,
)

/**
 * Thermal + low-battery governor.
 *
 * Escalation order (matching the requirement):
 *   1. lengthen the analysis hop (stride > 1),
 *   2. drop duty-cycled models (M6 anomaly, M8 BirdNET),
 *   3. pause inference above the hard limit and emit a warning event.
 * Resume is hysteretic: the level only drops after the temperature falls below
 * the previous threshold minus `hysteresis_c`, so a phone sitting at 45 °C does
 * not oscillate between WARM and HOT every heartbeat.
 */
class ThermalGovernor(
    private val config: AppConfig,
    private val settings: SettingsStore,
) {
    private var current = GovernorLevel.NORMAL
    private var lowBattery = false
    private var criticalBattery = false
    private var lastReason = "normal"

    fun update(
        temperatureC: Double,
        thermalStatus: Int,
        batteryPercent: Int,
        charging: Boolean,
    ): Pair<GovernorOutput, List<GovernorAlert>> {
        val alerts = mutableListOf<GovernorAlert>()
        val hyst = config.android.thermal.hysteresisC
        val warm = settings.thermalWarmC
        val hot = settings.thermalHotC
        val critical = settings.thermalCriticalC
        val hardPause = settings.thermalHardPauseC

        val tempRank = when {
            temperatureC.isNaN() -> 0
            temperatureC >= hardPause -> 4
            temperatureC >= critical -> 3
            temperatureC >= hot -> 2
            temperatureC >= warm -> 1
            else -> 0
        }
        val statusRank = when {
            thermalStatus >= PowerManager.THERMAL_STATUS_EMERGENCY -> 4
            thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL -> 3
            thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> 2
            thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> 1
            else -> 0
        }
        val target = max(tempRank, statusRank)

        if (target > current.rank) {
            current = GovernorLevel.values().first { it.rank == target }
        } else if (target < current.rank) {
            while (current.rank > target) {
                val prev = current.rank - 1
                val tempOk = temperatureC.isNaN() || temperatureC <= threshold(prev, warm, hot, critical, hardPause) - hyst
                val statusOk = statusRank <= prev
                if (tempOk && statusOk) {
                    current = GovernorLevel.values().first { it.rank == prev }
                } else break
            }
        }

        val low = !charging && batteryPercent in 0..settings.lowBatteryPct
        val criticalBatt = !charging && batteryPercent in 0..settings.criticalBatteryPct
        if (criticalBatt && !criticalBattery) {
            alerts += GovernorAlert(
                "battery_critical", "CRITICAL",
                "battery critical: $batteryPercent% and not charging"
            )
        } else if (low && !lowBattery && !criticalBatt) {
            alerts += GovernorAlert(
                "battery_low", "WARNING",
                "low battery: $batteryPercent% and not charging"
            )
        }
        lowBattery = low
        criticalBattery = criticalBatt

        // Low battery uses the same levers as heat: longer hop, fewer models.
        var effective = current
        if (criticalBatt && effective.rank < GovernorLevel.HOT.rank) effective = GovernorLevel.HOT
        else if (low && effective.rank < GovernorLevel.WARM.rank) effective = GovernorLevel.WARM

        val t = config.android.thermal
        val output = when (effective) {
            GovernorLevel.NORMAL -> GovernorOutput(
                effective, 1, settings.m8Duty, 1, false,
                if (low) "low battery" else "normal", low, criticalBatt
            )
            GovernorLevel.WARM -> GovernorOutput(
                effective, t.warmStride, t.warmM8Duty.coerceAtLeast(1), t.warmM6Every.coerceAtLeast(1),
                false,
                reasonFor(effective, temperatureC, thermalStatus, low, criticalBatt),
                low, criticalBatt
            )
            GovernorLevel.HOT -> GovernorOutput(
                effective, t.hotStride, t.hotM8Duty, t.hotM6Every,
                false,
                reasonFor(effective, temperatureC, thermalStatus, low, criticalBatt),
                low, criticalBatt
            )
            GovernorLevel.CRITICAL -> GovernorOutput(
                effective, t.hotStride, 0, 0, false,
                reasonFor(effective, temperatureC, thermalStatus, low, criticalBatt),
                low, criticalBatt
            )
            GovernorLevel.PAUSED -> GovernorOutput(
                effective, 1, 0, 0, true,
                reasonFor(effective, temperatureC, thermalStatus, low, criticalBatt),
                low, criticalBatt
            )
        }

        if (effective == GovernorLevel.CRITICAL && lastReason != "critical" && !output.pauseInference) {
            alerts += GovernorAlert("thermal_critical", "WARNING", output.reason)
        }
        if (effective == GovernorLevel.PAUSED && lastReason != "paused") {
            alerts += GovernorAlert("thermal_paused", "CRITICAL", output.reason)
        }
        lastReason = when (effective) {
            GovernorLevel.CRITICAL -> "critical"
            GovernorLevel.PAUSED -> "paused"
            else -> output.reason
        }
        return output to alerts
    }

    private fun threshold(
        rank: Int,
        warm: Double,
        hot: Double,
        critical: Double,
        hardPause: Double,
    ): Double = when (rank) {
        0 -> warm
        1 -> hot
        2 -> critical
        else -> hardPause
    }

    private fun reasonFor(
        level: GovernorLevel,
        tempC: Double,
        status: Int,
        low: Boolean,
        criticalBatt: Boolean,
    ): String {
        val temp = if (tempC.isNaN()) "temp n/a" else String.format(java.util.Locale.US, "%.1f C", tempC)
        val thermal = BatteryMonitor.thermalLabel(status)
        val battery = when {
            criticalBatt -> "battery critical"
            low -> "battery low"
            else -> null
        }
        return listOfNotNull(
            level.name.lowercase(),
            temp,
            "thermal=$thermal",
            battery,
        ).joinToString(", ")
    }
}
