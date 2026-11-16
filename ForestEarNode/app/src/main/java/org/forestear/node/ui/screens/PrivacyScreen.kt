package org.forestear.node.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.forestear.node.audio.CollectionRecorder
import org.forestear.node.config.AppConfig
import org.forestear.node.service.NodeStatus
import org.forestear.node.settings.MODE_PLAINTEXT_FALLBACK
import org.forestear.node.settings.SettingsStore
import org.forestear.node.ui.ForestAmber
import org.forestear.node.ui.ForestGreen
import org.forestear.node.ui.ForestMuted
import org.forestear.node.ui.ForestRed
import org.forestear.node.ui.InfoBanner
import org.forestear.node.ui.KeyValueRow
import org.forestear.node.ui.SectionCard
import org.forestear.node.util.AndroidAssetSource
import java.util.Locale

/**
 * Privacy and retention screen: consent status, token encryption status,
 * retention setting and a local-audio delete action. Every status shown here
 * is read from the device; nothing is assumed.
 */
@Composable
fun PrivacyScreen(status: NodeStatus, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsStore.get(context) }
    val config = remember { runCatching { AppConfig.load(AndroidAssetSource(context)) }.getOrNull() }

    var consentAt by remember { mutableStateOf(settings.consentAcceptedUtc) }
    // Reading the token first migrates any legacy plaintext token, so the mode
    // shown here reflects the real state rather than "unset".
    var tokenMode by remember { mutableStateOf(settings.token.let { settings.tokenStorageMode }) }
    var retention by remember { mutableIntStateOf(settings.retentionDays) }
    var files by remember { mutableIntStateOf(0) }
    var bytes by remember { mutableLongStateOf(0L) }
    var notice by remember { mutableStateOf("") }

    fun refreshStats() {
        if (config == null) return
        runCatching {
            CollectionRecorder(context, settings, config).use { it.stats() }
        }.onSuccess { s ->
            files = s.chunks
            bytes = s.bytes
        }
    }

    LaunchedEffect(Unit) { refreshStats() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(
            title = "Privacy and retention",
            subtitle = "What this phone stores, and what leaves it.",
        ) {
            Text(
                "Full policy: docs/PRIVACY.md in the ForestEar repository.",
                fontSize = 11.sp,
                color = ForestMuted,
            )
        }

        SectionCard(title = "Consent") {
            KeyValueRow(
                "Accepted",
                if (settings.consentAccepted) "yes" else "no",
                if (settings.consentAccepted) ForestGreen else ForestRed,
            )
            KeyValueRow(
                "Accepted at",
                consentAt.ifBlank { "never" }.let { if (it.length > 19) it.take(19) + "Z" else it },
            )
            KeyValueRow("Notice version", settings.consentVersion.toString())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        settings.revokeConsent()
                        consentAt = ""
                        notice = "Consent revoked. The gate will show again on next launch."
                    },
                ) { Text("Revoke consent", fontSize = 12.sp) }
            }
            if (notice.isNotBlank()) {
                InfoBanner(notice, ForestAmber)
            }
        }

        SectionCard(title = "Backend token storage", accent = tokenAccent(tokenMode)) {
            KeyValueRow(
                "Mode",
                when (tokenMode) {
                    MODE_PLAINTEXT_FALLBACK -> "PLAINTEXT FALLBACK (not secure)"
                    "unset" -> "not set"
                    else -> "encrypted (Android Keystore AES/GCM)"
                },
                tokenAccent(tokenMode),
            )
            if (tokenMode == MODE_PLAINTEXT_FALLBACK) {
                Text(
                    "The Keystore path failed on this device, so the token is " +
                        "stored in plain SharedPreferences. Do not deploy this " +
                        "build as-is.",
                    fontSize = 12.sp,
                    color = ForestRed,
                )
            }
            Text(
                "Settings export never includes the token. Re-enter it after " +
                    "importing on a new phone.",
                fontSize = 11.sp,
                color = ForestMuted,
            )
            OutlinedButton(
                onClick = {
                    val tok = settings.token
                    if (tok.isBlank()) {
                        notice = "No token set; nothing to re-encrypt."
                    } else {
                        settings.token = tok
                        tokenMode = settings.tokenStorageMode
                        notice = if (settings.tokenEncrypted) {
                            "Token re-encrypted with the Android Keystore."
                        } else {
                            "Encryption unavailable; token is still plaintext."
                        }
                    }
                },
            ) { Text("Re-encrypt token now", fontSize = 12.sp) }
        }

        SectionCard(title = "Retention", accent = ForestAmber) {
            KeyValueRow("Stated retention", "$retention day(s)")
            Text(
                "This prototype does not delete events or clips automatically. " +
                    "Treat this value as the operator's written policy; enforce " +
                    "it on the backend.",
                fontSize = 12.sp,
                color = ForestMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        retention = (retention - 10).coerceAtLeast(1)
                        settings.retentionDays = retention
                    },
                ) { Text("-10 days", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = {
                        retention = (retention + 10).coerceAtMost(3650)
                        settings.retentionDays = retention
                    },
                ) { Text("+10 days", fontSize = 12.sp) }
            }
        }

        SectionCard(title = "Local audio") {
            KeyValueRow("Collected chunks", "$files file(s)")
            KeyValueRow("Size on disk", fmtBytes(bytes))
            KeyValueRow(
                "Collection",
                if (settings.collectionEnabled) "enabled" else "off (default)",
                if (settings.collectionEnabled) ForestAmber else ForestGreen,
            )
            Button(
                onClick = {
                    val removed = runCatching {
                        CollectionRecorder(context, settings, config!!).use { it.clearAll() }
                    }
                    notice = removed.fold(
                        { "Deleted $it collected file(s) from this phone." },
                        { "Delete failed: ${it.message}" },
                    )
                    refreshStats()
                },
                enabled = config != null,
                colors = ButtonDefaults.buttonColors(containerColor = ForestRed),
            ) { Text("Delete collected audio now", fontSize = 12.sp) }
            if (status.running) {
                Text(
                    "Monitoring is running; the recorder may create a new chunk " +
                        "immediately after deletion. Turn collection off in Tools " +
                        "to stop that.",
                    fontSize = 11.sp,
                    color = ForestAmber,
                )
            }
            Text(
                "Speech-contaminated chunks are dropped whole and never saved. " +
                    "This is best-effort, not a guarantee.",
                fontSize = 11.sp,
                color = ForestMuted,
            )
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back to settings")
        }
    }
}

private fun tokenAccent(mode: String) = when (mode) {
    MODE_PLAINTEXT_FALLBACK -> ForestRed
    "unset" -> ForestAmber
    else -> ForestGreen
}

private fun fmtBytes(b: Long): String = when {
    b >= 1_000_000_000 -> String.format(Locale.US, "%.2f GB", b / 1e9)
    b >= 1_000_000 -> String.format(Locale.US, "%.1f MB", b / 1e6)
    b >= 1_000 -> String.format(Locale.US, "%.1f kB", b / 1e3)
    else -> "$b B"
}
