package org.forestear.node.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The SNTP client only reports an offset; it never sets the clock. Packet
 * parsing is a pure function, so a synthetic reply is enough to pin the maths.
 */
class SntpTest {

    private val ntpUnixOffset = 2_208_988_800L

    @Test
    fun requestPacketIsA48ByteV3ClientPacket() {
        val packet = SntpClient.requestPacket()
        assertEquals(48, packet.size)
        assertEquals(0x1B, packet[0].toInt() and 0xFF) // LI=0, VN=3, mode=3 (client)
        for (i in 1 until packet.size) assertEquals(0, packet[i].toInt())
    }

    @Test
    fun offsetAndDelayFromSyntheticReply() {
        // Client sent at t1 and received at t4; the server received and replied
        // at the same instant (t2 == t3), so the delay is simply t4 - t1.
        val t1 = 1_000_000L
        val t4 = 1_000_200L
        val serverTime = 1_001_000L
        val packet = reply(t2 = serverTime, t3 = serverTime)

        val result = SntpClient.offsetFromResponse(packet, t1, t4, server = "pool.test")

        assertNotNull(result)
        assertEquals(900L, result!!.offsetMs)
        assertEquals(200L, result.delayMs)
        assertEquals("pool.test", result.server)
        assertTrue(result.queriedUtc.isNotBlank())
    }

    @Test
    fun shortPacketIsRejected() {
        assertNull(SntpClient.offsetFromResponse(ByteArray(47), 0L, 1L))
        assertNull(SntpClient.offsetFromResponse(ByteArray(0), 0L, 1L))
    }

    @Test
    fun zeroTransmitTimestampIsRejected() {
        // A 48-byte all-zero reply has a zero "seconds" field, which means the
        // server timestamp is absent; reporting an offset would be a guess.
        assertNull(SntpClient.offsetFromResponse(ByteArray(48), 1_000L, 2_000L))
    }

    @Test
    fun zeroReceiveTimestampIsRejected() {
        val packet = ByteArray(48)
        writeTimestamp(packet, 40, 1_700_000_000_000L) // t3 present, t2 zero
        assertNull(SntpClient.offsetFromResponse(packet, 1_000L, 2_000L))
    }

    @Test
    fun timestampDecodingConvertsTheNtpEpochToUnixMillis() {
        val packet = ByteArray(48)
        writeTimestamp(packet, 0, 1_700_000_000_000L)
        assertEquals(1_700_000_000_000L, SntpClient.timestampMs(packet, 0))
    }

    @Test
    fun timestampFractionIsSubMillisecondAccurate() {
        val packet = ByteArray(48)
        for (millis in listOf(0L, 250L, 500L, 750L)) {
            val expected = 1_700_000_000_000L + millis
            writeTimestamp(packet, 8, expected)
            val decoded = SntpClient.timestampMs(packet, 8)
            assertNotNull(decoded)
            assertTrue("decoded $decoded for $expected", abs(decoded!! - expected) <= 1L)
        }
    }

    @Test
    fun timestampRejectsOutOfRangeOffsets() {
        val packet = ByteArray(48)
        assertNull(SntpClient.timestampMs(packet, -1))
        assertNull(SntpClient.timestampMs(packet, 41)) // 41 + 8 > 48
    }

    /** Build a 48-byte reply with server receive (32) and transmit (40) times. */
    private fun reply(t2: Long, t3: Long): ByteArray = ByteArray(48).also {
        writeTimestamp(it, 32, t2)
        writeTimestamp(it, 40, t3)
    }

    /** Encode a Unix-epoch millisecond value as an NTP 64-bit timestamp. */
    private fun writeTimestamp(packet: ByteArray, offset: Int, unixMs: Long) {
        val seconds = unixMs / 1000 + ntpUnixOffset
        val millis = unixMs % 1000
        val fraction = millis * (1L shl 32) / 1000L
        var s = seconds
        for (i in 3 downTo 0) {
            packet[offset + i] = (s and 0xFF).toByte()
            s = s shr 8
        }
        var f = fraction
        for (i in 7 downTo 4) {
            packet[offset + i] = (f and 0xFF).toByte()
            f = f shr 8
        }
    }
}
