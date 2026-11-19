package org.forestear.node.transport

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.forestear.node.settings.SettingsStore
import org.forestear.node.util.FLog
import org.forestear.node.util.TimeUtils

/** Records sent/delivery status reported by the carrier for SMS fallback. */
class SmsDeliveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val settings = SettingsStore.get(context)
        val id = intent.getStringExtra(EXTRA_ID) ?: "?"
        val number = intent.getStringExtra(EXTRA_NUMBER) ?: "?"
        when (intent.action) {
            ACTION_SENT -> {
                val status = if (resultCode == Activity.RESULT_OK) "sent" else "send_failed($resultCode)"
                settings.lastSmsStatus = "$status $number ${TimeUtils.isoUtc().take(19)}"
                FLog.i("SMS sent result $status for $number (id=$id)")
            }
            ACTION_DELIVERED -> {
                val status = if (resultCode == Activity.RESULT_OK) "delivered" else "delivery_failed($resultCode)"
                settings.lastSmsStatus = "$status $number ${TimeUtils.isoUtc().take(19)}"
                FLog.i("SMS delivery result $status for $number (id=$id)")
            }
        }
    }

    companion object {
        const val ACTION_SENT = "org.forestear.node.SMS_SENT"
        const val ACTION_DELIVERED = "org.forestear.node.SMS_DELIVERED"
        const val EXTRA_ID = "message_id"
        const val EXTRA_NUMBER = "number"
    }
}
