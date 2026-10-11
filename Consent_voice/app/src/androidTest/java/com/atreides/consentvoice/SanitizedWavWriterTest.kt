package com.atreides.consentvoice

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SanitizedWavWriterTest {
    @Test
    fun highSeparatorPeaksDoNotClipOrChangeMuteRanges() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val output = File(context.cacheDir, "sanitized-writer-test.wav")
        try {
            SanitizedWavWriter.write(output, floatArrayOf(0f, 0.5f, 1f, 2f, -2f, 0f))
            val samples = ByteBuffer.wrap(output.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                .apply { position(44) }
            val decoded = ShortArray(6) { samples.short }
            assertEquals(0, decoded[0].toInt())
            assertEquals(0, decoded[5].toInt())
            assertTrue(decoded.maxOf { kotlin.math.abs(it.toInt()) } < Short.MAX_VALUE)
            assertTrue(kotlin.math.abs(decoded[3].toInt() - 2 * decoded[2].toInt()) <= 2)
            assertTrue(kotlin.math.abs(decoded[4].toInt() + decoded[3].toInt()) <= 1)
        } finally {
            output.delete()
        }
    }
}
