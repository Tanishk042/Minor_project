package org.forestear.node.model

import android.content.Context
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.forestear.node.util.FLog
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.roundToInt

enum class DelegateKind {
    CPU_XNNPACK,
    GPU;

    companion object {
        fun fromName(name: String?): DelegateKind =
            if (name.equals("GPU", ignoreCase = true)) GPU else CPU_XNNPACK
    }
}

data class TensorSpec(
    val index: Int,
    val shape: IntArray,
    val dtype: DataType,
    val scale: Float,
    val zeroPoint: Int,
) {
    val elements: Int get() = shape.fold(1) { acc, v -> acc * v }
    val bytesPerElement: Int
        get() = when (dtype) {
            DataType.FLOAT32 -> 4
            DataType.INT8, DataType.UINT8 -> 1
            else -> 4
        }
}

/**
 * One TFLite interpreter with the exact input/output quantisation handling used
 * by forestear/edge_agent/zoo.py.
 *
 * XNNPACK is the default CPU path. The delegate class is instantiated through
 * reflection so the app still builds/runs on TFLite builds that fold XNNPACK
 * into the CPU kernel (the delegate is then simply absent and the default CPU
 * kernels are used). The GPU delegate is used only when the benchmark selected
 * it for that model.
 */
class TfliteModel private constructor(
    val name: String,
    private val interpreter: Interpreter,
    private val delegate: Any?,
    val input: TensorSpec,
    val output: TensorSpec,
) : Closeable {

    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(input.elements * input.bytesPerElement)
            .order(ByteOrder.nativeOrder())
    private val outputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(output.elements * output.bytesPerElement)
            .order(ByteOrder.nativeOrder())

    fun run(values: FloatArray): FloatArray {
        require(values.size == input.elements) {
            "$name expects ${input.elements} inputs, got ${values.size}"
        }
        inputBuffer.clear()
        when (input.dtype) {
            DataType.FLOAT32 -> inputBuffer.asFloatBuffer().put(values)
            DataType.INT8 -> {
                val scale = input.scale
                val zp = input.zeroPoint
                for (v in values) {
                    val q = if (scale == 0f) 0 else (v / scale + zp).roundToInt()
                    inputBuffer.put(q.coerceIn(-128, 127).toByte())
                }
            }
            else -> error("$name unsupported input dtype ${input.dtype}")
        }
        inputBuffer.rewind()
        outputBuffer.rewind()
        interpreter.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()

        val out = FloatArray(output.elements)
        when (output.dtype) {
            DataType.FLOAT32 -> outputBuffer.asFloatBuffer().get(out)
            DataType.INT8 -> {
                val scale = output.scale
                val zp = output.zeroPoint
                for (i in out.indices) {
                    out[i] = (outputBuffer.get().toInt() - zp) * scale
                }
            }
            else -> error("$name unsupported output dtype ${output.dtype}")
        }
        return out
    }

    override fun close() {
        runCatching { interpreter.close() }
        (delegate as? Closeable)?.let { runCatching { it.close() } }
    }

    companion object {
        fun load(
            context: Context,
            assetPath: String,
            name: String,
            kind: DelegateKind,
            threads: Int = 2,
        ): TfliteModel {
            val options = Interpreter.Options().setNumThreads(threads)
            var delegate: Any? = null
            if (kind == DelegateKind.GPU) {
                val gpu = GpuDelegate()
                options.addDelegate(gpu)
                delegate = gpu
            } else {
                delegate = tryXnnpack(options)
            }
            val interpreter = try {
                Interpreter(mapAsset(context, assetPath), options)
            } catch (e: Throwable) {
                (delegate as? Closeable)?.let { runCatching { it.close() } }
                throw e
            }
            interpreter.allocateTensors()
            val inSpec = spec(interpreter.getInputTensor(0))
            val outSpec = spec(interpreter.getOutputTensor(0))
            FLog.i(
                "loaded $name ($assetPath) in=${inSpec.shape.contentToString()} " +
                    "${inSpec.dtype} out=${outSpec.shape.contentToString()} " +
                    "${outSpec.dtype} delegate=${if (kind == DelegateKind.GPU) "GPU" else "CPU_XNNPACK"}"
            )
            return TfliteModel(name, interpreter, delegate, inSpec, outSpec)
        }

        fun mapAsset(context: Context, path: String): ByteBuffer {
            return try {
                context.assets.openFd(path).use { afd ->
                    FileInputStream(afd.fileDescriptor).use { fis ->
                        fis.channel.map(
                            FileChannel.MapMode.READ_ONLY,
                            afd.startOffset,
                            afd.declaredLength,
                        )
                    }
                }
            } catch (e: Exception) {
                FLog.w("asset $path is compressed; copying to a direct buffer")
                val bytes = context.assets.open(path).use { it.readBytes() }
                val buf = ByteBuffer.allocateDirect(bytes.size)
                    .order(ByteOrder.nativeOrder())
                buf.put(bytes)
                buf.rewind()
                buf
            }
        }

        private fun spec(details: org.tensorflow.lite.Tensor): TensorSpec =
            TensorSpec(
                index = details.index(),
                shape = details.shape().copyOf(),
                dtype = details.dataType(),
                scale = details.quantizationParams().scale,
                zeroPoint = details.quantizationParams().zeroPoint,
            )

        private fun tryXnnpack(options: Interpreter.Options): Any? = runCatching {
            val cls = Class.forName("org.tensorflow.lite.xnnpack.XNNPackDelegate")
            val instance = cls.getDeclaredConstructor().newInstance()
            options.addDelegate(instance as Delegate)
            instance
        }.getOrNull()
    }
}

