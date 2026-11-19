package org.forestear.node.transport

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import org.forestear.node.settings.SettingsStore
import org.forestear.node.util.FLog
import org.forestear.node.util.TimeUtils
import org.json.JSONArray
import org.json.JSONObject

data class SimInfo(
    val subscriptionId: Int,
    val slotIndex: Int,
    val carrier: String,
    val displayName: String,
)

/**
 * SMS fallback with dual-SIM awareness.
 *
 * The Nord CE 2 Lite has two nano-SIM slots. The user picks a subscription in
 * Settings; `SmsManager.getSmsManagerForSubscriptionId` then binds the send to
 * that SIM. Compact payloads are < 160 chars so they are a single SMS, and
 * rate limiting is persisted across restarts.
 */
class SmsSender(
    context: Context,
    private val settings: SettingsStore,
    private val transport: Transport,
) {
    private val appContext = context.applicationContext
    private val lock = Any()

    fun smsCapable(): Boolean =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    fun permissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    fun subscriptions(): List<SimInfo> {
        val sm = appContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
            as? SubscriptionManager ?: return emptyList()
        return runCatching {
            sm.activeSubscriptionInfoList.orEmpty().map { info: SubscriptionInfo ->
                SimInfo(
                    subscriptionId = info.subscriptionId,
                    slotIndex = info.simSlotIndex,
                    carrier = info.carrierName?.toString() ?: "",
                    displayName = info.displayName?.toString() ?: "SIM ${info.simSlotIndex + 1}",
                )
            }
        }.onFailure { FLog.w("subscription list failed: ${it.message}") }.getOrDefault(emptyList())
    }

    fun sendAlert(
        payload: JSONObject,
        minIntervalS: Long = 300L,
        maxPerHour: Int = 6,
    ): Boolean {
        if (!settings.smsEnabled) return false
        val numbers = settings.smsNumberList()
        if (numbers.isEmpty()) {
            FLog.w("SMS enabled but no destination numbers configured")
            return false
        }
        if (!permissionGranted()) {
            settings.lastSmsStatus = "SEND_SMS permission denied"
            return false
        }
        val now = System.currentTimeMillis()
        if (!canSend(now, minIntervalS, maxPerHour)) {
            settings.lastSmsStatus = "rate limited"
            return false
        }
        val text = transport.compactPayload(payload)
        val manager = smsManager()
        var anyOk = false
        for ((index, number) in numbers.withIndex()) {
            val ok = runCatching {
                val id = "$now-$index"
                manager.sendTextMessage(
                    number, null, text,
                    sentIntent(id, number), deliveryIntent(id, number)
                )
                true
            }.onFailure {
                FLog.e("SMS send failed to $number", it)
            }.getOrDefault(false)
            if (ok) anyOk = true
        }
        if (anyOk) {
            recordSend(now)
            settings.lastSmsStatus = "sent ${TimeUtils.isoUtc(now)}"
            FLog.i("SMS fallback sent (${text.length} chars) to ${numbers.size} number(s)")
        } else {
            settings.lastSmsStatus = "send failed"
        }
        return anyOk
    }

    /** Self-test: one short message, bypassing the alert rate limit. */
    fun sendTest(number: String): Boolean {
        if (!permissionGranted()) return false
        val text = "ForestEar Node self-test ${TimeUtils.isoUtc().take(19)}"
        return runCatching {
            smsManager().sendTextMessage(
                number, null, text,
                sentIntent("test", number), deliveryIntent("test", number)
            )
            settings.lastSmsStatus = "test sent"
            true
        }.onFailure { FLog.e("test SMS failed", it) }.getOrDefault(false)
    }

    fun canSend(nowMs: Long, minIntervalS: Long, maxPerHour: Int): Boolean = synchronized(lock) {
        val times = sentTimes()
        val cutoff = nowMs - 3_600_000L
        val recent = times.filter { it >= cutoff }
        if (recent.size >= maxPerHour) return false
        val last = recent.maxOrNull() ?: return true
        return nowMs - last >= minIntervalS * 1000L
    }

    fun recordSend(nowMs: Long) = synchronized(lock) {
        val cutoff = nowMs - 3_600_000L
        val arr = JSONArray()
        sentTimes().filter { it >= cutoff }.forEach { arr.put(it) }
        arr.put(nowMs)
        settings.smsSentTimes = arr.toString()
    }

    private fun sentTimes(): List<Long> = runCatching {
        val arr = JSONArray(settings.smsSentTimes)
        (0 until arr.length()).map { arr.getLong(it) }
    }.getOrDefault(emptyList())

    private fun smsManager(): SmsManager {
        val chosen = settings.smsSubscriptionId
        val subId = if (chosen >= 0) chosen else SubscriptionManager.getDefaultSubscriptionId()
        return if (subId >= 0) {
            SmsManager.getSmsManagerForSubscriptionId(subId)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }

    private fun sentIntent(id: String, number: String): PendingIntent {
        val intent = Intent(appContext, SmsDeliveryReceiver::class.java)
            .setAction(SmsDeliveryReceiver.ACTION_SENT)
            .putExtra(SmsDeliveryReceiver.EXTRA_ID, id)
            .putExtra(SmsDeliveryReceiver.EXTRA_NUMBER, number)
        return PendingIntent.getBroadcast(
            appContext, id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun deliveryIntent(id: String, number: String): PendingIntent {
        val intent = Intent(appContext, SmsDeliveryReceiver::class.java)
            .setAction(SmsDeliveryReceiver.ACTION_DELIVERED)
            .putExtra(SmsDeliveryReceiver.EXTRA_ID, id)
            .putExtra(SmsDeliveryReceiver.EXTRA_NUMBER, number)
        return PendingIntent.getBroadcast(
            appContext, (id.hashCode() + 1), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
