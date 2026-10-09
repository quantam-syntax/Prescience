package com.example.consent_cam.recognition

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.consent_cam.ble.QrCodeRenderer
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecognitionDeviceTest {
    @Test
    fun pairingQrRendersAndDecodesOffline() {
        val value = "consentcam:v1:1234abcd:abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ"
        val bitmap = QrCodeRenderer.render(value, 800)
        val scanner = BarcodeScanning.getClient()
        try {
            val barcodes = Tasks.await(scanner.process(InputImage.fromBitmap(bitmap, 0)))
            assertTrue(barcodes.any { it.rawValue == value })
        } finally {
            scanner.close()
            bitmap.recycle()
        }
    }

    @Test
    fun bundledFaceNetModelProducesNormalized512Vector() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).apply {
            val pixels = IntArray(width * height) { index ->
                val x = index % width
                val y = index / width
                android.graphics.Color.rgb(x, y, (x + y) / 2)
            }
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
        val embedder = LiteRtFaceEmbedder(context)
        val embedding = try {
            embedder.embed(bitmap).getOrThrow()
        } finally {
            embedder.close()
            bitmap.recycle()
        }

        assertEquals(FACENET_EMBEDDING_DIMENSIONS, embedding.size)
        assertTrue(EmbeddingMath.isUnitVector(embedding))
        embedding.fill(0f)
    }

    @Test
    fun ownerProfileIsEncryptedAndRoundTripsThroughAndroidKeystore() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = OwnerProfileStore(context)
        store.delete()
        val embedding = FloatArray(FACENET_EMBEDDING_DIMENSIONS).also { it[0] = 1f }
        val profile = EnrollmentProfile(FACENET_MODEL_ID, embedding.size, embedding, 1234)

        try {
            store.save(profile).getOrThrow()
            val loaded = store.load().getOrThrow()
            val loadedEmbedding = loaded.embeddingCopy()
            assertEquals(1f, loadedEmbedding[0], 0f)
            assertTrue(EmbeddingMath.isUnitVector(loadedEmbedding))
            loadedEmbedding.fill(0f)
            loaded.close()
        } finally {
            profile.close()
            store.delete()
        }
    }
}
