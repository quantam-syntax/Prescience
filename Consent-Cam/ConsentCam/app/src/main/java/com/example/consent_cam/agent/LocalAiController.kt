package com.example.consent_cam.agent

import android.content.Context
import android.net.Uri
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class LocalAiStatus { MODEL_MISSING, LOADING, READY, GENERATING, ERROR, CLOSED }
enum class LocalAiBackend(val label: String) { GPU("GPU"), CPU("CPU") }

data class LocalAiUiState(
    val status: LocalAiStatus = LocalAiStatus.MODEL_MISSING,
    val statusText: String = "Local model not provisioned",
    val modelName: String? = null,
    val backend: LocalAiBackend? = null,
    val inferenceCount: Int = 0,
    val lastLatencyMs: Long? = null,
    val decodeTokensPerSecond: Double? = null,
    val lastResponse: String? = null,
    val cloudCalls: Int = 0,
    val importProgressPercent: Int? = null,
    val thermalLevel: ThermalLevel = ThermalLevel.NORMAL,
    val lastTrigger: String? = null,
    val fallbackState: String = "E4B preferred; E2B and CPU enabled",
)

/** Offline-only LiteRT-LM runtime. It explains deterministic state but cannot change privacy. */
class LocalAiController(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val generationMutex = Mutex()
    private val initializing = AtomicBoolean(false)
    private val lifecycleGeneration = AtomicLong(0)
    private val mutableState = MutableStateFlow(LocalAiUiState())
    @Volatile private var engine: Engine? = null

    val state: StateFlow<LocalAiUiState> = mutableState.asStateFlow()

    fun modelDirectory(): File = (
        appContext.getExternalFilesDir("models") ?: File(appContext.filesDir, "models")
        ).also(File::mkdirs)

    fun importModel(uri: Uri) {
        if (engine != null || initializing.get()) {
            mutableState.value = mutableState.value.copy(
                statusText = "Finish local AI initialization before importing a model",
            )
            return
        }
        val requestGeneration = lifecycleGeneration.get()
        mutableState.value = mutableState.value.copy(
            status = LocalAiStatus.LOADING,
            statusText = "Checking selected local model...",
            importProgressPercent = 0,
            lastResponse = null,
        )
        scope.launch(Dispatchers.IO) {
            var partial: File? = null
            val result = runCatching {
                val descriptorSize = appContext.contentResolver
                    .openFileDescriptor(uri, "r")
                    ?.use { it.statSize }
                    ?: -1L
                val expected = LocalModelCatalog.identifyBySize(descriptorSize)
                    ?: error("Select the exact verified Gemma 3n E4B or E2B LiteRT-LM file")
                val directory = modelDirectory()
                check(directory.usableSpace >= expected.expectedBytes + MINIMUM_FREE_SPACE_AFTER_IMPORT) {
                    "Not enough free storage for ${expected.displayName}"
                }
                partial = File(directory, ".${expected.fileName}.partial")
                partial!!.delete()
                appContext.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Could not open the selected model" }
                    partial!!.outputStream().buffered().use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        var copied = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            if (isCurrent(requestGeneration)) {
                                val percent = ((copied * 100L) / expected.expectedBytes)
                                    .toInt()
                                    .coerceIn(0, 99)
                                mutableState.value = mutableState.value.copy(
                                    statusText = "Importing ${expected.displayName}: $percent%",
                                    importProgressPercent = percent,
                                )
                            }
                        }
                        buffer.fill(0)
                    }
                }
                check(partial!!.length() == expected.expectedBytes) { "Imported model size did not match" }
                val destination = File(directory, expected.fileName)
                if (destination.exists()) check(destination.delete()) { "Could not replace the existing model" }
                check(partial!!.renameTo(destination)) { "Could not finish the model import" }
                partial = null
                expected
            }
            partial?.delete()
            if (!isCurrent(requestGeneration)) return@launch
            result.fold(
                onSuccess = { spec ->
                    mutableState.value = mutableState.value.copy(
                        status = LocalAiStatus.MODEL_MISSING,
                        statusText = "${spec.displayName} imported; tap Initialize",
                        importProgressPercent = null,
                    )
                },
                onFailure = { error ->
                    mutableState.value = mutableState.value.copy(
                        status = LocalAiStatus.ERROR,
                        statusText = "Model import failed (${error.message ?: error.javaClass.simpleName})",
                        importProgressPercent = null,
                    )
                },
            )
        }
    }

    fun initialize() {
        if (engine != null || !initializing.compareAndSet(false, true)) return
        val requestGeneration = lifecycleGeneration.get()
        mutableState.value = mutableState.value.copy(
            status = LocalAiStatus.LOADING,
            statusText = "Searching for E4B/E2B and initializing locally...",
            lastResponse = null,
        )
        scope.launch {
            try {
                val available = LocalModelCatalog.findAvailable(
                    listOf(modelDirectory(), File(appContext.filesDir, "models")),
                )
                if (available.isEmpty()) {
                    if (isCurrent(requestGeneration)) {
                        mutableState.value = mutableState.value.copy(
                            status = LocalAiStatus.MODEL_MISSING,
                            statusText = "Copy a verified Gemma 3n E4B or E2B .litertlm file into the model folder",
                            importProgressPercent = null,
                        )
                    }
                    return@launch
                }

                var lastFailure = "No compatible local backend"
                for ((spec, file) in available) {
                    for (backend in LocalAiBackend.entries) {
                        if (!isCurrent(requestGeneration)) return@launch
                        val candidate = try {
                            createEngine(file, backend)
                        } catch (error: Throwable) {
                            lastFailure = "${spec.displayName} ${backend.label}: ${error.javaClass.simpleName}"
                            continue
                        }
                        try {
                            withContext(Dispatchers.IO) { candidate.initialize() }
                            if (!isCurrent(requestGeneration)) {
                                runCatching { candidate.close() }
                                return@launch
                            }
                            engine = candidate
                            mutableState.value = mutableState.value.copy(
                                status = LocalAiStatus.READY,
                                statusText = "Offline model ready",
                                modelName = spec.displayName,
                                backend = backend,
                                importProgressPercent = null,
                                fallbackState = if (spec == LocalModelCatalog.preferredModels.first() && backend == LocalAiBackend.GPU) {
                                    "Primary E4B GPU"
                                } else {
                                    "Fallback: ${spec.displayName} ${backend.label}"
                                },
                            )
                            return@launch
                        } catch (error: Throwable) {
                            runCatching { candidate.close() }
                            lastFailure = "${spec.displayName} ${backend.label}: ${error.javaClass.simpleName}"
                        }
                    }
                }
                if (isCurrent(requestGeneration)) {
                    mutableState.value = mutableState.value.copy(
                        status = LocalAiStatus.ERROR,
                        statusText = "Local initialization failed ($lastFailure)",
                        importProgressPercent = null,
                    )
                }
            } finally {
                initializing.set(false)
            }
        }
    }

    fun runPrivacyAudit(snapshot: PrivacyAuditSnapshot, trigger: String = "Manual privacy audit") {
        val activeEngine = engine ?: run {
            mutableState.value = mutableState.value.copy(statusText = "Initialize the local model first")
            return
        }
        if (mutableState.value.status != LocalAiStatus.READY) return
        val requestGeneration = lifecycleGeneration.get()
        scope.launch {
            generationMutex.withLock {
                if (!isCurrent(requestGeneration)) return@withLock
                mutableState.value = mutableState.value.copy(
                    status = LocalAiStatus.GENERATING,
                    statusText = "Running offline privacy explanation...",
                    lastResponse = null,
                    lastTrigger = trigger,
                )
                val startedNs = System.nanoTime()
                val result: Result<Pair<String, Double?>> = runCatching {
                    withContext(Dispatchers.IO) {
                        activeEngine.createConversation(conversationConfig()).use { conversation ->
                            val message = conversation.sendMessage(snapshot.prompt())
                            val response = message.contents.contents
                                .filterIsInstance<Content.Text>()
                                .joinToString(separator = "") { content -> content.text }
                                .trim()
                            require(response.isNotBlank()) { "Model returned an empty response" }
                            response to null
                        }
                    }
                }
                if (!isCurrent(requestGeneration)) return@withLock
                val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
                result.fold(
                    onSuccess = { (response, tokensPerSecond) ->
                        mutableState.value = mutableState.value.copy(
                            status = LocalAiStatus.READY,
                            statusText = "Offline privacy explanation complete",
                            inferenceCount = mutableState.value.inferenceCount + 1,
                            lastLatencyMs = elapsedMs,
                            decodeTokensPerSecond = tokensPerSecond,
                            lastResponse = response,
                        )
                    },
                    onFailure = { error ->
                        mutableState.value = mutableState.value.copy(
                            status = LocalAiStatus.READY,
                            statusText = "Local inference failed (${error.javaClass.simpleName})",
                        )
                    },
                )
            }
        }
    }

    /** Answers a spoken question using only the current, non-identifying app snapshot. */
    fun answerQuestion(question: String, snapshot: PrivacyAuditSnapshot) {
        val activeEngine = engine ?: run {
            mutableState.value = mutableState.value.copy(statusText = "Initialize the local model first")
            return
        }
        if (mutableState.value.status != LocalAiStatus.READY || question.isBlank()) return
        val requestGeneration = lifecycleGeneration.get()
        scope.launch {
            generationMutex.withLock {
                if (!isCurrent(requestGeneration)) return@withLock
                mutableState.value = mutableState.value.copy(
                    status = LocalAiStatus.GENERATING,
                    statusText = "Answering from ConsentCam state offline...",
                    lastResponse = null,
                    lastTrigger = "Voice question",
                )
                val startedNs = System.nanoTime()
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        activeEngine.createConversation(conversationConfig()).use { conversation ->
                            val response = conversation.sendMessage(snapshot.questionPrompt(question))
                                .contents.contents
                                .filterIsInstance<Content.Text>()
                                .joinToString(separator = "") { it.text }
                                .trim()
                            require(response.isNotBlank()) { "Model returned an empty response" }
                            response
                        }
                    }
                }
                if (!isCurrent(requestGeneration)) return@withLock
                val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
                result.fold(
                    onSuccess = { response ->
                        mutableState.value = mutableState.value.copy(
                            status = LocalAiStatus.READY,
                            statusText = "Offline app answer ready",
                            inferenceCount = mutableState.value.inferenceCount + 1,
                            lastLatencyMs = elapsedMs,
                            lastResponse = response,
                        )
                    },
                    onFailure = { error ->
                        mutableState.value = mutableState.value.copy(
                            status = LocalAiStatus.READY,
                            statusText = "Local question failed (${error.message ?: error.javaClass.simpleName})",
                        )
                    },
                )
            }
        }
    }

    fun updateThermalLevel(level: ThermalLevel) {
        if (level == ThermalLevel.CRITICAL) {
            runCatching { engine?.close() }
            engine = null
            mutableState.value = mutableState.value.copy(
                status = LocalAiStatus.MODEL_MISSING,
                statusText = "Local AI suspended at critical thermal level; camera protection continues",
                thermalLevel = level,
            )
        } else {
            mutableState.value = mutableState.value.copy(thermalLevel = level)
        }
    }

    override fun close() {
        lifecycleGeneration.incrementAndGet()
        scope.coroutineContext[Job]?.cancel()
        runCatching { engine?.close() }
        engine = null
        mutableState.value = LocalAiUiState(LocalAiStatus.CLOSED, "Local model closed")
    }

    private fun createEngine(file: File, backend: LocalAiBackend): Engine = Engine(
        EngineConfig(
            modelPath = file.absolutePath,
            backend = when (backend) {
                LocalAiBackend.GPU -> Backend.GPU()
                LocalAiBackend.CPU -> Backend.CPU()
            },
            cacheDir = appContext.cacheDir.absolutePath,
        ),
    )

    private fun conversationConfig() = ConversationConfig(
        systemInstruction = Contents.of(
            "You are ConsentCam's offline privacy explainer. Never identify people, decide face matches, or authorize unblurring.",
        ),
        samplerConfig = SamplerConfig(topK = 32, topP = 0.9, temperature = 0.2, seed = 7),
        maxOutputToken = 96,
    )

    private fun isCurrent(expectedGeneration: Long): Boolean = lifecycleGeneration.get() == expectedGeneration
}

private const val COPY_BUFFER_BYTES = 1024 * 1024
private const val MINIMUM_FREE_SPACE_AFTER_IMPORT = 512L * 1024L * 1024L
