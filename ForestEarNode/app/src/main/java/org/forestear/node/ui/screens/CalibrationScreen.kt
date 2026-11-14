package org.forestear.node.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.forestear.node.audio.AudioDeviceManager
import org.forestear.node.audio.MicLevels
import org.forestear.node.audio.MicProbe
import org.forestear.node.service.NodeState
import org.forestear.node.settings.InputChoice
import org.forestear.node.settings.SettingsStore
import org.forestear.node.ui.ForestAmber
import org.forestear.node.ui.ForestGreen
import org.forestear.node.ui.ForestMuted
import org.forestear.node.ui.ForestRed
import org.forestear.node.ui.InfoBanner
import org.forestear.node.ui.KeyValueRow
import org.forestear.node.ui.Metric
import org.forestear.node.ui.PermissionGroups
import org.forestear.node.ui.PermissionSnapshot
import org.forestear.node.ui.ScoreBar
import org.forestear.node.ui.SectionCard
import org.forestear.node.ui.SettingChips
import org.forestear.node.ui.SettingSlider
import org.forestear.node.ui.StatusPill
import java.util.Locale
import kotlin.math.log10

@Composable
fun CalibrationScreen(
    permissions: PermissionSnapshot,
    onRequest: (Array<String>) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { SettingsStore.get(context) }
    val manager = remember { AudioDeviceManager(context) }
    val status by NodeState.flow.collectAsStateWithLifecycle()

    var deviceEpoch by remember { mutableIntStateOf(0) }
    var choice by remember { mutableStateOf(settings.inputChoice) }
    var levels by remember { mutableStateOf<MicLevels?>(null) }
    var running by remember { mutableStateOf(false) }
    val probeHolder = remember { mutableStateOf<MicProbe?>(null) }

    val deviceList = remember(deviceEpoch) { manager.inputDevices().map { manager.describe(it) } }
    val chosen = remember(deviceEpoch, choice) { manager.choose(choice) }
    val isHeadset = chosen != null && manager.isExternal(chosen)
    var gain by remember(isHeadset) { mutableFloatStateOf(settings.gainFor(isHeadset)) }

    DisposableEffect(Unit) {
        onDispose {
            probeHolder.value?.stop()
            probeHolder.value = null
        }
    }

    fun stopProbe() {
        probeHolder.value?.stop()
        probeHolder.value = null
        running = false
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Input calibration", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Live dBFS, peak, clipping and per-input gain",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = { stopProbe(); onBack() }) { Text("Back") }
        }

        if (status.running) {
            InfoBanner(
                "Monitoring is running and owns the microphone. Android may share it, but " +
                    "levels can be distorted. Stop monitoring for a clean calibration.",
                ForestAmber,
                "Service is running",
            )
        }
        if (!permissions.mic) {
            InfoBanner(
                "Microphone permission is required to probe the input.",
                ForestRed,
                "No permission",
            )
            Button(onClick = { onRequest(PermissionGroups.core()) }, modifier = Modifier.fillMaxWidth()) {
                Text("Grant microphone permission")
            }
        }

        SectionCard(title = "Input device") {
            SettingChips(
                title = "Selection",
                options = listOf(
                    InputChoice.AUTO.name to "Auto (prefer headset)",
                    InputChoice.BUILTIN.name to "Built-in mic",
                    InputChoice.WIRED_HEADSET.name to "Wired TRRS",
                ),
                selected = choice.name,
                onSelect = {
                    choice = InputChoice.valueOf(it)
                    settings.inputChoice = choice
                    stopProbe()
                },
            )
            KeyValueRow(
                "TRRS headset",
                if (manager.isWiredHeadsetConnected()) "detected" else "not detected",
                if (manager.isWiredHeadsetConnected()) ForestGreen else ForestMuted,
            )
            KeyValueRow("Chosen device", chosen?.let { manager.describe(it).name } ?: "platform default")
            KeyValueRow("Raw UNPROCESSED advertised", if (manager.unprocessedSupported()) "yes" else "no / unknown")
            OutlinedButton(
                onClick = { deviceEpoch++ },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Refresh device list") }
        }

        SectionCard(
            title = "Detected inputs",
            subtitle = "A USB-C lavalier shows as USB audio and is preferred by Auto. " +
                "Bluetooth SCO is never selected because it band-limits the signal.",
        ) {
            if (deviceList.isEmpty()) {
                Text("No input devices reported.", fontSize = 13.sp, color = ForestMuted)
            }
            for (d in deviceList) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name, fontSize = 13.sp)
                        Text(
                            "${d.typeLabel} · id ${d.id}" + if (d.isExternal) " · external" else " · internal",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val selected = chosen?.id == d.id
                    StatusPill(
                        if (selected) "IN USE" else if (d.isExternal) "EXTERNAL" else "INTERNAL",
                        if (selected) ForestGreen else ForestMuted,
                    )
                }
            }
        }

        SectionCard(title = "Live level") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("RMS", levels?.let { "${fmt(it.rmsDbfs)} dBFS" } ?: "—")
                Metric("Peak", levels?.let { "${fmt(it.peakDbfs)} dBFS" } ?: "—")
                Metric(
                    "Clip",
                    levels?.let { "${fmt(it.clipRatio * 100, 1)}%" } ?: "—",
                    if (levels?.clipping == true) ForestRed else ForestGreen,
                )
            }
            if (levels != null) {
                ScoreBar(
                    "Level (target roughly -30 to -12 dBFS speech/ambience)",
                    ((levels!!.rmsDbfs + 90.0) / 90.0).toFloat().coerceIn(0f, 1f),
                    when {
                        levels!!.clipping -> ForestRed
                        levels!!.rmsDbfs > -6 -> ForestAmber
                        levels!!.rmsDbfs < -45 -> ForestAmber
                        else -> ForestGreen
                    },
                    trailing = fmt(levels!!.rmsDbfs),
                )
                KeyValueRow("Input", levels!!.input)
                KeyValueRow("Effects", levels!!.effects)
                if (levels!!.clipping) {
                    InfoBanner(
                        "Clipping destroys the spectral features the models expect. Move the " +
                            "mic away, lower the source, or reduce gain.",
                        ForestRed,
                        "Clipping detected",
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (!permissions.mic) {
                            onRequest(PermissionGroups.core())
                        } else if (!running) {
                            val probe = MicProbe(context, settings)
                            probeHolder.value = probe
                            probe.start { l -> levels = l }
                            running = true
                        }
                    },
                    enabled = !running,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ForestGreen,
                        contentColor = Color(0xFF062117),
                    ),
                ) { Text("Start probe") }
                OutlinedButton(onClick = { stopProbe() }, enabled = running) { Text("Stop") }
            }
        }

        SectionCard(
            title = "Per-input gain",
            subtitle = "Applied by the capture path on the next microphone open. " +
                "Raw probe levels above do not include gain; the estimate below does.",
        ) {
            Text(
                if (isHeadset) "Wired/USB external input" else "Built-in microphone",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            SettingSlider(
                title = "Gain",
                value = gain,
                range = 0.25f..8f,
                valueLabel = String.format(Locale.US, "%.2fx", gain),
                onChange = {
                    gain = it
                    settings.setGain(isHeadset, it)
                },
            )
            val adjustedRms = levels?.let { it.rmsDbfs + 20.0 * log10(gain.toDouble()) }
            val adjustedPeak = levels?.let { it.peakDbfs + 20.0 * log10(gain.toDouble()) }
            KeyValueRow("Estimated RMS with gain", adjustedRms?.let { "${fmt(it)} dBFS" } ?: "—")
            KeyValueRow("Estimated peak with gain", adjustedPeak?.let { "${fmt(it)} dBFS" } ?: "—")
            if (adjustedPeak != null && adjustedPeak > -0.5) {
                Text(
                    "Estimated peak would clip: reduce gain before restarting monitoring.",
                    fontSize = 12.sp,
                    color = ForestRed,
                )
            }
            OutlinedButton(
                onClick = { settings.setGain(isHeadset, 1.0f); gain = 1.0f },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Reset gain to 1.0x") }
            Spacer(Modifier.height(2.dp))
            Text(
                "Restart monitoring (Status → Stop → Start) to apply a gain change to the " +
                    "running capture path.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        InfoBanner(
            "Speech is never recorded or uploaded. The calibration probe only computes " +
                "levels in memory and discards the samples.",
            ForestGreen,
            "Privacy",
        )
        Spacer(Modifier.height(8.dp))
    }
}

private fun fmt(v: Double, digits: Int = 1): String =
    String.format(Locale.US, "%.${digits}f", v)
