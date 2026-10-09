package com.example.consent_cam.vision

import com.consentcam.privacy.api.NormalizedRect

/** Maps ML Kit's upright image coordinates into normalized display coordinates. */
object FaceCoordinateMapper {
    data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    data class FloatRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

    fun normalize(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        mirrorHorizontally: Boolean,
        cropRect: IntRect = IntRect(0, 0, sourceWidth, sourceHeight),
    ): NormalizedRect {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(cropRect.width > 0 && cropRect.height > 0)
        require(cropRect.left >= 0 && cropRect.top >= 0)
        require(cropRect.right <= sourceWidth && cropRect.bottom <= sourceHeight)

        val normalizedLeft = ((left - cropRect.left).toFloat() / cropRect.width).coerceIn(0f, 1f)
        val normalizedRight = ((right - cropRect.left).toFloat() / cropRect.width).coerceIn(0f, 1f)
        val mappedLeft = if (mirrorHorizontally) 1f - normalizedRight else normalizedLeft
        val mappedRight = if (mirrorHorizontally) 1f - normalizedLeft else normalizedRight

        return NormalizedRect(
            left = minOf(mappedLeft, mappedRight),
            top = ((top - cropRect.top).toFloat() / cropRect.height).coerceIn(0f, 1f),
            right = maxOf(mappedLeft, mappedRight),
            bottom = ((bottom - cropRect.top).toFloat() / cropRect.height).coerceIn(0f, 1f),
        )
    }

    /** Rotates an ImageProxy crop rectangle into ML Kit's upright coordinate system. */
    fun rotateCropRect(
        cropRect: IntRect,
        bufferWidth: Int,
        bufferHeight: Int,
        rotationDegrees: Int,
    ): IntRect {
        require(bufferWidth > 0 && bufferHeight > 0)
        require(cropRect.left >= 0 && cropRect.top >= 0)
        require(cropRect.right <= bufferWidth && cropRect.bottom <= bufferHeight)
        return when (rotationDegrees.mod(360)) {
            0 -> cropRect
            90 -> IntRect(
                left = bufferHeight - cropRect.bottom,
                top = cropRect.left,
                right = bufferHeight - cropRect.top,
                bottom = cropRect.right,
            )
            180 -> IntRect(
                left = bufferWidth - cropRect.right,
                top = bufferHeight - cropRect.bottom,
                right = bufferWidth - cropRect.left,
                bottom = bufferHeight - cropRect.top,
            )
            270 -> IntRect(
                left = cropRect.top,
                top = bufferWidth - cropRect.right,
                right = cropRect.bottom,
                bottom = bufferWidth - cropRect.left,
            )
            else -> error("Rotation must be a multiple of 90 degrees")
        }
    }

    /**
     * Converts a display-oriented normalized face rectangle back into the raw analysis buffer.
     * The result can be mapped through CameraX's sensor transforms for each output surface.
     */
    fun denormalizeToBuffer(
        rect: NormalizedRect,
        orientedCrop: IntRect,
        bufferWidth: Int,
        bufferHeight: Int,
        rotationDegrees: Int,
        mirrorHorizontally: Boolean,
    ): FloatRect {
        val displayLeft = if (mirrorHorizontally) 1f - rect.right else rect.left
        val displayRight = if (mirrorHorizontally) 1f - rect.left else rect.right
        val upright = FloatRect(
            orientedCrop.left + displayLeft * orientedCrop.width,
            orientedCrop.top + rect.top * orientedCrop.height,
            orientedCrop.left + displayRight * orientedCrop.width,
            orientedCrop.top + rect.bottom * orientedCrop.height,
        )
        return unrotateRect(upright, bufferWidth, bufferHeight, rotationDegrees)
    }

    private fun unrotateRect(
        upright: FloatRect,
        bufferWidth: Int,
        bufferHeight: Int,
        rotationDegrees: Int,
    ): FloatRect {
        val raw = when (rotationDegrees.mod(360)) {
            0 -> upright
            90 -> FloatRect(
                upright.top,
                bufferHeight - upright.right,
                upright.bottom,
                bufferHeight - upright.left,
            )
            180 -> FloatRect(
                bufferWidth - upright.right,
                bufferHeight - upright.bottom,
                bufferWidth - upright.left,
                bufferHeight - upright.top,
            )
            270 -> FloatRect(
                bufferWidth - upright.bottom,
                upright.left,
                bufferWidth - upright.top,
                upright.right,
            )
            else -> error("Rotation must be a multiple of 90 degrees")
        }
        return FloatRect(
            raw.left.coerceIn(0f, bufferWidth.toFloat()),
            raw.top.coerceIn(0f, bufferHeight.toFloat()),
            raw.right.coerceIn(0f, bufferWidth.toFloat()),
            raw.bottom.coerceIn(0f, bufferHeight.toFloat()),
        )
    }
}
