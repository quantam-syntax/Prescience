package com.example.consent_cam.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.consent_cam.MainActivity
import com.example.consent_cam.R

class ConsentCamWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_consentcam)
            val consent = context.getSharedPreferences("consentcam_demo_settings", Context.MODE_PRIVATE)
                .getString("appearance_consent", "OFF") ?: "OFF"
            views.setTextViewText(R.id.widget_status, "Consent: $consent")
            val launch = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, launch)
            manager.updateAppWidget(id, views)
        }
    }
}
