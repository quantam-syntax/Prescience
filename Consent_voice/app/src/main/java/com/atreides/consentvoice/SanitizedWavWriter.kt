package com.atreides.consentvoice

import com.atreides.voiceconsent.VOICE_SAMPLE_RATE_HZ
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/** Writes only an already-sanitized PCM buffer. */
object SanitizedWavWriter {
    fun write(file: File, samples: FloatArray, sampleRateHz: Int = VOICE_SAMPLE_RATE_HZ) {
        val dataSize = samples.size * 2
        BufferedOutputStream(FileOutputStream(file)).use { out ->
            out.write("RIFF".encodeToByteArray())
            out.writeLeInt(36 + dataSize)
            out.write("WAVEfmt ".encodeToByteArray())
            out.writeLeInt(16)
            out.writeLeShort(1)
            out.writeLeShort(1)
            out.writeLeInt(sampleRateHz)
            out.writeLeInt(sampleRateHz * 2)
            out.writeLeShort(2)
            out.writeLeShort(16)
            out.write("data".encodeToByteArray())
            out.writeLeInt(dataSize)
            samples.forEach { sample ->
                val pcm = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort().toInt()
                out.write(pcm and 0xff)
                out.write((pcm shr 8) and 0xff)
            }
        }
    }

    private fun BufferedOutputStream.writeLeInt(value: Int) {
        write(value and 0xff); write((value shr 8) and 0xff); write((value shr 16) and 0xff); write((value shr 24) and 0xff)
    }

    private fun BufferedOutputStream.writeLeShort(value: Int) {
        write(value and 0xff); write((value shr 8) and 0xff)
    }
}
