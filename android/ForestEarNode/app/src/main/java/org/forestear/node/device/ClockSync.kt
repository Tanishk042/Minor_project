package org.forestear.node.device

import org.forestear.node.util.FLog
import org.forestear.node.util.TimeUtils
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

data class ClockSyncResult(
    val offsetMs: Long,
    val delayMs: Long,
    val server: String,
    val queriedUtc: String,
)

/**
 * Minimal SNTP client (RFC 4330 / NTPv3 request) used only to report the phone
 * clock offset in the heartbeat. No time is ever set; the node has no permission
 * to change the system clock and does not try.
 *
 * Packet parsing is a pure function so it can be tested with a synthetic reply.
 */
object SntpClient {
    private const val NTP_UNIX_OFFSET_S = 2_208_988_800L
    private const val PACKET_BYTES = 48

    fun query(host: String = "pool.ntp.org", timeoutMs: Int = 3000): ClockSyncResult? {
        return runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                val request = requestPacket()
                val address = InetAddress.getByName(host)
                val t1 = System.currentTimeMillis()
                socket.send(DatagramPacket(request, request.size, address, 123))
                val buffer = ByteArray(PACKET_BYTES)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                val t4 = System.currentTimeMillis()
                val trimmed = buffer.copyOf(response.length)
                offsetFromResponse(trimmed, t1, t4, host)
            }
        }.onFailure { FLog.d("SNTP query failed: ${it.message}") }.getOrNull()
    }

    fun requestPacket(): ByteArray = ByteArray(PACKET_BYTES).also { it[0] = 0x1B } // LI=0 VN=3 mode=3

    /**
     * @param t1 client send time (epoch ms), [t4] client receive time.
     * Returns null when the packet is too short or has a zero transmit timestamp.
     */
    fun offsetFromResponse(
        packet: ByteArray,
        t1: Long,
        t4: Long,
        server: String = "unknown",
    ): ClockSyncResult? {
        if (packet.size < PACKET_BYTES) return null
        val t2 = timestampMs(packet, 32) ?: return null // server receive
        val t3 = timestampMs(packet, 40) ?: return null // server transmit
        val offset = ((t2 - t1) + (t3 - t4)) / 2
        val delay = (t4 - t1) - (t3 - t2)
        return ClockSyncResult(
            offsetMs = offset,
            delayMs = delay,
            server = server,
            queriedUtc = TimeUtils.isoUtc(t4),
        )
    }

    /** NTP timestamp (seconds since 1900 + 32-bit fraction) -> Unix epoch ms. */
    fun timestampMs(packet: ByteArray, offset: Int): Long? {
        if (offset < 0 || offset + 8 > packet.size) return null
        var seconds = 0L
        for (i in 0 until 4) {
            seconds = (seconds shl 8) or (packet[offset + i].toLong() and 0xFF)
        }
        var fraction = 0L
        for (i in 4 until 8) {
            fraction = (fraction shl 8) or (packet[offset + i].toLong() and 0xFF)
        }
        if (seconds == 0L) return null
        val unixSeconds = seconds - NTP_UNIX_OFFSET_S
        val millis = fraction * 1000L / (1L shl 32)
        return unixSeconds * 1000L + millis
    }
}
