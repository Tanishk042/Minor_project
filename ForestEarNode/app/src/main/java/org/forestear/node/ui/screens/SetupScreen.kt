package org.forestear.node.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.forestear.node.service.NodeStatus
import org.forestear.node.service.ProtectionAction
import org.forestear.node.service.ProtectionChecker
import org.forestear.node.settings.SettingsStore
import org.forestear.node.ui.ForestAmber
import org.forestear.node.ui.ForestGreen
import org.forestear.node.ui.ForestMuted
import org.forestear.node.ui.ForestRed
import org.forestear.node.ui.InfoBanner
import org.forestear.node.ui.KeyValueRow
import org.forestear.node.ui.PermissionGroups
import org.forestear.node.ui.PermissionSnapshot
import org.forestear.node.ui.SectionCard
import org.forestear.node.ui.SettingTextField
import org.forestear.node.ui.StatusPill
import java.util.concurrent.TimeUnit

private const val STEPS = 6

@Composable
fun SetupScreen(
    status: NodeStatus,
    permissions: PermissionSnapshot,
    onRequest: (Array<String>) -> Unit,
    onDone: (start: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { SettingsStore.get(context) }
    val checker = remember { ProtectionChecker(context, settings) }
    var step by rememberSaveable { mutableIntStateOf(0) }
    var protection by remember { mutableStateOf(checker.check()) }
    var backendResult by remember { mutableStateOf<String?>(null) }
    var backendOk by remember { mutableStateOf(false) }
    var backendBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(step) {
        protection = checker.check()
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
                Text("Node setup", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Step ${step + 1} of $STEPS",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StepDots(step)
        }

        when (step) {
            0 -> WelcomeStep()
            1 -> PermissionsStep(permissions, onRequest)
            2 -> BatteryStep(protection, checker) { protection = checker.check() }
            3 -> OemStep(protection, settings, checker) { protection = checker.check() }
            4 -> BackendStep(
                settings = settings,
                result = backendResult,
                ok = backendOk,
                busy = backendBusy,
                onTest = {
                    backendBusy = true
                    backendResult = null
                    scope.launch {
                        val (ok, text) = probeBackend(settings.backendUrl)
                        backendOk = ok
                        backendResult = text
                        backendBusy = false
                    }
                },
            )
            else -> FinishStep(status, permissions, protection)
        }

        if (step == STEPS - 1) {
            if (!permissions.mic) {
                InfoBanner(
                    "RECORD_AUDIO is required before the service can start.",
                    ForestRed,
                    "Microphone not granted",
                )
                Button(
                    onClick = { onRequest(PermissionGroups.core()) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Grant microphone permission") }
            }
            Button(
                onClick = { onDone(true) },
                enabled = permissions.mic,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ForestGreen,
                    contentColor = Color(0xFF062117),
                ),
            ) { Text(if (status.running) "Finish (already monitoring)" else "Finish and start monitoring") }
            OutlinedButton(
                onClick = { onDone(false) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Finish without starting") }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) {
                OutlinedButton(onClick = { step-- }) { Text("Back") }
            }
            Spacer(Modifier.weight(1f))
            if (step < STEPS - 1) {
                Button(onClick = { step++ }) { Text("Next") }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StepDots(step: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 0 until STEPS) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (i <= step) ForestGreen else ForestMuted.copy(alpha = 0.3f))
            )
        }
    }
}

@Composable
private fun WelcomeStep() {
    SectionCard(title = "What this app does") {
        Text(
            "ForestEar Node turns this phone into an acoustic sensor. Nine models run " +
                "entirely on the phone and classify gunshots, chainsaws, vehicles, human " +
                "presence, weather and bird calls. Only JSON alerts, heartbeats and " +
                "optional telemetry leave the device.",
            fontSize = 13.sp,
        )
        KeyValueRow("Inference", "on-device (TFLite / generated trees)")
        KeyValueRow("Audio sent by default", "none")
        KeyValueRow("Speech", "never stored or uploaded")
        KeyValueRow("Alert clip", "not recorded on Android")
    }
    SectionCard(title = "Honest expectations") {
        Text(
            "The Python reference pipeline measured 5.56 ms per hop on the development " +
                "laptop (CPU, batch 1). On-phone latency is unmeasured until you run " +
                "Diagnostics → Benchmark on this handset, so treat those numbers as " +
                "reference only. Detection quality on this microphone is also unmeasured " +
                "until you collect local audio and retrain.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    InfoBanner(
        "This is a research prototype, not a certified security device. Do not rely on " +
            "it as the only warning for life-safety decisions.",
        ForestAmber,
        "Read me first",
    )
}

@Composable
private fun PermissionsStep(
    permissions: PermissionSnapshot,
    onRequest: (Array<String>) -> Unit,
) {
    SectionCard(title = "Required", subtitle = "The node cannot monitor without these.") {
        PermissionRow("Microphone", permissions.mic, required = true) {
            onRequest(PermissionGroups.core())
        }
        PermissionRow("Notifications", permissions.notifications, required = true) {
            onRequest(PermissionGroups.core())
        }
    }
    SectionCard(
        title = "Optional",
        subtitle = "Used only for the feature named. Denying them keeps the node working.",
    ) {
        PermissionRow("Location", permissions.location, required = false) {
            onRequest(PermissionGroups.location())
        }
        Text(
            "Fixed coordinates are used by default; grant location only if you want the " +
                "phone's last known fix.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PermissionRow("Phone state (SIM / signal)", permissions.phoneState, required = false) {
            onRequest(PermissionGroups.phoneState())
        }
        PermissionRow("SMS fallback", permissions.sms, required = false) {
            onRequest(PermissionGroups.sms())
        }
        Text(
            "SMS is off by default and is only sent for CRITICAL events after you enable " +
                "it in Settings.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    required: Boolean,
    onGrant: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title + if (required) " *" else "",
            modifier = Modifier.weight(1f),
            fontSize = 14.sp,
        )
        if (granted) {
            StatusPill("GRANTED", ForestGreen)
        } else {
            StatusPill(if (required) "REQUIRED" else "OPTIONAL", ForestAmber)
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = onGrant) { Text("Grant", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun BatteryStep(
    protection: org.forestear.node.service.ProtectionReport,
    checker: ProtectionChecker,
    refresh: () -> Unit,
) {
    val exempt = protection.items.firstOrNull { it.id == "battery_exemption" }?.ok == true
    SectionCard(
        title = "Battery optimisation exemption",
        subtitle = "OxygenOS aggressively freezes background apps; without this the node dies.",
    ) {
        KeyValueRow(
            "Status",
            if (exempt) "exempt" else "not exempt",
            if (exempt) ForestGreen else ForestRed,
        )
        if (!exempt) {
            Button(
                onClick = {
                    checker.open(ProtectionAction.BATTERY_EXEMPTION)
                    refresh()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Request exemption") }
            OutlinedButton(onClick = refresh, modifier = Modifier.fillMaxWidth()) {
                Text("Re-check")
            }
        }
        Text(
            "The system dialog must be accepted. The app verifies this with " +
                "isIgnoringBatteryOptimizations, so it cannot claim success falsely.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    InfoBanner(
        "Use a charger for 24 h soak tests. On battery, expect roughly a day of runtime " +
            "with the thermal governor active; measure it with Tools → Soak test.",
        ForestAmber,
        "Power",
    )
}

@Composable
private fun OemStep(
    protection: org.forestear.node.service.ProtectionReport,
    settings: SettingsStore,
    checker: ProtectionChecker,
    refresh: () -> Unit,
) {
    SectionCard(
        title = "OxygenOS / ColorOS protections",
        subtitle = "These switches have no public API; the app asks you to confirm them.",
    ) {
        val bg = protection.items.firstOrNull { it.id == "background_activity" }?.ok == true
        val auto = protection.items.firstOrNull { it.id == "auto_launch" }?.ok == true
        val recents = protection.items.firstOrNull { it.id == "recents_lock" }?.ok == true

        OemRow(
            "Allow background activity",
            bg,
            "Open app details → Battery → Allow background activity",
            onOpen = { checker.open(ProtectionAction.APP_DETAILS) },
            onConfirm = {
                settings.backgroundActivityConfirmed = true
                refresh()
            },
        )
        OemRow(
            "Auto-launch",
            auto,
            "Enable ForestEar Node in the OEM auto-launch list",
            onOpen = { checker.open(ProtectionAction.AUTO_LAUNCH) },
            onConfirm = {
                settings.autoLaunchConfirmed = true
                refresh()
            },
        )
        OemRow(
            "Lock in recents",
            recents,
            "Open Recents, long-press ForestEar Node, tap the lock icon",
            onOpen = { checker.open(ProtectionAction.RECENTS) },
            onConfirm = {
                settings.recentsLockedConfirmed = true
                refresh()
            },
        )
    }
    InfoBanner(
        "The app shows a permanent warning on the Status tab while any of these checks is " +
            "missing. It never pretends they are enabled.",
        ForestAmber,
    )
}

@Composable
private fun OemRow(
    title: String,
    confirmed: Boolean,
    detail: String,
    onOpen: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), fontSize = 14.sp)
            StatusPill(if (confirmed) "CONFIRMED" else "MISSING", if (confirmed) ForestGreen else ForestAmber)
        }
        Text(detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!confirmed) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpen) { Text("Open settings", fontSize = 12.sp) }
                Button(onClick = onConfirm) { Text("I did it", fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun BackendStep(
    settings: SettingsStore,
    result: String?,
    ok: Boolean,
    busy: Boolean,
    onTest: () -> Unit,
) {
    SectionCard(
        title = "Backend",
        subtitle = "The phone posts JSON alerts to this URL. Use the laptop's LAN address " +
            "when the phone is on Wi-Fi; 127.0.0.1 only works with adb reverse.",
    ) {
        SettingTextField("Node ID", settings.nodeId, { settings.nodeId = it })
        SettingTextField(
            "Backend URL",
            settings.backendUrl,
            { settings.backendUrl = it },
            KeyboardType.Uri,
            "e.g. http://192.168.1.20:8000",
        )
        SettingTextField(
            "Auth token",
            settings.token,
            { settings.token = it },
            supporting = "Must match FORESTEAR_TOKEN on the backend (default forestear-dev-token).",
        )
        Button(onClick = onTest, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Testing…" else "Test /health")
        }
        if (result != null) {
            Text(
                result,
                fontSize = 12.sp,
                color = if (ok) ForestGreen else ForestRed,
            )
        }
    }
    InfoBanner(
        "Recommended: a 4G-only SIM with a small data plan. The node sends a few KB per " +
            "heartbeat and nothing else unless streaming is enabled.",
        ForestGreen,
        "Connectivity",
    )
}

@Composable
private fun FinishStep(
    status: NodeStatus,
    permissions: PermissionSnapshot,
    protection: org.forestear.node.service.ProtectionReport,
) {
    SectionCard(title = "Checklist") {
        CheckRow("Microphone permission", permissions.mic)
        CheckRow("Notification permission", permissions.notifications)
        CheckRow("Battery exemption", protection.items.firstOrNull { it.id == "battery_exemption" }?.ok == true)
        CheckRow("Background activity confirmed", protection.items.firstOrNull { it.id == "background_activity" }?.ok == true)
        CheckRow("Auto-launch confirmed", protection.items.firstOrNull { it.id == "auto_launch" }?.ok == true)
        CheckRow("Recents lock confirmed", protection.items.firstOrNull { it.id == "recents_lock" }?.ok == true)
        CheckRow("Service currently running", status.running)
    }
    Text(
        "You can change every value later in Settings. The Status tab keeps showing a " +
            "warning while a required protection is missing.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CheckRow(label: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 13.sp)
        StatusPill(if (ok) "OK" else "MISSING", if (ok) ForestGreen else ForestAmber)
    }
}

private suspend fun probeBackend(url: String): Pair<Boolean, String> =
    withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
        runCatching {
            val request = Request.Builder().url(url.trimEnd('/') + "/health").get().build()
            client.newCall(request).execute().use { response ->
                if (response.code in 200..299) {
                    true to "HTTP ${response.code} — backend reachable"
                } else {
                    false to "HTTP ${response.code} — check token/URL"
                }
            }
        }.getOrElse {
            false to "unreachable: ${it.message ?: it.javaClass.simpleName}"
        }
    }
