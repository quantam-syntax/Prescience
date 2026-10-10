package com.atreides.consentvoice

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.atreides.faceguidance.EnrollmentCameraPose
import com.atreides.faceguidance.FaceGuidanceAnalyzer
import com.atreides.faceguidance.FaceGuidanceState
import com.atreides.faceguidance.GuidanceStatus
import com.atreides.voiceconsent.PersonaAccessory
import com.atreides.voiceconsent.PersonaEmojiProfile
import com.atreides.voiceconsent.PersonaEyes
import com.atreides.voiceconsent.PersonaHair
import com.atreides.voiceconsent.PersonaMouth
import com.atreides.voiceconsent.PersonaPalette
import com.atreides.voiceconsent.OnDeviceVoicePipeline
import com.atreides.voiceconsent.MoonshineTranscriber
import com.atreides.voiceconsent.PcmConsentRedactor
import com.atreides.voiceconsent.SpeechState
import com.atreides.voiceconsent.SessionState
import com.atreides.voiceconsent.VOICE_SAMPLE_RATE_HZ
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 41)
        }
        setContent { ConsentVoiceApp() }
    }
}

private enum class CaptureMode { ENROLLMENT, SESSION }

@androidx.compose.runtime.Composable
private fun ConsentVoiceApp() {
    var showGuidedEnrollment by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<SessionState>(SessionState.Idle) }
    var persona by remember { mutableStateOf(PersonaEmojiProfile()) }
    var profile by remember { mutableStateOf<List<FloatArray>?>(null) }
    var enrollmentSamples by remember { mutableStateOf<List<FloatArray>>(emptyList()) }
    var captureMode by remember { mutableStateOf<CaptureMode?>(null) }
    var sessionResult by remember { mutableStateOf("No consent session has been exported.") }
    var liveTranscript by remember { mutableStateOf("") }
    var recordingSeconds by remember { mutableStateOf(0) }
    var modelStatus by remember { mutableStateOf("Starting local voice models…") }
    val transcriptModelState = remember { mutableStateOf<MoonshineTranscriber?>(null) }
    var transcriptModelStatus by remember { mutableStateOf("Loading local transcript model…") }
    var sanitizedExport by remember { mutableStateOf<java.io.File?>(null) }
    var isPlayingExport by remember { mutableStateOf(false) }
    var playingExportPath by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    var recordingHistory by remember(context) { mutableStateOf(SanitizedRecordingHistory.list(context)) }
    val capture = remember { InMemoryMicCapture() }
    val passportStore = remember { VoicePassportStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val player = remember { MediaPlayer() }
    fun togglePlayback(file: java.io.File) {
        if (isPlayingExport && playingExportPath == file.absolutePath) {
            player.pause()
            isPlayingExport = false
        } else {
            runCatching {
                player.reset()
                player.setDataSource(file.absolutePath)
                player.setOnCompletionListener { isPlayingExport = false }
                player.prepare()
                player.start()
                playingExportPath = file.absolutePath
                isPlayingExport = true
            }.onFailure { sessionResult = "Could not play sanitized recording: ${it.message}" }
        }
    }
    fun downloadSanitized(file: java.io.File) {
        scope.launch {
            sessionResult = withContext(Dispatchers.IO) {
                runCatching { SanitizedAudioDownloader.download(context, file) }
                    .getOrElse { "Could not save sanitized audio: ${it.message}" }
            }
        }
    }
    val enrollmentAudioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            sessionResult = "Loading enrollment recording locallyâ€¦"
            val pcm = withContext(Dispatchers.Default) {
                runCatching { ImportedAudioDecoder.decodeTo16kMono(context, uri) }
            }.getOrElse {
                sessionResult = "Could not read enrollment recording: ${it.message}"
                return@launch
            }
            sessionResult = withContext(Dispatchers.Default) {
                runCatching {
                    val pipeline = OnDeviceVoicePipeline(context)
                    try {
                        val enrolled = pipeline.enrollEmbedding(pcm)
                        if (enrolled == null) {
                            "Enrollment rejected: not enough clear speech. Select a 30â€“60 second recording containing only your voice."
                        } else {
                            profile = listOf(enrolled)
                            state = SessionState.Idle
                            "External enrollment imported locally (${enrolled.size}-D embedding). The source recording was not saved."
                        }
                    } finally { pipeline.close() }
                }.getOrElse { "Could not enroll selected recording: ${it.message}" }
            }
        }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val enrolled = profile ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            sessionResult = "Loading selected audio locally…"
            val pcm = withContext(Dispatchers.Default) {
                runCatching { ImportedAudioDecoder.decodeTo16kMono(context, uri) }
            }.getOrElse {
                sessionResult = "Could not read selected audio: ${it.message}"
                return@launch
            }
            sessionResult = "Sanitizing selected audio locally…"
            sessionResult = withContext(Dispatchers.Default) {
                runCatching {
                    val pipeline = OnDeviceVoicePipeline(context)
                    try {
                        val decisions = pipeline.decisions(pcm, enrolled)
                        val sanitized = PcmConsentRedactor.redact(pcm, decisions)
                        val export = SanitizedRecordingHistory.newExport(context)
                        SanitizedWavWriter.write(export, sanitized)
                        sanitizedExport = export
                        recordingHistory = SanitizedRecordingHistory.list(context)
                        val confirmed = decisions.count { it.state == SpeechState.PROTECTED }
                        val ambiguous = decisions.count { it.state == SpeechState.UNCERTAIN }
                        val retained = decisions.count { it.state == SpeechState.UNMATCHED }
                        val warning = if (confirmed == 0) "WARNING: No confirmed voice match; no speech was redacted. " else ""
                        "${warning}Selected audio processed locally: $confirmed confirmed match(es) muted; $ambiguous uncertain and $retained nonmatching utterance(s) retained. Review before sharing. Tap Play or ↓ to download."
                    } finally { pipeline.close() }
                }.getOrElse { "Could not sanitize selected audio: ${it.message}" }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            player.release()
            transcriptModelState.value?.close()
        }
    }
    LaunchedEffect(Unit) {
        modelStatus = withContext(Dispatchers.Default) {
            runCatching {
                val pipeline = OnDeviceVoicePipeline(context)
                val dimension = pipeline.embeddingDimension
                pipeline.close()
                "On-device VAD + voice model ready ($dimension dimensions)"
            }.getOrElse { "Model startup failed: ${it.message}" }
        }
    }
    LaunchedEffect(Unit) {
        if (MoonshineTranscriber.isAvailable(context)) {
            val result = withContext(Dispatchers.Default) { runCatching { MoonshineTranscriber(context) } }
            transcriptModelState.value = result.getOrNull()
            transcriptModelStatus = if (result.isSuccess) {
                "On-device live transcript ready"
            } else {
                "Local transcript model unavailable: ${result.exceptionOrNull()?.message}"
            }
        } else {
            transcriptModelStatus = "Live transcript is optional and not installed; voice protection remains available."
        }
    }
    LaunchedEffect(Unit) {
        passportStore.load().getOrNull()?.let { restored ->
            profile = restored
            sessionResult = "Restored your encrypted Voice Passport (${restored.size} position samples)."
        }
    }
    LaunchedEffect(captureMode) {
        recordingSeconds = 0
        if (captureMode != null) while (captureMode != null) {
            delay(1_000)
            recordingSeconds += 1
        }
    }
    LaunchedEffect(captureMode, transcriptModelState.value) {
        if (captureMode == null) return@LaunchedEffect
        val transcriber = transcriptModelState.value
        if (transcriber == null) {
            liveTranscript = "Preparing local transcript…"
            return@LaunchedEffect
        }
        liveTranscript = "Listening locally…"
        try {
            while (captureMode != null) {
                delay(3_000)
                val window = capture.snapshot().takeLast(VOICE_SAMPLE_RATE_HZ * 8).toFloatArray()
                if (window.size >= VOICE_SAMPLE_RATE_HZ) {
                    val text = withContext(Dispatchers.Default) { runCatching { transcriber.transcribe(window) }.getOrDefault("") }
                    if (text.isNotBlank()) liveTranscript = text
                }
            }
        } finally { }
    }
    if (showGuidedEnrollment) {
        GuidedVoiceEnrollmentScreen(
            onCancel = { showGuidedEnrollment = false },
            onCompleted = { guidedTakes ->
                showGuidedEnrollment = false
                scope.launch {
                    sessionResult = "Creating the protected voice profile locally…"
                    sessionResult = withContext(Dispatchers.Default) {
                        runCatching {
                            val pipeline = OnDeviceVoicePipeline(context)
                            try {
                                val enrolled = pipeline.enrollEmbedding(guidedTakes)
                                if (enrolled == null) {
                                    "Enrollment rejected: one or more takes did not contain enough clear speech. Please try again."
                                } else {
                                    passportStore.save(enrolled).getOrThrow()
                                    profile = enrolled
                                    state = SessionState.Idle
                                    "Protected voice enrolled with four position-specific references. Camera frames and recordings were not saved."
                                }
                            } finally {
                                pipeline.close()
                            }
                        }.getOrElse { "Could not create the voice profile: ${it.message}" }
                    }
                }
            },
        )
        return
    }
    MaterialTheme {
        Scaffold { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Offline Privacy Passport", style = MaterialTheme.typography.headlineSmall)
                Text("Status: ${state.label}")
                Text("Voice matching, BLE, and microphone capture are being connected locally.")
                Text(modelStatus, style = MaterialTheme.typography.bodySmall)
                Text(transcriptModelStatus, style = MaterialTheme.typography.bodySmall)
                Text("Voice Consent Studio", style = MaterialTheme.typography.titleMedium)
                Text("Raw audio stays in memory. Only the consent-sanitized export is written.")
                val passportPositions = listOf(
                    "front, close to your mouth", "right side, close", "left side, close",
                    "front, arm's length",
                )
                if (profile == null) Text("Voice Passport: ${enrollmentSamples.size}/4 close-range positions accepted", style = MaterialTheme.typography.bodySmall)
                if (captureMode == null) {
                    Button(
                        onClick = {
                            state = SessionState.EnrollmentRequired
                            showGuidedEnrollment = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (profile == null) "Enroll protected voice" else "Re-enroll protected voice")
                    }
                    Button(
                        onClick = { enrollmentAudioPicker.launch(arrayOf("audio/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Import enrollment recording") }
                    Text(
                        "Use a 30–60 second local recording containing only your voice. It is decoded and enrolled on-device; the original is not copied.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        enabled = profile != null,
                        onClick = {
                            runCatching {
                                capture.start()
                            captureMode = CaptureMode.SESSION
                            liveTranscript = "Listening locally…"
                                state = SessionState.Active
                                sessionResult = "Consent session recording. Speak as the enrolled person and another speaker."
                            }.onFailure { sessionResult = "Microphone error: ${it.message}" }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Start protected consent session") }
                    Button(
                        enabled = profile != null,
                        onClick = { audioPicker.launch(arrayOf("audio/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Select audio from phone and sanitize") }
                } else {
                    Button(onClick = {
                        val mode = captureMode ?: return@Button
                        val pcm = capture.stop()
                        captureMode = null
                        scope.launch {
                            sessionResult = "Analysing locally…"
                            sessionResult = withContext(Dispatchers.Default) {
                                runCatching {
                                    when (mode) {
                                        CaptureMode.ENROLLMENT -> {
                                            val pipeline = OnDeviceVoicePipeline(context)
                                            try {
                                                val sample = pipeline.enrollEmbedding(pcm)
                                                if (sample == null) {
                                                    "This position was rejected: not enough clear solo speech. Repeat the same step in a quieter place."
                                                } else {
                                                    val next = enrollmentSamples + sample
                                                    if (next.size == VoicePassportStore.REQUIRED_SAMPLES) {
                                                        passportStore.save(next).getOrThrow()
                                                        profile = next
                                                        enrollmentSamples = emptyList()
                                                        "Voice Passport complete: 4 encrypted close-range samples saved locally."
                                                    } else {
                                                        enrollmentSamples = next
                                                        "Voice Passport step ${next.size}/4 accepted. Record the next position."
                                                    }
                                                }
                                            } finally { pipeline.close() }
                                        }
                                        CaptureMode.SESSION -> {
                                            val enrolled = profile ?: error("Complete the Voice Passport first")
                                            val pipeline = OnDeviceVoicePipeline(context)
                                            try {
                                                val decisions = pipeline.decisions(pcm, enrolled)
                                                val confirmedProtected = decisions.count { it.state == SpeechState.PROTECTED }
                                                val ambiguous = decisions.count { it.state == SpeechState.UNCERTAIN }
                                                val overlapWithheld = decisions.count { it.state == SpeechState.OVERLAP }
                                                val retained = decisions.count { it.state == SpeechState.UNMATCHED }
                                                val scoreRange = decisions
                                                    .map { it.score }
                                                    .let { scores -> if (scores.isEmpty()) "n/a" else "${"%.2f".format(scores.min())}–${"%.2f".format(scores.max())}" }
                                                val sanitized = PcmConsentRedactor.redact(pcm, decisions)
                                                val export = SanitizedRecordingHistory.newExport(context)
                                                SanitizedWavWriter.write(export, sanitized)
                                                sanitizedExport = export
                                                recordingHistory = SanitizedRecordingHistory.list(context)
                                                val transcript = transcriptModelState.value?.transcribe(sanitized).orEmpty()
                                                liveTranscript = if (transcript.isBlank()) "[No speech retained in sanitized export]" else transcript
                                                state = SessionState.Idle
                                                val warning = if (confirmedProtected == 0 && overlapWithheld == 0) "WARNING: No confirmed voice match; no speech was redacted. " else ""
                                                "${warning}Export created: $confirmedProtected confirmed match(es) and $overlapWithheld overlap window(s) muted; $ambiguous uncertain and $retained nonmatching utterance(s) retained. Review before sharing. Local match scores $scoreRange. ${export.name} is the only saved audio."
                                            } finally { pipeline.close() }
                                        }
                                    }
                                }.getOrElse { "Local processing failed: ${it.message}" }
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (captureMode == CaptureMode.ENROLLMENT) "Stop and enroll" else "Stop, redact, and export")
                    }
                }
                if (captureMode != null) {
                    Text("● RECORDING  ${recordingSeconds}s", color = Color(0xFFD32F2F), style = MaterialTheme.typography.titleSmall)
                    Text("Live local transcript: $liveTranscript", style = MaterialTheme.typography.bodySmall)
                    if (captureMode == CaptureMode.ENROLLMENT) {
                        TextButton(onClick = {
                            capture.stop()
                            captureMode = null
                            enrollmentSamples = emptyList()
                            state = SessionState.Idle
                            sessionResult = "Voice Passport enrolment cancelled. No sample was saved; you can start again."
                        }) { Text("Cancel enrolment and restart") }
                    }
                }
                Text(sessionResult, style = MaterialTheme.typography.bodySmall)
                if (sanitizedExport?.exists() == true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { downloadSanitized(sanitizedExport!!) }) { Text("↓") }
                        Button(
                            onClick = { togglePlayback(sanitizedExport!!) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                if (isPlayingExport && playingExportPath == sanitizedExport!!.absolutePath) {
                                    "Pause latest sanitized recording"
                                } else {
                                    "Play latest sanitized recording"
                                },
                            )
                        }
                    }
                    Text(
                        "Playback is only the consent-sanitized export. The original microphone capture was never saved.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (recordingHistory.isNotEmpty()) {
                    Text("Sanitized recording history", style = MaterialTheme.typography.titleSmall)
                    recordingHistory.filter { it != sanitizedExport }.take(8).forEach { recording ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { downloadSanitized(recording) }) { Text("↓") }
                            Button(
                                onClick = { togglePlayback(recording) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    if (isPlayingExport && playingExportPath == recording.absolutePath) {
                                        "Pause · ${SanitizedRecordingHistory.label(recording)}"
                                    } else {
                                        "Play · ${SanitizedRecordingHistory.label(recording)}"
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("Your privacy emoji", style = MaterialTheme.typography.titleMedium)
                Text("This is a local style recipe, not a face scan or an uploaded image.")
                PersonaEditor(persona = persona, onPersonaChange = { persona = it })
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun FaceGuidanceScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionGranted = it
    }
    val poses = EnrollmentCameraPose.entries
    var poseIndex by remember { mutableIntStateOf(0) }
    var guidance by remember {
        mutableStateOf(FaceGuidanceState(poses.first(), GuidanceStatus.SEARCHING, poses.first().instruction))
    }
    val analyzer = remember { FaceGuidanceAnalyzer(poses.first()) { guidance = it } }
    DisposableEffect(Unit) { onDispose { analyzer.close() } }
    LaunchedEffect(poseIndex) { analyzer.setTargetPose(poses[poseIndex]) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (permissionGranted) {
            GuidanceCameraPreview(analyzer)
        } else {
            Button(
                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.align(Alignment.Center),
            ) { Text("Allow camera") }
        }
        Button(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(18.dp)) { Text("Back") }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color(0xDD10151C), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Camera positioning guide", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Text("Step ${poseIndex + 1} of ${poses.size}: ${poses[poseIndex].label}", color = Color(0xFF82D8FF))
            Text(guidance.message, color = if (guidance.canRecord) Color(0xFF72E6A6) else Color.White)
            LinearProgressIndicator(progress = { guidance.holdProgress }, modifier = Modifier.fillMaxWidth())
            Text(
                "This checks camera position only. Voice recording and audio quality remain part of the enrollment system.",
                color = Color(0xFFB9C2CC),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(enabled = poseIndex > 0, onClick = { poseIndex-- }, modifier = Modifier.weight(1f)) { Text("Previous") }
                Button(
                    enabled = guidance.canRecord,
                    onClick = { if (poseIndex < poses.lastIndex) poseIndex++ else onBack() },
                    modifier = Modifier.weight(1f),
                ) { Text(if (poseIndex == poses.lastIndex) "Finish" else "Position ready") }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun GuidanceCameraPreview(analyzer: FaceGuidanceAnalyzer) {
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
                    ResolutionStrategy(
                        android.util.Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                    ),
                ).build(),
            )
            .build().also { it.setAnalyzer(executor, analyzer) }
        provider.unbindAll()
        provider.bindToLifecycle(context as ComponentActivity, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
    }
    AndroidView(
        factory = {
            PreviewView(it).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
                .also { view -> previewView = view }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@androidx.compose.runtime.Composable
private fun PersonaEditor(persona: PersonaEmojiProfile, onPersonaChange: (PersonaEmojiProfile) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PersonaPreview(persona, Modifier.padding(end = 16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${persona.palette.label} · ${persona.hair.label}")
                    Text("${persona.eyes.label} eyes · ${persona.mouth.label}")
                    Text("${persona.accessory.label} accessory")
                }
            }
            PersonaChoice("Palette: ${persona.palette.label}") {
                onPersonaChange(persona.copy(palette = persona.palette.next()))
            }
            PersonaChoice("Hair: ${persona.hair.label}") {
                onPersonaChange(persona.copy(hair = persona.hair.next()))
            }
            PersonaChoice("Eyes: ${persona.eyes.label}") {
                onPersonaChange(persona.copy(eyes = persona.eyes.next()))
            }
            PersonaChoice("Mouth: ${persona.mouth.label}") {
                onPersonaChange(persona.copy(mouth = persona.mouth.next()))
            }
            PersonaChoice("Accessory: ${persona.accessory.label}") {
                onPersonaChange(persona.copy(accessory = persona.accessory.next()))
            }
            Text(
                "After peer approval, share only this recipe; the receiving device renders it locally.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun PersonaChoice(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

@androidx.compose.runtime.Composable
private fun PersonaPreview(persona: PersonaEmojiProfile, modifier: Modifier = Modifier) {
    val face = when (persona.palette) {
        PersonaPalette.SUNSET -> Color(0xFFFFC56E)
        PersonaPalette.OCEAN -> Color(0xFF78D4E8)
        PersonaPalette.MINT -> Color(0xFF9DE0B4)
        PersonaPalette.LILAC -> Color(0xFFC7B2F5)
    }
    Canvas(modifier = modifier.height(104.dp).fillMaxWidth(0.30f)) {
        val r = size.minDimension * 0.36f
        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        drawCircle(face, r, center)
        val eyeY = center.y - r * 0.12f
        val eyeOffset = r * 0.34f
        val eyeRadius = r * 0.075f
        when (persona.eyes) {
            PersonaEyes.DOTS -> {
                drawCircle(Color(0xFF1E293B), eyeRadius, center.copy(x = center.x - eyeOffset, y = eyeY))
                drawCircle(Color(0xFF1E293B), eyeRadius, center.copy(x = center.x + eyeOffset, y = eyeY))
            }
            PersonaEyes.HAPPY -> {
                drawArc(Color(0xFF1E293B), 190f, 160f, false, topLeft = center.copy(x = center.x - eyeOffset - eyeRadius, y = eyeY - eyeRadius), size = androidx.compose.ui.geometry.Size(eyeRadius * 2, eyeRadius * 1.4f), style = Stroke(5f))
                drawArc(Color(0xFF1E293B), 190f, 160f, false, topLeft = center.copy(x = center.x + eyeOffset - eyeRadius, y = eyeY - eyeRadius), size = androidx.compose.ui.geometry.Size(eyeRadius * 2, eyeRadius * 1.4f), style = Stroke(5f))
            }
            PersonaEyes.SPARKLE -> {
                drawCircle(Color.White, eyeRadius * 1.5f, center.copy(x = center.x - eyeOffset, y = eyeY))
                drawCircle(Color.White, eyeRadius * 1.5f, center.copy(x = center.x + eyeOffset, y = eyeY))
            }
        }
        val mouthY = center.y + r * 0.25f
        when (persona.mouth) {
            PersonaMouth.SMILE -> drawArc(Color(0xFF1E293B), 10f, 160f, false, topLeft = androidx.compose.ui.geometry.Offset(center.x - r * .35f, mouthY - r * .15f), size = androidx.compose.ui.geometry.Size(r * .7f, r * .5f), style = Stroke(6f))
            PersonaMouth.CALM -> drawLine(Color(0xFF1E293B), center.copy(x = center.x - r * .25f, y = mouthY), center.copy(x = center.x + r * .25f, y = mouthY), strokeWidth = 6f)
            PersonaMouth.GRIN -> drawRoundRect(Color.White, topLeft = center.copy(x = center.x - r * .28f, y = mouthY - r * .08f), size = androidx.compose.ui.geometry.Size(r * .56f, r * .25f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * .07f))
        }
        when (persona.hair) {
            PersonaHair.CURLY -> repeat(5) { drawCircle(Color(0xFF4A2D22), r * .18f, center.copy(x = center.x - r * .55f + it * r * .28f, y = center.y - r * .75f)) }
            PersonaHair.SWEEP -> drawArc(Color(0xFF4A2D22), 185f, 175f, true, topLeft = androidx.compose.ui.geometry.Offset(center.x - r, center.y - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 1.1f))
            PersonaHair.BOB -> drawArc(Color(0xFF293241), 180f, 180f, true, topLeft = androidx.compose.ui.geometry.Offset(center.x - r, center.y - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 1.55f))
            PersonaHair.CAP -> drawArc(Color(0xFFDC5F40), 180f, 180f, true, topLeft = androidx.compose.ui.geometry.Offset(center.x - r, center.y - r * 1.1f), size = androidx.compose.ui.geometry.Size(r * 2, r * 1.35f))
        }
        when (persona.accessory) {
            PersonaAccessory.NONE -> Unit
            PersonaAccessory.GLASSES -> {
                drawCircle(Color(0xFF1E293B), r * .23f, center.copy(x = center.x - eyeOffset, y = eyeY), style = Stroke(5f))
                drawCircle(Color(0xFF1E293B), r * .23f, center.copy(x = center.x + eyeOffset, y = eyeY), style = Stroke(5f))
                drawLine(Color(0xFF1E293B), center.copy(x = center.x - r * .11f, y = eyeY), center.copy(x = center.x + r * .11f, y = eyeY), strokeWidth = 5f)
            }
            PersonaAccessory.STAR -> drawCircle(Color(0xFFFFD23F), r * .13f, center.copy(x = center.x + r * .63f, y = center.y - r * .55f))
        }
    }
}

private fun <T> T.next(values: Array<T>): T = values[(values.indexOf(this) + 1) % values.size]
private fun PersonaPalette.next() = next(PersonaPalette.values())
private fun PersonaHair.next() = next(PersonaHair.values())
private fun PersonaEyes.next() = next(PersonaEyes.values())
private fun PersonaMouth.next() = next(PersonaMouth.values())
private fun PersonaAccessory.next() = next(PersonaAccessory.values())
