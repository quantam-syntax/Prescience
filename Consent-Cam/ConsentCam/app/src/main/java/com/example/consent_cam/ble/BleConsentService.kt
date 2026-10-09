package com.example.consent_cam.ble

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.Context
import android.os.IBinder
import androidx.core.app.NotificationCompat

class BleConsentService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(com.example.consent_cam.R.drawable.ic_launcher_foreground)
                .setContentTitle("ConsentCam privacy signal")
                .setContentText("Broadcasting and receiving consent over Bluetooth")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build(),
        )
        val controller = BleConsentRuntime.controller(this)
        val prefs = getSharedPreferences("consentcam_demo_settings", Context.MODE_PRIVATE)
        val consent = runCatching {
            AppearanceConsent.valueOf(prefs.getString("appearance_consent", null) ?: "OFF")
        }.getOrDefault(AppearanceConsent.OFF)
        controller.setConsent(consent)
        controller.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_CONSENT)?.let { value ->
            runCatching { AppearanceConsent.valueOf(value) }.onSuccess { consent ->
                getSharedPreferences("consentcam_demo_settings", Context.MODE_PRIVATE)
                    .edit().putString("appearance_consent", consent.name).apply()
                BleConsentRuntime.controller(this).setConsent(consent)
            }
        }
        BleConsentRuntime.controller(this).start()
        return START_STICKY
    }

    override fun onDestroy() {
        BleConsentRuntime.controller(this).stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "BLE consent exchange",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps offline consent broadcasting and scanning active"
                setShowBadge(false)
            },
        )
    }

    private companion object {
        const val EXTRA_CONSENT = "consent"
        const val CHANNEL_ID = "ble_consent_exchange"
        const val NOTIFICATION_ID = 1307
    }
}
