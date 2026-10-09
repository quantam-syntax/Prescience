package com.consentcam.ble.enrollment

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

data class GattChunk(
    val sessionId: UInt,
    val transferId: UInt,
    val sequenceIndex: Int,
    val totalCount: Int,
    val payload: ByteArray,
    val finalDigest: ByteArray?,
) {
    val isFinal: Boolean
        get() = sequenceIndex == totalCount - 1
}

object GattChunkCodec {
    const val VERSION: UByte = 1u
    const val HEADER_SIZE_BYTES = 16
    const val DIGEST_SIZE_BYTES = 32
    const val MAX_CHUNKS = 4_096
    private const val FINAL_FLAG = 1

    sealed interface DecodeResult {
        data class Success(val chunk: GattChunk) : DecodeResult
        data class Invalid(val reason: String) : DecodeResult
    }

    fun chunk(
        sessionId: UInt,
        transferId: UInt,
        transportPayload: ByteArray,
        maximumPacketBytes: Int,
    ): List<GattChunk> {
        val payloadCapacity = minOf(
            maximumPacketBytes - HEADER_SIZE_BYTES - DIGEST_SIZE_BYTES,
            UShort.MAX_VALUE.toInt(),
        )
        require(payloadCapacity > 0) { "Packet size is too small for authenticated chunking" }
        val total = maxOf(
            1L,
            (transportPayload.size.toLong() + payloadCapacity - 1L) / payloadCapacity,
        )
        require(total <= MAX_CHUNKS) { "Transfer requires too many chunks" }
        val totalCount = total.toInt()
        val digest = sha256(transportPayload)
        return List(totalCount) { index ->
            val start = index * payloadCapacity
            val end = minOf(start + payloadCapacity, transportPayload.size)
            GattChunk(
                sessionId = sessionId,
                transferId = transferId,
                sequenceIndex = index,
                totalCount = totalCount,
                payload = transportPayload.copyOfRange(start, end),
                finalDigest = if (index == totalCount - 1) digest.copyOf() else null,
            )
        }.also { digest.fill(0) }
    }

    fun encode(chunk: GattChunk): ByteArray {
        validateFields(chunk)?.let { throw IllegalArgumentException(it) }
        val digestBytes = chunk.finalDigest?.size ?: 0
        return ByteBuffer.allocate(HEADER_SIZE_BYTES + chunk.payload.size + digestBytes)
            .order(ByteOrder.BIG_ENDIAN)
            .put(VERSION.toByte())
            .putInt(chunk.sessionId.toInt())
            .putInt(chunk.transferId.toInt())
            .putShort(chunk.sequenceIndex.toShort())
            .putShort(chunk.totalCount.toShort())
            .put(if (chunk.isFinal) FINAL_FLAG.toByte() else 0)
            .putShort(chunk.payload.size.toShort())
            .put(chunk.payload)
            .apply { chunk.finalDigest?.let(::put) }
            .array()
    }

    fun decode(packet: ByteArray): DecodeResult {
        if (packet.size < HEADER_SIZE_BYTES) return DecodeResult.Invalid("Packet too short")
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.get().toUByte()
        if (version != VERSION) return DecodeResult.Invalid("Unsupported chunk version")
        val sessionId = buffer.int.toUInt()
        val transferId = buffer.int.toUInt()
        val sequenceIndex = buffer.short.toUShort().toInt()
        val totalCount = buffer.short.toUShort().toInt()
        val flags = buffer.get().toInt() and 0xff
        if (flags and FINAL_FLAG.inv() != 0) return DecodeResult.Invalid("Unknown chunk flags")
        val payloadSize = buffer.short.toUShort().toInt()
        val isFinal = flags and FINAL_FLAG != 0
        val expectedSize = HEADER_SIZE_BYTES + payloadSize + if (isFinal) DIGEST_SIZE_BYTES else 0
        if (packet.size != expectedSize) return DecodeResult.Invalid("Packet length mismatch")
        val payload = ByteArray(payloadSize).also(buffer::get)
        val digest = if (isFinal) ByteArray(DIGEST_SIZE_BYTES).also(buffer::get) else null
        val chunk = GattChunk(sessionId, transferId, sequenceIndex, totalCount, payload, digest)
        return validateFields(chunk)?.let(DecodeResult::Invalid) ?: DecodeResult.Success(chunk)
    }

