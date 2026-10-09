package com.example.consent_cam

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.consent_cam.agent.LocalAiController
import com.example.consent_cam.agent.LocalAiStatus
import com.example.consent_cam.agent.LocalAiUiState
import com.example.consent_cam.agent.PrivacyAuditSnapshot
import com.example.consent_cam.agent.DeviceThermalMonitor
import com.example.consent_cam.agent.ThermalGovernor
import com.example.consent_cam.agent.ThermalLevel
import com.example.consent_cam.ble.AppearanceConsent
import com.example.consent_cam.ble.BleConsentController
import com.example.consent_cam.ble.BleConsentRuntime
import com.example.consent_cam.ble.BleConsentService
import com.example.consent_cam.ble.BleConsentUiState
import com.example.consent_cam.ble.QrCodeRenderer
import com.example.consent_cam.ble.QrScannerController
import com.example.consent_cam.ble.PrivacyZonePreset
import com.example.consent_cam.camera.ConsentCameraController
import com.example.consent_cam.camera.CameraCapabilities
import com.example.consent_cam.privacy.ProximityPrivacyCoordinator
import com.example.consent_cam.privacy.rendering.FacePixelationOverlay
import com.example.consent_cam.privacy.rendering.FaceRegionSmoother
import com.example.consent_cam.privacy.rendering.ProtectedFrameState
import com.example.consent_cam.ui.theme.ConsentCamTheme
import com.example.consent_cam.vision.FaceDetectionState
import com.example.consent_cam.recognition.EnrollmentUiState
import com.example.consent_cam.recognition.AvatarPreset
import com.example.consent_cam.recognition.ExactFaceMatcher
import com.example.consent_cam.recognition.FaceEnrollmentController
import com.example.consent_cam.recognition.FaceEmbeddingRuntimeState
import com.example.consent_cam.recognition.FaceRecognitionController
import com.example.consent_cam.recognition.LiteRtFaceEmbedder
import com.example.consent_cam.recognition.OwnerProfileStore
import com.example.consent_cam.recognition.RecognitionUiState
import com.example.consent_cam.recognition.TrustedCameraStore
import com.example.consent_cam.recognition.TrustedCameraState
import com.example.consent_cam.recognition.DevicePairingCode
import com.example.consent_cam.recognition.SessionProfile
import com.consentcam.privacy.api.FaceConsentResolution
import com.consentcam.privacy.api.PrivacyMode as CorePrivacyMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

private val CameraBlack = Color(0xFF050505)
private val CameraPanel = Color(0xFF151515)
private val CameraPanelLight = Color(0xFF232323)
private val IqooYellow = Color(0xFFFFD600)
private val MutedText = Color(0xFFB9B9B9)
private val SafeGreen = Color(0xFF75E29B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ConsentCamTheme(darkTheme = true, dynamicColor = false) { ConsentCamApp() } }
    }
}

private enum class AppScreen { DASHBOARD, CAMERA, SETTINGS, REVIEW, ENROLLMENT }
private enum class CaptureMode { PHOTO, VIDEO }
private enum class PrivacyMode(val title: String, val description: String) {
    PROTECTION("Protection", "Protect nearby people when a consent signal is active."),
    ENHANCED("Enhanced protection", "Use a temporary encrypted face reference for precise protection."),
}

