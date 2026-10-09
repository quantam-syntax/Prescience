package com.example.consent_cam.recognition

import android.content.Context
import android.graphics.Bitmap
import com.example.consent_cam.hardware.AcceleratorPolicy
import com.example.consent_cam.hardware.ComputeUnit
import com.qualcomm.qti.QnnDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate

enum class FaceEmbeddingBackend(val label: String) {
    NOT_INITIALIZED("Not initialized"),
    HEXAGON_NPU("Hexagon NPU / QNN HTP"),
    GPU("GPU"),
    CPU_XNNPACK("CPU / XNNPACK"),
}

data class FaceEmbeddingRuntimeState(
    val backend: FaceEmbeddingBackend = FaceEmbeddingBackend.NOT_INITIALIZED,
    val lastInferenceMs: Long? = null,
    val fallbackReason: String? = null,
)

/** FaceNet-512 runtime with same-thread Hexagon NPU, GPU, and CPU execution. */
class LiteRtFaceEmbedder(context: Context) : FaceEmbedder {
    private val appContext = context.applicationContext
    private val inferenceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "consentcam-facenet")
    }
    private val inferenceDispatcher = inferenceExecutor.asCoroutineDispatcher()
    private val mutableRuntimeState = MutableStateFlow(FaceEmbeddingRuntimeState())
    private var interpreter: Interpreter? = null
    private var qnnDelegate: QnnDelegate? = null
    private var gpuDelegate: GpuDelegate? = null
    private val failedBackends = linkedSetOf<FaceEmbeddingBackend>()
    private val backendFailures = mutableListOf<String>()

    val runtimeState: StateFlow<FaceEmbeddingRuntimeState> = mutableRuntimeState.asStateFlow()

    override suspend fun embed(alignedFace: Bitmap): Result<FloatArray> = withContext(inferenceDispatcher) {
        runCatching {
            val input = preprocess(alignedFace)
            val output = Array(1) { FloatArray(FACENET_EMBEDDING_DIMENSIONS) }
            val startedNs = System.nanoTime()
            try {
                runWithFallback(input, output)
                EmbeddingMath.normalize(output[0])
                    ?: error("FaceNet produced an invalid embedding")
            } finally {
                output[0].fill(0f)
                clear(input)
                mutableRuntimeState.value = mutableRuntimeState.value.copy(
                    lastInferenceMs = (System.nanoTime() - startedNs) / 1_000_000L,
                )
            }
        }
    }

    override fun close() {
        runCatching {
            inferenceExecutor.submit {
                interpreter?.close()
                interpreter = null
                qnnDelegate?.close()
                qnnDelegate = null
                gpuDelegate?.close()
                gpuDelegate = null
            }.get()
        }
        inferenceDispatcher.close()
    }

    private fun requireInterpreter(): Interpreter {
        interpreter?.let { return it }
        val compatibility = CompatibilityList()
        return when (AcceleratorPolicy.nextAvailable(failedComputeUnits(), compatibility.isDelegateSupportedOnThisDevice)) {
            ComputeUnit.HEXAGON_NPU -> runCatching { createNpuInterpreter() }.getOrElse { error ->
                recordBackendFailure(FaceEmbeddingBackend.HEXAGON_NPU, error)
                requireInterpreter()
            }
            ComputeUnit.ADRENO_GPU -> runCatching {
                val delegate = GpuDelegate(compatibility.bestOptionsForThisDevice)
                try {
                    val created = Interpreter(
                        loadModel(appContext),
                        Interpreter.Options().addDelegate(delegate),
                    )
                    gpuDelegate = delegate
                    interpreter = created
                    mutableRuntimeState.value = FaceEmbeddingRuntimeState(
                        backend = FaceEmbeddingBackend.GPU,
                        fallbackReason = fallbackSummary(),
                    )
                    created
                } catch (error: Throwable) {
                    delegate.close()
                    throw error
                }
            }.getOrElse { error ->
                recordBackendFailure(FaceEmbeddingBackend.GPU, error)
                requireInterpreter()
            }
            ComputeUnit.ORYON_CPU -> Interpreter(
                loadModel(appContext),
                Interpreter.Options().apply {
                    setNumThreads(CPU_THREADS)
                    setUseXNNPACK(true)
                },
            ).also { created ->
                interpreter = created
                mutableRuntimeState.value = FaceEmbeddingRuntimeState(
                    backend = FaceEmbeddingBackend.CPU_XNNPACK,
                    fallbackReason = fallbackSummary(),
                )
            }
        }
    }

    private fun createNpuInterpreter(): Interpreter {
        val nativeLibraryDir = appContext.applicationInfo.nativeLibraryDir
        val cacheDirectory = java.io.File(appContext.codeCacheDir, "qnn/facenet").also { it.mkdirs() }
        val options = QnnDelegate.Options().apply {
            setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND)
            setLibraryPath("$nativeLibraryDir/libQnnHtp.so")
            setSkelLibraryDir(nativeLibraryDir)
            setCacheDir(cacheDirectory.absolutePath)
            setModelToken(QNN_MODEL_TOKEN)
            setHtpOptions(
                QnnDelegate.Options.HtpPerformanceMode.HTP_PERFORMANCE_SUSTAINED_HIGH_PERFORMANCE,
                QnnDelegate.Options.HtpPrecision.HTP_PRECISION_FP16,
                QnnDelegate.Options.HtpPdSession.HTP_PD_SESSION_UNSIGNED,
                QnnDelegate.Options.HtpOptimizationStrategy.HTP_OPTIMIZE_FOR_INFERENCE,
            )
            setLogLevel(QnnDelegate.Options.LogLevel.LOG_OFF)
            setProfiling(QnnDelegate.Options.ProfilingOptions.PROFILING_OFF)
        }
        val delegate = QnnDelegate(options)
        try {
            check(delegate.isAvailable) { "QNN HTP backend unavailable" }
            val created = Interpreter(loadModel(appContext), Interpreter.Options().addDelegate(delegate))
            qnnDelegate = delegate
            interpreter = created
            mutableRuntimeState.value = FaceEmbeddingRuntimeState(
                backend = FaceEmbeddingBackend.HEXAGON_NPU,
                fallbackReason = fallbackSummary(),
            )
            return created
        } catch (error: Throwable) {
            delegate.close()
            throw error
        }
    }

    private fun runWithFallback(input: ByteBuffer, output: Array<FloatArray>) {
        val active = requireInterpreter()
        try {
            active.run(input, output)
        } catch (firstFailure: Throwable) {
            val failed = mutableRuntimeState.value.backend
            if (failed == FaceEmbeddingBackend.CPU_XNNPACK) throw firstFailure
            recordBackendFailure(failed, firstFailure)
            closeActiveRuntime()
            input.rewind()
            output[0].fill(0f)
            requireInterpreter().run(input, output)
        }
    }

    private fun closeActiveRuntime() {
        interpreter?.close()
        interpreter = null
        qnnDelegate?.close()
        qnnDelegate = null
        gpuDelegate?.close()
        gpuDelegate = null
    }

    private fun recordBackendFailure(backend: FaceEmbeddingBackend, error: Throwable) {
        failedBackends += backend
        val reason = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
        backendFailures += "${backend.label}: $reason"
    }

    private fun fallbackSummary(): String? = backendFailures.takeIf { it.isNotEmpty() }?.joinToString("; ")

    private fun failedComputeUnits(): Set<ComputeUnit> = failedBackends.mapNotNullTo(linkedSetOf()) { backend ->
        when (backend) {
            FaceEmbeddingBackend.HEXAGON_NPU -> ComputeUnit.HEXAGON_NPU
            FaceEmbeddingBackend.GPU -> ComputeUnit.ADRENO_GPU
            FaceEmbeddingBackend.CPU_XNNPACK -> ComputeUnit.ORYON_CPU
            FaceEmbeddingBackend.NOT_INITIALIZED -> null
        }
    }

    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        require(!bitmap.isRecycled) { "Face crop has already been released" }
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        val channels = FloatArray(pixels.size * 3)
        try {
            resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            var sum = 0.0
            var outputIndex = 0
            pixels.forEach { pixel ->
                val red = ((pixel shr 16) and 0xff).toFloat()
                val green = ((pixel shr 8) and 0xff).toFloat()
                val blue = (pixel and 0xff).toFloat()
                channels[outputIndex++] = red
                channels[outputIndex++] = green
                channels[outputIndex++] = blue
                sum += red + green + blue
            }
            val mean = (sum / channels.size).toFloat()
            var variance = 0.0
            channels.forEach { value ->
                val delta = value - mean
                variance += delta * delta
            }
            val deviation = kotlin.math.max(
                kotlin.math.sqrt(variance / channels.size).toFloat(),
                1f / kotlin.math.sqrt(channels.size.toFloat()),
            )
            return ByteBuffer.allocateDirect(channels.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .also { buffer -> channels.forEach { value -> buffer.putFloat((value - mean) / deviation) } }
                .apply { rewind() }
        } finally {
            pixels.fill(0)
            channels.fill(0f)
            if (resized !== bitmap && !resized.isRecycled) resized.recycle()
        }
    }

    private fun clear(buffer: ByteBuffer) {
        buffer.clear()
        while (buffer.remaining() >= Float.SIZE_BYTES) buffer.putFloat(0f)
        buffer.clear()
    }

    private companion object {
        const val INPUT_SIZE = 160
        const val CPU_THREADS = 4
        const val MODEL_ASSET = "facenet_512.tflite"
        const val QNN_MODEL_TOKEN = "consentcam_facenet512_fp16_v1"

        fun loadModel(context: Context): ByteBuffer {
            val descriptor = context.assets.openFd(MODEL_ASSET)
            return descriptor.use {
                java.io.FileInputStream(it.fileDescriptor).channel.use { channel ->
                    channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, it.startOffset, it.declaredLength)
                }
            }
        }
    }
}
