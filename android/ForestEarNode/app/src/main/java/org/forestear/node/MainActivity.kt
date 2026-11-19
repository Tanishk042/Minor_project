package org.forestear.node

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import org.forestear.node.service.NodeService
import org.forestear.node.ui.AppRoot
import org.forestear.node.ui.ForestEarTheme
import org.forestear.node.ui.permissionSnapshot

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            var permissionEpoch by remember { mutableIntStateOf(0) }
            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { permissionEpoch++ }
            val permissions = remember(permissionEpoch) { permissionSnapshot(context) }

            // Android 14: a boot/watchdog start can only create a dataSync
            // foreground service. Once the activity is visible, upgrade it to a
            // microphone FGS and start the pipeline. Only do this when the
            // service is already running so opening the app never silently
            // starts monitoring.
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                permissionEpoch++
                if (NodeService.isRunning && permissionSnapshot(context).mic) {
                    NodeService.activityStarted(context)
                }
            }

            ForestEarTheme {
                AppRoot(
                    permissions = permissions,
                    requestPermissions = { launcher.launch(it) },
                )
            }
        }
    }
}
