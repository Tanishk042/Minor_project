package org.forestear.node.transport

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellSignalStrength
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import org.forestear.node.settings.SettingsStore
import org.forestear.node.util.FLog

data class SignalInfo(
    val dbm: Int?,
    val networkType: String,
    val operator: String?,
    val simLabel: String?,
)

/**
 * Signal strength + data network type for coverage mapping.
 *
 * `getSignalStrength()` needs API 28 (the Nord CE 2 Lite runs Android 12/13),
 * but the code degrades to a null dBm on API 26/27 instead of crashing.
 */
class SignalMonitor(context: Context, private val settings: SettingsStore) {
    private val appContext = context.applicationContext
    private val telephony =
        appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    fun sample(): SignalInfo {
        val tm = telephony ?: return SignalInfo(null, "no telephony", null, null)
        val granted = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            return SignalInfo(null, "permission denied", null, null)
        }
        val subId = settings.smsSubscriptionId
        val scoped = if (subId >= 0) {
            runCatching { tm.createForSubscriptionId(subId) }.getOrDefault(tm)
        } else tm
        val type = runCatching {
            if (Build.VERSION.SDK_INT >= 24) scoped.dataNetworkType else scoped.networkType
        }.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
        val dbm = if (Build.VERSION.SDK_INT >= 28) {
            runCatching {
                scoped.signalStrength?.cellSignalStrengths
                    ?.mapNotNull { strength ->
                        runCatching { strength.dbm }.getOrNull()
                    }
                    ?.filter { it != CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN }
                    ?.maxOrNull()
            }.onFailure { FLog.d("signalStrength failed: ${it.message}") }.getOrNull()
        } else null
        val operator = runCatching { scoped.networkOperatorName?.ifBlank { null } }.getOrNull()
        val label = runCatching { scoped.simOperatorName?.ifBlank { null } }.getOrNull()
        return SignalInfo(dbm, networkTypeName(type), operator, label)
    }

    fun networkTypeName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
        TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
        TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO0"
        TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDOA"
        TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT"
        TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
        TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
        TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
        TelephonyManager.NETWORK_TYPE_IDEN -> "iDEN"
        TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDOB"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_EHRPD -> "eHRPD"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPAP"
        TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD-SCDMA"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
        20 -> "NR" // NETWORK_TYPE_NR (API 29 constant)
        else -> "UNKNOWN($type)"
    }
}
