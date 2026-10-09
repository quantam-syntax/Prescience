package com.example.consent_cam.vision

import org.junit.Assert.assertEquals
import org.junit.Test
import com.consentcam.privacy.api.NormalizedRect

class FaceCoordinateMapperTest {
    @Test
    fun normalizeConvertsAndClampsDetectorCoordinates() {
        val result = FaceCoordinateMapper.normalize(
            left = -20,
            top = 25,
            right = 120,
            bottom = 75,
            sourceWidth = 100,
            sourceHeight = 100,
            mirrorHorizontally = false,
        )

        assertEquals(0f, result.left, 0f)
        assertEquals(0.25f, result.top, 0f)
        assertEquals(1f, result.right, 0f)
        assertEquals(0.75f, result.bottom, 0f)
    }

    @Test
    fun normalizeMirrorsFrontCameraHorizontally() {
        val result = FaceCoordinateMapper.normalize(
            left = 10,
            top = 20,
            right = 40,
            bottom = 60,
            sourceWidth = 100,
            sourceHeight = 100,
            mirrorHorizontally = true,
        )

        assertEquals(0.60f, result.left, 0.0001f)
        assertEquals(0.90f, result.right, 0.0001f)
        assertEquals(0.20f, result.top, 0.0001f)
        assertEquals(0.60f, result.bottom, 0.0001f)
    }

    @Test
    fun normalizeUsesCameraViewportCropInsteadOfFullBuffer() {
        val result = FaceCoordinateMapper.normalize(
            left = 30,
            top = 20,
            right = 70,
            bottom = 80,
            sourceWidth = 100,
            sourceHeight = 100,
            mirrorHorizontally = false,
            cropRect = FaceCoordinateMapper.IntRect(20, 10, 80, 90),
        )

        assertEquals(1f / 6f, result.left, 0.0001f)
        assertEquals(0.125f, result.top, 0.0001f)
        assertEquals(5f / 6f, result.right, 0.0001f)
        assertEquals(0.875f, result.bottom, 0.0001f)
    }

    @Test
    fun cropRectRotatesIntoUprightCoordinates() {
        val crop = FaceCoordinateMapper.rotateCropRect(
            cropRect = FaceCoordinateMapper.IntRect(10, 20, 90, 60),
            bufferWidth = 100,
            bufferHeight = 80,
            rotationDegrees = 90,
        )

        assertEquals(FaceCoordinateMapper.IntRect(20, 10, 60, 90), crop)
    }

    @Test
    fun denormalizeReturnsRawBufferCoordinatesForRotatedMirroredCrop() {
        val raw = FaceCoordinateMapper.denormalizeToBuffer(
            rect = NormalizedRect(0.25f, 0.20f, 0.75f, 0.80f),
            orientedCrop = FaceCoordinateMapper.IntRect(20, 10, 60, 90),
            bufferWidth = 100,
            bufferHeight = 80,
            rotationDegrees = 90,
            mirrorHorizontally = true,
        )

        assertEquals(26f, raw.left, 0.001f)
        assertEquals(30f, raw.top, 0.001f)
        assertEquals(74f, raw.right, 0.001f)
        assertEquals(50f, raw.bottom, 0.001f)
    }
}
