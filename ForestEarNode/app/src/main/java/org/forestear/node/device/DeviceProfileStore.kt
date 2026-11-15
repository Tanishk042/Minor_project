package org.forestear.node.device

import android.content.Context
import android.os.Build
import org.forestear.node.util.FLog
import java.io.File

/**
 * Persists the capability profile to `device_profile.json` and re-probes when
 * the app version changes (first launch and every update, as required).
 */
class DeviceProfileStore(private val context: Context) {
    private val file = File(context.filesDir, FILE_NAME)

    fun load(): DeviceProfile? = runCatching {
        if (!file.exists()) null else DeviceProfile.fromJson(file.readText())
    }.onFailure { FLog.w("device profile read failed: ${it.message}") }.getOrNull()

    fun save(profile: DeviceProfile) {
        runCatching { file.writeText(profile.toJsonText()) }
            .onFailure { FLog.w("device profile write failed: ${it.message}") }
    }

    /**
     * Probe if missing, stale, or [force]. Never throws.
     *
     * When the debug "mock capabilities" switch is on (Tools → Debug), a
     * deterministic profile that deliberately lacks several capabilities is
     * returned instead, so the missing-capability paths can be exercised on any
     * handset.
     */
    fun current(force: Boolean = false, mock: Boolean? = null): DeviceProfile {
        val useMock = mock ?: runCatching {
            org.forestear.node.settings.SettingsStore.get(context).mockCapabilities
        }.getOrDefault(false)
        if (useMock) {
            val mockProfile = MockCapabilities.profile()
            save(mockProfile)
            return mockProfile
        }
        val existing = load()
        val version = appVersionCode()
        if (!force && existing != null &&
            existing.schema == DeviceProfile.SCHEMA &&
            existing.appVersionCode == version
        ) {
            return existing
        }
        val fresh = runCatching { DeviceProbe(context).probe() }
            .onFailure { FLog.e("device probe failed", it) }
            .getOrElse {
                MockCapabilities.profile(
                    missing = setOf("probe_failed"),
                    manufacturer = Build.MANUFACTURER ?: "unknown",
                    model = Build.MODEL ?: "unknown",
                    sdkInt = Build.VERSION.SDK_INT,
                )
            }
        save(fresh)
        return fresh
    }

    fun export(target: File): Boolean = runCatching {
        val profile = load() ?: current()
        target.parentFile?.mkdirs()
        target.writeText(profile.toJsonText())
        true
    }.getOrDefault(false)

    fun path(): String = file.absolutePath

    private fun appVersionCode(): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(0L)

    companion object {
        const val FILE_NAME = "device_profile.json"
    }
}
