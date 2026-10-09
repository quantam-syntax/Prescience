package com.example.consent_cam.agent

import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThermalLevel(val label: String) {
    NORMAL("Normal"),
    LIGHT("Light"),
    MODERATE("Moderate"),
    SEVERE("Severe"),
    CRITICAL("Critical"),
}

object ThermalGovernor {
    fun auditIntervalMs(level: ThermalLevel): Long? = when (level) {
        ThermalLevel.NORMAL, ThermalLevel.LIGHT -> 10_000L
        ThermalLevel.MODERATE -> 20_000L
        ThermalLevel.SEVERE, ThermalLevel.CRITICAL -> null
    }
}

class DeviceThermalMonitor(context: Context) : AutoCloseable {
    private val powerManager = context.applicationContext.getSystemService(PowerManager::class.java)
    private val mutableLevel = MutableStateFlow(readCurrent())
    val level: StateFlow<ThermalLevel> = mutableLevel.asStateFlow()

    private val listener = PowerManager.OnThermalStatusChangedListener { status ->
        mutableLevel.value = map(status)
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager?.addThermalStatusListener(listener)
        }
    }

    override fun close() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager?.removeThermalStatusListener(listener)
        }
    }

    private fun readCurrent(): ThermalLevel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        map(powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE)
    } else {
        ThermalLevel.NORMAL
    }

    private fun map(status: Int): ThermalLevel = when {
        status >= PowerManager.THERMAL_STATUS_CRITICAL -> ThermalLevel.CRITICAL
        status >= PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.SEVERE
        status >= PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.MODERATE
        status >= PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.LIGHT
        else -> ThermalLevel.NORMAL
    }
}
