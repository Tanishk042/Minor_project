package org.forestear.node.audio

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal 16-bit mono PCM WAV writer that patches the RIFF sizes on close. */
class WavWriter(
    val file: File,
    private val sampleRate: Int = 16000,
) : Closeable {
    private val out = FileOutputStream(file, false)
    private var dataBytes = 0L
    private var closed = false

    init {
        file.parentFile?.mkdirs()
        out.write(header(0))
    }

    fun write(samples: ShortArray, count: Int = samples.size) {
        check(!closed) { "WavWriter already closed" }
        if (count <= 0) return
        val buf = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.asShortBuffer().put(samples, 0, count)
        out.write(buf.array())
        dataBytes += count * 2L
    }

    fun bytesWritten(): Long = dataBytes

    override fun close() {
        if (closed) return
        closed = true
        runCatching { out.flush() }
        runCatching { out.close() }
        patchSizes()
    }

    /** Close and remove a chunk that must never be stored (speech detected). */
    fun abort() {
        runCatching { close() }
        runCatching { file.delete() }
    }

    private fun patchSizes() {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4)
            raf.write(intLe(36 + dataBytes))
            raf.seek(40)
            raf.write(intLe(dataBytes))
        }
    }

    private fun intLe(v: Long): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()

    private fun header(dataLen: Long): ByteArray {
        val buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt((36 + dataLen).toInt())
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1) // PCM
        buf.putShort(1) // mono
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2) // byte rate
        buf.putShort(2) // block align
        buf.putShort(16) // bits per sample
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataLen.toInt())
        return buf.array()
    }
}
