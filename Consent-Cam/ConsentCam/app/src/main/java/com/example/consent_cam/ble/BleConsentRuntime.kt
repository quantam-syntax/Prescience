package com.example.consent_cam.ble

import android.content.Context

/** One process-wide BLE controller shared by the foreground service and Compose UI. */
object BleConsentRuntime {
    @Volatile private var instance: BleConsentController? = null

    fun controller(context: Context): BleConsentController = instance ?: synchronized(this) {
        instance ?: BleConsentController(context.applicationContext).also { instance = it }
    }

    fun close() = synchronized(this) {
        instance?.close()
        instance = null
    }
}
