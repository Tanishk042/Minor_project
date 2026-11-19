package org.forestear.node.transport

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.forestear.node.service.NodeState
import org.forestear.node.settings.SettingsStore
import org.forestear.node.util.FLog
import org.forestear.node.util.TimeUtils
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP transport to the ForestEar backend, matching forestear/edge_agent/transport.py:
 *   POST /ingest/event, /ingest/heartbeat, /ingest/scores,
 *        /ingest/biodiversity, /ingest/suppressed
 * Auth header: X-ForestEar-Token.
 *
 * Events are queued durably when offline and flushed with exponential backoff.
 * Heartbeats/scores are best-effort and never queued (they are stale by design).
 */
class Transport(
    context: Context,
    private val settings: SettingsStore,
    private val queue: OfflineQueue,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    @Volatile var online: Boolean = false
        private set
    @Volatile var sentEvents: Long = 0
        private set

    private var backoffMs = 1_000L
    private var nextFlushAt = 0L
    private val lock = Any()

    fun sendEvent(payload: JSONObject): Boolean {
        val ok = post("/ingest/event", payload, timeoutS = 5)
        if (ok) {
            sentEvents++
            NodeState.update { it.copy(backendOnline = true, alertsSent = sentEvents) }
            flush()
        } else {
            queue.enqueue(payload.toString())
            NodeState.update { it.copy(backendOnline = false, queueSize = queue.count()) }
        }
        return ok
    }

    fun sendHeartbeat(health: JSONObject, scores: JSONObject? = null, lat: Double, lon: Double): Boolean {
        val body = JSONObject()
            .put("node_id", settings.nodeId)
            .put("health", health)
            .put("scores", scores ?: JSONObject())
            .put("online", online)
            .put("lat", lat)
            .put("lon", lon)
        val ok = post("/ingest/heartbeat", body, timeoutS = 5)
        NodeState.update { it.copy(backendOnline = ok, lastHeartbeatMs = System.currentTimeMillis()) }
        if (ok) flush()
        return ok
    }

    fun sendScores(payload: JSONObject): Boolean =
        post("/ingest/scores", payload, timeoutS = 2)

    fun sendBiodiversity(payload: JSONObject): Boolean =
        post("/ingest/biodiversity", payload, timeoutS = 3)

    fun sendSuppressed(payload: JSONObject): Boolean =
        post("/ingest/suppressed", payload, timeoutS = 3)

    /** Governor / battery / thermal warning as a normal event so it is stored. */
    fun sendSystemEvent(cls: String, severity: String, message: String, lat: Double, lon: Double): Boolean {
        val payload = JSONObject()
            .put("node_id", settings.nodeId)
            .put("timestamp_utc", TimeUtils.isoUtc())
            .put("lat", lat)
            .put("lon", lon)
            .put("class", cls)
            .put("severity", severity)
            .put("confidence", 1.0)
            .put("per_model_scores", JSONObject().put("message", message))
            .put("rms_db", 0.0)
            .put("persistence", "")
            .put("escalated", false)
            .put("battery", null)
            .put("cpu", null)
            .put("inference_ms", JSONObject())
            .put("suppressed_by", null)
            .put("clip_path", null)
        return sendEvent(payload)
    }

    fun flush(maxItems: Int = 50): Int {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (now < nextFlushAt) return 0
        }
        var sent = 0
        for (item in queue.pending(maxItems)) {
            if (item.tries > 30) {
                queue.delete(item.id)
                continue
            }
            val ok = try {
                postJson("/ingest/event", item.payload, timeoutS = 5)
            } catch (e: Exception) {
                false
            }
            if (ok) {
                queue.delete(item.id)
                sent++
                sentEvents++
            } else {
                queue.incrementTries(item.id)
                synchronized(lock) {
                    nextFlushAt = System.currentTimeMillis() + backoffMs
                    backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
                }
                break
            }
        }
        if (sent > 0) {
            synchronized(lock) {
                backoffMs = 1_000L
                nextFlushAt = 0
            }
            FLog.i("offline queue flushed $sent events (${queue.count()} remain)")
        }
        NodeState.update { it.copy(queueSize = queue.count(), backendOnline = online || sent > 0) }
        return sent
    }

    fun queueSize(): Int = queue.count()

    fun compactPayload(payload: JSONObject): String {
        val ts = payload.optString("timestamp_utc", TimeUtils.isoUtc())
        val utc = if (ts.length >= 19) ts.substring(0, 19) else ts
        return String.format(
            java.util.Locale.US,
            "FE|%s|%s|%s|%.2f|%.4f|%.4f|%s",
            payload.optString("node_id", settings.nodeId),
            payload.optString("class", "?"),
            payload.optString("severity", "?"),
            payload.optDouble("confidence", 0.0),
            payload.optDouble("lat", 0.0),
            payload.optDouble("lon", 0.0),
            utc,
        )
    }

    private fun post(path: String, payload: JSONObject, timeoutS: Long): Boolean =
        postJson(path, payload.toString(), timeoutS)

    private fun postJson(path: String, body: String, timeoutS: Long): Boolean {
        val request = Request.Builder()
            .url(settings.backendUrl + path)
            .header("X-ForestEar-Token", settings.token)
            .post(body.toRequestBody(jsonType))
            .build()
        return try {
            client.newBuilder()
                .readTimeout(timeoutS, TimeUnit.SECONDS)
                .build()
                .newCall(request)
                .execute()
                .use { response ->
                    val ok = response.code in 200..299
                    if (!ok) FLog.w("POST $path -> ${response.code}")
                    if (ok) online = true
                    ok
                }
        } catch (e: IOException) {
            online = false
            FLog.d("POST $path failed: ${e.message}")
            false
        }
    }
}