@Composable
private fun ConsentCamApp() {
    val context = LocalContext.current
    val preferences = remember {
        context.getSharedPreferences("consentcam_demo_settings", Context.MODE_PRIVATE)
    }
    val bleController = remember { BleConsentRuntime.controller(context.applicationContext) }
    val bleState by bleController.state.collectAsStateWithLifecycle()
    val receivedProfiles by bleController.preciseProfiles.collectAsStateWithLifecycle()
    val faceEmbedder = remember { LiteRtFaceEmbedder(context.applicationContext) }
    val embeddingRuntime by faceEmbedder.runtimeState.collectAsStateWithLifecycle()
    val localAiController = remember { LocalAiController(context.applicationContext) }
    val localAiState by localAiController.state.collectAsStateWithLifecycle()
    val thermalMonitor = remember { DeviceThermalMonitor(context.applicationContext) }
    val thermalLevel by thermalMonitor.level.collectAsStateWithLifecycle()
    val enrollmentController = remember {
        FaceEnrollmentController(faceEmbedder, OwnerProfileStore(context.applicationContext))
    }
    val trustedCameraStore = remember { TrustedCameraStore(context.applicationContext) }
    val trustedCameraState by trustedCameraStore.state.collectAsStateWithLifecycle()
    val appScope = rememberCoroutineScope()
    val enrollmentState by enrollmentController.state.collectAsStateWithLifecycle()
    val recognitionController = remember {
        FaceRecognitionController(ExactFaceMatcher(faceEmbedder))
    }
    val recognitionState by recognitionController.state.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf(AppScreen.CAMERA) }
    var privacyMode by remember {
        mutableStateOf(runCatching {
            PrivacyMode.valueOf(preferences.getString("privacy_mode", null) ?: "")
        }.getOrDefault(PrivacyMode.ENHANCED))
    }
    var privacyEnabled by remember { mutableStateOf(preferences.getBoolean("privacy_enabled", true)) }
    var lastMedia by remember { mutableStateOf<Uri?>(null) }
    var lastMediaType by remember { mutableStateOf("Photo") }
    var cameraPermissionGranted by remember { mutableStateOf(hasCameraPermission(context)) }
    var blePermissionsGranted by remember { mutableStateOf(BleConsentController.hasRequiredPermissions(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        cameraPermissionGranted = result[Manifest.permission.CAMERA] == true || hasCameraPermission(context)
        blePermissionsGranted = BleConsentController.hasRequiredPermissions(context)
    }
    val modelImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(localAiController::importModel)
    }
    val requestPermissions = {
        permissionLauncher.launch(requiredAppPermissions())
    }
    val setConsent: (AppearanceConsent) -> Unit = { consent ->
        preferences.edit().putString("appearance_consent", consent.name).apply()
        bleController.setConsent(consent)
    }
    val setPrivacyMode: (PrivacyMode) -> Unit = { mode ->
        privacyMode = mode
        preferences.edit().putString("privacy_mode", mode.name).apply()
    }
    val setPrivacyZone: (PrivacyZonePreset) -> Unit = { preset ->
        preferences.edit().putString("privacy_zone", preset.name).apply()
        bleController.setPrivacyZonePreset(preset)
    }
    LaunchedEffect(Unit) {
        trustedCameraStore.load()
        val savedConsent = runCatching {
            AppearanceConsent.valueOf(preferences.getString("appearance_consent", null) ?: "")
        }.getOrDefault(AppearanceConsent.OFF)
        val savedZone = runCatching {
            PrivacyZonePreset.valueOf(preferences.getString("privacy_zone", null) ?: "")
        }.getOrDefault(PrivacyZonePreset.FAR)
        bleController.setPrivacyZonePreset(savedZone)
        bleController.setConsent(savedConsent)
        if (!cameraPermissionGranted || !blePermissionsGranted) requestPermissions()
    }
    LaunchedEffect(thermalLevel) { localAiController.updateThermalLevel(thermalLevel) }
    DisposableEffect(blePermissionsGranted) {
        if (blePermissionsGranted) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BleConsentService::class.java),
            )
        }
        onDispose { }
    }
    LaunchedEffect(enrollmentState.hasProfile, enrollmentState.profileRevision, trustedCameraState.revision, trustedCameraState.identity) {
        val profile = if (enrollmentState.hasProfile) enrollmentController.loadProfile() else null
        try {
            val transportProfile = profile?.withTrustMetadata(
                identity = trustedCameraState.identity,
                name = trustedCameraState.displayName,
                trustedCameras = trustedCameraState.cameras.map { it.identity }.toSet(),
            )
            try { bleController.setOwnerProfile(transportProfile) } finally { transportProfile?.close() }
        } finally {
            profile?.close()
        }
    }
    LaunchedEffect(receivedProfiles.map { it.sessionId to it.consent }) {
        recognitionController.setProfiles(receivedProfiles.map { it.copyForConsumer() })
    }
    DisposableEffect(Unit) {
        onDispose {
            recognitionController.close()
            enrollmentController.close()
            faceEmbedder.close()
            localAiController.close()
            thermalMonitor.close()
        }
    }

    Surface(color = CameraBlack, modifier = Modifier.fillMaxSize()) {
        when (screen) {
            AppScreen.DASHBOARD -> DashboardScreen(
                privacyMode = privacyMode,
                cameraReady = cameraPermissionGranted,
                bleState = bleState,
                onSetConsent = setConsent,
                onStartBle = bleController::start,
                onOpenCamera = { screen = AppScreen.CAMERA },
                onOpenSettings = { screen = AppScreen.SETTINGS },
                onRequestPermissions = requestPermissions,
            )
            AppScreen.CAMERA -> CameraScreen(
                privacyMode = privacyMode,
                privacyEnabled = privacyEnabled,
                bleState = bleState,
                receivedProfiles = receivedProfiles,
                recorderIdentity = trustedCameraState.identity,
                recognitionState = recognitionState,
                recognitionController = recognitionController,
                hasPermission = cameraPermissionGranted,
                latestMedia = lastMedia,
                onOpenSettings = { screen = AppScreen.SETTINGS },
                onCaptureSaved = { uri, type -> lastMedia = uri; lastMediaType = type },
                onOpenReview = { if (lastMedia != null) screen = AppScreen.REVIEW },
                onRequestPermissions = requestPermissions,
                localAiController = localAiController,
                localAiState = localAiState,
                thermalLevel = thermalLevel,
                onCameraSessionActive = bleController::setCameraSessionActive,
            )
            AppScreen.SETTINGS -> SettingsScreen(
                privacyMode = privacyMode,
                privacyEnabled = privacyEnabled,
                enrollmentState = enrollmentState,
                embeddingRuntime = embeddingRuntime,
                localAiState = localAiState,
                localModelDirectory = localAiController.modelDirectory().absolutePath,
                bleState = bleState,
                trustedCameraState = trustedCameraState,
                nearbyProfiles = receivedProfiles,
                onSetPrivacyMode = setPrivacyMode,
                onPrivacyEnabledChanged = {
                    privacyEnabled = it
                    preferences.edit().putBoolean("privacy_enabled", it).apply()
                },
                onSetConsent = setConsent,
                onEnroll = { screen = AppScreen.ENROLLMENT },
                onDeleteProfile = enrollmentController::deleteProfile,
                onAvatarEnabledChanged = enrollmentController::setAvatarEnabled,
                onAvatarPresetSelected = enrollmentController::selectAvatarPreset,
                onSaveDisplayName = { name -> appScope.launch { trustedCameraStore.setDisplayName(name) } },
                onTrustCamera = { profile -> appScope.launch { trustedCameraStore.trust(profile.deviceIdentity, profile.displayName) } },
                onRevokeCamera = { identity -> appScope.launch { trustedCameraStore.revoke(identity) } },
                onSetPrivacyZone = setPrivacyZone,
                onImportLocalModel = { modelImportLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                onInitializeLocalAi = localAiController::initialize,
                onRunLocalAudit = {
                    localAiController.runPrivacyAudit(
                        PrivacyAuditSnapshot(
                            trackedFaces = 0,
                            nearbyConsentSessions = bleState.nearbySessionCount,
                            nearbyProtectSessions = bleState.nearbyProtectCount,
                            protectedRegions = 0,
                            privacyMode = privacyMode.name,
                        ),
                    )
                },
                onBack = { screen = AppScreen.CAMERA },
            )
            AppScreen.REVIEW -> ReviewScreen(lastMedia, lastMediaType, { screen = AppScreen.CAMERA })
            AppScreen.ENROLLMENT -> EnrollmentScreen(
                enrollmentController = enrollmentController,
                enrollmentState = enrollmentState,
                hasPermission = cameraPermissionGranted,
                onBack = { screen = AppScreen.SETTINGS },
                onRequestPermissions = requestPermissions,
            )
        }
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun openInGallery(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, "No gallery app is available", Toast.LENGTH_SHORT).show()
    }
}

private fun requiredAppPermissions(): Array<String> = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    addAll(BleConsentController.requiredPermissions())
}.toTypedArray()

