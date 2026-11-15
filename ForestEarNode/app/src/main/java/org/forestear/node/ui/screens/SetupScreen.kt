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
import org.forestear.node.device.DeviceProfileStore
import org.forestear.node.device.OemProfile
import org.forestear.node.service.NodeService
import org.forestear.node.service.NodeStatus
import org.forestear.node.service.ProtectionAction
import org.forestear.node.service.ProtectionChecker
import org.forestear.node.service.ProtectionItem
import org.forestear.node.service.ProtectionReport
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
import org.forestear.node.ui.SettingSlider
import org.forestear.node.ui.SettingTextField
import org.forestear.node.ui.StatusPill
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val STEPS = 7

/**
 * First-run wizard. Works on any Android 8+ handset: the OEM steps come from the
 * JSON profile table, OS-verifiable checks are marked "verified" and everything
 * else is shown as user-confirmed, never assumed.
 */
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
    val profile = remember { checker.profile() }
    val device = remember { DeviceProfileStore(context).current() }
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
            0 -> WelcomeStep(device.headline())
            1 -> PermissionsStep(permissions, onRequest)
            2 -> BatteryStep(protection, profile, checker) { protection = checker.check() }
            3 -> OemStep(protection, settings, profile, checker) { protection = checker.check() }
            4 -> ContinuityStep(status, settings)
            5 -> BackendStep(
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
            else -> FinishStep(status, permissions, protection, profile)
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
                onClick = {
                    settings.wizardCompleted = true
                    onDone(true)
                },
                enabled = permissions.mic,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ForestGreen,
                    contentColor = Color(0xFF062117),
                ),
            ) { Text(if (status.running) "Finish (already monitoring)" else "Finish and start monitoring") }
            OutlinedButton(
                onClick = {
                    settings.wizardCompleted = true
                    onDone(false)
                },
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
private fun WelcomeStep(deviceHeadline: String) {
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
        KeyValueRow("This handset", deviceHeadline)
    }
    SectionCard(title = "Honest expectations") {
        Text(
            "The Python reference pipeline measured 5.56 ms per hop on the development " +
                "laptop (CPU, batch 1). On-phone latency is unmeasured until you run " +
                "Diagnostics → Benchmark on this handset, so treat those numbers as " +
                "reference only. The app chooses a performance tier from that measurement " +
                "and can drop the most expensive models if the phone cannot keep up.",
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
    protection: ProtectionReport,
    profile: OemProfile,
    checker: ProtectionChecker,
    refresh: () -> Unit,
) {
    val exempt = protection.items.firstOrNull { it.id == "battery_exemption" }?.ok == true
    SectionCard(
        title = "Battery optimisation exemption",
        subtitle = "${profile.displayName} may freeze background apps; without this the node can die.",
    ) {
        KeyValueRow(
            "Status",
            if (exempt) "exempt (verified by the OS)" else "not exempt",
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
            "The app verifies this with isIgnoringBatteryOptimizations, so it cannot claim " +
                "success falsely. If the OEM dialog does not appear, the app falls back to " +
                "the standard Android battery-optimisation screen.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    InfoBanner(
        "Use a charger for 24 h soak tests. On battery, runtime depends on the pack, the " +
            "performance tier and the thermal governor; measure it with Tools → Soak test.",
        ForestAmber,
        "Power",
    )
}

@Composable
private fun OemStep(
    protection: ProtectionReport,
    settings: SettingsStore,
    profile: OemProfile,
    checker: ProtectionChecker,
    refresh: () -> Unit,
) {
    SectionCard(
        title = "${profile.displayName} background protections",
        subtitle = "OS-verifiable switches are marked verified. Everything else has no public " +
            "API, so the app shows the maker's steps and records only your confirmation.",
    ) {
        val checks = listOf(
            OemCheck("battery", "battery_exemption", "Battery optimisation exemption", false, true),
            OemCheck("background_activity", "background_activity", "Allow background activity", false, false),
            OemCheck("autostart", "auto_launch", "Auto-launch", false, false),
            OemCheck("recents", "recents_lock", "Lock in recents", false, false),
            OemCheck("overlay", "overlay", "Display over other apps", true, true),
            OemCheck("charge_limit", "charge_limit", "Charge limit / battery care", true, false),
            OemCheck("battery_health", "battery_health", "Battery swelling check", true, false),
        )
        for (check in checks) {
            val item = protection.items.firstOrNull { it.id == check.itemId }
            if (item == null && profile.stepsFor(check.step).isEmpty()) continue
            OemRow(
                title = check.title,
                item = item,
                steps = profile.stepsFor(check.step),
                optional = check.optional || item?.optional == true,
                verifiedByOs = check.verifiedByOs,
                onOpen = { checker.openStep(check.step) },
                onConfirm = {
                    when (check.step) {
                        "background_activity" -> settings.backgroundActivityConfirmed = true
                        "autostart" -> settings.autoLaunchConfirmed = true
                        "recents" -> settings.recentsLockedConfirmed = true
                        "overlay" -> settings.overlayConfirmed = true
                        "charge_limit" -> settings.chargeLimitConfirmed = true
                        "battery_health" -> settings.batteryHealthInspected = true
                    }
                    refresh()
                },
            )
        }
        if (profile.notes.isNotBlank()) {
            Text(
                profile.notes,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Maker-specific guidance: ${profile.dontkillmyappUrl}",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    InfoBanner(
        "The Status tab keeps showing a warning while a required protection is missing. " +
            "The app never pretends an unverifiable switch is enabled.",
        ForestAmber,
    )
}

private data class OemCheck(
    val step: String,
    val itemId: String,
    val title: String,
    val optional: Boolean,
    val verifiedByOs: Boolean,
)

@Composable
private fun OemRow(
    title: String,
    item: ProtectionItem?,
    steps: List<String>,
    optional: Boolean,
    verifiedByOs: Boolean,
    onOpen: () -> Unit,
    onConfirm: () -> Unit,
) {
    val ok = item?.ok == true
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title + if (optional) " (optional)" else "", modifier = Modifier.weight(1f), fontSize = 14.sp)
            StatusPill(
                when {
                    ok && verifiedByOs -> "VERIFIED"
                    ok -> "CONFIRMED"
                    optional -> "OPTIONAL"
                    else -> "MISSING"
                },
                if (ok) ForestGreen else if (optional) ForestMuted else ForestAmber,
            )
        }
        if (steps.isNotEmpty()) {
            for (line in steps) {
                Text("• $line", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            item?.detail?.let {
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!ok) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpen) { Text("Open settings", fontSize = 12.sp) }
                if (!verifiedByOs) {
                    Button(onClick = onConfirm) { Text("I did it", fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun ContinuityStep(status: NodeStatus, settings: SettingsStore) {
    val context = LocalContext.current
    var minutes by remember { mutableIntStateOf(settings.continuityMinutes) }
    val result = remember(status.continuity, minutes) { parseContinuity(status.continuity) }
    SectionCard(
        title = "Screen-off continuity self-test",
        subtitle = "The most common failure is the maker freezing the app after the screen " +
            "turns off. This counts analysed audio windows while you leave the phone alone.",
    ) {
        SettingSlider(
            "Test length",
            minutes.toFloat(),
            1f..60f,
            "$minutes min",
            {
                minutes = it.toInt()
                settings.continuityMinutes = minutes
            },
            "Keep monitoring running; screen state cannot be forced by an app.",
        )
        if (!status.running) {
            InfoBanner(
                "Start monitoring on the Status tab before running the continuity test.",
                ForestAmber,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    NodeService.action(
                        context,
                        NodeService.ACTION_CONTINUITY_START,
                    )
                },
                enabled = status.running,
            ) { Text("Start test", fontSize = 12.sp) }
            OutlinedButton(
                onClick = {
                    NodeService.action(
                        context,
                        NodeService.ACTION_CONTINUITY_STOP,
                    )
                },
                enabled = status.running,
            ) { Text("Stop and finish", fontSize = 12.sp) }
        }
        Text(
            "Turn the screen off and leave the phone still for $minutes min, then stop the test.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (result != null) {
            val pass = result.first == "PASS"
            KeyValueRow(
                "Last result",
                result.first,
                if (pass) ForestGreen else ForestRed,
            )
            KeyValueRow("Coverage", result.second)
        } else {
            Text("No continuity result recorded yet.", fontSize = 12.sp, color = ForestMuted)
        }
    }
}

/** Returns (outcome, "coverage, gaps") or null. */
private fun parseContinuity(json: String): Pair<String, String>? = runCatching {
    if (json.isBlank()) return null
    val o = JSONObject(json)
    val outcome = o.optString("outcome", "")
    if (outcome.isBlank()) return null
    val coverage = o.optDouble("coverage", 0.0)
    val gaps = o.optInt("gap_events", 0)
    outcome to "%.0f%%, %d gap event(s)".format(Locale.US, coverage * 100, gaps)
}.getOrNull()

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
            "when the phone is on Wi-Fi; 127.0.0.1 only works with adb reverse. A Wi-Fi-only " +
            "tablet works the same way.",
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
        "Recommended: a 4G-only SIM with a small data plan, or Wi-Fi. The node sends a few " +
            "KB per heartbeat and nothing else unless streaming is enabled.",
        ForestGreen,
        "Connectivity",
    )
}

@Composable
private fun FinishStep(
    status: NodeStatus,
    permissions: PermissionSnapshot,
    protection: ProtectionReport,
    profile: OemProfile,
) {
    SectionCard(title = "Checklist", subtitle = "Verified items are read from the OS; the " +
        "rest are what you confirmed in this wizard.") {
        CheckRow("Microphone permission", permissions.mic, verified = true)
        CheckRow("Notification permission", permissions.notifications, verified = true)
        CheckRow("Battery exemption", ok(protection, "battery_exemption"), verified = true)
        CheckRow("Background activity", ok(protection, "background_activity"), verified = false)
        CheckRow("Auto-launch", ok(protection, "auto_launch"), verified = false)
        CheckRow("Recents lock", ok(protection, "recents_lock"), verified = false)
        CheckRow("Overlay permission (optional)", ok(protection, "overlay"), verified = true, optional = true)
        CheckRow("Charge limit set (optional)", ok(protection, "charge_limit"), verified = false, optional = true)
        CheckRow("Battery swelling checked (optional)", ok(protection, "battery_health"), verified = false, optional = true)
        CheckRow("Service currently running", status.running, verified = true)
    }
    InfoBanner(
        "${profile.displayName}: run Diagnostics → Benchmark once so the app can pick a " +
            "performance tier for this handset. Until then it starts conservatively at tier C.",
        ForestAmber,
        "Next step",
    )
    Text(
        "You can change every value later in Settings. The Status tab keeps showing a " +
            "warning while a required protection is missing.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun ok(protection: ProtectionReport, id: String): Boolean =
    protection.items.firstOrNull { it.id == id }?.ok == true

@Composable
private fun CheckRow(
    label: String,
    ok: Boolean,
    verified: Boolean,
    optional: Boolean = false,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 13.sp)
        StatusPill(
            when {
                ok && verified -> "VERIFIED"
                ok -> "CONFIRMED"
                optional -> "OPTIONAL"
                else -> "MISSING"
            },
            if (ok) ForestGreen else if (optional) ForestMuted else ForestAmber,
        )
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
