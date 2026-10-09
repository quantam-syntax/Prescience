package com.example.consent_cam.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.consentcam.ble.broadcast.ConsentBroadcastSession
import com.consentcam.ble.protocol.ConsentAdvertisementCodec
import com.consentcam.ble.protocol.ConsentFlags
import com.consentcam.ble.api.ProximityBand
import com.consentcam.ble.session.ConsentStatus
import com.consentcam.ble.session.DirectBleConsentRegistry
import com.consentcam.ble.proximity.ProximityTracker
import com.example.consent_cam.recognition.EnrollmentProfile
import com.example.consent_cam.recognition.EnrollmentProfileCodec
import com.example.consent_cam.recognition.SessionProfile
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class AppearanceConsent(val label: String, internal val flags: UByte?) {
    OFF("OFF", null),
    ALLOW("ALLOW", ConsentFlags.ALLOW_APPEARANCE.toUByte()),
    PROTECT("PROTECT", 0u),
}

data class BleConsentUiState(
    val supported: Boolean = true,
    val bluetoothEnabled: Boolean = false,
    val permissionsGranted: Boolean = false,
    val advertising: Boolean = false,
    val scanning: Boolean = false,
    val ownConsent: AppearanceConsent = AppearanceConsent.OFF,
    val acceptedTokenCount: Int = 0,
    val nearbySessionCount: Int = 0,
    val nearbyProtectCount: Int = 0,
    val proximityBand: ProximityBand = ProximityBand.LOST,
    val smoothedRssi: Double? = null,
    val protectionActive: Boolean = false,
    val preciseProfileAvailable: Boolean = false,
    val preciseProfileCount: Int = 0,
    val preciseMatchingActive: Boolean = false,
    val preciseStatus: String = "Waiting for nearby enrolled phones",
    val privacyZonePreset: PrivacyZonePreset = PrivacyZonePreset.FAR,
    val status: String = "BLE is starting",
)

