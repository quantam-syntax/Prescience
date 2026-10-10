package com.atreides.consentvoice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.atreides.faceguidance.EnrollmentCameraPose
import com.atreides.faceguidance.FaceGuidanceAnalyzer
import com.atreides.faceguidance.FaceGuidanceState
import com.atreides.faceguidance.GuidanceStatus
import com.atreides.voiceconsent.VOICE_SAMPLE_RATE_HZ
import com.atreides.voiceconsent.WhisperTranscriber
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class GuidedEnrollmentPhase { POSITIONING, RECORDING, VALIDATING, RETRY }

private val enrollmentPrompts = listOf(
    "My voice is private and I choose when it may be shared.",
    "Clear consent helps technology respect every person in the conversation.",
    "This recording stays on my device while my voice profile is created.",
    "I can participate freely while keeping control of my own identity.",
)

@Composable
internal fun GuidedVoiceEnrollmentScreen(
    onCancel: () -> Unit,
    onCompleted: (List<FloatArray>) -> Unit,
) {
    BackHandler(onBack = onCancel)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val capture = remember { InMemoryMicCapture() }
    val acceptedTakes = remember { mutableStateListOf<FloatArray>() }
    var poseIndex by remember { mutableIntStateOf(0) }
    var attempt by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf(GuidedEnrollmentPhase.POSITIONING) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var transcript by remember { mutableStateOf("") }
    var matchScore by remember { mutableStateOf<Float?>(null) }
    var pendingTake by remember { mutableStateOf<FloatArray?>(null) }
    var whisper by remember { mutableStateOf<WhisperTranscriber?>(null) }
    var whisperStatus by remember { mutableStateOf("Loading offline Whisper…") }
    var cameraAllowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var microphoneAllowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        cameraAllowed = grants[Manifest.permission.CAMERA] == true || cameraAllowed
        microphoneAllowed = grants[Manifest.permission.RECORD_AUDIO] == true || microphoneAllowed
    }
    val poses = EnrollmentCameraPose.entries
    var guidance by remember {
        mutableStateOf(FaceGuidanceState(poses.first(), GuidanceStatus.SEARCHING, poses.first().instruction))
    }
    val analyzer = remember { FaceGuidanceAnalyzer(poses.first()) { guidance = it } }

    DisposableEffect(Unit) {
        onDispose {
            capture.stop()
            analyzer.close()
            whisper?.close()
        }
    }
    LaunchedEffect(Unit) {
        if (!cameraAllowed || !microphoneAllowed) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
        val result = withContext(Dispatchers.Default) { runCatching { WhisperTranscriber(context) } }
        whisper = result.getOrNull()
        whisperStatus = if (result.isSuccess) "Offline Whisper ready" else "Whisper unavailable: ${result.exceptionOrNull()?.message}"
    }
    LaunchedEffect(poseIndex, attempt) {
        analyzer.setTargetPose(poses[poseIndex])
        phase = GuidedEnrollmentPhase.POSITIONING
        transcript = ""
        matchScore = null
        pendingTake = null
        elapsedMs = 0
    }
    LaunchedEffect(guidance.canRecord, phase, whisper, cameraAllowed, microphoneAllowed) {
        if (
            guidance.canRecord && phase == GuidedEnrollmentPhase.POSITIONING && whisper != null &&
            cameraAllowed && microphoneAllowed
        ) {
            delay(350)
            if (guidance.canRecord && phase == GuidedEnrollmentPhase.POSITIONING) {
                runCatching { capture.start() }
                    .onSuccess { phase = GuidedEnrollmentPhase.RECORDING }
                    .onFailure {
                        transcript = "Microphone could not start: ${it.message}"
                        phase = GuidedEnrollmentPhase.RETRY
                    }
            }
        }
    }
    LaunchedEffect(phase) {
        val started = android.os.SystemClock.elapsedRealtime()
        while (phase == GuidedEnrollmentPhase.RECORDING) {
            elapsedMs = android.os.SystemClock.elapsedRealtime() - started
            delay(120)
        }
    }

    fun acceptTake(pcm: FloatArray) {
        pendingTake = null
        acceptedTakes += pcm
        if (poseIndex == poses.lastIndex) {
            onCompleted(acceptedTakes.toList())
        } else {
            poseIndex++
        }
    }

    fun validateTake() {
        if (phase != GuidedEnrollmentPhase.RECORDING) return
        val pcm = capture.stop()
        phase = GuidedEnrollmentPhase.VALIDATING
        scope.launch {
            val recognized = withContext(Dispatchers.Default) {
                runCatching { requireNotNull(whisper).transcribe(pcm) }.getOrDefault("")
            }
            val score = maxOf(
                promptSimilarity(enrollmentPrompts[poseIndex], recognized),
                promptKeywordCoverage(enrollmentPrompts[poseIndex], recognized),
            )
            transcript = recognized
            matchScore = score
            pendingTake = pcm
            val enoughAudio = pcm.size >= VOICE_SAMPLE_RATE_HZ * 3
            if (enoughAudio && score >= REQUIRED_PROMPT_MATCH) {
                acceptTake(pcm)
            } else {
                phase = GuidedEnrollmentPhase.RETRY
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (cameraAllowed) GuidedEnrollmentCameraPreview(analyzer)
        Button(onClick = onCancel, modifier = Modifier.align(Alignment.TopStart).padding(18.dp)) { Text("Cancel") }

        Column(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 78.dp, start = 20.dp, end = 20.dp)
                .background(Color(0x9910151C), RoundedCornerShape(20.dp)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Guided voice enrollment", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Text("Take ${poseIndex + 1} of ${poses.size} · ${poses[poseIndex].label}", color = Color(0xFF82D8FF))
            Text(whisperStatus, color = Color(0xFFB9C2CC), style = MaterialTheme.typography.bodySmall)
            if (phase == GuidedEnrollmentPhase.POSITIONING) {
                Text(guidance.message, color = if (guidance.canRecord) Color(0xFF72E6A6) else Color.White)
                LinearProgressIndicator(progress = { guidance.holdProgress }, modifier = Modifier.fillMaxWidth())
            }
        }

        if (phase == GuidedEnrollmentPhase.RECORDING || phase == GuidedEnrollmentPhase.VALIDATING) {
            TeleprompterCard(
                prompt = enrollmentPrompts[poseIndex],
                highlightedWord = (elapsedMs / WORD_INTERVAL_MS).toInt(),
                validating = phase == GuidedEnrollmentPhase.VALIDATING,
                onFinished = ::validateTake,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
            )
        }

        if (phase == GuidedEnrollmentPhase.RETRY) {
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color(0xE610151C), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Please repeat this take", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("Whisper heard: ${transcript.ifBlank { "No clear sentence" }}", color = Color.White)
                Text("Speech check: ${((matchScore ?: 0f) * 100).toInt()}% · required 58%", color = Color(0xFFFFC46B))
                Button(
                    enabled = pendingTake?.size?.let { it >= VOICE_SAMPLE_RATE_HZ * 3 } == true &&
                        (matchScore ?: 0f) >= RELAXED_PROMPT_MATCH,
                    onClick = { pendingTake?.let(::acceptTake) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Accept with relaxed check (35%)") }
                Button(onClick = { attempt++ }, modifier = Modifier.fillMaxWidth()) { Text("Try this position again") }
                Text(
                    "The relaxed option changes prompt validation only. It still requires enough speech for the voice profile.",
                    color = Color(0xFFB9C2CC),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (!cameraAllowed || !microphoneAllowed) {
            Button(
                onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) },
                modifier = Modifier.align(Alignment.Center),
            ) { Text("Allow camera and microphone") }
        }
    }
}

@Composable
private fun TeleprompterCard(
    prompt: String,
    highlightedWord: Int,
    validating: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val words = prompt.split(' ')
    val styled = buildAnnotatedString {
        words.forEachIndexed { index, word ->
            if (index > 0) append(' ')
            pushStyle(
                SpanStyle(
                    color = if (index == highlightedWord.coerceAtMost(words.lastIndex)) Color(0xFF82D8FF) else Color.White,
                    fontWeight = if (index == highlightedWord.coerceAtMost(words.lastIndex)) FontWeight.Bold else FontWeight.Normal,
                ),
            )
            append(word)
            pop()
        }
    }
    Column(
        modifier = modifier.fillMaxWidth().background(Color(0xAA000000), RoundedCornerShape(24.dp)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Read naturally", color = Color(0xFFB9C2CC))
        Text(styled, color = Color.White, fontSize = 28.sp, lineHeight = 38.sp, textAlign = TextAlign.Center)
        Button(enabled = !validating, onClick = onFinished, modifier = Modifier.fillMaxWidth()) {
            Text(if (validating) "Whisper is validating…" else "I finished speaking")
        }
    }
}

@Composable
private fun GuidedEnrollmentCameraPreview(analyzer: FaceGuidanceAnalyzer) {
    val context = LocalContext.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            cameraProvider?.unbindAll()
            executor.shutdown()
        }
    }
    LaunchedEffect(previewView) {
        val view = previewView ?: return@LaunchedEffect
        val provider = ProcessCameraProvider.getInstance(context).get()
        cameraProvider = provider
        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(android.util.Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                ).build(),
            )
            .build().also { it.setAnalyzer(executor, analyzer) }
        provider.unbindAll()
        provider.bindToLifecycle(context as ComponentActivity, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
    }
    AndroidView(
        factory = { PreviewView(it).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }.also { view -> previewView = view } },
        modifier = Modifier.fillMaxSize(),
    )
}

private fun promptSimilarity(expected: String, actual: String): Float {
    val left = normalizedWords(expected)
    val right = normalizedWords(actual)
    if (left.isEmpty() || right.isEmpty()) return 0f
    val previous = IntArray(right.size + 1) { it }
    left.forEachIndexed { leftIndex, leftWord ->
        val current = IntArray(right.size + 1)
        current[0] = leftIndex + 1
        right.forEachIndexed { rightIndex, rightWord ->
            current[rightIndex + 1] = minOf(
                current[rightIndex] + 1,
                previous[rightIndex + 1] + 1,
                previous[rightIndex] + if (leftWord == rightWord) 0 else 1,
            )
        }
        current.copyInto(previous)
    }
    return (1f - previous.last().toFloat() / maxOf(left.size, right.size)).coerceIn(0f, 1f)
}

private fun normalizedWords(value: String): List<String> = value.lowercase()
    .replace(Regex("[^a-z0-9' ]"), " ")
    .split(Regex("\\s+"))
    .filter(String::isNotBlank)

private fun promptKeywordCoverage(expected: String, actual: String): Float {
    val expectedKeywords = normalizedWords(expected).filterNot(COMMON_PROMPT_WORDS::contains).toSet()
    if (expectedKeywords.isEmpty()) return 0f
    val actualWords = normalizedWords(actual).toSet()
    return expectedKeywords.count(actualWords::contains).toFloat() / expectedKeywords.size
}

private val COMMON_PROMPT_WORDS = setOf(
    "a", "and", "be", "every", "for", "i", "in", "is", "it", "may", "my", "of", "on", "own", "the", "this", "while",
)

private const val REQUIRED_PROMPT_MATCH = 0.58f
private const val RELAXED_PROMPT_MATCH = 0.35f
private const val WORD_INTERVAL_MS = 480L
