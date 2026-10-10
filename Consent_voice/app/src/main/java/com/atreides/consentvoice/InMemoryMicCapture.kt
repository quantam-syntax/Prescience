package com.atreides.consentvoice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.media.AudioRecord
import android.media.MediaRecorder
import com.atreides.voiceconsent.VOICE_SAMPLE_RATE_HZ
import java.util.concurrent.atomic.AtomicBoolean

/** Captures 16 kHz mono PCM in RAM only. No raw recording is written to disk. */
class InMemoryMicCapture(private val context: Context) {
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
        // Prefer the handset microphone for the room-capture demo. Android may
        // decline this on a particular device, in which case AudioRecord keeps
        // its platform-selected input route rather than failing the session.
        val builtInMic = newRecorder.routedDevice?.takeIf { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        if (builtInMic == null) {
            val audioManager = context.getSystemService(AudioManager::class.java)
            audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS)
                ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?.let { newRecorder.preferredDevice = it }
        }
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

    /** A bounded, RAM-only tail for live analysis; avoids copying an entire session. */
    fun recentSnapshot(maxSamples: Int): FloatArray = synchronized(captured) {
        require(maxSamples > 0)
        val first = (captured.size - maxSamples).coerceAtLeast(0)
        FloatArray(captured.size - first) { captured[first + it] }
    }
}
