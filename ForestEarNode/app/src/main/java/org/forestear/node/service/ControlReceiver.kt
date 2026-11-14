package org.forestear.node.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import org.forestear.node.util.FLog

/**
 * adb/debug control surface. These actions are exported so a laptop can drive a
 * deployed node:
 *
 *   adb shell am broadcast -a org.forestear.node.SOAK_ON -p org.forestear.node
 *   adb shell am broadcast -a org.forestear.node.START   -p org.forestear.node
 *   adb shell am broadcast -a org.forestear.node.STOP    -p org.forestear.node
 *
 * START and STOP are routed through the service's own actions so a debug stop
 * disables auto-start exactly like the in-app Stop button (the watchdog will not
 * immediately undo it). On Android 14 a background START cannot upgrade to a
 * microphone foreground service; open the app once after booting.
 */
class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        FLog.i("control action: $action")
        val mapped = when (action) {
            ACTION_START -> NodeService.ACTION_START
            ACTION_STOP -> NodeService.ACTION_STOP
            ACTION_SOAK_ON -> NodeService.ACTION_SOAK_ON
            ACTION_SOAK_OFF -> NodeService.ACTION_SOAK_OFF
            ACTION_COLLECT_ON -> NodeService.ACTION_COLLECT_ON
            ACTION_COLLECT_OFF -> NodeService.ACTION_COLLECT_OFF
            ACTION_STREAM_ON -> NodeService.ACTION_STREAM_ON
            ACTION_STREAM_OFF -> NodeService.ACTION_STREAM_OFF
            else -> return
        }
        val svc = Intent(context, NodeService::class.java).setAction(mapped)
        val started = runCatching { ContextCompat.startForegroundService(context, svc) }
            .onFailure { FLog.e("control action failed: $action", it) }
            .isSuccess
        if (!started) {
            // STOP must still work if the process is in a state where a
            // foreground-service start is refused.
            if (mapped == NodeService.ACTION_STOP) {
                runCatching { context.stopService(Intent(context, NodeService::class.java)) }
            }
        }
    }

    companion object {
        const val ACTION_START = "org.forestear.node.START"
        const val ACTION_STOP = "org.forestear.node.STOP"
        const val ACTION_SOAK_ON = "org.forestear.node.SOAK_ON"
        const val ACTION_SOAK_OFF = "org.forestear.node.SOAK_OFF"
        const val ACTION_COLLECT_ON = "org.forestear.node.COLLECT_ON"
        const val ACTION_COLLECT_OFF = "org.forestear.node.COLLECT_OFF"
        const val ACTION_STREAM_ON = "org.forestear.node.STREAM_ON"
        const val ACTION_STREAM_OFF = "org.forestear.node.STREAM_OFF"
    }
}
