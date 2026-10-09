package com.atreides.consentvoice

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.File

/** Copies an already-sanitized WAV to the user's Downloads collection. Never exports raw capture. */
object SanitizedAudioDownloader {
    fun download(context: Context, source: File): String {
        return copyToDownloads(context, source, "Download/Consent Voice", "Saved sanitized audio to Downloads/Consent Voice")
    }

    private fun copyToDownloads(context: Context, source: File, folder: String, successMessage: String): String {
        require(source.isFile) { "Audio recording is unavailable" }
        val name = source.name
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create the download")
        try {
            context.contentResolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } }
                ?: error("Could not write the download")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            return successMessage
        } catch (error: Throwable) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
    }
}
