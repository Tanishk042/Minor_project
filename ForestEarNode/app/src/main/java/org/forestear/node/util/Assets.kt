package org.forestear.node.util

import android.content.Context
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Abstraction over Android assets and JVM test resources. */
interface AssetSource {
    fun open(path: String): InputStream
    fun readBytes(path: String): ByteArray = open(path).use { it.readBytes() }
    fun readText(path: String): String = open(path).bufferedReader().use { it.readText() }
}

class AndroidAssetSource(private val context: Context) : AssetSource {
    override fun open(path: String): InputStream = context.assets.open(path)
}

class ClasspathAssetSource : AssetSource {
    override fun open(path: String): InputStream {
        val stream = javaClass.classLoader?.getResourceAsStream(path)
            ?: throw java.io.FileNotFoundException("classpath asset not found: $path")
        return stream
    }
}

object Binary {
    fun floats(bytes: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(buf.remaining() / 4)
        buf.asFloatBuffer().get(out)
        return out
    }

    fun floatBytes(values: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.asFloatBuffer().put(values)
        return buf.array()
    }

    fun pcm16LeBytes(samples: ShortArray): ByteArray {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.asShortBuffer().put(samples)
        return buf.array()
    }
}
