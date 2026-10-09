package com.example.consent_cam.privacy.rendering

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.consent_cam.vision.FaceDetectionState

/** Draws verification boxes while real pixelation is rendered by [ProtectedOutputEffect]. */
@Composable
fun FacePixelationOverlay(
    detection: FaceDetectionState,
    protectedFrame: ProtectedFrameState,
    protectionActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val sourceWidth = if (protectedFrame.hasFrameGeometry) protectedFrame.sourceWidth else detection.sourceWidth
    val sourceHeight = if (protectedFrame.hasFrameGeometry) protectedFrame.sourceHeight else detection.sourceHeight
    if (sourceWidth <= 0 || sourceHeight <= 0) return

    Canvas(modifier) {
        val scale = maxOf(size.width / sourceWidth, size.height / sourceHeight)
        val scaledWidth = sourceWidth * scale
        val scaledHeight = sourceHeight * scale
        val offsetX = (size.width - scaledWidth) / 2f
        val offsetY = (size.height - scaledHeight) / 2f

        if (!protectionActive) {
            detection.faces.forEach { face ->
                val normalized = face.normalizedRect
                drawRoundRect(
                    color = DetectionGreen,
                    topLeft = Offset(
                        offsetX + normalized.left * scaledWidth,
                        offsetY + normalized.top * scaledHeight,
                    ),
                    size = Size(
                        normalized.width * scaledWidth,
                        normalized.height * scaledHeight,
                    ),
                    cornerRadius = CornerRadius(18.dp.toPx()),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }
    }
}

private val DetectionGreen = Color(0xFF75E29B)
