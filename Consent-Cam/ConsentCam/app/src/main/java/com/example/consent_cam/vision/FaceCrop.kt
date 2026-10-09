package com.example.consent_cam.vision

import android.graphics.Bitmap

class FaceCrop(
    val trackingId: Int,
    val bitmap: Bitmap,
    val timestampNs: Long,
    val yawDegrees: Float,
    val rollDegrees: Float,
    val quality: Float,
) : AutoCloseable {
    override fun close() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}
