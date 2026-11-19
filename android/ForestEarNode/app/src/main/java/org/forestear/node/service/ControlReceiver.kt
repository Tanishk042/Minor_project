package org.forestear.node.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import org.forestear.node.device.RecoveryLog
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
 *
 * RECORD_RECOVERY is handled here rather than in the service: the kill test has
 * to be able to record its result after the service process has died.
 */
class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        FLog.i("control action: $action")
        if (action == ACTION_RECORD_RECOVERY) {
            recordRecovery(context, intent)
            return
        }
        if (action == ACTION_CLEAR_RECOVERY) {
            runCatching { RecoveryLog(context).clear() }
                .onFailure { FLog.e("clear recovery log failed", it) }
            return
        }
        val mapped = when (action) {
            ACTION_START -> NodeService.ACTION_START
            ACTION_STOP -> NodeService.ACTION_STOP
            ACTION_SOAK_ON -> NodeService.ACTION_SOAK_ON
            ACTION_SOAK_OFF -> NodeService.ACTION_SOAK_OFF
            ACTION_COLLECT_ON -> NodeService.ACTION_COLLECT_ON
            ACTION_COLLECT_OFF -> NodeService.ACTION_COLLECT_OFF
            ACTION_STREAM_ON -> NodeService.ACTION_STREAM_ON
            ACTION_STREAM_OFF -> NodeService.ACTION_STREAM_OFF
            ACTION_CONTINUITY_START -> NodeService.ACTION_CONTINUITY_START
            ACTION_CONTINUITY_STOP -> NodeService.ACTION_CONTINUITY_STOP
            ACTION_REPROBE -> NodeService.ACTION_REPROBE
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

    private fun recordRecovery(context: Context, intent: Intent) {
        val path = intent.getStringExtra(EXTRA_PATH)?.trim().orEmpty()
        val outcome = intent.getStringExtra(EXTRA_OUTCOME)?.trim()?.uppercase().orEmpty()
        val detail = intent.getStringExtra(EXTRA_DETAIL).orEmpty()
        if (path.isBlank() || outcome !in setOf("PASS", "FAIL", "NOT RUN")) {
            FLog.w("ignored recovery record: path='$path' outcome='$outcome'")
            return
        }
        runCatching { RecoveryLog(context).record(path, outcome, detail) }
            .onFailure { FLog.e("record recovery result failed", it) }
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
        const val ACTION_CONTINUITY_START = "org.forestear.node.CONTINUITY_START"
        const val ACTION_CONTINUITY_STOP = "org.forestear.node.CONTINUITY_STOP"
        const val ACTION_REPROBE = "org.forestear.node.REPROBE"
        const val ACTION_RECORD_RECOVERY = "org.forestear.node.RECORD_RECOVERY"
        const val ACTION_CLEAR_RECOVERY = "org.forestear.node.CLEAR_RECOVERY"

        const val EXTRA_PATH = "path"
        const val EXTRA_OUTCOME = "outcome"
        const val EXTRA_DETAIL = "detail"
    }
}
