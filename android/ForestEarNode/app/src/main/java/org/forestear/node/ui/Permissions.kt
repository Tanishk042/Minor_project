package org.forestear.node.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

data class PermissionSnapshot(
    val mic: Boolean,
    val notifications: Boolean,
    val sms: Boolean,
    val phoneState: Boolean,
    val location: Boolean,
) {
    val coreReady: Boolean
        get() = mic && (notifications || Build.VERSION.SDK_INT < 33)
}

fun permissionSnapshot(context: Context): PermissionSnapshot {
    fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    return PermissionSnapshot(
        mic = granted(Manifest.permission.RECORD_AUDIO),
        notifications = if (Build.VERSION.SDK_INT >= 33) {
            granted(Manifest.permission.POST_NOTIFICATIONS)
        } else true,
        sms = granted(Manifest.permission.SEND_SMS),
        phoneState = granted(Manifest.permission.READ_PHONE_STATE),
        location = granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(Manifest.permission.ACCESS_COARSE_LOCATION),
    )
}

object PermissionGroups {
    fun core(): Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    fun sms(): Array<String> = arrayOf(Manifest.permission.SEND_SMS)

    fun phoneState(): Array<String> = arrayOf(Manifest.permission.READ_PHONE_STATE)

    fun location(): Array<String> =
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
}
