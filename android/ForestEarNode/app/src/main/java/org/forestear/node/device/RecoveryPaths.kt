package org.forestear.node.device

import android.content.Context
import org.forestear.node.util.TimeUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class RecoveryResult(
    val path: String,
    val outcome: String, // PASS / FAIL / NOT RUN
    val detail: String,
    val recordedUtc: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("path", path)
        .put("outcome", outcome)
        .put("detail", detail)
        .put("recorded_utc", recordedUtc)

    companion object {
        fun fromJson(o: JSONObject): RecoveryResult = RecoveryResult(
            path = o.optString("path", ""),
            outcome = o.optString("outcome", "NOT RUN"),
            detail = o.optString("detail", ""),
            recordedUtc = o.optString("recorded_utc", ""),
        )
    }
}

/**
 * Persisted results of the empirical recovery-path tests (kill, swipe, reboot,
 * watchdog, screen-off continuity, microphone theft). The compatibility report
 * prints exactly what is stored here, including "NOT RUN".
 */
class RecoveryLog(private val context: Context) {
    private val file = File(context.filesDir, FILE_NAME)

    fun load(): List<RecoveryResult> = runCatching {
        if (!file.exists()) emptyList()
        else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .map { RecoveryResult.fromJson(it) }
        }
    }.getOrDefault(emptyList())

    fun record(path: String, outcome: String, detail: String) {
        val next = load().filterNot { it.path == path } + RecoveryResult(
            path = path,
            outcome = outcome,
            detail = detail,
            recordedUtc = TimeUtils.isoUtc(),
        )
        save(next)
    }

    fun clear() {
        runCatching { file.delete() }
    }

    fun save(results: List<RecoveryResult>) {
        val arr = JSONArray()
        results.forEach { arr.put(it.toJson()) }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(arr.toString(2))
        }
    }

    companion object {
        const val FILE_NAME = "recovery_paths.json"

        val KNOWN_PATHS = listOf(
            "kill_service", "swipe_away", "reboot", "boot_receiver",
            "watchdog", "screen_off", "mic_theft",
        )
    }
}
