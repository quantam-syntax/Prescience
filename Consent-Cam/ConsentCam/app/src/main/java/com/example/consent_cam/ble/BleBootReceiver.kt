package com.example.consent_cam.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Restarts only the BLE consent service; camera and microphone remain user-started. */
class BleBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return
        ContextCompat.startForegroundService(
            context,
            Intent(context, BleConsentService::class.java),
        )
    }
}
