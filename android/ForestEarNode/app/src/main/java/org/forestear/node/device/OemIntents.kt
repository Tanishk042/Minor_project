package org.forestear.node.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import org.forestear.node.util.FLog

/**
 * Launches OEM settings screens from the JSON table.
 *
 * Every candidate is resolved first and every launch is wrapped in try/catch.
 * If nothing resolves we fall back to the app-details screen, then to the
 * standard battery-optimisation settings. The UI never claims a switch is on
 * unless the OS verifies it or the user explicitly confirms it.
 */
class OemIntents(
    private val context: Context,
    private val table: OemProfileTable,
) {
    fun profile(): OemProfile {
        val manufacturer = android.os.Build.MANUFACTURER ?: ""
        val brand = android.os.Build.BRAND ?: ""
        val model = android.os.Build.MODEL ?: ""
        return table.forDevice(manufacturer, brand, model)
    }

    fun resolve(step: String): Intent? {
        val profile = profile()
        for (spec in profile.intentsFor(step)) {
            val expanded = spec.expand(context.packageName)
            val intent = build(expanded) ?: continue
            if (canResolve(intent)) return intent
        }
        return null
    }

    fun open(step: String): Boolean {
        val intent = resolve(step) ?: fallback(step)
        if (intent == null) {
            FLog.w("no activity resolved for OEM step $step")
            return false
        }
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.onFailure { FLog.w("OEM step $step launch failed: ${it.message}") }.getOrDefault(false)
    }

    fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))

    fun batteryExemptionIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))

    fun overlayIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            .setData(Uri.parse("package:${context.packageName}"))

    fun openAppDetails(): Boolean = launch(appDetailsIntent())
    fun openBatteryExemption(): Boolean = launch(batteryExemptionIntent())
    fun openOverlay(): Boolean = launch(overlayIntent())

    fun openRecents(): Boolean = false // no public intent; the wizard shows steps

    private fun fallback(step: String): Intent? = when (step) {
        "battery" -> batteryExemptionIntent()
        "overlay" -> overlayIntent()
        else -> appDetailsIntent()
    }

    private fun launch(intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.onFailure { FLog.w("intent launch failed: ${it.message}") }.getOrDefault(false)

    private fun build(spec: OemIntentSpec): Intent? {
        val component = spec.component?.let { ComponentName.unflattenFromString(it) }
        if (component != null) return Intent().setComponent(component)
        val action = spec.action ?: return null
        val intent = Intent(action)
        spec.data?.let { intent.data = Uri.parse(it) }
        return intent
    }

    private fun canResolve(intent: Intent): Boolean = runCatching {
        when {
            intent.component != null -> {
                context.packageManager.getActivityInfo(intent.component!!, 0)
                true
            }
            else -> context.packageManager.resolveActivity(intent, 0) != null
        }
    }.getOrDefault(false)
}
