package com.example.consent_cam.ble

import android.content.Context
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class QrScannerController(private val context: Context) : AutoCloseable {
    private val providerFuture = ProcessCameraProvider.getInstance(context)
    private val executor = Executors.newSingleThreadExecutor()
    private val processing = AtomicBoolean(false)
    private val delivered = AtomicBoolean(false)
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build(),
    )
    private var analysis: ImageAnalysis? = null

    @OptIn(ExperimentalGetImage::class)
    fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onCode: (String) -> Boolean,
        onError: (String) -> Unit,
    ) {
        delivered.set(false)
        providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { useCase ->
                        useCase.setAnalyzer(executor) { imageProxy ->
                            if (!processing.compareAndSet(false, true)) {
                                imageProxy.close()
                                return@setAnalyzer
                            }
                            val mediaImage = imageProxy.image
                            if (mediaImage == null) {
                                processing.set(false)
                                imageProxy.close()
                                return@setAnalyzer
                            }
                            scanner.process(InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees))
                                .addOnSuccessListener { barcodes ->
                                    val value = barcodes.firstNotNullOfOrNull(Barcode::getRawValue)
                                    if (value != null && delivered.compareAndSet(false, true) && !onCode(value)) {
                                        delivered.set(false)
                                    }
                                }
                                .addOnFailureListener { error -> onError(error.message ?: "QR scan failed") }
                                .addOnCompleteListener {
                                    processing.set(false)
                                    imageProxy.close()
                                }
                        }
                    }
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    requireNotNull(analysis),
                )
            }.onFailure { onError(it.message ?: "QR camera could not start") }
        }, ContextCompat.getMainExecutor(context))
    }

    fun unbind() {
        analysis?.clearAnalyzer()
        analysis = null
        if (providerFuture.isDone) runCatching { providerFuture.get().unbindAll() }
    }

    override fun close() {
        unbind()
        scanner.close()
        executor.shutdown()
    }
}