@Composable
private fun DashboardScreen(
    privacyMode: PrivacyMode,
    cameraReady: Boolean,
    bleState: BleConsentUiState,
    onSetConsent: (AppearanceConsent) -> Unit,
    onStartBle: () -> Unit,
    onOpenCamera: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 34.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ConsentCam", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("Privacy Lens", color = IqooYellow, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            CircleButton("⚙", "Open settings", onOpenSettings)
        }
        Spacer(Modifier.height(42.dp))
        Text("Ready to capture responsibly", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text("Your camera stays local. Privacy signals decide when protection starts.", color = MutedText, fontSize = 15.sp, lineHeight = 22.sp)
        Spacer(Modifier.height(28.dp))
        Card(colors = CardDefaults.cardColors(containerColor = CameraPanel), shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(if (cameraReady) SafeGreen else IqooYellow))
                    Spacer(Modifier.width(10.dp))
                    Text(if (cameraReady) "Camera ready" else "Camera permission needed", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(22.dp))
                Text("ACTIVE PRIVACY LEVEL", color = MutedText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(privacyMode.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(privacyMode.description, color = MutedText, fontSize = 14.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { StatusPill("On-device", SafeGreen); StatusPill("No cloud", IqooYellow) }
            }
        }
        Spacer(Modifier.height(16.dp))
        BleConsentCard(bleState, onSetConsent, onStartBle, onRequestPermissions)
        Spacer(Modifier.weight(1f))
        Button(onClick = onOpenCamera, shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = IqooYellow, contentColor = Color.Black), modifier = Modifier.fillMaxWidth()) {
            Text("Open Camera", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 7.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text("iQOO 15 optimized  •  Local protection", color = MutedText, fontSize = 12.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
}

@Composable
private fun CameraScreen(
    privacyMode: PrivacyMode,
    privacyEnabled: Boolean,
    bleState: BleConsentUiState,
    receivedProfiles: List<com.example.consent_cam.recognition.SessionProfile>,
    recorderIdentity: String,
    recognitionState: RecognitionUiState,
    recognitionController: FaceRecognitionController,
    hasPermission: Boolean,
    latestMedia: Uri?,
    onOpenSettings: () -> Unit,
    onCaptureSaved: (Uri, String) -> Unit,
    onOpenReview: () -> Unit,
    onRequestPermissions: () -> Unit,
    localAiController: LocalAiController,
    localAiState: LocalAiUiState,
    thermalLevel: ThermalLevel,
    onCameraSessionActive: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { ConsentCameraController(context.applicationContext) }
    val privacyCoordinator = remember { ProximityPrivacyCoordinator() }
    val faceRegionSmoother = remember { FaceRegionSmoother() }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var cameraCapabilities by remember { mutableStateOf(CameraCapabilities()) }
    var selectedZoom by remember { mutableStateOf(1f) }
    var flashEnabled by remember { mutableStateOf(false) }
    var captureMode by remember { mutableStateOf(CaptureMode.PHOTO) }
    var isRecording by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }
    var shutterFlash by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableIntStateOf(0) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var faceDetection by remember { mutableStateOf(FaceDetectionState()) }
    var protectedFrame by remember { mutableStateOf(ProtectedFrameState()) }
    // A trust grant is never established from uncertainty. Once a face has been confidently
    // matched to a trusted participant, retain that visibility decision for its current ML Kit
    // tracking ID. This avoids blur flicker from a single poor crop; losing the track, revoking
    // trust, or losing the trusted session clears the lease immediately.
    var trustedFaceLeases by remember { mutableStateOf<Map<Int, UInt>>(emptyMap()) }
    // A nearby consent signal alone must not block ordinary photography. Protected output is
    // required only while a confirmed PROTECT face has an active rendered region.
    fun requiresProtectedOutput(): Boolean = privacyEnabled && protectedFrame.regions.any { it.enabled }

    var recordingWasActive by remember { mutableStateOf(false) }

    LaunchedEffect(shutterFlash) {
        if (shutterFlash) {
            delay(75)
            shutterFlash = false
        }
    }
    LaunchedEffect(isRecording) {
        recordingSeconds = 0
        if (!isRecording && recordingWasActive) {
            recordingWasActive = false
            localAiController.runPrivacyAudit(
                PrivacyAuditSnapshot(
                    trackedFaces = faceDetection.faces.size,
                    nearbyConsentSessions = bleState.nearbySessionCount,
                    nearbyProtectSessions = bleState.nearbyProtectCount,
                    protectedRegions = protectedFrame.regions.count { it.enabled },
                    privacyMode = privacyMode.name,
                ),
                trigger = "Final session summary",
            )
        }
        while (isRecording) {
            recordingWasActive = true
            delay(1_000)
            recordingSeconds += 1
        }
    }
    LaunchedEffect(isRecording, thermalLevel, localAiState.status) {
        if (!isRecording || localAiState.status != LocalAiStatus.READY) return@LaunchedEffect
        val intervalMs = ThermalGovernor.auditIntervalMs(thermalLevel) ?: return@LaunchedEffect
        while (isRecording) {
            delay(intervalMs)
            localAiController.runPrivacyAudit(
                PrivacyAuditSnapshot(
                    trackedFaces = faceDetection.faces.size,
                    nearbyConsentSessions = bleState.nearbySessionCount,
                    nearbyProtectSessions = bleState.nearbyProtectCount,
                    protectedRegions = protectedFrame.regions.count { it.enabled },
                    privacyMode = privacyMode.name,
                ),
                trigger = "Periodic recording audit",
            )
        }
    }
    val enhancedProfileAvailable = privacyMode == PrivacyMode.ENHANCED && bleState.preciseProfileAvailable
    val privacySignalActive = if (privacyMode == PrivacyMode.ENHANCED) {
        enhancedProfileAvailable
    } else {
        bleState.protectionActive
    }
    LaunchedEffect(
        faceDetection,
        bleState.protectionActive,
        bleState.preciseMatchingActive,
        bleState.preciseProfileAvailable,
        privacyEnabled,
        privacyMode,
        recognitionState.association,
        recognitionState.faceResolutions,
        recognitionState.protectedSessionByTrackingId,
        receivedProfiles,
        recorderIdentity,
        trustedFaceLeases,
    ) {
        if (privacyMode == PrivacyMode.ENHANCED) {
            recognitionController.updateFaces(
                faces = faceDetection.faces,
                protectionActive = privacyEnabled && enhancedProfileAvailable,
                requestCrops = controller::requestFaceCrops,
            )
        }
        // Trust never creates a face association. It only changes the result for an already
        // confirmed MATCHED_PROTECT face in Enhanced mode; unknown/ambiguous faces remain visible.
        val profilesBySession = receivedProfiles.associateBy { it.sessionId }
        val newlyTrustedLeases = recognitionState.protectedSessionByTrackingId.mapNotNull { (trackingId, sessionId) ->
            profilesBySession[sessionId]
                ?.takeIf { recorderIdentity.isNotBlank() && recorderIdentity in it.trustedCameraIdentities }
                ?.let { trackingId to sessionId }
        }.toMap()
        val visibleIds = faceDetection.faces.mapTo(mutableSetOf()) { it.trackingId }
        val activeTrustedSessionIds = profilesBySession.values
            .filter { recorderIdentity.isNotBlank() && recorderIdentity in it.trustedCameraIdentities }
            .mapTo(mutableSetOf()) { it.sessionId }
        val retainedLeases = trustedFaceLeases.filter { (trackingId, sessionId) ->
            trackingId in visibleIds && sessionId in activeTrustedSessionIds
        }
        val nextTrustedLeases = retainedLeases + newlyTrustedLeases
        if (nextTrustedLeases != trustedFaceLeases) trustedFaceLeases = nextTrustedLeases
        val trustedTrackingIds = nextTrustedLeases.keys
        val effectiveResolutions = recognitionState.faceResolutions.mapValues { (trackingId, resolution) ->
            if (trackingId in trustedTrackingIds) {
                FaceConsentResolution.MATCHED_ALLOW
            } else resolution
        }
        // The coordinator gives a resolved association precedence over per-face results. Remove
        // only trusted, already-confirmed faces from that association so their MATCHED_ALLOW
        // decision takes effect; any other confirmed PROTECT face remains protected.
        val effectiveAssociation = if (
            recognitionState.association.status == com.consentcam.privacy.api.PreciseAssociationStatus.RESOLVED
        ) {
            val stillProtected = recognitionState.association.protectedTrackingIds - trustedTrackingIds
            if (stillProtected.isEmpty()) {
                com.consentcam.privacy.api.PreciseAssociationState.resolving()
            } else {
                com.consentcam.privacy.api.PreciseAssociationState.resolved(stillProtected)
            }
        } else recognitionState.association
        val regions = privacyCoordinator.regions(
            faces = faceDetection.faces,
            proximityProtectionActive = privacySignalActive,
            mode = if (privacyMode == PrivacyMode.ENHANCED) CorePrivacyMode.PRECISE else CorePrivacyMode.PROXIMITY,
            preciseAssociation = if (enhancedProfileAvailable) {
                effectiveAssociation
            } else {
                com.consentcam.privacy.api.PreciseAssociationState()
            },
            faceResolutions = effectiveResolutions,
            privacyEnabled = privacyEnabled,
        )
        // A selective session must not retain a stale mask while its face association is
        // uncertain. A previously verified trusted track is explicitly represented as ALLOW;
        // PROXIMITY mode still supplies enabled regions for broad protection.
        val hasConfirmedProtectedRegion = regions.any { it.enabled }
        val avatarStylesBySession = receivedProfiles.associate { it.sessionId to it }
        val avatarStylesByTrackingId = recognitionState.protectedSessionByTrackingId.mapNotNull { (trackingId, sessionId) ->
            val profile = avatarStylesBySession[sessionId]
            profile?.avatarStyle?.takeIf { profile.presentation == com.example.consent_cam.recognition.ProtectionPresentation.AVATAR }
                ?.let { trackingId to it }
        }.toMap()
        val avatarPresetsByTrackingId = recognitionState.protectedSessionByTrackingId.mapNotNull { (trackingId, sessionId) ->
            avatarStylesBySession[sessionId]?.avatarPreset?.let { trackingId to it }
        }.toMap()
        protectedFrame = ProtectedFrameState(
            regions = faceRegionSmoother.update(
                regions = regions,
                frameTimestampNs = faceDetection.timestampNs,
                protectionActive = privacyEnabled && privacySignalActive && hasConfirmedProtectedRegion,
            ),
            sourceWidth = faceDetection.sourceWidth,
            sourceHeight = faceDetection.sourceHeight,
            detectionState = faceDetection,
            avatarStylesByTrackingId = avatarStylesByTrackingId,
            avatarPresetsByTrackingId = avatarPresetsByTrackingId,
        )
        controller.updateProtection(
            protectedFrame,
            privacyEnabled && privacySignalActive && hasConfirmedProtectedRegion,
        )
    }

    DisposableEffect(hasPermission, previewView, lensFacing) {
        if (hasPermission && previewView != null) {
            controller.bind(
                lifecycleOwner = lifecycleOwner,
                previewView = previewView!!,
                lensFacing = lensFacing,
                onFacesDetected = { faceDetection = it },
                onError = { cameraError = it },
                onCameraReady = { capabilities ->
                    cameraCapabilities = capabilities
                    selectedZoom = 1f
                    flashEnabled = false
                },
            )
        }
        onDispose {
            faceDetection = FaceDetectionState()
            protectedFrame = ProtectedFrameState()
            faceRegionSmoother.clear()
            controller.unbind()
        }
    }
    DisposableEffect(hasPermission) {
        if (hasPermission) onCameraSessionActive(true)
        onDispose { onCameraSessionActive(false) }
    }
    DisposableEffect(controller) {
        onDispose { controller.close() }
    }
    Box(Modifier.fillMaxSize().background(CameraBlack)) {
        if (hasPermission) AndroidView(
            factory = { PreviewView(it).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE } },
            update = { previewView = it },
            modifier = Modifier.fillMaxSize(),
        ) else PermissionPanel(onRequestPermissions)
        if (hasPermission) {
            FacePixelationOverlay(
                detection = faceDetection,
                protectedFrame = protectedFrame,
                protectionActive = privacyEnabled && privacySignalActive,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 26.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    bleState.ownConsent.label,
                    if (bleState.ownConsent == AppearanceConsent.PROTECT) IqooYellow else SafeGreen,
                )
                Spacer(Modifier.weight(1f))
                CircleButton(if (flashEnabled) "ON" else "ϟ", "Flash") {
                    controller.setFlashEnabled(
                        enabled = !flashEnabled,
                        onComplete = { flashEnabled = it },
                        onError = { cameraError = it },
                    )
                }
                Spacer(Modifier.width(10.dp))
                CircleButton("⚙", "Camera settings", onOpenSettings)
            }
            Spacer(Modifier.height(16.dp)); PrivacyBadge(
                privacyMode,
                privacyEnabled,
                bleState,
                faceDetection.faces.size,
                if (privacyMode == PrivacyMode.ENHANCED) {
                    if (trustedFaceLeases.isNotEmpty() && recognitionState.status.startsWith("Match uncertain")) {
                        "Trusted camera: verified person remains visible"
                    } else recognitionState.status
                } else null,
            )
            if (isRecording) {
                Spacer(Modifier.height(9.dp))
                RecordingBadge(recordingSeconds)
            }
            Spacer(Modifier.weight(1f))
            ZoomRow(selectedZoom, cameraCapabilities) { ratio ->
                controller.setZoomRatio(ratio) { cameraError = it }
                selectedZoom = ratio.coerceIn(
                    cameraCapabilities.minimumZoomRatio,
                    cameraCapabilities.maximumZoomRatio,
                )
            }
            Spacer(Modifier.height(18.dp))
            CaptureModeRow(captureMode) { if (!isRecording) captureMode = it }
            Spacer(Modifier.height(16.dp))
            CameraControls(
                captureMode, isRecording, isCapturing, latestMedia,
                { latestMedia?.let { openInGallery(context, it) } },
                {
                    controller.setFlashEnabled(false, { flashEnabled = false }, { flashEnabled = false })
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }
                },
            ) {
                if (!hasPermission) return@CameraControls
                if (captureMode == CaptureMode.PHOTO) {
                    if (isCapturing) return@CameraControls
                    isCapturing = true
                    shutterFlash = true
                    controller.takePhoto(requiresProtectedOutput(), { uri ->
                        isCapturing = false
                        Toast.makeText(context, "Photo saved to Gallery", Toast.LENGTH_SHORT).show()
                        onCaptureSaved(uri, "Photo")
                    }, {
                        isCapturing = false
                        cameraError = it
                    })
                } else if (isRecording) {
                    controller.stopRecording(); isRecording = false
                } else {
                    val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    controller.startRecording(hasAudio, requiresProtectedOutput(), {
                        isRecording = true
                    }, { uri ->
                        isRecording = false
                        Toast.makeText(context, "Video saved to Gallery", Toast.LENGTH_SHORT).show()
                        onCaptureSaved(uri, "Video")
                    }, { isRecording = false; cameraError = it })
                }
            }
        }
        if (shutterFlash) {
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.78f)))
        }
        if (cameraError != null) AlertDialog(
            onDismissRequest = { cameraError = null }, containerColor = CameraPanel, titleContentColor = Color.White, textContentColor = MutedText,
            title = { Text("Camera notice") }, text = { Text(cameraError!!) }, confirmButton = { OutlinedButton(onClick = { cameraError = null }) { Text("Got it", color = IqooYellow) } },
        )
    }
}

