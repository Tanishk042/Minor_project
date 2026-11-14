package org.forestear.node.service

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import org.forestear.node.settings.SettingsStore

enum class ProtectionAction { BATTERY_EXEMPTION, APP_DETAILS, AUTO_LAUNCH, RECENTS }

data class ProtectionItem(
    val id: String,
    val title: String,
    val ok: Boolean,
    val detail: String,
    val action: ProtectionAction? = null,
)

data class ProtectionReport(
    val items: List<ProtectionItem>,
    val checkedAtMs: Long = System.currentTimeMillis(),
) {
    val allOk: Boolean get() = items.all { it.ok }
    val missing: List<ProtectionItem> get() = items.filter { !it.ok }
}

/**
 * OxygenOS/ColorOS kill protection checks.
 *
 * Battery-optimisation exemption is verifiable with
 * `isIgnoringBatteryOptimizations`. Auto-launch, "allow background activity" and
 * recents locking are OEM-only switches with no public API, so the wizard opens
 * the closest deep link and asks the user to confirm; the app never claims they
 * are enabled without that confirmation.
 */
class ProtectionChecker(private val context: Context, private val settings: SettingsStore) {

    private val pm = context.packageManager

    fun check(): ProtectionReport {
        val items = mutableListOf<ProtectionItem>()
        val audio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        items += ProtectionItem(
            "mic_permission", "Microphone permission", audio,
            if (audio) "granted" else "RECORD_AUDIO denied — the service cannot listen"
        )
        if (Build.VERSION.SDK_INT >= 33) {
            val notif = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            items += ProtectionItem(
                "notifications", "Notification permission", notif,
                if (notif) "granted" else "denied — Android may hide the foreground notification"
            )
        }
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val exempt = power.isIgnoringBatteryOptimizations(context.packageName)
        items += ProtectionItem(
            "battery_exemption", "Battery optimisation exemption", exempt,
            if (exempt) "app is exempt" else "OxygenOS will throttle or kill the service",
            ProtectionAction.BATTERY_EXEMPTION
        )
        items += ProtectionItem(
            "background_activity", "Allow background activity", settings.backgroundActivityConfirmed,
            if (settings.backgroundActivityConfirmed) "confirmed by user"
            else "open app details → Battery → Allow background activity",
            ProtectionAction.APP_DETAILS
        )
        items += ProtectionItem(
            "auto_launch", "Auto-launch enabled", settings.autoLaunchConfirmed,
            if (settings.autoLaunchConfirmed) "confirmed by user"
            else "enable in OxygenOS auto-launch settings (deep link below)",
            ProtectionAction.AUTO_LAUNCH
        )
        items += ProtectionItem(
            "recents_lock", "Locked in recents", settings.recentsLockedConfirmed,
            if (settings.recentsLockedConfirmed) "confirmed by user"
            else "open Recents, long-press ForestEar Node, tap the lock icon",
            ProtectionAction.RECENTS
        )
        if (settings.smsEnabled) {
            val sms = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
                PackageManager.PERMISSION_GRANTED
            items += ProtectionItem(
                "sms_permission", "SMS permission", sms,
                if (sms) "granted" else "SEND_SMS denied — fallback alerts disabled"
            )
        }
        return ProtectionReport(items)
    }

    fun batteryExemptionIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))

    fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))

    fun autoLaunchIntent(): Intent? {
        val candidates = listOf(
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ),
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity"
            ),
            ComponentName(
                "com.oplus.safecenter",
                "com.oplus.safecenter.permission.startup.StartupAppListActivity"
            ),
            ComponentName(
                "com.oplus.safecenter",
                "com.oplus.safecenter.startupapp.StartupAppListActivity"
            ),
            ComponentName(
                "com.oneplus.security",
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
            ),
            ComponentName(
                "com.oneplus.security",
                "com.oneplus.security.startupapp.StartupAppListActivity"
            ),
        )
        for (component in candidates) {
            val ok = runCatching {
                pm.getActivityInfo(component, 0)
                true
            }.getOrDefault(false)
            if (ok) {
                return Intent().setComponent(component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return null
    }

    /** Opens the best available screen for an action; false if nothing handled it. */
    fun open(action: ProtectionAction): Boolean {
        val intent = when (action) {
            ProtectionAction.BATTERY_EXEMPTION -> batteryExemptionIntent()
            ProtectionAction.APP_DETAILS -> appDetailsIntent()
            ProtectionAction.AUTO_LAUNCH -> autoLaunchIntent() ?: appDetailsIntent()
            ProtectionAction.RECENTS -> null
        } ?: return false
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }
}
