package com.atreides.consentvoice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.atreides.voiceconsent.VOICE_SAMPLE_RATE_HZ
import java.util.concurrent.atomic.AtomicBoolean

/** Captures 16 kHz mono PCM in RAM only. No raw recording is written to disk. */
class InMemoryMicCapture {
    private val running = AtomicBoolean(false)
    private val captured = ArrayList<Float>()
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null

    fun start() {
        check(!running.get()) { "Capture is already running" }
        val minBuffer = AudioRecord.getMinBufferSize(
            VOICE_SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuffer > 0) { "The device does not support 16 kHz mono capture" }
        captured.clear()
        val newRecorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            VOICE_SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, 4_096),
        )
        check(newRecorder.state == AudioRecord.STATE_INITIALIZED) { "Could not open the microphone" }
        recorder = newRecorder
        running.set(true)
        newRecorder.startRecording()
        worker = Thread {
            val buffer = ShortArray(1_024)
            while (running.get()) {
                val count = newRecorder.read(buffer, 0, buffer.size)
                if (count > 0) synchronized(captured) {
                    repeat(count) { captured += buffer[it] / 32768f }
                }
            }
        }.apply { name = "consent-voice-capture"; start() }
    }

    fun stop(): FloatArray {
        if (!running.compareAndSet(true, false)) return FloatArray(0)
        runCatching { recorder?.stop() }
        worker?.join(1_000)
        recorder?.release()
        recorder = null
        worker = null
        return synchronized(captured) { captured.toFloatArray() }
    }

    fun snapshot(): FloatArray = synchronized(captured) { captured.toFloatArray() }
}
