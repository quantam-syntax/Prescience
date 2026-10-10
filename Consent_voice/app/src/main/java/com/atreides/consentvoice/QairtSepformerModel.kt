package com.atreides.consentvoice

import android.content.Context
import java.io.File

/** Copies the no-compress DLC from the APK only once, because QAIRT requires a filesystem path. */
object QairtSepformerModel {
    private const val assetName = "qairt/sepformer-w8a16-sm8850-cached.dlc"
    private const val modelName = "sepformer-w8a16-sm8850-cached.dlc"

    @Synchronized
    fun initialize(context: Context): String? {
        val target = File(context.filesDir, modelName)
        if (!target.exists() || target.length() == 0L) {
            context.assets.open(assetName).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        return QairtSepformer.initialize(target.absolutePath, context.applicationInfo.nativeLibraryDir)
    }
}