@Composable
private fun SettingsScreen(
    privacyMode: PrivacyMode,
    privacyEnabled: Boolean,
    enrollmentState: EnrollmentUiState,
    embeddingRuntime: FaceEmbeddingRuntimeState,
    localAiState: LocalAiUiState,
    localModelDirectory: String,
    bleState: BleConsentUiState,
    trustedCameraState: TrustedCameraState,
    nearbyProfiles: List<SessionProfile>,
    onSetPrivacyMode: (PrivacyMode) -> Unit,
    onPrivacyEnabledChanged: (Boolean) -> Unit,
    onSetConsent: (AppearanceConsent) -> Unit,
    onEnroll: () -> Unit,
    onDeleteProfile: () -> Unit,
    onAvatarEnabledChanged: (Boolean) -> Unit,
    onAvatarPresetSelected: (AvatarPreset) -> Unit,
    onSaveDisplayName: (String) -> Unit,
    onTrustCamera: (SessionProfile) -> Unit,
    onRevokeCamera: (String) -> Unit,
    onSetPrivacyZone: (PrivacyZonePreset) -> Unit,
    onImportLocalModel: () -> Unit,
    onInitializeLocalAi: () -> Unit,
    onRunLocalAudit: () -> Unit,
    onBack: () -> Unit,
) {
    var displayNameDraft by remember(trustedCameraState.displayName) { mutableStateOf(trustedCameraState.displayName) }
    var pendingTrust by remember { mutableStateOf<Pair<String, String>?>(null) }
    var enteredPairingCode by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 34.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { CircleButton("‹", "Back", onBack); Spacer(Modifier.width(16.dp)); Text("Settings", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(38.dp)); Text("MY CONSENT SIGNAL", color = MutedText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = CameraPanel), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppearanceConsent.entries.forEach { consent ->
                        Button(
                            onClick = { onSetConsent(consent) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (bleState.ownConsent == consent) IqooYellow else CameraPanelLight,
                                contentColor = if (bleState.ownConsent == consent) Color.Black else Color.White,
                            ),
                        ) { Text(consent.label, maxLines = 1, softWrap = false, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    when (bleState.ownConsent) {
                        AppearanceConsent.OFF -> "OFF: broadcast nothing. Nearby consent signals are still received."
                        AppearanceConsent.ALLOW -> "ALLOW: tell nearby ConsentCam cameras your matched face may stay visible."
                        AppearanceConsent.PROTECT -> "PROTECT: send an encrypted session-only face profile so only your matched face is blurred."
                    },
                    color = MutedText,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        }
        Spacer(Modifier.height(22.dp)); Text("PRIVACY PROTECTION", color = MutedText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = CameraPanel), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Apply nearby consent automatically", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(
                        if (privacyEnabled) "Camera masks faces that request PROTECT"
                        else "Received signals are ignored; your own signal is unchanged",
                        color = MutedText,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
                Switch(checked = privacyEnabled, onCheckedChange = onPrivacyEnabledChanged)
            }
        }
        PrivacyMode.entries.forEach { mode ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable { onSetPrivacyMode(mode) }, shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = if (mode == privacyMode) CameraPanelLight else CameraPanel),
                border = if (mode == privacyMode) androidx.compose.foundation.BorderStroke(1.dp, IqooYellow) else null,
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(20.dp).clip(CircleShape).border(2.dp, if (mode == privacyMode) IqooYellow else MutedText, CircleShape), contentAlignment = Alignment.Center) {
                        if (mode == privacyMode) Box(Modifier.size(10.dp).clip(CircleShape).background(IqooYellow))
                    }
                    Spacer(Modifier.width(14.dp)); Column { Text(mode.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp); Spacer(Modifier.height(4.dp)); Text(mode.description, color = MutedText, fontSize = 13.sp, lineHeight = 18.sp) }
                }
            }
        }
        Spacer(Modifier.height(12.dp)); Text("PRIVACY ZONE", color = MutedText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrivacyZonePreset.entries.forEach { preset ->
                OutlinedButton(
                    onClick = { onSetPrivacyZone(preset) },
                    modifier = Modifier.weight(1f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (bleState.privacyZonePreset == preset) IqooYellow else MutedText,
                    ),
                ) { Text(preset.label, color = if (bleState.privacyZonePreset == preset) IqooYellow else Color.White) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Approximate signal range where another phone's consent applies. Bodies and walls affect RSSI; Room is recommended for the audience demo.",
            color = MutedText,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(18.dp)); Text("PRECISE FACE MATCH", color = MutedText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = CameraPanel), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text(enrollmentState.status, color = if (enrollmentState.hasProfile) SafeGreen else Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp)); Text(bleState.preciseStatus, color = MutedText, fontSize = 13.sp, lineHeight = 18.sp)
                if (bleState.preciseProfileCount > 0) {
                    Text("Temporary profiles: ${bleState.preciseProfileCount}", color = SafeGreen, fontSize = 13.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        append("FaceNet backend: ${embeddingRuntime.backend.label}")
                        embeddingRuntime.lastInferenceMs?.let { append(" - ${it} ms") }
                    },
                    color = MutedText,
                    fontSize = 13.sp,
                )
                embeddingRuntime.fallbackReason?.let { reason ->
                    Spacer(Modifier.height(4.dp))
                    Text("Accelerator fallback: $reason", color = MutedText, fontSize = 12.sp)
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "Profiles exchange automatically over encrypted BLE. No pairing, QR, Wi-Fi, or internet is used.",
                    color = MutedText,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onEnroll,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = IqooYellow, contentColor = Color.Black),
                ) { Text(if (enrollmentState.hasProfile) "Re-enrol my face" else "Enrol my face") }
                if (enrollmentState.hasProfile) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Avatar replacement", color = Color.White, fontWeight = FontWeight.Bold)
                            Text("Your matched PROTECT face uses a local illustrated avatar. Blur is the fallback.", color = MutedText, fontSize = 12.sp, lineHeight = 16.sp)
                        }
                        Switch(checked = enrollmentState.avatarEnabled, onCheckedChange = onAvatarEnabledChanged)
                    }
                    if (enrollmentState.avatarEnabled) {
                        Spacer(Modifier.height(10.dp))
                        Text("Choose your character", color = MutedText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        AvatarPreset.entries.chunked(2).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { preset ->
                                    val selected = preset == enrollmentState.avatarPreset
                                    OutlinedButton(
                                        onClick = { onAvatarPresetSelected(preset) },
                                        modifier = Modifier.weight(1f),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) IqooYellow else MutedText),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 9.dp),
                                    ) {
                                        Text(preset.label, color = if (selected) IqooYellow else Color.White, fontSize = 11.sp, maxLines = 2, textAlign = TextAlign.Center)
                                    }
                                }
                            }
                            Spacer(Modifier.height(7.dp))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onDeleteProfile, modifier = Modifier.fillMaxWidth()) { Text("Delete profile", color = Color.White) }
                }
            }
        }
        Spacer(Modifier.height(22.dp)); Text("TRUSTED CAMERAS", color = MutedText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = CameraPanel), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("Let selected cameras capture you", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text("A trusted camera sees your already-confirmed PROTECT face normally in Enhanced protection. Each phone has its own pairing code; enter the recorder's code to grant access. You can revoke it immediately.", color = MutedText, fontSize = 12.sp, lineHeight = 17.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = displayNameDraft,
                    onValueChange = { displayNameDraft = it.take(48) },
                    label = { Text("My shared display name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { onSaveDisplayName(displayNameDraft) }, modifier = Modifier.fillMaxWidth()) { Text("Save display name", color = IqooYellow) }
                Spacer(Modifier.height(10.dp))
                Text("This phone's pairing code: ${DevicePairingCode.forIdentity(trustedCameraState.identity)}", color = SafeGreen, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                val nearbyCameras = nearbyProfiles.filter { it.deviceIdentity.isNotBlank() && it.deviceIdentity != trustedCameraState.identity }
                Spacer(Modifier.height(14.dp))
                Text("NEARBY PRESCIENCE CAMERAS", color = MutedText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                if (nearbyCameras.isEmpty()) {
                    Spacer(Modifier.height(5.dp)); Text("No nearby enrolled Prescience camera yet. The other phone must have an enrolled profile and an active ALLOW or PROTECT signal.", color = MutedText, fontSize = 12.sp, lineHeight = 17.sp)
                } else nearbyCameras.forEach { profile ->
                    val trusted = trustedCameraState.cameras.any { it.identity == profile.deviceIdentity }
                    val label = profile.displayName.ifBlank { "Nearby Prescience" }
                    val code = DevicePairingCode.forIdentity(profile.deviceIdentity)
                    Spacer(Modifier.height(8.dp))
                    Text(label, color = Color.White, fontWeight = FontWeight.Bold)
                    Text("Recorder pairing code: $code", color = MutedText, fontSize = 12.sp)
                    if (!trusted) {
                        OutlinedButton(onClick = {
                            pendingTrust = profile.deviceIdentity to label
                            enteredPairingCode = ""
                        }, modifier = Modifier.fillMaxWidth()) { Text("Connect this camera", color = IqooYellow) }
                    } else {
                        OutlinedButton(onClick = { onRevokeCamera(profile.deviceIdentity) }, modifier = Modifier.fillMaxWidth()) { Text("Revoke $label", color = Color.White) }
                    }
                }
                if (trustedCameraState.cameras.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp)); Text("TRUSTED UNTIL REVOKED", color = MutedText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    trustedCameraState.cameras.filter { trusted -> nearbyCameras.none { it.deviceIdentity == trusted.identity } }.forEach { trusted ->
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(trusted.displayName, color = Color.White, modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = { onRevokeCamera(trusted.identity) }) { Text("Revoke", color = Color.White, fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
        pendingTrust?.let { (identity, label) ->
            val expectedCode = DevicePairingCode.forIdentity(identity)
            AlertDialog(
                onDismissRequest = { pendingTrust = null },
                containerColor = CameraPanel,
                titleContentColor = Color.White,
                textContentColor = MutedText,
                title = { Text("Connect $label") },
                text = {
                    Column {
                        Text("Enter the 6-digit code displayed on $label. This confirms you selected the nearby recorder you intend to trust.")
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = enteredPairingCode,
                            onValueChange = { enteredPairingCode = it.filter(Char::isDigit).take(6) },
                            label = { Text("Recorder pairing code") },
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            nearbyProfiles.firstOrNull { it.deviceIdentity == identity }?.let(onTrustCamera)
                            pendingTrust = null
                        },
                        enabled = enteredPairingCode == expectedCode,
                        colors = ButtonDefaults.buttonColors(containerColor = IqooYellow, contentColor = Color.Black),
                    ) { Text("Connect") }
                },
                dismissButton = { OutlinedButton(onClick = { pendingTrust = null }) { Text("Cancel", color = Color.White) } },
            )
        }
        Spacer(Modifier.height(22.dp)); Text("LOCAL ON-DEVICE AI", color = MutedText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = CameraPanel), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text(localAiState.statusText, color = if (localAiState.status == LocalAiStatus.READY) SafeGreen else Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Model: ${localAiState.modelName ?: "E4B preferred / E2B fallback"}",
                    color = MutedText,
                    fontSize = 13.sp,
                )
                Text(
                    "Backend: ${localAiState.backend?.label ?: "not initialized"}  •  Gemma NPU package not provisioned",
                    color = MutedText,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                Text(
                    "Inferences: ${localAiState.inferenceCount}  •  Cloud calls: ${localAiState.cloudCalls}",
                    color = MutedText,
                    fontSize = 13.sp,
                )
                Text(
                    "Thermal: ${localAiState.thermalLevel.label}  •  ${localAiState.fallbackState}",
                    color = MutedText,
                    fontSize = 13.sp,
                )
                localAiState.lastTrigger?.let { trigger ->
                    Text("Last trigger: $trigger", color = MutedText, fontSize = 13.sp)
                }
                localAiState.lastLatencyMs?.let { latency ->
                    Text(
                        buildString {
                            append("Last latency: ${latency} ms")
                            localAiState.decodeTokensPerSecond?.let { speed ->
                                append("  •  ${"%.1f".format(java.util.Locale.US, speed)} token/s")
                            }
                        },
                        color = MutedText,
                        fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("Model folder: $localModelDirectory", color = MutedText, fontSize = 11.sp, lineHeight = 15.sp)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onImportLocalModel,
                        modifier = Modifier.weight(1f),
                        enabled = localAiState.status != LocalAiStatus.LOADING && localAiState.status != LocalAiStatus.GENERATING,
                    ) { Text("Import model", color = IqooYellow) }
                    Button(
                        onClick = onInitializeLocalAi,
                        modifier = Modifier.weight(1f),
                        enabled = localAiState.status != LocalAiStatus.LOADING && localAiState.status != LocalAiStatus.GENERATING,
                        colors = ButtonDefaults.buttonColors(containerColor = IqooYellow, contentColor = Color.Black),
                    ) { Text("Initialize") }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onRunLocalAudit,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = localAiState.status == LocalAiStatus.READY,
                ) { Text("Run local audit", color = IqooYellow) }
                localAiState.lastResponse?.let { response ->
                    Spacer(Modifier.height(12.dp))
                    Text(response, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
        }
        Spacer(Modifier.height(22.dp)); Text("PRIVACY PROMISE", color = MutedText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = CameraPanel), shape = RoundedCornerShape(20.dp)) { Text("ConsentCam keeps camera processing on this device. Session-only consent data is deleted when protection ends.", color = Color.White, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(18.dp)) }
    }
}

@Composable
private fun EnrollmentScreen(
    enrollmentController: FaceEnrollmentController,
    enrollmentState: EnrollmentUiState,
    hasPermission: Boolean,
    onBack: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraController = remember { ConsentCameraController(context.applicationContext) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var detectedFaces by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { enrollmentController.reset() }
    DisposableEffect(hasPermission, previewView) {
        if (hasPermission && previewView != null) {
            cameraController.bind(
                lifecycleOwner = lifecycleOwner,
                previewView = requireNotNull(previewView),
                lensFacing = CameraSelector.LENS_FACING_FRONT,
                onFacesDetected = { detectedFaces = it.faces.size },
                onError = { error = it },
            )
        }
        onDispose { cameraController.unbind() }
    }
    DisposableEffect(cameraController) { onDispose { cameraController.close() } }

    Box(Modifier.fillMaxSize().background(CameraBlack)) {
        if (hasPermission) {
            AndroidView(
                factory = { PreviewView(it).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } },
                update = { previewView = it },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            PermissionPanel(onRequestPermissions)
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 34.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleButton("‹", "Back", onBack)
                Spacer(Modifier.width(14.dp))
                Text("Enrol protected face", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xE6151515)), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(enrollmentState.status, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text("${enrollmentState.acceptedSamples}/${enrollmentState.requiredSamples} secure samples • $detectedFaces face(s)", color = MutedText, fontSize = 13.sp)
                    Spacer(Modifier.height(14.dp))
                    Button(
                        enabled = hasPermission && !enrollmentState.processing && detectedFaces == 1,
                        onClick = {
                            val accepted = cameraController.requestFaceCrops(enrollmentController::submit)
                            if (!accepted) error = "Wait for the previous sample to finish"
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = IqooYellow, contentColor = Color.Black),
                    ) { Text(if (enrollmentState.acceptedSamples == 0) "Capture first sample" else "Capture next sample", fontWeight = FontWeight.Bold) }
                    if (enrollmentState.hasProfile && enrollmentState.status == "Protected-face profile ready") {
                        Spacer(Modifier.height(10.dp)); OutlinedButton(onClick = onBack) { Text("Done", color = IqooYellow) }
                    }
                }
            }
        }
        if (error != null) AlertDialog(
            onDismissRequest = { error = null },
            containerColor = CameraPanel,
            title = { Text("Enrollment notice", color = Color.White) },
            text = { Text(requireNotNull(error), color = MutedText) },
            confirmButton = { OutlinedButton(onClick = { error = null }) { Text("Got it", color = IqooYellow) } },
        )
    }
}

@Composable
private fun PairingQrScreen(
    qrPayload: String?,
    preciseStatus: String,
    onBack: () -> Unit,
) {
    val qrBitmap = remember(qrPayload) { qrPayload?.let(QrCodeRenderer::render) }
    DisposableEffect(qrBitmap) { onDispose { qrBitmap?.recycle() } }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CircleButton("‹", "Back", onBack)
            Spacer(Modifier.width(16.dp)); Text("Pair protected face", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(42.dp))
        if (qrBitmap != null) {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "ConsentCam protected profile pairing QR",
                    modifier = Modifier.padding(16.dp).fillMaxWidth().aspectRatio(1f),
                )
            }
            Spacer(Modifier.height(22.dp)); Text("Scan this once from the recorder phone. Your photo is never included in this code.", color = MutedText, fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Center)
        } else {
            Text("Create an enrolled face profile, select PROTECT, and make sure BLE is running.", color = Color.White, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(18.dp)); Text(preciseStatus, color = IqooYellow, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PairingScannerScreen(
    hasPermission: Boolean,
    onCode: (String) -> Boolean,
    onBack: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanner = remember { QrScannerController(context.applicationContext) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(hasPermission, previewView) {
        if (hasPermission && previewView != null) {
            scanner.bind(lifecycleOwner, requireNotNull(previewView), onCode) { error = it }
        }
        onDispose { scanner.unbind() }
    }
    DisposableEffect(scanner) { onDispose { scanner.close() } }
    Box(Modifier.fillMaxSize().background(CameraBlack)) {
        if (hasPermission) {
            AndroidView(
                factory = { PreviewView(it).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } },
                update = { previewView = it },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            PermissionPanel(onRequestPermissions)
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 34.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleButton("‹", "Back", onBack)
                Spacer(Modifier.width(14.dp)); Text("Scan pairing QR", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xE6151515)), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Text("Point this recorder phone at the QR shown on the PROTECT phone.", color = Color.White, modifier = Modifier.padding(18.dp), textAlign = TextAlign.Center)
            }
        }
        if (error != null) AlertDialog(
            onDismissRequest = { error = null },
            containerColor = CameraPanel,
            title = { Text("Pairing notice", color = Color.White) },
            text = { Text(requireNotNull(error), color = MutedText) },
            confirmButton = { OutlinedButton(onClick = { error = null }) { Text("Got it", color = IqooYellow) } },
        )
    }
}

@Composable
private fun ReviewScreen(uri: Uri?, mediaType: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 34.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { CircleButton("‹", "Back", onBack); Spacer(Modifier.width(16.dp)); Text("Captured $mediaType", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(30.dp))
        Card(colors = CardDefaults.cardColors(containerColor = CameraPanel), shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth().aspectRatio(0.72f)) {
            if (uri == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No media yet", color = Color.White) }
            } else {
                SavedMediaPreview(uri, mediaType, Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.height(22.dp)); StatusPill("Protected output saved", SafeGreen); Spacer(Modifier.height(12.dp)); Text("Saved locally in the ConsentCam album. The same GPU face protection is applied to preview, photos, and video.", color = MutedText, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun SavedMediaPreview(uri: Uri, mediaType: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { context.contentResolver.loadThumbnail(uri, Size(720, 960), null) }.getOrNull()
            } else {
                runCatching { context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
            }
        }
    }
    Box(modifier.background(CameraPanel), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = "Latest saved $mediaType", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text("Loading $mediaType…", color = MutedText)
        }
    }
}

@Composable
private fun PermissionPanel(onRequestPermissions: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Camera permission needed", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(12.dp)); Text("ConsentCam needs the camera to show the privacy viewfinder.", color = MutedText, textAlign = TextAlign.Center); Spacer(Modifier.height(22.dp)); Button(onClick = onRequestPermissions, colors = ButtonDefaults.buttonColors(containerColor = IqooYellow, contentColor = Color.Black)) { Text("Allow camera") }
    }
}

@Composable
private fun PrivacyBadge(
    privacyMode: PrivacyMode,
    privacyEnabled: Boolean,
    bleState: BleConsentUiState,
    detectedFaceCount: Int,
    preciseStatus: String?,
) {
    Row(Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xE6101010)).border(1.dp, Color(0xFF454545), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (privacyEnabled && bleState.protectionActive) IqooYellow else SafeGreen)); Spacer(Modifier.width(9.dp)); Column { Text(if (privacyEnabled) "${privacyMode.title} active • $detectedFaceCount face(s)" else "Automatic protection disabled", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold); Text(if (!privacyEnabled) "BLE consent broadcasting remains active" else preciseStatus ?: if (bleState.protectionActive) "Nearby PROTECT • preview masked" else "${bleState.nearbySessionCount} BLE consent session(s)", color = MutedText, fontSize = 12.sp) }
    }
}

@Composable
private fun BleConsentCard(
    state: BleConsentUiState,
    onSetConsent: (AppearanceConsent) -> Unit,
    onStartBle: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CameraPanel),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("BLE CONSENT DEMO", color = MutedText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(state.status, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppearanceConsent.entries.forEach { consent ->
                    Button(
                        onClick = { onSetConsent(consent) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.ownConsent == consent) IqooYellow else CameraPanelLight,
                            contentColor = if (state.ownConsent == consent) Color.Black else Color.White,
                        ),
                    ) { Text(consent.label, fontWeight = FontWeight.Bold) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Advertising: ${if (state.advertising) "ON" else "OFF"}  •  Scanning: ${if (state.scanning) "ON" else "OFF"}",
                color = MutedText,
                fontSize = 12.sp,
            )
            Text(
                "Tokens accepted: ${state.acceptedTokenCount}  •  Nearby: ${state.nearbySessionCount}  •  Band: ${state.proximityBand}",
                color = if (state.protectionActive) IqooYellow else SafeGreen,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = if (state.permissionsGranted) onStartBle else onRequestPermissions,
            ) {
                Text(
                    if (state.permissionsGranted) "Start / retry BLE"
                    else "Allow BLE permissions",
                )
            }
        }
    }
}

@Composable
private fun RecordingBadge(seconds: Int) {
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color(0xE60D0D0D)).padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFFF63B30)))
        Spacer(Modifier.width(8.dp))
        Text("REC %02d:%02d".format(minutes, remainingSeconds), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ZoomRow(
    selectedZoom: Float,
    capabilities: CameraCapabilities,
    onZoomSelected: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        listOf(0.6f, 1f, 2f).forEach { ratio ->
            val supported = ratio >= capabilities.minimumZoomRatio && ratio <= capabilities.maximumZoomRatio
            val selected = kotlin.math.abs(selectedZoom - ratio) < 0.05f
            Text(
                text = if (ratio == 1f) "1×" else "${ratio}×",
                color = when { selected -> IqooYellow; supported -> Color.White; else -> Color.DarkGray },
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(enabled = supported) { onZoomSelected(ratio) }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun CaptureModeRow(selected: CaptureMode, onModeChanged: (CaptureMode) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CaptureMode.entries.forEach { mode -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 20.dp).clickable { onModeChanged(mode) }) { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }, color = if (mode == selected) IqooYellow else Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp); Spacer(Modifier.height(4.dp)); Box(Modifier.width(if (mode == selected) 22.dp else 0.dp).height(2.dp).background(IqooYellow)) } } }
}

