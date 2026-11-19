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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.forestear.node.config.AppConfig
import org.forestear.node.device.DeviceProfileStore
import org.forestear.node.device.PerfTier
import org.forestear.node.service.NodeService
import org.forestear.node.settings.InputChoice
import org.forestear.node.settings.SettingsStore
import org.forestear.node.transport.OfflineQueue
import org.forestear.node.transport.SmsSender
import org.forestear.node.transport.Transport
import org.forestear.node.ui.ForestAmber
import org.forestear.node.ui.ForestGreen
import org.forestear.node.ui.ForestMuted
import org.forestear.node.ui.ForestRed
import org.forestear.node.ui.InfoBanner
import org.forestear.node.ui.KeyValueRow
import org.forestear.node.ui.PermissionGroups
import org.forestear.node.ui.PermissionSnapshot
import org.forestear.node.ui.SectionCard
import org.forestear.node.ui.SettingChips
import org.forestear.node.ui.SettingNumberField
import org.forestear.node.ui.SettingSlider
import org.forestear.node.ui.SettingSwitch
import org.forestear.node.ui.SettingTextField
import org.forestear.node.util.AndroidAssetSource
import java.util.Locale

@Composable
fun SettingsScreen(
    permissions: PermissionSnapshot,
    onRequest: (Array<String>) -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenPrivacy: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { SettingsStore.get(context) }
    val config = remember { runCatching { AppConfig.load(AndroidAssetSource(context)) }.getOrNull() }
    val transport = remember { Transport(context, settings, OfflineQueue(context)) }
    val sms = remember { SmsSender(context, settings, transport) }
    val sims = remember(permissions.phoneState, permissions.sms) {
        if (permissions.phoneState) sms.subscriptions() else emptyList()
    }

    var autoStart by remember { mutableStateOf(settings.autoStart) }
    var heartbeat by remember { mutableIntStateOf(settings.heartbeatS) }
    var budget by remember { mutableIntStateOf(settings.analysisBudgetMs) }
    var choice by remember { mutableStateOf(settings.inputChoice) }
    var gainBuiltin by remember { mutableFloatStateOf(settings.gainFor(false)) }
    var gainHeadset by remember { mutableFloatStateOf(settings.gainFor(true)) }
    var m8Duty by remember { mutableIntStateOf(settings.m8Duty) }

    var thGunshot by remember { mutableFloatStateOf(settings.threshold("gunshot").toFloat()) }
    var thChainsaw by remember { mutableFloatStateOf(settings.threshold("chainsaw").toFloat()) }
    var thVehicle by remember { mutableFloatStateOf(settings.threshold("vehicle").toFloat()) }
    var thHuman by remember { mutableFloatStateOf(settings.threshold("human").toFloat()) }
    var thAnomaly by remember { mutableFloatStateOf(settings.threshold("anomaly").toFloat()) }

    var warmC by remember { mutableFloatStateOf(settings.thermalWarmC.toFloat()) }
    var hotC by remember { mutableFloatStateOf(settings.thermalHotC.toFloat()) }
    var criticalC by remember { mutableFloatStateOf(settings.thermalCriticalC.toFloat()) }
    var pauseC by remember { mutableFloatStateOf(settings.thermalHardPauseC.toFloat()) }
    var lowBattery by remember { mutableIntStateOf(settings.lowBatteryPct) }
    var criticalBattery by remember { mutableIntStateOf(settings.criticalBatteryPct) }

    var smsEnabled by remember { mutableStateOf(settings.smsEnabled) }
    var smsSubId by remember { mutableIntStateOf(settings.smsSubscriptionId) }
    var streamingEnabled by remember { mutableStateOf(settings.streamingEnabled) }
    var useFixed by remember { mutableStateOf(settings.useFixedLocation) }
    var collectionEnabled by remember { mutableStateOf(settings.collectionEnabled) }
    var tierOverride by remember { mutableStateOf(settings.tierOverride) }
    var continuityMinutes by remember { mutableIntStateOf(settings.continuityMinutes) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val deviceStore = remember { DeviceProfileStore(context) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(settings.exportJson().toByteArray())
            } != null
        }.getOrDefault(false)
        notice = if (ok) "Settings exported." else "Export failed."
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        notice = when {
            text == null -> "Could not read the selected file."
            settings.importJson(text) ->
                "Settings imported. Stop and start monitoring to apply input/tier changes."
            else -> "Import rejected: not a ForestEar settings file (schema mismatch)."
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        Text(
            "Values are read live by the service on the next analysis hop. Input device and " +
                "gain changes need the capture path to reopen (stop/start monitoring).",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        notice?.let { InfoBanner(it, ForestGreen) }

        SectionCard(title = "Node identity and backend") {
            SettingTextField("Node ID", settings.nodeId, { settings.nodeId = it })
            SettingTextField(
                "Backend URL",
                settings.backendUrl,
                { settings.backendUrl = it },
                KeyboardType.Uri,
                "Use the laptop LAN address for a real phone; 127.0.0.1 needs adb reverse.",
            )
            SettingTextField(
                "Auth token",
                settings.token,
                { settings.token = it },
                supporting = "Must match the backend FORESTEAR_TOKEN.",
            )
        }

        SectionCard(
            title = "Performance tier",
            subtitle = "Automatic uses the last benchmark. A manual override is always " +
                "honoured, but the app notes when it exceeds the latency budget.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = tierOverride == null,
                    onClick = {
                        tierOverride = null
                        settings.tierOverride = null
                        notice = "Tier set to automatic; the next benchmark re-selects it."
                    },
                    label = { Text("Auto", fontSize = 12.sp) },
                )
                for (tier in PerfTier.values()) {
                    FilterChip(
                        selected = tierOverride == tier,
                        onClick = {
                            tierOverride = tier
                            settings.tierOverride = tier
                            notice = "Tier override ${tier.name}; models reload on the next hop."
                        },
                        label = { Text(tier.name, fontSize = 12.sp) },
                    )
                }
            }
            KeyValueRow(
                "Active plan",
                settings.tierPlan()?.let { "${it.tier.name} — ${it.reason}" } ?: "not selected yet",
            )
            KeyValueRow(
                "Last benchmark",
                settings.lastBenchmarkTotalMs.let {
                    if (it.isFinite() && it > 0) "%.1f ms per window".format(Locale.US, it)
                    else "not measured on this phone"
                },
            )
            Text(
                "A = all 9 models / 0.5 s hop · B = all / 1 s with duty cycling · " +
                    "C = core models / 1–2 s · D = core models + raw streaming.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "Service") {
            SettingSwitch(
                "Auto-start after reboot",
                autoStart,
                {
                    autoStart = it
                    settings.autoStart = it
                },
                "Re-arms the boot receiver, WorkManager and AlarmManager watchdogs.",
            )
            SettingSlider(
                "Heartbeat interval",
                heartbeat.toFloat(),
                5f..120f,
                "$heartbeat s",
                {
                    heartbeat = it.toInt()
                    settings.heartbeatS = heartbeat
                },
                "Battery, thermal, signal, queue and uptime are reported at this rate.",
            )
            SettingSlider(
                "Analysis latency budget",
                budget.toFloat(),
                100f..1500f,
                "$budget ms",
                {
                    budget = it.toInt()
                    settings.analysisBudgetMs = budget
                },
                "Above budget the runtime governor lengthens the hop, then duty-cycles " +
                    "anomaly and BirdNET, then falls back to streaming-only.",
            )
            SettingSlider(
                "Continuity test length",
                continuityMinutes.toFloat(),
                1f..60f,
                "$continuityMinutes min",
                {
                    continuityMinutes = it.toInt()
                    settings.continuityMinutes = continuityMinutes
                },
                "Used by Setup and Tools → screen-off continuity test.",
            )
            SettingSlider(
                "BirdNET duty cycle",
                m8Duty.toFloat(),
                1f..50f,
                "every $m8Duty hop(s)",
                {
                    m8Duty = it.toInt()
                    settings.m8Duty = m8Duty
                },
                "M8 is the most expensive model; 1 runs it every hop.",
            )
        }

        SectionCard(title = "Audio input") {
            SettingChips(
                title = "Preferred input",
                options = listOf(
                    InputChoice.AUTO.name to "Auto",
                    InputChoice.BUILTIN.name to "Built-in",
                    InputChoice.WIRED_HEADSET.name to "3.5 mm",
                    InputChoice.USB.name to "USB",
                ),
                selected = choice.name,
                onSelect = {
                    choice = InputChoice.valueOf(it)
                    settings.inputChoice = choice
                },
                subtitle = "Auto prefers USB, then a wired 3.5 mm headset, then the built-in mic. " +
                    "Bluetooth is never chosen automatically.",
            )
            SettingSlider(
                "Built-in mic gain",
                gainBuiltin,
                0.25f..8f,
                String.format(Locale.US, "%.2fx", gainBuiltin),
                {
                    gainBuiltin = it
                    settings.setGain(false, it)
                },
            )
            SettingSlider(
                "Wired/USB gain",
                gainHeadset,
                0.25f..8f,
                String.format(Locale.US, "%.2fx", gainHeadset),
                {
                    gainHeadset = it
                    settings.setGain(true, it)
                },
            )
            OutlinedButton(onClick = onOpenCalibration, modifier = Modifier.fillMaxWidth()) {
                Text("Open live calibration")
            }
        }

        SectionCard(
            title = "Detection thresholds",
            subtitle = "M9 probability thresholds. Raising them reduces false alarms and " +
                "also reduces recall; these are not tuned for this phone yet.",
        ) {
            ThresholdSlider("Gunshot", thGunshot) {
                thGunshot = it
                settings.setThreshold("gunshot", it.toDouble())
            }
            ThresholdSlider("Chainsaw", thChainsaw) {
                thChainsaw = it
                settings.setThreshold("chainsaw", it.toDouble())
            }
            ThresholdSlider("Vehicle", thVehicle) {
                thVehicle = it
                settings.setThreshold("vehicle", it.toDouble())
            }
            ThresholdSlider("Human presence", thHuman) {
                thHuman = it
                settings.setThreshold("human", it.toDouble())
            }
            ThresholdSlider("Anomaly (M6 z)", thAnomaly) {
                thAnomaly = it
                settings.setThreshold("anomaly", it.toDouble())
            }
            if (config != null) {
                OutlinedButton(
                    onClick = {
                        for (cls in listOf("gunshot", "chainsaw", "vehicle", "human", "anomaly")) {
                            val def = config.decision.thresholds[cls] ?: continue
                            settings.setThreshold(cls, def)
                            when (cls) {
                                "gunshot" -> thGunshot = def.toFloat()
                                "chainsaw" -> thChainsaw = def.toFloat()
                                "vehicle" -> thVehicle = def.toFloat()
                                "human" -> thHuman = def.toFloat()
                                "anomaly" -> thAnomaly = def.toFloat()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Reset to config defaults") }
            }
        }

        SectionCard(
            title = "Thermal governor",
            subtitle = "Escalation: lengthen hop → drop M6/M8 → pause inference. Resume is " +
                "hysteretic so the phone does not oscillate at the threshold.",
        ) {
            ThermalSlider("Warm", warmC) {
                warmC = it
                settings.thermalWarmC = it.toDouble()
            }
            ThermalSlider("Hot", hotC) {
                hotC = it
                settings.thermalHotC = it.toDouble()
            }
            ThermalSlider("Critical", criticalC) {
                criticalC = it
                settings.thermalCriticalC = it.toDouble()
            }
            ThermalSlider("Hard pause", pauseC) {
                pauseC = it
                settings.thermalHardPauseC = it.toDouble()
            }
            if (!(warmC < hotC && hotC < criticalC && criticalC < pauseC)) {
                Text(
                    "Thresholds are out of order; the governor uses the highest matching level " +
                        "but you should fix this.",
                    fontSize = 12.sp,
                    color = ForestRed,
                )
            }
            SettingSlider(
                "Low battery",
                lowBattery.toFloat(),
                5f..50f,
                "$lowBattery%",
                {
                    lowBattery = it.toInt()
                    settings.lowBatteryPct = lowBattery
                },
                "Below this and not charging: longer hop and fewer models.",
            )
            SettingSlider(
                "Critical battery",
                criticalBattery.toFloat(),
                1f..30f,
                "$criticalBattery%",
                {
                    criticalBattery = it.toInt()
                    settings.criticalBatteryPct = criticalBattery
                },
                "Below this: HOT-level reductions plus a CRITICAL system event.",
            )
        }

        SectionCard(title = "SMS fallback") {
            SettingSwitch(
                "Enable SMS for CRITICAL alerts",
                smsEnabled,
                {
                    smsEnabled = it
                    settings.smsEnabled = it
                },
                "Sent through the selected SIM; compact payload under 160 characters.",
            )
            if (!permissions.sms) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SEND_SMS not granted", modifier = Modifier.weight(1f), fontSize = 13.sp, color = ForestAmber)
                    OutlinedButton(onClick = { onRequest(PermissionGroups.sms()) }) {
                        Text("Grant", fontSize = 12.sp)
                    }
                }
            }
            SettingTextField(
                "Destination numbers",
                settings.smsNumbers,
                { settings.smsNumbers = it },
                KeyboardType.Phone,
                "Comma-separated. One SMS per number per alert, rate limited.",
            )
            Text("SIM selection", fontSize = 14.sp)
            if (!permissions.phoneState) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Grant phone state to list SIMs",
                        modifier = Modifier.weight(1f),
                        fontSize = 12.sp,
                        color = ForestAmber,
                    )
                    OutlinedButton(onClick = { onRequest(PermissionGroups.phoneState()) }) {
                        Text("Grant", fontSize = 12.sp)
                    }
                }
            } else if (sims.isEmpty()) {
                Text("No active subscriptions reported.", fontSize = 12.sp, color = ForestMuted)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = smsSubId < 0,
                        onClick = {
                            smsSubId = -1
                            settings.smsSubscriptionId = -1
                        },
                        label = { Text("Default", fontSize = 12.sp) },
                    )
                    for (sim in sims) {
                        FilterChip(
                            selected = smsSubId == sim.subscriptionId,
                            onClick = {
                                smsSubId = sim.subscriptionId
                                settings.smsSubscriptionId = sim.subscriptionId
                            },
                            label = {
                                Text(
                                    "SIM ${sim.slotIndex + 1} ${sim.carrier}".trim(),
                                    fontSize = 12.sp,
                                )
                            },
                        )
                    }
                }
            }
            KeyValueRow("Last SMS status", settings.lastSmsStatus)
            Text(
                "SMS is a last-resort path. It is not guaranteed to arrive, and a dual-SIM " +
                    "phone needs the correct subscription selected.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "Streaming (debug)") {
            SettingSwitch(
                "Stream raw 16 kHz PCM",
                streamingEnabled,
                {
                    streamingEnabled = it
                    settings.streamingEnabled = it
                },
                "Sends every capture block over WebSocket to the laptop. Off by default.",
            )
            SettingTextField(
                "Streaming URL",
                settings.streamingUrl,
                { settings.streamingUrl = it },
                KeyboardType.Uri,
                "e.g. ws://192.168.1.20:8765/stream — run android/tools/stream_receiver.py",
            )
            if (streamingEnabled) {
                InfoBanner(
                    "Raw audio can contain speech. Enable this only for a supervised debugging " +
                        "session; the phone still performs all inference locally.",
                    ForestRed,
                    "Privacy",
                )
            }
        }

        SectionCard(title = "Location") {
            SettingSwitch(
                "Use fixed coordinates",
                useFixed,
                {
                    useFixed = it
                    settings.useFixedLocation = it
                },
                "A deployed node does not move; fixed coordinates avoid background location.",
            )
            if (useFixed) {
                SettingNumberField("Latitude", settings.fixedLat, { settings.fixedLat = it })
                SettingNumberField("Longitude", settings.fixedLon, { settings.fixedLon = it })
            } else if (!permissions.location) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Location permission not granted", modifier = Modifier.weight(1f), fontSize = 13.sp, color = ForestAmber)
                    OutlinedButton(onClick = { onRequest(PermissionGroups.location()) }) {
                        Text("Grant", fontSize = 12.sp)
                    }
                }
            }
        }

        SectionCard(title = "Ambient collection (explicit opt-in)") {
            SettingSwitch(
                "Collect ambient audio for local retraining",
                collectionEnabled,
                {
                    collectionEnabled = it
                    settings.collectionEnabled = it
                },
                "Writes WAV chunks to app-specific external storage. Off by default.",
            )
            if (collectionEnabled) {
                InfoBanner(
                    "Any chunk that ever contains human presence is deleted whole, never " +
                        "trimmed or uploaded. Chunks are capped and rotated. Export with adb.",
                    ForestAmber,
                    "Privacy rules",
                )
            }
            KeyValueRow("Chunk length", "${config?.android?.collection?.chunkS ?: 60} s")
            KeyValueRow(
                "Storage cap",
                "${((config?.android?.collection?.maxBytes ?: 2_000_000_000L) / 1_000_000_000L)} GB",
            )
        }

        SectionCard(
            title = "Privacy and retention",
            subtitle = "Consent status, token encryption, retention policy and local-audio " +
                "deletion live on one screen.",
        ) {
            KeyValueRow(
                "Backend token",
                when (settings.token.let { settings.tokenStorageMode }) {
                    "plaintext-fallback" -> "PLAINTEXT FALLBACK"
                    "unset" -> "not set"
                    else -> "encrypted (Keystore)"
                },
                if (settings.tokenEncrypted) ForestGreen else ForestAmber,
            )
            OutlinedButton(onClick = onOpenPrivacy, modifier = Modifier.fillMaxWidth()) {
                Text("Open privacy and retention", fontSize = 12.sp)
            }
        }

        SectionCard(
            title = "Device profile and settings files",
            subtitle = "The capability probe runs on first launch and after every app update. " +
                "Export/import moves configuration only; it never includes audio, logs, the " +
                "device profile or the backend token.",
        ) {
            val device = remember { deviceStore.load() }
            KeyValueRow("Profile file", deviceStore.path())
            KeyValueRow("Device", device?.headline() ?: "not probed yet")
            KeyValueRow("Profile schema", device?.schema?.toString() ?: "—")
            if (!device?.missing.isNullOrEmpty()) {
                KeyValueRow(
                    "Missing capabilities",
                    device!!.missing.joinToString(", "),
                    ForestAmber,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        if (settings.mockCapabilities) {
                            notice = "Mock capabilities are on; turn them off to re-probe the real device."
                        } else if (NodeService.isRunning) {
                            NodeService.action(context, NodeService.ACTION_REPROBE)
                            notice = "Re-probing this handset; check Status in a moment."
                        } else {
                            scope.launch {
                                withContext(Dispatchers.IO) { deviceStore.current(force = true) }
                                notice = "Device profile re-probed."
                            }
                        }
                    },
                ) { Text("Re-probe device", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = { exportLauncher.launch("forestear_settings.json") },
                ) { Text("Export settings", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                ) { Text("Import settings", fontSize = 12.sp) }
            }
        }

        Text(
            "Changing backend URL/token takes effect immediately. Changing input choice or " +
                "gain requires reopening the microphone.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ThresholdSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    SettingSlider(
        title = label,
        value = value,
        range = 0.05f..0.99f,
        valueLabel = String.format(Locale.US, "%.2f", value),
        onChange = onChange,
    )
}

@Composable
private fun ThermalSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    SettingSlider(
        title = label,
        value = value,
        range = 30f..65f,
        valueLabel = String.format(Locale.US, "%.1f C", value),
        onChange = onChange,
    )
}