    internal fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    internal fun validateFields(chunk: GattChunk): String? = when {
        chunk.totalCount !in 1..MAX_CHUNKS -> "Invalid total chunk count"
        chunk.sequenceIndex !in 0 until chunk.totalCount -> "Invalid sequence index"
        chunk.payload.size > UShort.MAX_VALUE.toInt() -> "Chunk payload too large"
        chunk.isFinal && chunk.finalDigest?.size != DIGEST_SIZE_BYTES -> "Final digest missing or malformed"
        !chunk.isFinal && chunk.finalDigest != null -> "Digest is permitted only on final chunk"
        else -> null
    }
}

class GattTransferReassembler(
    private val expectedSessionId: UInt,
    private val expectedTransferId: UInt,
    private val maximumTransferBytes: Int = 4 * 1024 * 1024,
) : AutoCloseable {
    sealed interface AddResult {
        data object Accepted : AddResult
        data object Duplicate : AddResult
        data class Complete(val transportPayload: ByteArray) : AddResult
        data class Rejected(val reason: String) : AddResult
    }

    private val chunks = mutableMapOf<Int, ByteArray>()
    private var expectedTotal: Int? = null
    private var finalDigest: ByteArray? = null
    private var receivedBytes = 0
    private var terminal = false

    init {
        require(maximumTransferBytes > 0)
    }

    @Synchronized
    fun add(chunk: GattChunk): AddResult {
        if (terminal) return AddResult.Rejected("Transfer is already terminal")
        if (chunk.sessionId != expectedSessionId || chunk.transferId != expectedTransferId) {
            return fail("Chunk belongs to another transfer")
        }
        GattChunkCodec.validateFields(chunk)?.let { return fail(it) }
        val total = expectedTotal
        if (total != null && total != chunk.totalCount) return fail("Conflicting total count")
        expectedTotal = chunk.totalCount
        val existing = chunks[chunk.sequenceIndex]
        if (existing != null) {
            return if (existing.contentEquals(chunk.payload)) {
                AddResult.Duplicate
            } else {
                fail("Conflicting duplicate chunk")
            }
        }
        if (receivedBytes + chunk.payload.size > maximumTransferBytes) {
            return fail("Transfer exceeds size limit")
        }
        chunks[chunk.sequenceIndex] = chunk.payload.copyOf()
        receivedBytes += chunk.payload.size
        chunk.finalDigest?.let { digest -> finalDigest = digest.copyOf() }
        val expectedDigest = finalDigest
        if (chunks.size != chunk.totalCount || expectedDigest == null) return AddResult.Accepted

        val output = ByteArrayOutputStream(receivedBytes)
        for (index in 0 until chunk.totalCount) {
            val bytes = chunks[index] ?: return AddResult.Accepted
            output.write(bytes)
        }
        val assembled = output.toByteArray()
        val calculatedDigest = GattChunkCodec.sha256(assembled)
        if (!MessageDigest.isEqual(calculatedDigest, expectedDigest)) {
            calculatedDigest.fill(0)
            assembled.fill(0)
            return fail("Final payload digest mismatch")
        }
        calculatedDigest.fill(0)
        terminal = true
        clearBuffers()
        return AddResult.Complete(assembled)
    }

    @Synchronized
    override fun close() {
        terminal = true
        clearBuffers()
    }

    private fun fail(reason: String): AddResult.Rejected {
        terminal = true
        clearBuffers()
        return AddResult.Rejected(reason)
    }

    private fun clearBuffers() {
        chunks.values.forEach { it.fill(0) }
        chunks.clear()
        finalDigest?.fill(0)
        finalDigest = null
        receivedBytes = 0
    }
}
