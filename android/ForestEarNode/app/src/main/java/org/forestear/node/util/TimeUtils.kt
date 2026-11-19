package org.forestear.node.util

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

object TimeUtils {
    private val fileStamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC)

    fun isoUtc(epochMillis: Long = System.currentTimeMillis()): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis))

    fun isoUtc(epochSeconds: Double): String {
        val ms = (epochSeconds * 1000.0).toLong()
        return isoUtc(ms)
    }

    fun fileStampUtc(epochMillis: Long = System.currentTimeMillis()): String =
        fileStamp.format(Instant.ofEpochMilli(epochMillis))

    fun utcHour(epochMillis: Long = System.currentTimeMillis()): Double {
        val z = java.time.ZonedDateTime.now(ZoneOffset.UTC)
        return z.hour + z.minute / 60.0 + z.second / 3600.0
    }

    fun humanDuration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val d = s / 86_400
        val h = (s % 86_400) / 3_600
        val m = (s % 3_600) / 60
        val sec = s % 60
        return when {
            d > 0 -> String.format(Locale.US, "%dd %02dh %02dm", d, h, m)
            h > 0 -> String.format(Locale.US, "%dh %02dm %02ds", h, m, sec)
            m > 0 -> String.format(Locale.US, "%dm %02ds", m, sec)
            else -> String.format(Locale.US, "%ds", sec)
        }
    }
}
