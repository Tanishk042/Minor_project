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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.forestear.node.config.AppConfig
import org.forestear.node.device.DeviceProfileStore
import org.forestear.node.device.RecoveryLog
import org.forestear.node.diagnostics.Benchmark
import org.forestear.node.diagnostics.BenchmarkReport
import org.forestear.node.diagnostics.SelfTest
import org.forestear.node.diagnostics.SelfTestReport
import org.forestear.node.diagnostics.TestStatus
import org.forestear.node.features.FeatureAssets
import org.forestear.node.service.NodeStatus
import org.forestear.node.settings.SettingsStore
import org.forestear.node.ui.ForestAmber
import org.forestear.node.ui.ForestGreen
import org.forestear.node.ui.ForestRed
import org.forestear.node.ui.InfoBanner
import org.forestear.node.ui.KeyValueRow
import org.forestear.node.ui.SectionCard
import org.forestear.node.ui.StatusPill
import org.forestear.node.util.AndroidAssetSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

@Composable
fun DiagnosticsScreen(status: NodeStatus) {
    val context = LocalContext.current
    val settings = remember { SettingsStore.get(context) }
    val scope = rememberCoroutineScope()

    var selfTest by remember { mutableStateOf<SelfTestReport?>(null) }
    var progress by remember { mutableStateOf("") }
    var selfBusy by remember { mutableStateOf(false) }
    var selfError by remember { mutableStateOf<String?>(null) }

    var bench by remember { mutableStateOf<BenchmarkReport?>(null) }
    var benchBusy by remember { mutableStateOf(false) }
    var benchError by remember { mutableStateOf<String?>(null) }
    var reportPath by remember { mutableStateOf<String?>(null) }
    val profile = remember { DeviceProfileStore(context).current() }
    val recovery = remember { RecoveryLog(context).load() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Diagnostics", style = MaterialTheme.typography.titleLarge)
        Text(
            "Hardware self-test and per-model latency benchmark. Both run on this phone " +
                "and write JSON to the app's external files directory.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (status.running) {
            InfoBanner(
                "Monitoring is running and already holds all nine models in memory. The " +
                    "self-test/benchmark load a second copy; on an 8 GB phone stop monitoring " +
                    "first if Android kills the app.",
                ForestAmber,
                "Memory warning",
            )
        }

        SectionCard(
            title = "Hardware self-test",
            subtitle = "Mic level + clipping, headset, permissions, protections, network, " +
                "backend, model load, SMS capability, battery/thermal and signal.",
        ) {
            if (selfBusy) {
                Text("Running: $progress", fontSize = 13.sp, color = ForestAmber)
            }
            Button(
                onClick = {
                    selfBusy = true
                    selfError = null
                    selfTest = null
                    scope.launch {
                        val result = runCatching {
                            withContext(Dispatchers.IO) {
                                SelfTest(context, settings).run { p -> progress = p }
                            }
                        }
                        result.onSuccess {
                            selfTest = it
                            writeSelfTest(context, it)
                        }.onFailure {
                            selfError = it.message ?: it.javaClass.simpleName
                        }
                        selfBusy = false
                    }
                },
                enabled = !selfBusy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ForestGreen,
                    contentColor = Color(0xFF062117),
                ),
            ) { Text(if (selfBusy) "Running self-test…" else "Run self-test") }
            selfError?.let { Text("Self-test failed: $it", color = ForestRed, fontSize = 12.sp) }
            val report = selfTest
            if (report != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(
                        if (report.failed == 0) "PASS" else "${report.failed} FAIL",
                        if (report.failed == 0) ForestGreen else ForestRed,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "  ${report.items.size} checks, ${report.warned} warning(s)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (item in report.items) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(item.title, modifier = Modifier.weight(1f), fontSize = 13.sp)
                            StatusPill(item.status.name, statusColor(item.status))
                        }
                        Text(
                            item.detail,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SectionCard(
            title = "Capability profile",
            subtitle = "Probed from this handset. Unknown values are recorded as missing, not guessed.",
        ) {
            KeyValueRow("Device", profile.headline())
            KeyValueRow("SoC", "${profile.build.socManufacturer} ${profile.build.socModel}")
            KeyValueRow("RAM / storage", "${profile.build.ramTotalMb} MB / ${profile.build.freeStorageMb} MB free")
            KeyValueRow(
                "Audio inputs",
                profile.audio.inputs.joinToString { "${it.name} (${it.typeLabel})" }
                    .ifBlank { "none enumerated" },
            )
            KeyValueRow(
                "Effects",
                "AGC=${profile.audio.agcAvailable} NS=${profile.audio.nsAvailable} " +
                    "AEC=${profile.audio.aecAvailable} · UNPROCESSED=${profile.audio.unprocessedSupported}",
            )
            KeyValueRow(
                "Telephony",
                if (!profile.telephony.hasTelephony) "none (Wi-Fi-only device)"
                else "${profile.telephony.simSlots} slot(s), ${profile.telephony.subscriptions.size} subscription(s), " +
                    "SMS=${profile.telephony.hasSms}",
            )
            KeyValueRow(
                "Location",
                "gps=${profile.location.hasGps} network=${profile.location.hasNetworkProvider} " +
                    "fused=${profile.location.hasFusedProvider} (fixed coordinates always allowed)",
            )
            KeyValueRow(
                "Battery",
                "${profile.battery.percent}% · ${profile.battery.plugged} · " +
                    "temp=${profile.battery.temperatureC?.let { fmt(it, 1) + " C" } ?: "unknown"} · " +
                    "thermal=${profile.battery.thermalLabel} · removable=${profile.battery.removable}",
            )
            if (profile.missing.isNotEmpty()) {
                KeyValueRow(
                    "Missing",
                    profile.missing.joinToString(", "),
                    ForestAmber,
                )
            }
        }

        SectionCard(
            title = "Compatibility report",
            subtitle = "Writes docs/compatibility_report_<model>.md from the probe, the last " +
                "self-test and the recorded recovery paths. Unrun paths stay NOT RUN.",
        ) {
            Button(
                onClick = {
                    val report = selfTest
                    if (report == null) return@Button
                    scope.launch {
                        val file = withContext(Dispatchers.IO) {
                            SelfTest(context, settings).saveCompatibilityReport(report)
                        }
                        reportPath = file?.absolutePath ?: "write failed"
                    }
                },
                enabled = selfTest != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (selfTest == null) "Run the self-test first" else "Write compatibility report") }
            reportPath?.let { KeyValueRow("Report file", it) }
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "docs")
            Text(
                "adb pull /sdcard/Android/data/org.forestear.node/files/docs/ ./phone-docs/",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            KeyValueRow("Directory", dir.absolutePath)
        }

        SectionCard(
            title = "Recovery paths",
            subtitle = "These decide whether a phone survives as a node. The app records only " +
                "paths that were actually exercised; the rest stay NOT RUN.",
        ) {
            val known = listOf(
                "kill_service" to "Killed by the system / adb",
                "swipe_away" to "Swiped from Recents",
                "reboot" to "Phone rebooted",
                "boot_receiver" to "Boot receiver restart",
                "watchdog" to "Watchdog restart",
                "screen_off" to "Screen-off continuity",
                "mic_theft" to "Microphone taken by another app",
            )
            for ((id, title) in known) {
                val result = recovery.firstOrNull { it.path == id }
                KeyValueRow(
                    title,
                    (result?.outcome ?: "NOT RUN") + (result?.detail?.let { " · $it" } ?: ""),
                    when (result?.outcome) {
                        "PASS" -> ForestGreen
                        "FAIL" -> ForestRed
                        else -> ForestAmber
                    },
                )
            }
            Text(
                "Run kill/swipe/reboot tests with android/scripts/recovery_test.sh while the " +
                    "phone is connected; results are appended to recovery_paths.json.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(
            title = "Latency benchmark",
            subtitle = "CPU XNNPACK vs GPU delegate per model, then a full-pipeline run. " +
                "The faster stable delegate is saved and used by the service.",
        ) {
            if (benchBusy) {
                Text(
                    "Benchmarking… this loads every model twice and takes a minute.",
                    fontSize = 13.sp,
                    color = ForestAmber,
                )
            }
            Button(
                onClick = {
                    benchBusy = true
                    benchError = null
                    bench = null
                    scope.launch {
                        val result = runCatching {
                            withContext(Dispatchers.IO) {
                                val source = AndroidAssetSource(context)
                                val config = AppConfig.load(source)
                                val assets = FeatureAssets(source, config.features)
                                Benchmark(context, config, assets, settings).run()
                            }
                        }
                        result.onSuccess { bench = it }.onFailure {
                            benchError = it.message ?: it.javaClass.simpleName
                        }
                        benchBusy = false
                    }
                },
                enabled = !benchBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (benchBusy) "Running benchmark…" else "Run benchmark") }
            benchError?.let { Text("Benchmark failed: $it", color = ForestRed, fontSize = 12.sp) }
            val report = bench
            if (report != null) {
                KeyValueRow("Generated", report.generatedUtc.take(19) + "Z")
                KeyValueRow("Full pipeline mean", "${fmt(report.totalMeanMs, 1)} ms")
                KeyValueRow("Process CPU", "${fmt(report.cpuPct, 0)} %")
                KeyValueRow("PSS RAM", "${fmt(report.ramMb, 0)} MB")
                for (m in report.perModel) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m.model.uppercase(), modifier = Modifier.weight(1f), fontSize = 13.sp)
                            StatusPill(
                                if (m.ok) m.delegate else "FAILED",
                                if (m.ok) ForestGreen else ForestRed,
                            )
                        }
                        Text(
                            if (m.ok) {
                                "mean ${fmt(m.meanMs, 2)} ms · p95 ${fmt(m.p95Ms, 2)} ms · " +
                                    "${m.runs} runs" + if (m.note.isNotBlank()) " · ${m.note}" else ""
                            } else {
                                "failed: ${m.note}"
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(report.note, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SectionCard(title = "Report files") {
            val logs = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
            KeyValueRow("benchmark.json", File(logs, "benchmark.json").absolutePath)
            KeyValueRow("selftest.json", File(logs, "selftest.json").absolutePath)
            KeyValueRow("battery_log.csv", File(logs, "battery_log.csv").absolutePath)
            KeyValueRow("soak_report.json", File(logs, "soak_report.json").absolutePath)
            Text(
                "adb pull /sdcard/Android/data/org.forestear.node/files/logs/ ./phone-logs/",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        InfoBanner(
            "A passing self-test means the sensors and permissions work. It does not mean " +
                "detection is accurate on this microphone; that requires labelled local audio.",
            ForestAmber,
            "What a pass means",
        )
        Spacer(Modifier.height(8.dp))
    }
}

private fun statusColor(status: TestStatus): Color = when (status) {
    TestStatus.PASS -> ForestGreen
    TestStatus.WARN -> ForestAmber
    TestStatus.FAIL -> ForestRed
}

private fun fmt(v: Double, digits: Int): String =
    String.format(Locale.US, "%.${digits}f", v)

private fun writeSelfTest(context: android.content.Context, report: SelfTestReport) {
    val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs").also { it.mkdirs() }
    val items = JSONArray()
    for (item in report.items) {
        items.put(
            JSONObject()
                .put("id", item.id)
                .put("title", item.title)
                .put("status", item.status.name)
                .put("detail", item.detail)
        )
    }
    val root = JSONObject()
        .put("generated_utc", report.generatedUtc)
        .put("failed", report.failed)
        .put("warned", report.warned)
        .put("items", items)
    runCatching { File(dir, "selftest.json").writeText(root.toString(2)) }
}