/** Thin Android adapter around Rahul's tested protocol and proximity core. */
class BleConsentController(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter
        get() = bluetoothManager?.adapter
    private val serviceUuid = ParcelUuid(
        UUID.fromString(ConsentAdvertisementCodec.SERVICE_UUID),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val directRegistry = DirectBleConsentRegistry(
        proximityTracker = ProximityTracker(PrivacyZonePreset.FAR.config()),
        staleTimeoutMs = 8_000L,
    )
    private val ownSessionIds = mutableSetOf<UInt>()
    private val mutableState = MutableStateFlow(BleConsentUiState())
    private val profileLock = Any()
    private val profilesBySession = mutableMapOf<UInt, SessionProfile>()
    private val mutablePreciseProfiles = MutableStateFlow<List<SessionProfile>>(emptyList())
    private val preciseTransport = PreciseProfileTransport(
        context = appContext,
        onStatus = { status -> mutableState.value = mutableState.value.copy(preciseStatus = status) },
        onProfile = ::storePreciseProfile,
        onProfileRemoved = ::removePreciseProfile,
    )

    val state: StateFlow<BleConsentUiState> = mutableState.asStateFlow()
    val preciseProfiles: StateFlow<List<SessionProfile>> = mutablePreciseProfiles.asStateFlow()

    private var broadcastSession: ConsentBroadcastSession? = null
    private var advertiserCallback: AdvertiseCallback? = null
    private var scanner: BluetoothLeScanner? = null
    private var refreshJob: Job? = null
    private var started = false
    private var acceptedTokenCount = 0
    private var advertisedCounter = -1L
    private var ownerProfileBytes: ByteArray? = null
    private val preciseProfileLostSinceMs = mutableMapOf<UInt, Long>()
    @Volatile private var cameraSessionActive = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            acceptScan(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::acceptScan)
        }

        override fun onScanFailed(errorCode: Int) {
            mutableState.value = mutableState.value.copy(
                scanning = false,
                status = "BLE scan failed ($errorCode)",
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (started) {
            if (!mutableState.value.advertising && mutableState.value.ownConsent != AppearanceConsent.OFF) {
                restartAdvertising(newSession = true)
            }
            if (!mutableState.value.scanning) startScanning()
            return
        }
        val adapter = bluetoothAdapter
        val supported = adapter != null && appContext.packageManager.hasSystemFeature(
            PackageManager.FEATURE_BLUETOOTH_LE,
        )
        val permissions = hasRequiredPermissions(appContext)
        val enabled = permissions && adapter?.isEnabled == true
        mutableState.value = mutableState.value.copy(
            supported = supported,
            bluetoothEnabled = enabled,
            permissionsGranted = permissions,
            status = when {
                !supported -> "BLE is not supported"
                !permissions -> "BLE permission required"
                !enabled -> "Turn Bluetooth on"
                else -> "Starting BLE consent exchange"
            },
        )
        if (!supported || !permissions || !enabled) return

        started = true
        if (mutableState.value.ownConsent != AppearanceConsent.OFF) restartAdvertising(newSession = true)
        startScanning()
        refreshJob = scope.launch {
            while (isActive) {
                delay(1_000)
                refreshNearbyState()
                val counter = System.currentTimeMillis() / 1_000L / 30L
                if (counter != advertisedCounter) restartAdvertising(newSession = false)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!hasRequiredPermissions(appContext)) {
            started = false
            return
        }
        advertiserCallback?.let { callback ->
            bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(callback)
        }
        advertiserCallback = null
        scanner?.stopScan(scanCallback)
        scanner = null
        refreshJob?.cancel()
        refreshJob = null
        broadcastSession?.close()
        broadcastSession = null
        preciseTransport.stopServer()
        started = false
        mutableState.value = mutableState.value.copy(
            advertising = false,
            scanning = false,
            status = "BLE consent exchange stopped",
        )
    }

    @SuppressLint("MissingPermission")
    fun setConsent(consent: AppearanceConsent) {
        if (mutableState.value.ownConsent == consent) return
        mutableState.value = mutableState.value.copy(
            ownConsent = consent,
            status = if (started) mutableState.value.status else "${consent.label} is ready; start BLE to broadcast it",
        )
        if (started) {
            if (consent == AppearanceConsent.OFF) {
                advertiserCallback?.let { callback -> bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(callback) }
                advertiserCallback = null
                broadcastSession?.close()
                broadcastSession = null
                preciseTransport.stopServer()
                mutableState.value = mutableState.value.copy(
                    advertising = false,
                    status = "Consent broadcast OFF; still scanning nearby phones",
                )
            } else {
                restartAdvertising(newSession = true)
            }
        }
    }

    fun setOwnerProfile(profile: EnrollmentProfile?) {
        ownerProfileBytes?.fill(0)
        ownerProfileBytes = profile?.let(EnrollmentProfileCodec::encode)
        if (started) restartAdvertising(newSession = true)
    }

    @SuppressLint("MissingPermission")
    private fun restartAdvertising(newSession: Boolean) {
        val ownConsent = mutableState.value.ownConsent
        if (ownConsent == AppearanceConsent.OFF) return
        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser ?: run {
            mutableState.value = mutableState.value.copy(
                advertising = false,
                status = "This phone cannot advertise BLE",
            )
            return
        }
        advertiserCallback?.let(advertiser::stopAdvertising)
        advertiserCallback = null
        if (newSession || broadcastSession == null) {
            broadcastSession?.close()
            val preciseAvailable = ownerProfileBytes != null
            val flags = (
                requireNotNull(ownConsent.flags).toInt() or
                    if (preciseAvailable) ConsentFlags.PRECISE_MATCH_AVAILABLE else 0
                ).toUByte()
            broadcastSession = ConsentBroadcastSession.create(flags)
            ownSessionIds += requireNotNull(broadcastSession).sessionId
            if (preciseAvailable) {
                preciseTransport.startServer(
                    sessionId = requireNotNull(broadcastSession).sessionId,
                    encodedProfile = requireNotNull(ownerProfileBytes),
                )
            } else {
                preciseTransport.stopServer()
            }
        }
        val session = requireNotNull(broadcastSession)
        val epochSeconds = System.currentTimeMillis() / 1_000L
        advertisedCounter = epochSeconds / 30L
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(
                serviceUuid,
                session.serviceData(epochSeconds),
            )
            .build()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(ownerProfileBytes != null)
            .setTimeout(0)
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                mutableState.value = mutableState.value.copy(
                    advertising = true,
                    status = "Advertising ${mutableState.value.ownConsent.label}; scanning for consent",
                )
            }

            override fun onStartFailure(errorCode: Int) {
                mutableState.value = mutableState.value.copy(
                    advertising = false,
                    status = "BLE advertising failed ($errorCode)",
                )
            }
        }
        advertiserCallback = callback
        advertiser.startAdvertising(settings, data, callback)
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        val activeScanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            mutableState.value = mutableState.value.copy(status = "BLE scanner unavailable")
            return
        }
        scanner = activeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        // Some OriginOS builds do not match a 128-bit UUID carried only in the
        // service-data AD structure. Scan unfiltered, then accept only our exact
        // UUID and validated 10-byte protocol payload in acceptScan().
        activeScanner.startScan(emptyList(), settings, scanCallback)
        mutableState.value = mutableState.value.copy(scanning = true)
    }

    private fun acceptScan(result: ScanResult) {
        val serviceData = result.scanRecord?.getServiceData(serviceUuid) ?: return
        val decoded = ConsentAdvertisementCodec.decode(serviceData, result.rssi)
        if (decoded !is ConsentAdvertisementCodec.DecodeResult.Success) return
        val advertisement = decoded.advertisement
        if (advertisement.sessionId in ownSessionIds) return
        preciseTransport.observe(
            device = result.device,
            advertisement = advertisement,
        )
        val now = SystemClock.elapsedRealtime()
        val observed = directRegistry.observe(advertisement, now)
            ?: return
        acceptedTokenCount += 1
        mutableState.value = mutableState.value.copy(
            acceptedTokenCount = acceptedTokenCount,
            proximityBand = observed.proximity.band,
            smoothedRssi = observed.proximity.smoothedRssi,
            status = "Accepted BLE v1 token (${if (ConsentFlags.isAllow(advertisement.flags)) "ALLOW" else "PROTECT"})",
        )
        refreshNearbyState()
    }

    private fun refreshNearbyState() {
        val now = SystemClock.elapsedRealtime()
        val active = directRegistry.activeSessions(now)
        val nearbyProtect = active.count { session ->
            session.consent == ConsentStatus.PROTECT && session.proximity.insidePrivacyZone
        }
        val strongest = active.maxByOrNull { it.proximity.smoothedRssi }?.proximity
        val activeIds = active.mapTo(mutableSetOf()) { it.sessionId }
        val storedProfiles = synchronized(profileLock) { profilesBySession.values.toList() }
        storedProfiles.forEach { profile ->
            if (profile.sessionId in activeIds) {
                preciseProfileLostSinceMs.remove(profile.sessionId)
            } else {
                val lostSince = preciseProfileLostSinceMs.getOrPut(profile.sessionId) { now }
                if (!cameraSessionActive && now - lostSince >= PRECISE_PROFILE_LOST_GRACE_MS) {
                    removePreciseProfile(profile.sessionId)
                    preciseProfileLostSinceMs.remove(profile.sessionId)
                }
            }
        }
        val preciseProtectInsideZone = active.any { session ->
            session.consent == ConsentStatus.PROTECT &&
                session.proximity.insidePrivacyZone &&
                synchronized(profileLock) { profilesBySession.containsKey(session.sessionId) }
        }
        mutableState.value = mutableState.value.copy(
            nearbySessionCount = active.size,
            nearbyProtectCount = nearbyProtect,
            proximityBand = strongest?.band ?: ProximityBand.LOST,
            smoothedRssi = strongest?.smoothedRssi,
            protectionActive = nearbyProtect > 0,
            preciseMatchingActive = preciseProtectInsideZone,
        )
    }

    /** Keeps received profiles only in RAM for this open camera session through scanner gaps. */
    fun setCameraSessionActive(active: Boolean) {
        if (cameraSessionActive == active) return
        cameraSessionActive = active
        if (!active) {
            val sessionIds = synchronized(profileLock) { profilesBySession.keys.toList() }
            sessionIds.forEach(::removePreciseProfile)
            preciseProfileLostSinceMs.clear()
        }
        refreshNearbyState()
    }

    fun setPrivacyZonePreset(preset: PrivacyZonePreset) {
        directRegistry.replaceProximityTracker(ProximityTracker(preset.config()))
        mutableState.value = mutableState.value.copy(
            privacyZonePreset = preset,
            nearbySessionCount = 0,
            nearbyProtectCount = 0,
            proximityBand = ProximityBand.LOST,
            smoothedRssi = null,
            protectionActive = false,
            preciseMatchingActive = false,
            status = "${preset.label} privacy zone active; recalibrating BLE",
        )
    }

    private fun storePreciseProfile(profile: SessionProfile) {
        synchronized(profileLock) {
            profilesBySession.put(profile.sessionId, profile)?.close()
            publishPreciseProfilesLocked()
        }
    }

    private fun removePreciseProfile(sessionId: UInt) {
        synchronized(profileLock) {
            profilesBySession.remove(sessionId)?.close()
            publishPreciseProfilesLocked()
        }
        preciseTransport.forgetSession(sessionId)
    }

    private fun publishPreciseProfilesLocked() {
        val profiles = profilesBySession.values.toList()
        mutablePreciseProfiles.value = profiles
        mutableState.value = mutableState.value.copy(
            preciseProfileAvailable = profiles.isNotEmpty(),
            preciseProfileCount = profiles.size,
        )
    }

    override fun close() {
        stop()
        scope.coroutineContext[Job]?.cancel()
        directRegistry.close()
        preciseTransport.close()
        synchronized(profileLock) {
            profilesBySession.values.forEach(SessionProfile::close)
            profilesBySession.clear()
            mutablePreciseProfiles.value = emptyList()
        }
        preciseProfileLostSinceMs.clear()
        ownerProfileBytes?.fill(0)
        ownerProfileBytes = null
        ownSessionIds.clear()
    }

    companion object {
        // The RSSI registry already retains an eight-second signal-loss grace.
        // Keeping a stale PROTECT embedding beyond it can override a new ALLOW session.
        private const val PRECISE_PROFILE_LOST_GRACE_MS = 0L
        fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        fun hasRequiredPermissions(context: Context): Boolean = requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
}
