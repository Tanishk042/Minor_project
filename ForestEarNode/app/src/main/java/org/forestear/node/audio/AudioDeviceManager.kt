package org.forestear.node.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import org.forestear.node.settings.InputChoice
import org.forestear.node.util.FLog

data class InputDevice(
    val id: Int,
    val name: String,
    val type: Int,
    val typeLabel: String,
    val isExternal: Boolean,
)

/**
 * Input-device selection for the OnePlus Nord CE 2 Lite 5G.
 *
 * The phone has a 3.5 mm TRRS jack, so a wired lavalier appears as
 * TYPE_WIRED_HEADSET. We also treat USB headsets as external so a USB-C
 * lavalier works, but never silently fall back to Bluetooth SCO: a SCO link
 * would band-limit the audio and destroy the model inputs.
 */
class AudioDeviceManager(context: Context) {
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun inputDevices(): List<AudioDeviceInfo> =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()

    fun describe(device: AudioDeviceInfo): InputDevice =
        InputDevice(
            id = device.id,
            name = device.productName?.toString()?.ifBlank { null } ?: typeLabel(device.type),
            type = device.type,
            typeLabel = typeLabel(device.type),
            isExternal = isExternal(device),
        )

    fun wiredHeadset(): AudioDeviceInfo? =
        inputDevices().firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }

    fun usbMic(): AudioDeviceInfo? =
        inputDevices().firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }

    fun builtinMic(): AudioDeviceInfo? =
        inputDevices().firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

    fun isWiredHeadsetConnected(): Boolean = wiredHeadset() != null

    fun externalDevices(): List<AudioDeviceInfo> = inputDevices().filter { isExternal(it) }

    /** Stable key for per-input calibration gain. */
    fun inputKey(device: AudioDeviceInfo?): String = when (device?.type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "headset"
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE -> "usb"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "builtin"
        null -> "default"
        else -> "type_${device.type}"
    }

    /** AUTO prefers USB, then a plugged 3.5 mm headset, then the built-in mic. */
    fun choose(choice: InputChoice): AudioDeviceInfo? {
        val inputs = inputDevices()
        val wired = inputs.filter { isExternal(it) && !isUsb(it) }
        val usb = inputs.filter { isUsb(it) }
        val builtin = inputs.filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        return when (choice) {
            InputChoice.USB -> usb.firstOrNull()
            InputChoice.WIRED_HEADSET -> wired.firstOrNull()
            InputChoice.BUILTIN -> builtin.firstOrNull()
            InputChoice.AUTO -> usb.firstOrNull() ?: wired.firstOrNull() ?: builtin.firstOrNull()
        }
    }

    private fun isUsb(device: AudioDeviceInfo): Boolean =
        device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_USB_DEVICE

    /**
     * True when the platform advertises a raw path. Android returns the string
     * "true"/"false"; some OEM builds return null even when the source works,
     * so the caller also falls back to MIC after a failed open.
     */
    fun unprocessedSupported(): Boolean {
        val value = runCatching {
            audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
        }.getOrNull()
        FLog.i("PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED=$value")
        return value.equals("true", ignoreCase = true)
    }

    fun isExternal(device: AudioDeviceInfo): Boolean = when (device.type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE -> true
        else -> Build.VERSION.SDK_INT >= 31 &&
            device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }

    fun typeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "built-in mic"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset (TRRS)"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE headset"
        else -> "input type $type"
    }
}