/**
 * YAMNet exposes three outputs (521 AudioSet scores, 1024-d embedding and an
 * internal patch tensor). We only use the first two and flatten defensively so
 * the code works whether TFLite returns [1,521] or a higher-rank tensor.
 */
class YamnetModel(
    context: Context,
    assetPath: String = "models/m1_yamnet.tflite",
    kind: DelegateKind = DelegateKind.CPU_XNNPACK,
) : Closeable {
    private val interpreter: Interpreter
    private val delegate: Any?
    private val scoresIndex: Int
    private val embeddingIndex: Int
    private val inputElements: Int

    private val inputBuffer: ByteBuffer
    private val scoresBuffer: ByteBuffer
    private val embeddingBuffer: ByteBuffer
    private val outputMap: Map<Int, Any>

    init {
        val options = Interpreter.Options().setNumThreads(2)
        var d: Any? = null
        if (kind == DelegateKind.GPU) {
            val gpu = GpuDelegate()
            options.addDelegate(gpu)
            d = gpu
        }
        delegate = d
        interpreter = Interpreter(TfliteModel.mapAsset(context, assetPath), options)
        interpreter.allocateTensors()
        val inp = interpreter.getInputTensor(0)
        inputElements = inp.shape().fold(1) { acc, v -> acc * v }
        var s = -1
        var e = -1
        for (i in 0 until interpreter.outputTensorCount) {
            val out = interpreter.getOutputTensor(i)
            val n = out.shape().fold(1) { acc, v -> acc * v }
            if (n == 521) s = i
            if (n == 1024) e = i
        }
        require(s >= 0 && e >= 0) { "YAMNet must expose 521 + 1024 outputs" }
        scoresIndex = s
        embeddingIndex = e
        inputBuffer = ByteBuffer
            .allocateDirect(inputElements * 4)
            .order(ByteOrder.nativeOrder())
        scoresBuffer = ByteBuffer
            .allocateDirect(521 * 4)
            .order(ByteOrder.nativeOrder())
        embeddingBuffer = ByteBuffer
            .allocateDirect(1024 * 4)
            .order(ByteOrder.nativeOrder())
        // ByteBuffer outputs sidestep rank checks, so the same code works for
        // (521,) or (1,521) output shapes across YAMNet builds.
        outputMap = mapOf(scoresIndex to scoresBuffer, embeddingIndex to embeddingBuffer)
        FLog.i("YAMNet loaded: input=$inputElements scores=$scoresIndex emb=$embeddingIndex")
    }

    fun run(wav: FloatArray): Pair<FloatArray, FloatArray> {
        val core = FloatArray(inputElements)
        val n = minOf(wav.size, inputElements)
        System.arraycopy(wav, 0, core, 0, n)
        inputBuffer.clear()
        inputBuffer.asFloatBuffer().put(core)
        inputBuffer.rewind()
        scoresBuffer.rewind()
        embeddingBuffer.rewind()
        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)
        val scores = FloatArray(521)
        val emb = FloatArray(1024)
        scoresBuffer.rewind()
        embeddingBuffer.rewind()
        scoresBuffer.asFloatBuffer().get(scores)
        embeddingBuffer.asFloatBuffer().get(emb)
        return scores to emb
    }

    override fun close() {
        runCatching { interpreter.close() }
        (delegate as? Closeable)?.let { runCatching { it.close() } }
    }
}