@Composable
private fun CameraControls(captureMode: CaptureMode, isRecording: Boolean, isCapturing: Boolean, latestMedia: Uri?, onGallery: () -> Unit, onSwitchCamera: () -> Unit, onShutter: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(if (latestMedia != null) CameraPanelLight else Color(0xFF474747)).clickable(enabled = latestMedia != null) { onGallery() }, contentAlignment = Alignment.Center) {
            if (latestMedia == null) Text("□", color = Color.White, fontSize = 23.sp) else SavedMediaPreview(latestMedia, "media", Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)))
        }
        Box(Modifier.size(82.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).padding(6.dp).clickable(enabled = !isCapturing) { onShutter() }, contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().clip(if (captureMode == CaptureMode.VIDEO && isRecording) RoundedCornerShape(10.dp) else CircleShape).background(if (captureMode == CaptureMode.VIDEO) Color(0xFFF63B30) else if (isCapturing) Color(0xFFD9D9D9) else Color.White))
        }
        CircleButton("↻", "Switch camera", onSwitchCamera)
    }
}

@Composable
private fun CircleButton(label: String, contentDescription: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xB5000000)).border(1.dp, Color(0x553D3D3D), CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) { Text(label, color = Color.White, fontSize = 23.sp, maxLines = 1, overflow = TextOverflow.Clip) }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.13f)).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(7.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(6.dp)); Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
}
