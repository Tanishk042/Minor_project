package org.forestear.node.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.forestear.node.config.AppConfig
import org.forestear.node.service.NodeService
import org.forestear.node.service.NodeState
import org.forestear.node.settings.SettingsStore
import org.forestear.node.ui.screens.CalibrationScreen
import org.forestear.node.ui.screens.ConsentScreen
import org.forestear.node.ui.screens.DiagnosticsScreen
import org.forestear.node.ui.screens.PrivacyScreen
import org.forestear.node.ui.screens.SettingsScreen
import org.forestear.node.ui.screens.SetupScreen
import org.forestear.node.ui.screens.StatusScreen
import org.forestear.node.ui.screens.ToolsScreen
import org.forestear.node.util.AndroidAssetSource

enum class AppTab(val label: String, val icon: ImageVector, val inBar: Boolean = true) {
    STATUS("Status", Icons.Filled.Home),
    SETUP("Setup", Icons.Filled.CheckCircle),
    DIAGNOSTICS("Diagnose", Icons.Filled.Info),
    SETTINGS("Settings", Icons.Filled.Settings),
    TOOLS("Tools", Icons.Filled.Build),
    CALIBRATION("Calibration", Icons.Filled.Build, inBar = false),
    PRIVACY("Privacy", Icons.Filled.Lock, inBar = false),
}

@Composable
fun AppRoot(
    permissions: PermissionSnapshot,
    requestPermissions: (Array<String>) -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { SettingsStore.get(context) }
    val config = remember {
        runCatching { AppConfig.load(AndroidAssetSource(context)) }.getOrNull()
    }
    val status by NodeState.flow.collectAsStateWithLifecycle()

    var tabName by rememberSaveable {
        mutableStateOf(if (settings.setupComplete) AppTab.STATUS.name else AppTab.SETUP.name)
    }
    val tab = runCatching { AppTab.valueOf(tabName) }.getOrDefault(AppTab.STATUS)
    var consent by remember { mutableStateOf(settings.consentAccepted) }

    fun startMonitoring() {
        if (!permissions.mic) {
            requestPermissions(PermissionGroups.core())
            return
        }
        NodeService.start(context)
    }

    if (config == null) {
        Box(Modifier.fillMaxSize().background(ForestBackground), contentAlignment = Alignment.Center) {
            Text(
                "Asset configuration failed to load.\nReinstall the app.",
                color = ForestRed,
                modifier = Modifier.padding(24.dp),
            )
        }
        return
    }

    if (!consent) {
        ConsentScreen(
            onAccept = {
                settings.acceptConsent()
                consent = true
            },
        )
        return
    }

    Scaffold(
        containerColor = ForestBackground,
        bottomBar = {
            NavigationBar(containerColor = ForestSurface) {
                for (item in AppTab.values().filter { it.inBar }) {
                    NavigationBarItem(
                        selected = tab == item || (tab == AppTab.CALIBRATION && item == AppTab.TOOLS),
                        onClick = { tabName = item.name },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label, fontSize = 11.sp) },
                    )
                }
            }
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            Header(status.running, status.starting, settings.nodeId)
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    AppTab.STATUS -> StatusScreen(
                        status = status,
                        permissions = permissions,
                        frames = config.features.frames,
                        mels = config.features.nMels,
                        onStart = { startMonitoring() },
                        onStop = { NodeService.stop(context) },
                        onRequestMic = { requestPermissions(PermissionGroups.core()) },
                        onOpenSetup = { tabName = AppTab.SETUP.name },
                    )
                    AppTab.SETUP -> SetupScreen(
                        status = status,
                        permissions = permissions,
                        onRequest = requestPermissions,
                        onDone = { start ->
                            settings.setupComplete = true
                            if (start) startMonitoring()
                            tabName = AppTab.STATUS.name
                        },
                    )
                    AppTab.DIAGNOSTICS -> DiagnosticsScreen(status = status)
                    AppTab.SETTINGS -> SettingsScreen(
                        permissions = permissions,
                        onRequest = requestPermissions,
                        onOpenCalibration = { tabName = AppTab.CALIBRATION.name },
                        onOpenPrivacy = { tabName = AppTab.PRIVACY.name },
                    )
                    AppTab.TOOLS -> ToolsScreen(
                        status = status,
                        onOpenSetup = { tabName = AppTab.SETUP.name },
                        onOpenCalibration = { tabName = AppTab.CALIBRATION.name },
                    )
                    AppTab.CALIBRATION -> CalibrationScreen(
                        permissions = permissions,
                        onRequest = requestPermissions,
                        onBack = { tabName = AppTab.TOOLS.name },
                    )
                    AppTab.PRIVACY -> PrivacyScreen(
                        status = status,
                        onBack = { tabName = AppTab.SETTINGS.name },
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(running: Boolean, starting: Boolean, nodeId: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(ForestSurface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "ForestEar Node",
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                color = ForestText,
            )
            Text(
                "$nodeId · acoustic edge monitor",
                fontSize = 11.sp,
                color = ForestMuted,
            )
        }
        Spacer(Modifier.width(8.dp))
        StatusPill(
            when {
                starting -> "STARTING"
                running -> "LIVE"
                else -> "IDLE"
            },
            when {
                starting -> ForestAmber
                running -> ForestGreen
                else -> ForestMuted
            },
        )
    }
}
