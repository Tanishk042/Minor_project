package org.forestear.node.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import org.forestear.node.settings.SettingsStore
import org.forestear.node.util.FLog

/**
 * Fixed coordinates (default) or the phone's last known GPS/network fix.
 * A deployed node does not move, so fixed is the honest default; platform
 * location is opt-in and never requested in the background.
 */
class LocationProvider(context: Context, private val settings: SettingsStore) {
    private val appContext = context.applicationContext
    private val manager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    fun current(): Pair<Double, Double> {
        if (settings.useFixedLocation) return settings.fixedLat to settings.fixedLon
        val fine = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return settings.fixedLat to settings.fixedLon
        val lm = manager ?: return settings.fixedLat to settings.fixedLon
        val last: Location? = runCatching {
            lm.getProviders(true)
                .mapNotNull { provider -> runCatching { lm.getLastKnownLocation(provider) }.getOrNull() }
                .maxByOrNull { it.time }
        }.onFailure { FLog.w("location read failed: ${it.message}") }.getOrNull()
        return if (last != null) last.latitude to last.longitude
        else settings.fixedLat to settings.fixedLon
    }
}
