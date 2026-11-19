package org.forestear.node.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.forestear.node.settings.SettingsStore
import org.forestear.node.ui.ForestAmber
import org.forestear.node.ui.ForestBackground
import org.forestear.node.ui.ForestMuted
import org.forestear.node.ui.ForestSurface
import org.forestear.node.ui.ForestText
import org.forestear.node.ui.SectionCard

/**
 * First-launch consent gate. The user must acknowledge the notice before the
 * app can be used. This is a reminder of the operator's legal duty, not legal
 * cover by itself.
 */
@Composable
fun ConsentScreen(onAccept: () -> Unit) {
    var checked by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(ForestBackground)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Before you deploy", fontSize = 22.sp, color = ForestText)
        Text(
            "ForestEar Node listens to the environment continuously. Read this " +
                "before starting the service.",
            fontSize = 13.sp,
            color = ForestMuted,
        )

        SectionCard(title = "What the app does") {
            Bullet("Analyses 1 s audio windows on the phone and keeps scores, not audio.")
            Bullet("Sends alert events, heartbeats and live telemetry to your backend.")
            Bullet("Never uploads speech. Speech-dominated windows are suppressed.")
            Bullet("Stores raw ambient audio only if you explicitly enable collection.")
        }

        SectionCard(title = "Your responsibilities", accent = ForestAmber) {
            Bullet("Check local law on recording ambient audio and consent.")
            Bullet("Get the landowner's or manager's permission.")
            Bullet("Post a visible notice at the site that sensors are operating.")
            Bullet("Tell anyone hosting a node what it records and when.")
            Bullet("Do not deploy where private conversations are expected.")
        }

        SectionCard(title = "Retention") {
            Text(
                "Events and alert clips have no automatic expiry in this " +
                    "prototype. Decide a retention period, write it down, and " +
                    "delete old data yourself. The Privacy screen shows the " +
                    "current setting and can delete local audio.",
                fontSize = 12.sp,
                color = ForestMuted,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { checked = it })
            Text(
                "I understand the notice and will meet these obligations " +
                    "before deploying this node.",
                fontSize = 12.sp,
                color = ForestText,
            )
        }

        Button(
            onClick = onAccept,
            enabled = checked,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Accept and continue")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Version ${SettingsStore.CONSENT_VERSION} · this acceptance is " +
                "stored locally with a timestamp.",
            fontSize = 11.sp,
            color = ForestMuted,
        )
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.fillMaxWidth()) {
        Text("•  ", fontSize = 12.sp, color = ForestMuted)
        Text(text, fontSize = 12.sp, color = ForestText)
    }
}
