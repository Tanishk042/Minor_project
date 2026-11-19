package org.forestear.node.audio

import android.content.Context
import org.forestear.node.config.AppConfig
import org.forestear.node.settings.SettingsStore
import org.forestear.node.util.FLog
import org.forestear.node.util.TimeUtils
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class CollectionStats(
    val active: Boolean,
    val chunks: Int,
    val bytes: Long,
    val droppedForHuman: Int,
    val directory: String,
)

/**
 * Explicit opt-in ambient collection for retraining on this exact phone and
 * microphone.
 *
 * Privacy rules enforced here:
 *  - only writes when the user enabled collection in Settings;
 *  - a chunk that ever contains a human-presence detection is deleted whole,
 *    never trimmed and never uploaded;
 *  - files stay in the app-specific external directory, exportable with adb;
 *  - the directory is rotated by total bytes, oldest first.
 */
class CollectionRecorder(
    context: Context,
    private val settings: SettingsStore,
    private val config: AppConfig,
) : Closeable {
    private val dir: File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "collection")
    private val lock = Any()
    private var writer: WavWriter? = null
    private var chunkSamples = 0L
    private var chunkStartMs = 0L
    private var humanContaminated = false
    private var droppedForHuman = 0
    private val running = AtomicBoolean(false)

    private val chunkSamplesTarget: Long =
        config.android.collection.chunkS.toLong() * 16000L

    fun start() {
        if (!settings.collectionEnabled) return
        dir.mkdirs()
        running.set(true)
        FLog.i("collection enabled -> ${dir.absolutePath}")
    }

    fun stop() {
        running.set(false)
        synchronized(lock) { finalizeChunk() }
    }

    override fun close() = stop()

    /** Called from the capture thread with raw, pre-gain PCM. */
    fun append(pcm: ShortArray, timestampMs: Long) {
        if (!running.get() || !settings.collectionEnabled) return
        synchronized(lock) {
            if (humanContaminated) return
            if (writer == null) openChunk(timestampMs)
            val w = writer ?: return
            w.write(pcm)
            chunkSamples += pcm.size
            if (chunkSamples >= chunkSamplesTarget) finalizeChunk()
        }
    }

    /**
     * Called after each analysed window. Any human-presence score above the
     * configured threshold marks the current chunk as speech-contaminated and
     * deletes it. Collection resumes once the flag clears.
     */
    fun onHumanPresence(present: Boolean) {
        if (!running.get()) return
        synchronized(lock) {
            if (present) {
                if (!humanContaminated) {
                    humanContaminated = true
                    if (writer != null) {
                        droppedForHuman++
                        FLog.w("human presence detected: deleting current collection chunk")
                        writer?.abort()
                        writer = null
                        chunkSamples = 0
                    }
                }
            } else {
                humanContaminated = false
            }
        }
    }

    fun stats(): CollectionStats = synchronized(lock) {
        CollectionStats(
            active = running.get() && settings.collectionEnabled,
            chunks = files().size,
            bytes = files().sumOf { it.length() },
            droppedForHuman = droppedForHuman,
            directory = dir.absolutePath,
        )
    }

    fun files(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".wav") }
            ?.sortedBy { it.lastModified() } ?: emptyList()

    /** Deletes every collected chunk (explicit user action from Tools). */
    fun clearAll(): Int {
        var removed = 0
        synchronized(lock) {
            finalizeChunk()
            for (f in files()) {
                if (f.delete()) removed++
            }
        }
        FLog.i("collection cleared: $removed file(s)")
        return removed
    }

    private fun openChunk(timestampMs: Long) {
        dir.mkdirs()
        chunkStartMs = timestampMs
        val stamp = TimeUtils.fileStampUtc(timestampMs)
        val input = if (settings.inputChoice == org.forestear.node.settings.InputChoice.WIRED_HEADSET) {
            "headset"
        } else {
            "builtin"
        }
        val file = File(dir, "ambient_${stamp}_${input}.wav")
        writer = WavWriter(file, 16000)
        chunkSamples = 0
    }

    private fun finalizeChunk() {
        val w = writer ?: return
        runCatching { w.close() }
        writer = null
        chunkSamples = 0
        enforceCap()
    }

    private fun enforceCap() {
        val max = config.android.collection.maxBytes
        var all = files()
        var total = all.sumOf { it.length() }
        while (total > max && all.isNotEmpty()) {
            val oldest = all.first()
            val len = oldest.length()
            if (oldest.delete()) {
                total -= len
                all = all.drop(1)
                FLog.i("collection rotation: removed ${oldest.name}")
            } else break
        }
    }
}
