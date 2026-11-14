package org.forestear.node.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.forestear.node.audio.CollectionRecorder
import org.forestear.node.config.AppConfig
import org.forestear.node.diagnostics.SoakTracker
import org.forestear.node.power.BatteryCsvLogger
import org.forestear.node.service.NodeService
import org.forestear.node.service.NodeStatus
import org.forestear.node.service.ProtectionAction
import org.forestear.node.service.ProtectionChecker
import org.forestear.node.service.WatchdogAlarmReceiver
import org.forestear.node.service.WatchdogWorker
import org.forestear.node.settings.SettingsStore
import org.forestear.node.transport.OfflineQueue
import org.forestear.node.ui.ForestAmber
import org.forestear.node.ui.ForestGreen
import org.forestear.node.ui.ForestMuted
import org.forestear.node.ui.ForestRed
import org.forestear.node.ui.InfoBanner
import org.forestear.node.ui.KeyValueRow
import org.forestear.node.ui.SectionCard
import org.forestear.node.ui.SettingSwitch
import org.forestear.node.ui.SettingTextField
import org.forestear.node.ui.StatusPill
import org.forestear.node.util.AndroidAssetSource
import org.forestear.node.util.TimeUtils
import java.io.File
import java.util.Locale

@Composable
fun ToolsScreen(
    status: NodeStatus,
    onOpenSetup: () -> Unit,
    onOpenCalibration: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { SettingsStore.get(context) }
    val config = remember { runCatching { AppConfig.load(AndroidAssetSource(context)) }.getOrNull() }
    val checker = remember { ProtectionChecker(context, settings) }
    val soak = remember { SoakTracker(context, settings) }
    val queue = remember { OfflineQueue(context) }
    val batteryCsv = remember { BatteryCsvLogger(context) }

    var protection by remember { mutableStateOf(checker.check()) }
    var collectionEnabled by remember { mutableStateOf(settings.collectionEnabled) }
    var streamingEnabled by remember { mutableStateOf(settings.streamingEnabled) }
    var tick by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }

    // Live soak figures come from SharedPreferences, so poll rather than guess.
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            tick++
        }
    }

    val soakReport = remember(tick, status.soak) {
        if (soak.isActive()) soak.report() else null
    }
    val collectionStats = status.collection
    val logsDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
    val collectionDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "collection")
    val collectionFiles = remember(tick, collectionStats) {
        collectionDir.listFiles { f -> f.isFile && f.name.endsWith(".wav") }?.size ?: 0
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Tools", style = MaterialTheme.typography.titleLarge)
        Text(
            "Operational controls that are not part of day-to-day monitoring. Everything " +
                "here writes only to this phone unless you explicitly enable streaming.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!status.running) {
            InfoBanner(
                "Collection, streaming and the soak test are owned by the foreground " +
                    "service. Start monitoring from Status before switching them on; the " +
                    "settings below are saved either way.",
                ForestAmber,
                "Service is stopped",
            )
        }
        notice?.let {
            InfoBanner(it, ForestGreen)
        }

        SectionCard(
            title = "Ambient collection",
            subtitle = "Opt-in WAV chunks for local retraining. Any chunk that ever contains " +
                "human presence is deleted whole; nothing is uploaded automatically.",
        ) {
            SettingSwitch(
                "Collect ambient audio",
                collectionEnabled,
                { on ->
                    collectionEnabled = on
                    settings.collectionEnabled = on
                    if (status.running) {
                        NodeService.action(
                            context,
                            if (on) NodeService.ACTION_COLLECT_ON else NodeService.ACTION_COLLECT_OFF
                        )
                    }
                    notice = if (on) {
                        "Collection " + (if (status.running) "started." else "enabled; it starts with monitoring.")
                    } else {
                        "Collection stopped."
                    }
                },
                if (status.running) "The service is running, so this takes effect now."
                else "Saved; applied the next time monitoring starts.",
            )
            KeyValueRow(
                "State",
                when {
                    collectionStats == null -> if (collectionEnabled) "enabled, waiting" else "off"
                    collectionStats.active -> "recording"
                    else -> "enabled, not recording"
                },
                if (collectionStats?.active == true) ForestGreen else ForestMuted,
            )
            KeyValueRow("Chunks on disk", "$collectionFiles file(s)")
            KeyValueRow(
                "Size",
                collectionStats?.let { fmtBytes(it.bytes) }
                    ?: fmtBytes(collectionFiles * ((config?.android?.collection?.chunkS ?: 60) * 32_000L)),
            )
            KeyValueRow(
                "Deleted for human presence",
                (collectionStats?.droppedForHuman ?: 0).toString(),
                if ((collectionStats?.droppedForHuman ?: 0) > 0) ForestAmber else ForestMuted,
            )
            KeyValueRow("Directory", collectionDir.absolutePath)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        if (!status.running) {
                            notice = "Start monitoring first: the recorder is owned by the service."
                        } else {
                            val removed = runCatching {
                                CollectionRecorder(context, settings, config!!).use { it.clearAll() }
                            }
                            notice = removed.fold(
                                { "Deleted $it collected file(s)." },
                                { "Delete failed: ${it.message}" }
                            )
                        }
                    },
                    enabled = status.running && config != null,
                ) { Text("Delete all chunks", fontSize = 12.sp) }
                OutlinedButton(onClick = onOpenCalibration) {
                    Text("Calibrate input", fontSize = 12.sp)
                }
            }
            Text(
                "Export with: adb pull " + collectionDir.absolutePath + "/ ./phone-collection/",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(
            title = "Raw PCM streaming (debug)",
            subtitle = "Sends every captured 16 kHz block over WebSocket to a laptop. This can " +
                "contain speech, so it is off by default and intended for supervised debugging.",
        ) {
            SettingSwitch(
                "Stream raw audio",
                streamingEnabled,
                { on ->
                    streamingEnabled = on
                    settings.streamingEnabled = on
                    if (status.running) {
                        NodeService.action(
                            context,
                            if (on) NodeService.ACTION_STREAM_ON else NodeService.ACTION_STREAM_OFF
                        )
                    }
                    notice = if (on) {
                        "Streaming " + (if (status.running) "started." else "enabled; it starts with monitoring.")
                    } else {
                        "Streaming stopped."
                    }
                },
                "All inference still happens on this phone.",
            )
            SettingTextField(
                "Streaming URL",
                settings.streamingUrl,
                { settings.streamingUrl = it },
                KeyboardType.Uri,
                "ws://192.168.1.20:8765/stream — run android/tools/stream_receiver.py",
            )
            KeyValueRow(
                "Connection",
                when {
                    !streamingEnabled -> "off"
                    status.streaming -> "connected"
                    else -> "enabled, waiting"
                },
                if (status.streaming) ForestGreen else ForestMuted,
            )
            if (streamingEnabled) {
                InfoBanner(
                    "Raw audio can contain speech. Use a supervised session and turn this " +
                        "off when you are done.",
                    ForestRed,
                    "Privacy",
                )
            }
        }

        SectionCard(
            title = "24 h soak test",
            subtitle = "Records battery, temperature, governor level, restarts, dropped " +
                "frames and alert count. The report is written to the logs directory.",
        ) {
            SettingSwitch(
                "Soak mode",
                status.soak,
                { on ->
                    if (!status.running) {
                        notice = "Start monitoring first: soak sampling runs inside the service."
                    } else {
                        NodeService.action(
                            context,
                            if (on) NodeService.ACTION_SOAK_ON else NodeService.ACTION_SOAK_OFF
                        )
                        notice = if (on) "Soak test started." else "Soak test stopped; report written."
                    }
                },
                "Uses the running service's heartbeat; it is not a separate process.",
            )
            val report = soakReport
            if (report != null) {
                KeyValueRow("Elapsed", TimeUtils.humanDuration(report.durationS))
                KeyValueRow("Samples", report.samples.toString())
                KeyValueRow(
                    "Battery range",
                    "${report.minBatteryPct ?: "?"}% – ${report.maxBatteryPct ?: "?"}%"
                )
                KeyValueRow(
                    "Temperature range",
                    "${report.minTempC?.let { fmt(it, 1) } ?: "?"} C – " +
                        "${report.maxTempC?.let { fmt(it, 1) } ?: "?"} C"
                )
                KeyValueRow("Restarts during soak", (report.restartsEnd - report.restartsStart).toString())
                KeyValueRow("Dropped frames", report.droppedFrames.toString())
                KeyValueRow("Alerts sent", report.alertsSent.toString())
                KeyValueRow(
                    "24 h reached",
                    if (report.completed24h) "yes" else "not yet",
                    if (report.completed24h) ForestGreen else ForestAmber,
                )
            } else {
                Text(
                    "No soak test is running. Start one when the phone is on a charger and " +
                        "placed where it will stay for a day.",
                    fontSize = 12.sp,
                    color = ForestMuted,
                )
            }
            KeyValueRow("Report", File(logsDir, "soak_report.json").absolutePath)
            KeyValueRow("Samples CSV", File(logsDir, "soak_samples.csv").absolutePath)
        }

        SectionCard(
            title = "Battery log",
            subtitle = "One CSV row per minute, written by the running service.",
        ) {
            KeyValueRow("File", batteryCsv.file().absolutePath)
            KeyValueRow("Rows", batteryCsv.rows().toString())
            KeyValueRow("Size", fmtBytes(batteryCsv.file().length()))
            KeyValueRow("Rotates at", "5 MB → battery_log.1.csv")
        }

        SectionCard(
            title = "Watchdog and protections",
            subtitle = "Auto-start must stay enabled for the watchdogs to restart a dead node.",
        ) {
            KeyValueRow(
                "Auto-start",
                if (settings.autoStart) "enabled" else "disabled",
                if (settings.autoStart) ForestGreen else ForestRed,
            )
            KeyValueRow(
                "Battery exemption",
                if (protection.items.firstOrNull { it.id == "battery_exemption" }?.ok == true) "exempt" else "missing",
                if (protection.items.firstOrNull { it.id == "battery_exemption" }?.ok == true) ForestGreen else ForestAmber,
            )
            val missing = protection.missing
            KeyValueRow(
                "OEM checks missing",
                if (missing.isEmpty()) "none" else missing.joinToString { it.title },
                if (missing.isEmpty()) ForestGreen else ForestAmber,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        settings.autoStart = true
                        WatchdogWorker.schedule(context)
                        WatchdogAlarmReceiver.schedule(context)
                        notice = "Watchdogs armed (WorkManager 15 min, AlarmManager 5 min)."
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ForestGreen,
                        contentColor = Color(0xFF062117),
                    ),
                ) { Text("Enable and arm", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = {
                        settings.autoStart = false
                        WatchdogAlarmReceiver.cancel(context)
                        notice = "Auto-start disabled and the AlarmManager watchdog cancelled."
                    },
                ) { Text("Disable auto-start", fontSize = 12.sp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { protection = checker.check() }) {
                    Text("Re-check", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = { checker.open(ProtectionAction.BATTERY_EXEMPTION); protection = checker.check() },
                ) { Text("Battery settings", fontSize = 12.sp) }
                OutlinedButton(onClick = onOpenSetup) {
                    Text("Setup wizard", fontSize = 12.sp)
                }
            }
            Text(
                "OxygenOS has no public API for auto-launch or background-activity switches. " +
                    "The app records only what you confirm in Setup and never claims more.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(
            title = "Offline alert queue",
            subtitle = "Alert JSON is stored in SQLite until the backend accepts it. Live " +
                "telemetry is never queued.",
        ) {
            KeyValueRow("Pending events", queue.count().toString())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        queue.clear()
                        notice = "Offline alert queue cleared."
                    },
                ) { Text("Clear queue", fontSize = 12.sp) }
            }
        }

        SectionCard(title = "Logs and reports") {
            KeyValueRow("Logs directory", logsDir.absolutePath)
            KeyValueRow("Collection", collectionDir.absolutePath)
            Text(
                "adb pull /sdcard/Android/data/org.forestear.node/files/logs/ ./phone-logs/",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "adb pull /sdcard/Android/data/org.forestear.node/files/collection/ ./phone-collection/",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatusPill(
                if (status.backendOnline) "BACKEND ONLINE" else "BACKEND OFFLINE",
                if (status.backendOnline) ForestGreen else ForestAmber,
            )
        }

        Text(
            "Nothing on this screen uploads audio unless raw streaming is explicitly on.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }
}

private fun fmtBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000L -> String.format(Locale.US, "%.1f kB", bytes / 1_000.0)
    else -> "$bytes B"
}

private fun fmt(v: Double, digits: Int): String =
    String.format(Locale.US, "%.${digits}f", v)
