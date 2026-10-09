package com.example.consent_cam.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Rational
import android.view.Surface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import androidx.camera.core.CameraSelector
import androidx.camera.core.Camera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recording
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.consent_cam.vision.FaceDetectionState
import com.example.consent_cam.vision.FaceCrop
import com.example.consent_cam.vision.MlKitFaceDetector
import com.example.consent_cam.privacy.rendering.ProtectedFrameState
import com.example.consent_cam.privacy.rendering.ProtectedOutputEffect
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Binds CameraX use cases and delegates frames to the isolated detector adapter. Privacy masks
 * are deliberately not decided here; the coordinator supplies [BlurRegion] values later.
 */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class ConsentCameraController(private val context: Context) {
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val faceDetector = MlKitFaceDetector()
    private data class PendingFaceCropRequest(
        val createdAtMs: Long,
        val callback: (List<FaceCrop>) -> Unit,
    )

    private val pendingFaceCropRequest = AtomicReference<PendingFaceCropRequest?>(null)

    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private companion object {
        const val FACE_CROP_REQUEST_TIMEOUT_MS = 1_500L
    }
    private var protectedOutputEffect: ProtectedOutputEffect? = null
    private var activeCamera: Camera? = null
    @Volatile private var protectedOutputFailed = false

    fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        lensFacing: Int,
        onFacesDetected: (FaceDetectionState) -> Unit,
        onError: (String) -> Unit,
        onCameraReady: (CameraCapabilities) -> Unit = {},
    ) {
        cameraProviderFuture.addListener({
            try {
                if (protectedOutputEffect == null) {
                    protectedOutputEffect = ProtectedOutputEffect(context.applicationContext) { error ->
                        protectedOutputFailed = true
                        mainExecutor.execute {
                            stopRecording()
                            onError("Protected output stopped: ${error.message ?: "effect failure"}")
                        }
                    }
                }
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                imageAnalysis?.clearAnalyzer()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    android.util.Size(960, 720),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                ),
                            )
                            .build(),
                    )
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                            expireStalledFaceCropRequest()
                            faceDetector.analyze(
                                imageProxy = imageProxy,
                                mirrorHorizontally = lensFacing == CameraSelector.LENS_FACING_FRONT,
                                onResult = onFacesDetected,
                                captureFaceCrops = pendingFaceCropRequest.get() != null,
                                onFaceCrops = { crops ->
                                    pendingFaceCropRequest.getAndSet(null)?.callback?.invoke(crops)
                                        ?: crops.forEach(FaceCrop::close)
                                },
                                onError = onError,
                            )
                        }
                    }
                imageAnalysis = analysis
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD))
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)
                // The PreviewView viewport is not populated during an early Compose
                // layout pass on some OriginOS devices. A stable fallback viewport is
                // essential: ImageAnalysis, ImageCapture, and VideoCapture must share
                // one crop or a face rectangle can land on a stationary background in
                // the encoded output.
                val synchronizedViewPort = previewView.viewPort ?: ViewPort.Builder(
                    Rational(
                        previewView.width.coerceAtLeast(1),
                        previewView.height.coerceAtLeast(1),
                    ),
                    previewView.display?.rotation ?: Surface.ROTATION_0,
                ).setScaleType(ViewPort.FILL_CENTER).build()

                val selector = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    // iQOO exposes the ultra-wide capable logical rear camera separately
                    // from its 1x-only rear camera. Select the rear camera with the widest
                    // hardware zoom range so 0.6x is a real lens switch, not a fake crop.
                    CameraSelector.Builder().addCameraFilter { cameraInfos ->
                        val rearCameras = cameraInfos.filter { info ->
                            Camera2CameraInfo.from(info).getCameraCharacteristic(
                                CameraCharacteristics.LENS_FACING,
                            ) == CameraCharacteristics.LENS_FACING_BACK
                        }
                        val widest = rearCameras.minByOrNull { info ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                Camera2CameraInfo.from(info).getCameraCharacteristic(
                                    CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE,
                                )?.lower ?: 1f
                            } else {
                                1f
                            }
                        }
                        if (widest == null) mutableListOf() else mutableListOf(widest)
                    }.build()
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }
                cameraProvider.unbindAll()
                val useCaseGroup = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(analysis)
                    .addUseCase(imageCapture!!)
                    .addUseCase(videoCapture!!)
                    .addEffect(requireNotNull(protectedOutputEffect).cameraEffect)
                    .setViewPort(synchronizedViewPort)
                    .build()
                activeCamera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    useCaseGroup,
                ).also { camera ->
                    val zoomState = camera.cameraInfo.zoomState.value
                    val hardwareZoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Camera2CameraInfo.from(camera.cameraInfo)
                            .getCameraCharacteristic(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                    } else null
                    onCameraReady(
                        CameraCapabilities(
                            minimumZoomRatio = hardwareZoomRange?.lower ?: zoomState?.minZoomRatio ?: 1f,
                            maximumZoomRatio = hardwareZoomRange?.upper ?: zoomState?.maxZoomRatio ?: 1f,
                            hasFlash = camera.cameraInfo.hasFlashUnit(),
                        ),
                    )
                }
            } catch (error: Exception) {
                onError("Camera could not start: ${error.message ?: "unknown error"}")
            }
        }, mainExecutor)
    }

    fun unbind() {
        stopRecording()
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        pendingFaceCropRequest.getAndSet(null)?.callback?.invoke(emptyList())
        activeCamera = null
        if (cameraProviderFuture.isDone) {
            runCatching { cameraProviderFuture.get().unbindAll() }
        }
    }

    fun close() {
        unbind()
        faceDetector.close()
        protectedOutputEffect?.close()
        protectedOutputEffect = null
        analysisExecutor.shutdown()
    }

    fun updateProtection(
        protectedFrame: ProtectedFrameState,
        protectionActive: Boolean,
    ) {
        protectedOutputEffect?.update(protectedFrame, protectionActive)
    }

    fun setZoomRatio(requestedRatio: Float, onError: (String) -> Unit = {}) {
        val camera = activeCamera ?: return onError("Camera is still starting")
        val zoom = camera.cameraInfo.zoomState.value ?: return onError("Zoom is unavailable")
        val hardwareRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Camera2CameraInfo.from(camera.cameraInfo)
                .getCameraCharacteristic(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        } else null
        val supportedRatio = requestedRatio.coerceIn(
            hardwareRange?.lower ?: zoom.minZoomRatio,
            hardwareRange?.upper ?: zoom.maxZoomRatio,
        )
        val operation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hardwareRange != null) {
            Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_ZOOM_RATIO, supportedRatio)
                    .build(),
            )
        } else {
            camera.cameraControl.setZoomRatio(supportedRatio)
        }
        operation.addListener(
            { runCatching { operation.get() }.onFailure {
                mainExecutor.execute { onError("Zoom failed: ${it.message ?: "unsupported ratio"}") }
            } },
            mainExecutor,
        )
    }

    fun setFlashEnabled(enabled: Boolean, onComplete: (Boolean) -> Unit, onError: (String) -> Unit) {
        val camera = activeCamera ?: return onError("Camera is still starting")
        if (!camera.cameraInfo.hasFlashUnit()) {
            imageCapture?.flashMode = ImageCapture.FLASH_MODE_OFF
            onComplete(false)
            return onError("Flash is unavailable on this camera")
        }
        imageCapture?.flashMode = if (enabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        val operation = camera.cameraControl.enableTorch(enabled)
        operation.addListener(
            {
                runCatching { operation.get() }
                    .onSuccess { mainExecutor.execute { onComplete(enabled) } }
                    .onFailure { error -> mainExecutor.execute { onError("Flash failed: ${error.message ?: "camera rejected it"}") } }
            },
            mainExecutor,
        )
    }

    /** Requests aligned in-memory crops from one future analyzed frame. */
    fun requestFaceCrops(onResult: (List<FaceCrop>) -> Unit): Boolean {
        expireStalledFaceCropRequest()
        return pendingFaceCropRequest.compareAndSet(
            null,
            PendingFaceCropRequest(
                createdAtMs = android.os.SystemClock.elapsedRealtime(),
                callback = onResult,
            ),
        )
    }

    /** ML Kit may drop a frame without delivering crop results. Do not strand the next request. */
    private fun expireStalledFaceCropRequest() {
        val pending = pendingFaceCropRequest.get() ?: return
        if (android.os.SystemClock.elapsedRealtime() - pending.createdAtMs < FACE_CROP_REQUEST_TIMEOUT_MS) return
        if (pendingFaceCropRequest.compareAndSet(pending, null)) pending.callback(emptyList())
    }

    fun takePhoto(
        requireProtection: Boolean,
        onSaved: (Uri) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (requireProtection && !canProtectOutput()) {
            onError("Waiting for a protected face region; photo was not saved")
            return
        }
        val capture = imageCapture ?: run {
            onError("Camera is still starting")
            return
        }
        val name = "CONSENT_${timestamp()}"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/ConsentCam")
            }
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ).build()

        capture.takePicture(options, mainExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                val uri = result.savedUri
                if (protectedOutputFailed && uri != null) {
                    discardFailedOutput(uri, "Photo protection failed", onError)
                } else {
                    uri?.let(onSaved) ?: onError("Photo was saved but its location is unavailable")
                }
            }

            override fun onError(exception: ImageCaptureException) {
                onError("Photo failed: ${exception.message}")
            }
        })
    }

    fun startRecording(
        withAudio: Boolean,
        requireProtection: Boolean,
        onStarted: () -> Unit,
        onSaved: (Uri) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (requireProtection && !canProtectOutput()) {
            onError("Waiting for a protected face region; recording did not start")
            return
        }
        val capture = videoCapture ?: run {
            onError("Camera is still starting")
            return
        }
        if (activeRecording != null) return

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "CONSENT_${timestamp()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/ConsentCam")
            }
        }
        val output = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        ).setContentValues(values).build()

        var pending = capture.output.prepareRecording(context, output)
        if (
            withAudio &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            pending = pending.withAudioEnabled()
        }
        activeRecording = pending.start(mainExecutor) { event ->
            when (event) {
                is VideoRecordEvent.Start -> onStarted()
                is VideoRecordEvent.Finalize -> {
                    activeRecording = null
                    if (event.hasError() || protectedOutputFailed) {
                        discardFailedOutput(
                            event.outputResults.outputUri,
                            if (protectedOutputFailed) "Video protection failed" else "Video failed: ${event.error}",
                            onError,
                        )
                    } else {
                        event.outputResults.outputUri.let(onSaved)
                    }
                }
            }
        }
    }

    fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    private fun canProtectOutput(): Boolean =
        !protectedOutputFailed && protectedOutputEffect?.hasActiveRegions() == true

    /** Delete only the URI returned for this failed capture, never an album or prior media. */
    private fun discardFailedOutput(uri: Uri, reason: String, onError: (String) -> Unit) {
        if (uri == Uri.EMPTY) {
            onError(reason)
            return
        }
        val removed = runCatching { context.contentResolver.delete(uri, null, null) }.isSuccess
        onError(
            if (removed) "$reason; incomplete output discarded"
            else "$reason; incomplete output could not be removed from Gallery",
        )
    }

    private fun timestamp(): String = SimpleDateFormat(
        "yyyyMMdd_HHmmss",
        Locale.US,
    ).format(Date())
}

data class CameraCapabilities(
    val minimumZoomRatio: Float = 1f,
    val maximumZoomRatio: Float = 1f,
    val hasFlash: Boolean = false,
)
