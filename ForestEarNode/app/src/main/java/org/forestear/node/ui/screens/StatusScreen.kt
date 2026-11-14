package org.forestear.node.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.forestear.node.decision.Decision
import org.forestear.node.service.NodeStatus
import org.forestear.node.ui.ForestAmber
import org.forestear.node.ui.ForestGreen
import org.forestear.node.ui.ForestMuted
import org.forestear.node.ui.ForestRed
import org.forestear.node.ui.InfoBanner
import org.forestear.node.ui.KeyValueRow
import org.forestear.node.ui.Metric
import org.forestear.node.ui.PermissionSnapshot
import org.forestear.node.ui.ScoreBar
import org.forestear.node.ui.SectionCard
import org.forestear.node.ui.Spectrogram
import org.forestear.node.ui.StatusPill
import org.forestear.node.util.TimeUtils
import java.util.Locale

@Composable
fun StatusScreen(
    status: NodeStatus,
    permissions: PermissionSnapshot,
    frames: Int,
    mels: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRequestMic: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!permissions.mic) {
            InfoBanner(
                "The service cannot listen without RECORD_AUDIO. Grant it, then start monitoring.",
                ForestRed,
                "Microphone permission missing",
            )
        }
        val missing = status.protection?.missing.orEmpty()
        if (missing.isNotEmpty()) {
            InfoBanner(
                "OxygenOS may throttle or kill this node. Missing: " +
                    missing.joinToString { it.title } + ". Open Setup to fix.",
                ForestAmber,
                "Kill protection incomplete",
            )
            OutlinedButton(onClick = onOpenSetup, modifier = Modifier.fillMaxWidth()) {
                Text("Open setup checklist")
            }
        }

        HeroCard(status, onStart, onStop, onRequestMic, permissions)

        SectionCard(
            title = "Live spectrogram",
            subtitle = "64 mels x 98 frames, last analysed 1.0 s window (0.5 s hop).",
        ) {
            Spectrogram(
                values = status.lastWindow?.logMel,
                frames = frames,
                mels = mels,
            )
            Text(
                if (status.lastWindow == null) "No analysed window yet." else
                    "RMS ${fmt(status.lastWindow.rmsDb, 1)} dBFS  |  " +
                        "total ${fmt(status.lastWindow.inferenceMs["total"] ?: 0.0, 1)} ms  |  " +
                        "stride ${status.adaptiveStride}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "Fusion scores (M9)", subtitle = "Calibrated per-class probabilities.") {
            val m9 = status.lastWindow?.m9.orEmpty()
            for (cls in listOf("chainsaw", "gunshot", "vehicle", "human", "benign")) {
                ScoreBar(
                    label = cls,
                    value = m9[cls] ?: 0f,
                    color = classColor(cls),
                )
            }
        }

        SectionCard(title = "Base models") {
            val w = status.lastWindow
            if (w == null) {
                Text("Waiting for the first inference hop.", fontSize = 13.sp, color = ForestMuted)
            } else {
                ScoreBar("M2 gunshot CNN", w.m2, classColor("gunshot"))
                ScoreBar("M3 chainsaw head", w.m3, classColor("chainsaw"))
                ScoreBar("M4 vehicle head", w.m4, classColor("vehicle"))
                ScoreBar("M7 human head", w.m7, classColor("human"))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Metric("M6 anomaly z", fmt(w.m6Z, 2), if (w.m6Z >= 3) ForestAmber else ForestMuted)
                    Metric("M5 rain", fmt(w.m5["rain"] ?: 0f, 2))
                    Metric("wind", fmt(w.m5["wind"] ?: 0f, 2))
                    Metric("thunder", fmt(w.m5["thunder"] ?: 0f, 2))
                }
                val top = w.m1Scores.entries.sortedByDescending { it.value }.take(3)
                Text(
                    "YAMNet top: " + top.joinToString { "${it.key} ${fmt(it.value, 2)}" },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val birds = w.m8Species.orEmpty()
                Text(
                    if (birds.isEmpty()) "BirdNET: no species above 0.03 this hop (duty-cycled)."
                    else "BirdNET: " + birds.joinToString { "${it.species} ${fmt(it.confidence, 2)}" },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard(title = "Runtime") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("Uptime", TimeUtils.humanDuration(status.uptimeMs / 1000))
                Metric("Restarts", status.restartCount.toString())
                Metric("Audio restarts", status.audioRestartCount.toString())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("Dropped frames", status.droppedFrames.toString())
                Metric("Inference", "${fmt(status.inferenceMs, 1)} ms")
                Metric("Stride", status.adaptiveStride.toString())
            }
            KeyValueRow("Input", status.activeInput)
            KeyValueRow("Effects", status.effects.ifBlank { "n/a" })
            KeyValueRow("Last heartbeat", if (status.lastHeartbeatMs == 0L) "never" else
                TimeUtils.isoUtc(status.lastHeartbeatMs).take(19) + "Z")
            if (status.lastError != null) {
                KeyValueRow("Last error", status.lastError, ForestRed)
            }
        }

        SectionCard(title = "Power and thermal") {
            val b = status.battery
            if (b == null) {
                Text("Waiting for battery snapshot.", fontSize = 13.sp, color = ForestMuted)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("Battery", "${b.percent}%", if (b.percent <= 20 && !b.charging) ForestRed else ForestGreen)
                    Metric("Temp", if (b.temperatureValid) "${fmt(b.temperatureC, 1)} C" else "n/a")
                    Metric("Current", b.currentMa?.let { "${fmt(it, 0)} mA" } ?: "n/a")
                }
                KeyValueRow("Charging", if (b.charging) "yes (${b.plugged})" else "no")
                KeyValueRow("Thermal status", b.thermalLabel)
                KeyValueRow(
                    "Governor",
                    "${status.governorLevel} — ${status.governorReason}",
                    when (status.governorLevel) {
                        "NORMAL" -> ForestGreen
                        "WARM" -> ForestAmber
                        else -> ForestRed
                    },
                )
            }
        }

        SectionCard(title = "Transport") {
            KeyValueRow("Backend", if (status.backendOnline) "online" else "offline / queued",
                if (status.backendOnline) ForestGreen else ForestAmber)
            KeyValueRow("Offline queue", "${status.queueSize} event(s)")
            KeyValueRow("Alerts sent", status.alertsSent.toString())
            KeyValueRow("SMS", status.smsStatus)
            KeyValueRow("Signal", "${status.networkType}  ${status.signalDbm?.let { "$it dBm" } ?: "dBm n/a"}")
            KeyValueRow("Streaming", if (status.streaming) "connected" else "off")
            KeyValueRow("Collection", status.collection?.let {
                if (it.active) "active, ${it.chunks} chunk(s)" else "off"
            } ?: "off")
        }

        SectionCard(title = "Last event") {
            Text(
                status.lastEvent ?: "No alert has fired since the service started.",
                fontSize = 13.sp,
                color = if (status.lastEvent != null) ForestAmber else ForestMuted,
            )
            val decisions = status.lastDecisions.filter { it.fired || it.suppressedBy != null }
            if (decisions.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                for (d in decisions) DecisionRow(d)
            }
        }

        Text(
            "All inference happens on this phone. Network use is limited to JSON alerts," +
                " heartbeats and optional raw PCM streaming when you enable it.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HeroCard(
    status: NodeStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRequestMic: () -> Unit,
    permissions: PermissionSnapshot,
) {
    SectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ForestEar Node", style = MaterialTheme.typography.titleLarge)
                Text(
                    when {
                        status.starting -> "starting models…"
                        status.running && status.modelLoaded -> "monitoring"
                        status.running -> "service running, models not loaded"
                        else -> "stopped"
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(
                if (status.running) "LIVE" else "IDLE",
                if (status.running) ForestGreen else ForestMuted,
            )
        }
        Spacer(Modifier.height(6.dp))
        val threat = status.threatScore
        val threatColor = when {
            threat >= 0.8f -> ForestRed
            threat >= 0.5f -> ForestAmber
            else -> ForestGreen
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                fmt(threat * 100f, 0),
                fontSize = 46.sp,
                fontWeight = FontWeight.Bold,
                color = threatColor,
            )
            Text(" /100 threat", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp))
            Spacer(Modifier.weight(1f))
            StatusPill(
                when {
                    threat >= 0.8f -> "HIGH"
                    threat >= 0.5f -> "ELEVATED"
                    else -> "LOW"
                },
                threatColor,
            )
        }
        ScoreBar(
            "max non-benign M9 probability",
            threat,
            threatColor,
            trailing = fmt(threat, 3),
        )
        Spacer(Modifier.height(6.dp))
        if (status.running) {
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ForestRed.copy(alpha = 0.85f),
                    contentColor = Color(0xFF2A0808),
                ),
            ) { Text("Stop monitoring") }
        } else if (!permissions.mic) {
            Button(onClick = onRequestMic, modifier = Modifier.fillMaxWidth()) {
                Text("Grant microphone permission")
            }
        } else {
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ForestGreen,
                    contentColor = Color(0xFF062117),
                ),
            ) { Text("Start monitoring") }
        }
        Text(
            "Android keeps a persistent notification while monitoring. Stopping here also " +
                "disables auto-start so the watchdog will not restart it.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DecisionRow(d: Decision) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StatusPill(
            (d.severity ?: "SUPPRESSED"),
            when (d.severity) {
                "CRITICAL" -> ForestRed
                "WARNING" -> ForestAmber
                "INFO" -> ForestGreen
                else -> ForestMuted
            },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "${d.cls}  ${fmt(d.confidence, 2)}" + if (d.escalated) "  escalated" else "",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        if (d.suppressedBy != null) {
            Text(d.suppressedBy, fontSize = 11.sp, color = ForestMuted)
        } else {
            Text(d.kOfN, fontSize = 11.sp, color = ForestMuted)
        }
    }
}

private fun classColor(cls: String): Color = when (cls) {
    "gunshot" -> ForestRed
    "chainsaw" -> ForestAmber
    "vehicle" -> Color(0xFF56CCF2)
    "human" -> Color(0xFFBB6BD9)
    else -> ForestGreen
}

private fun fmt(v: Number, digits: Int): String =
    String.format(Locale.US, "%.${digits}f", v.toDouble())
