package org.forestear.node.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import org.forestear.node.device.OemIntents
import org.forestear.node.device.OemProfile
import org.forestear.node.device.OemProfileTable
import org.forestear.node.settings.SettingsStore
import org.forestear.node.util.AndroidAssetSource

enum class ProtectionAction { BATTERY_EXEMPTION, APP_DETAILS, AUTO_LAUNCH, RECENTS, OVERLAY }

data class ProtectionItem(
    val id: String,
    val title: String,
    val ok: Boolean,
    val detail: String,
    val action: ProtectionAction? = null,
    /** Optional items never make the node report "not protected". */
    val optional: Boolean = false,
)

data class ProtectionReport(
    val items: List<ProtectionItem>,
    val checkedAtMs: Long = System.currentTimeMillis(),
) {
    val allOk: Boolean get() = items.filterNot { it.optional }.all { it.ok }
    val missing: List<ProtectionItem> get() = items.filterNot { it.optional }.filter { !it.ok }
}

/**
 * Kill-protection checks for any Android manufacturer.
 *
 * Only the battery-optimisation exemption and the overlay permission can be
 * verified by the OS. Auto-launch, "allow background activity" and recents
 * locking are OEM-only switches with no public API, so the wizard opens the
 * closest deep link from the OEM table and asks the user to confirm; the app
 * never claims they are enabled without that confirmation.
 */
class ProtectionChecker(
    private val context: Context,
    private val settings: SettingsStore,
    table: OemProfileTable? = null,
    intents: OemIntents? = null,
) {
    private val oem: OemProfileTable = table ?: runCatching {
        OemProfileTable.load(AndroidAssetSource(context))
    }.getOrElse { OemProfileTable(listOf(OemProfileTable.fallback), emptyList(), "generic") }
    private val oemIntents: OemIntents = intents ?: OemIntents(context, oem)

    fun profile(): OemProfile = oemIntents.profile()

    /** Plain-language OEM steps for one checklist item (empty when none). */
    fun steps(step: String): List<String> = profile().stepsFor(step)

    fun check(): ProtectionReport {
        val items = mutableListOf<ProtectionItem>()
        val oemProfile = profile()
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
                if (notif) "granted"
                else "denied — Android may hide the foreground notification and alerts"
            )
        }
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val exempt = power.isIgnoringBatteryOptimizations(context.packageName)
        items += ProtectionItem(
            "battery_exemption", "Battery optimisation exemption", exempt,
            if (exempt) "app is exempt (verified by the OS)"
            else "not exempt — ${oemProfile.displayName} may throttle or kill the service",
            ProtectionAction.BATTERY_EXEMPTION
        )
        items += ProtectionItem(
            "background_activity", "Allow background activity", settings.backgroundActivityConfirmed,
            if (settings.backgroundActivityConfirmed) "confirmed by user"
            else oemProfile.stepsFor("background_activity").firstOrNull()
                ?: "open app details → Battery → Unrestricted",
            ProtectionAction.APP_DETAILS
        )
        items += ProtectionItem(
            "auto_launch", "Auto-launch enabled", settings.autoLaunchConfirmed,
            if (settings.autoLaunchConfirmed) "confirmed by user"
            else oemProfile.stepsFor("autostart").firstOrNull()
                ?: "enable auto-launch in ${oemProfile.displayName} settings",
            ProtectionAction.AUTO_LAUNCH
        )
        items += ProtectionItem(
            "recents_lock", "Locked in recents", settings.recentsLockedConfirmed,
            if (settings.recentsLockedConfirmed) "confirmed by user"
            else oemProfile.stepsFor("recents").firstOrNull()
                ?: "open Recents, long-press ForestEar Node, tap the lock icon",
            ProtectionAction.RECENTS
        )
        if (Build.VERSION.SDK_INT >= 23) {
            val overlay = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
            items += ProtectionItem(
                "overlay", "Display over other apps", overlay,
                if (overlay) "granted (verified by the OS)"
                else oemProfile.stepsFor("overlay").firstOrNull()
                    ?: "optional; may help some makers keep the service alive",
                ProtectionAction.OVERLAY,
                optional = true,
            )
        }
        items += ProtectionItem(
            "charge_limit", "Charge limit / battery care", settings.chargeLimitConfirmed,
            if (settings.chargeLimitConfirmed) "confirmed by user"
            else oemProfile.stepsFor("charge_limit").firstOrNull()
                ?: "optional; set an 80–85% charge limit if your phone offers one",
            optional = true,
        )
        items += ProtectionItem(
            "battery_health", "Battery swelling inspection", settings.batteryHealthInspected,
            if (settings.batteryHealthInspected) "confirmed by user"
            else oemProfile.stepsFor("battery_health").firstOrNull()
                ?: "optional; check for swelling or a bulging back cover before long deployments",
            optional = true,
        )
        if (settings.smsEnabled) {
            val sms = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
                PackageManager.PERMISSION_GRANTED
            val hasTelephony = context.packageManager
                .hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
            items += ProtectionItem(
                "sms_permission", "SMS permission", sms && hasTelephony,
                when {
                    !hasTelephony -> "no telephony on this device — SMS fallback cannot work"
                    sms -> "granted"
                    else -> "SEND_SMS denied — fallback alerts disabled"
                }
            )
        }
        if (!settings.useFixedLocation) {
            val fine = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            items += ProtectionItem(
                "location_permission", "Location permission", fine,
                if (fine) "granted" else "live location selected but permission denied; using fixed coordinates"
            )
        }
        return ProtectionReport(items)
    }

    /** Opens the best available screen for an action; false if nothing handled it. */
    fun open(action: ProtectionAction): Boolean = when (action) {
        ProtectionAction.BATTERY_EXEMPTION ->
            oemIntents.openBatteryExemption() || oemIntents.openAppDetails()
        ProtectionAction.APP_DETAILS -> oemIntents.openAppDetails()
        ProtectionAction.AUTO_LAUNCH ->
            oemIntents.open("autostart") || oemIntents.openAppDetails()
        ProtectionAction.OVERLAY -> oemIntents.openOverlay() || oemIntents.openAppDetails()
        ProtectionAction.RECENTS -> false
    }

    /** Opens the OEM screen for a named step, with the standard fallback. */
    fun openStep(step: String): Boolean = oemIntents.open(step)
}
