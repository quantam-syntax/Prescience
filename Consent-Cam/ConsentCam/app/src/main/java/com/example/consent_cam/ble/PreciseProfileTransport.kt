package com.example.consent_cam.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.consentcam.ble.api.ConsentAdvertisement
import com.consentcam.ble.enrollment.EncryptedEnrollmentPayload
import com.consentcam.ble.enrollment.EnrollmentAssociatedData
import com.consentcam.ble.enrollment.EnrollmentCipher
import com.consentcam.ble.enrollment.GattChunkCodec
import com.consentcam.ble.enrollment.GattTransferReassembler
import com.consentcam.ble.protocol.ConsentAdvertisementCodec
import com.consentcam.ble.protocol.ConsentFlags
import com.example.consent_cam.recognition.EnrollmentProfileCodec
import com.example.consent_cam.recognition.SessionProfile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.UUID

/** Automatic, bond-free encrypted exchange for a session-only face embedding. */
@SuppressLint("MissingPermission")
class PreciseProfileTransport(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onProfile: (SessionProfile) -> Unit,
    @Suppress("UNUSED_PARAMETER") onProfileRemoved: (UInt) -> Unit,
) : AutoCloseable {
    private data class ServerPayload(
        val sessionId: UInt,
        val keyPair: KeyPair,
        val encodedProfile: ByteArray,
    ) : AutoCloseable {
        override fun close() = encodedProfile.fill(0)
    }

    private data class PendingSession(val sessionId: UInt, val consent: AppearanceConsent)

    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val cipher = EnrollmentCipher()
    private val random = SecureRandom()
    private var server: BluetoothGattServer? = null
    private var serverPayload: ServerPayload? = null
    private var profileCharacteristic: BluetoothGattCharacteristic? = null
    private val outgoing = mutableMapOf<String, ArrayDeque<ByteArray>>()
    private var clientGatt: BluetoothGatt? = null
    private var clientTransferId = 0u
    private var clientSecret: ByteArray? = null
    private var reassembler: GattTransferReassembler? = null
    private var connectingAddress: String? = null
    private var connectionStartedMs = 0L
    private var pendingSession: PendingSession? = null
    private val receivedSessionIds = mutableSetOf<UInt>()
    private val lastAttemptBySession = mutableMapOf<UInt, Long>()

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (service.uuid == SERVICE_UUID) onStatus(
                if (status == BluetoothGatt.GATT_SUCCESS) "Encrypted face profile ready for nearby phones"
                else "Could not publish face profile service ($status)",
            )
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val bytes = if (characteristic.uuid == KEY_UUID) {
                serverPayload?.let { EphemeralProfileKeyExchange.publicKeyBytes(it.keyPair) }
            } else null
            if (bytes == null || offset !in 0..bytes.size) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            } else {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, bytes.copyOfRange(offset, bytes.size))
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val payload = serverPayload
            val valid = characteristic.uuid == CONTROL_UUID && !preparedWrite && offset == 0 && value.size > 4 && payload != null
            if (!valid) {
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                return
            }
            val transferId = ByteBuffer.wrap(value, 0, 4).order(ByteOrder.BIG_ENDIAN).int.toUInt()
            val secret = runCatching {
                EphemeralProfileKeyExchange.deriveSecret(requireNotNull(payload).keyPair.private, value.copyOfRange(4, value.size))
            }.getOrNull()
            if (secret == null) {
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                return
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            try {
                prepareTransfer(device, transferId, secret)
            } finally {
                secret.fill(0)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val valid = descriptor.uuid == CCCD_UUID && value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (responseNeeded) server?.sendResponse(device, requestId, if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, offset, null)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) sendNext(device) else clearOutgoing(device.address)
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) clearOutgoing(device.address)
        }
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== clientGatt) return gatt.close()
            when {
                status != BluetoothGatt.GATT_SUCCESS -> failClient("Face profile connection failed ($status)")
                newState == BluetoothProfile.STATE_CONNECTED -> {
                    onStatus("Nearby enrolled phone found; negotiating encrypted profile")
                    if (!gatt.requestMtu(PREFERRED_MTU)) gatt.discoverServices()
                }
                newState == BluetoothProfile.STATE_DISCONNECTED -> failClient("Face profile connection closed")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (gatt === clientGatt) gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(SERVICE_UUID)
            val notifications = service?.getCharacteristic(PROFILE_UUID)
            val key = service?.getCharacteristic(KEY_UUID)
            val descriptor = notifications?.getDescriptor(CCCD_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || notifications == null || key == null || descriptor == null) {
                return failClient("Encrypted face profile service is unavailable")
            }
            gatt.setCharacteristicNotification(notifications, true)
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (!gatt.writeDescriptor(descriptor)) failClient("Could not enable face profile transfer")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != CCCD_UUID) return
            val key = gatt.getService(SERVICE_UUID)?.getCharacteristic(KEY_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || key == null || !gatt.readCharacteristic(key)) {
                failClient("Could not read the ephemeral profile key")
            }
        }

        @Deprecated("API 26 compatibility")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            acceptServerKey(gatt, characteristic, characteristic.value ?: byteArrayOf(), status)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            acceptServerKey(gatt, characteristic, value, status)
        }

        @Deprecated("API 26 compatibility")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            acceptChunk(characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            acceptChunk(value)
        }
    }

    fun startServer(sessionId: UInt, encodedProfile: ByteArray) {
        stopServer()
        serverPayload = ServerPayload(sessionId, EphemeralProfileKeyExchange.generate(), encodedProfile.copyOf())
        val activeServer = bluetoothManager?.openGattServer(appContext, serverCallback)
            ?: return onStatus("This phone cannot serve an encrypted face profile")
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val control = BluetoothGattCharacteristic(CONTROL_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE)
        val key = BluetoothGattCharacteristic(KEY_UUID, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
        val profile = BluetoothGattCharacteristic(PROFILE_UUID, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ)
        profile.addDescriptor(BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(control)
        service.addCharacteristic(key)
        service.addCharacteristic(profile)
        profileCharacteristic = profile
        server = activeServer
        if (!activeServer.addService(service)) {
            stopServer()
            onStatus("Could not publish the encrypted face profile service")
        }
    }

    fun stopServer() {
        outgoing.keys.toList().forEach(::clearOutgoing)
        server?.close()
        server = null
        profileCharacteristic = null
        serverPayload?.close()
        serverPayload = null
    }

    fun observe(device: BluetoothDevice, advertisement: ConsentAdvertisement) {
        val now = SystemClock.elapsedRealtime()
        if (connectingAddress != null && now - connectionStartedMs >= TRANSFER_TIMEOUT_MS) {
            failClient("Face profile transfer timed out; retrying nearby phones")
        }
        if (!ConsentFlags.hasPreciseMatch(advertisement.flags) || advertisement.sessionId in receivedSessionIds || connectingAddress != null) return
        if (now - (lastAttemptBySession[advertisement.sessionId] ?: 0L) < RETRY_DELAY_MS) return
        lastAttemptBySession[advertisement.sessionId] = now
        pendingSession = PendingSession(
            advertisement.sessionId,
            if (ConsentFlags.isAllow(advertisement.flags)) AppearanceConsent.ALLOW else AppearanceConsent.PROTECT,
        )
        connectingAddress = device.address
        connectionStartedMs = now
        clientGatt = device.connectGatt(appContext, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun forgetSession(sessionId: UInt) {
        receivedSessionIds.remove(sessionId)
        lastAttemptBySession.remove(sessionId)
    }

    override fun close() {
        stopServer()
        clearClient()
        receivedSessionIds.clear()
        lastAttemptBySession.clear()
    }

    private fun acceptServerKey(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, serverPublicKey: ByteArray, status: Int) {
        if (gatt !== clientGatt) return
        if (characteristic.uuid != KEY_UUID) return
        if (status != BluetoothGatt.GATT_SUCCESS) return failClient("Ephemeral profile key read failed")
        val clientKeyPair = EphemeralProfileKeyExchange.generate()
        val secret = runCatching {
            EphemeralProfileKeyExchange.deriveSecret(clientKeyPair.private, serverPublicKey)
        }.getOrElse { return failClient("Ephemeral profile key was invalid") }
        clientSecret?.fill(0)
        clientSecret = secret
        clientTransferId = random.nextInt().toUInt()
        val publicKey = EphemeralProfileKeyExchange.publicKeyBytes(clientKeyPair)
        val request = ByteBuffer.allocate(4 + publicKey.size).order(ByteOrder.BIG_ENDIAN).putInt(clientTransferId.toInt()).put(publicKey).array()
        val control = gatt.getService(SERVICE_UUID)?.getCharacteristic(CONTROL_UUID)
            ?: return failClient("Face profile control is unavailable")
        control.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        control.value = request
        if (!gatt.writeCharacteristic(control)) failClient("Could not request the encrypted face profile")
    }

    private fun prepareTransfer(device: BluetoothDevice, transferId: UInt, secret: ByteArray) {
        val payload = serverPayload ?: return
        val encrypted = cipher.encrypt(secret, payload.encodedProfile, EnrollmentAssociatedData.encode(payload.sessionId, transferId))
        val transportBytes = encrypted.toTransportBytes()
        encrypted.close()
        val packets = try {
            GattChunkCodec.chunk(payload.sessionId, transferId, transportBytes, MAX_PACKET_BYTES).map(GattChunkCodec::encode)
        } finally {
            transportBytes.fill(0)
        }
        clearOutgoing(device.address)
        outgoing[device.address] = ArrayDeque(packets)
        sendNext(device)
    }

    private fun sendNext(device: BluetoothDevice) {
        val queue = outgoing[device.address] ?: return
        val packet = if (queue.isEmpty()) null else queue.removeFirst()
        if (packet == null) return clearOutgoing(device.address)
        val characteristic = profileCharacteristic ?: return
        val sent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server?.notifyCharacteristicChanged(device, characteristic, false, packet) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.value = packet
            server?.notifyCharacteristicChanged(device, characteristic, false) == true
        }
        if (!sent) clearOutgoing(device.address)
    }

    private fun clearOutgoing(address: String) {
        outgoing.remove(address)?.forEach { it.fill(0) }
    }

    private fun acceptChunk(packet: ByteArray) {
        val pending = pendingSession ?: return
        val decoded = GattChunkCodec.decode(packet)
        if (decoded !is GattChunkCodec.DecodeResult.Success) return failClient("Invalid face profile packet")
        val chunk = decoded.chunk
        if (chunk.sessionId != pending.sessionId || chunk.transferId != clientTransferId) return failClient("Face profile packet did not match the BLE session")
        val active = reassembler ?: GattTransferReassembler(pending.sessionId, clientTransferId).also { reassembler = it }
        when (val result = active.add(chunk)) {
            GattTransferReassembler.AddResult.Accepted, GattTransferReassembler.AddResult.Duplicate -> Unit
            is GattTransferReassembler.AddResult.Rejected -> failClient("Face profile transfer was rejected")
            is GattTransferReassembler.AddResult.Complete -> finishClientTransfer(result.transportPayload)
        }
    }

    private fun finishClientTransfer(transportBytes: ByteArray) {
        val pending = pendingSession ?: return
        val encrypted = EncryptedEnrollmentPayload.fromTransportBytes(transportBytes) ?: return failClient("Face profile encryption payload was invalid")
        val secret = clientSecret ?: return failClient("Face profile key was unavailable")
        val plaintext = try {
            cipher.decrypt(secret, encrypted, EnrollmentAssociatedData.encode(pending.sessionId, clientTransferId)).getOrNull()
        } finally {
            encrypted.close()
            transportBytes.fill(0)
        } ?: return failClient("Face profile authentication failed")
        val enrollment = try { EnrollmentProfileCodec.decode(plaintext) } finally { plaintext.fill(0) }
            ?: return failClient("Face profile model was incompatible")
        val embedding = enrollment.embeddingCopy()
        val profile = try { SessionProfile(pending.sessionId, pending.consent, enrollment.modelId, embedding) } finally {
            embedding.fill(0f)
            enrollment.close()
        }
        onProfile(profile)
        receivedSessionIds += pending.sessionId
        onStatus("Nearby ${pending.consent.label} face profile received automatically")
        clearClient()
    }

    private fun failClient(message: String) {
        onStatus(message)
        clearClient()
    }

    private fun clearClient() {
        clientGatt?.disconnect()
        clientGatt?.close()
        clientGatt = null
        connectingAddress = null
        clientTransferId = 0u
        clientSecret?.fill(0)
        clientSecret = null
        reassembler = null
        pendingSession = null
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString(ConsentAdvertisementCodec.SERVICE_UUID)
        val CONTROL_UUID: UUID = UUID.fromString("83c3b46e-0aef-4a4b-8b9e-44865c70a932")
        val PROFILE_UUID: UUID = UUID.fromString("83c3b46e-0aef-4a4b-8b9e-44865c70a933")
        val KEY_UUID: UUID = UUID.fromString("83c3b46e-0aef-4a4b-8b9e-44865c70a934")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val MAX_PACKET_BYTES = 160
        private const val PREFERRED_MTU = 185
        private const val RETRY_DELAY_MS = 3_000L
        private const val TRANSFER_TIMEOUT_MS = 12_000L
    }
}
