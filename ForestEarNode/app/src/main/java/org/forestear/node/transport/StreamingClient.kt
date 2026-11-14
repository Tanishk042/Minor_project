package org.forestear.node.transport

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.forestear.node.service.NodeState
import org.forestear.node.util.FLog
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Streams raw 16 kHz mono PCM to the laptop over WebSocket so the audio can be
 * monitored/recorded there. The phone still runs all inference locally; this is
 * a debugging aid, not part of the alert path.
 *
 * Each binary frame is one capture block (100 ms = 3200 bytes) of little-endian
 * int16 samples. A text frame with node metadata is sent on connect.
 */
class StreamingClient(private val nodeId: () -> String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var socket: WebSocket? = null
    private var url: String = ""

    @Volatile private var running = false
    @Volatile private var connected = false

    fun start(url: String) {
        if (running) stop()
        if (url.isBlank()) return
        this.url = url
        running = true
        connect()
    }

    fun stop() {
        running = false
        connected = false
        runCatching { socket?.close(1000, "stopped") }
        socket = null
        NodeState.update { it.copy(streaming = false) }
    }

    fun isConnected(): Boolean = connected

    fun send(pcm: ShortArray, count: Int = pcm.size) {
        if (!running || !connected) return
        val buf = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.asShortBuffer().put(pcm, 0, count)
        val bytes: ByteString = buf.array().toByteString()
        runCatching { socket?.send(bytes) }
    }

    fun shutdown() {
        stop()
        scheduler.shutdownNow()
    }

    private fun connect() {
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                FLog.i("streaming connected: $url")
                NodeState.update { it.copy(streaming = true) }
                webSocket.send(
                    JSONObject()
                        .put("type", "hello")
                        .put("node_id", nodeId())
                        .put("sample_rate", 16000)
                        .put("channels", 1)
                        .put("format", "s16le")
                        .toString()
                )
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                NodeState.update { it.copy(streaming = false) }
                FLog.w("streaming failed: ${t.message}")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                NodeState.update { it.copy(streaming = false) }
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!running) return
        runCatching {
            scheduler.schedule({ if (running) connect() }, 5, TimeUnit.SECONDS)
        }
    }
}
