package com.atreides.consentvoice

import android.content.Context
import com.atreides.voiceconsent.VOICE_SAMPLE_RATE_HZ
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** App-private history of consent-sanitized recordings. Raw microphone PCM is never stored here. */
object SanitizedRecordingHistory {
    private const val folderName = "sanitized-recordings"

    fun newExport(context: Context): File {
        val folder = folder(context)
        check(folder.exists() || folder.mkdirs()) { "Could not create recording history" }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        return File(folder, "consent-sanitized_$stamp.wav")
    }

    fun list(context: Context): List<File> = buildList {
        folder(context)
            .listFiles { file -> file.isFile && file.name.startsWith("consent-sanitized_") && file.extension == "wav" }
            ?.let(::addAll)
        // Preserve the single sanitized export created by earlier demo builds.
        val legacy = File(context.getExternalFilesDir(null) ?: context.filesDir, "consent-sanitized.wav")
        if (legacy.isFile) add(legacy)
    }.sortedByDescending(File::lastModified)

    fun label(file: File): String {
        val seconds = ((file.length() - 44).coerceAtLeast(0) / (VOICE_SAMPLE_RATE_HZ * 2)).coerceAtLeast(0)
        val stamp = file.name.removeSuffix(".wav").removePrefix("consent-sanitized_").replace('_', ' ')
        return "$stamp - ${seconds}s"
    }

    private fun folder(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, folderName)
}
