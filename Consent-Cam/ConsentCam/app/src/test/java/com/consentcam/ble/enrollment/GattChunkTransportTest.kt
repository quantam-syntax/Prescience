package com.consentcam.ble.enrollment

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GattChunkTransportTest {
    private val payload = ByteArray(200) { it.toByte() }

    @Test
    fun chunkPacketsRespectUnknownMtuBudgetAndRoundTripCodec() {
        val chunks = GattChunkCodec.chunk(1u, 2u, payload, maximumPacketBytes = 64)

        assertTrue(chunks.size > 1)
        chunks.forEach { chunk ->
            val packet = GattChunkCodec.encode(chunk)
            assertTrue(packet.size <= 64)
            val decoded = GattChunkCodec.decode(packet)
            assertTrue(decoded is GattChunkCodec.DecodeResult.Success)
            val decodedChunk = (decoded as GattChunkCodec.DecodeResult.Success).chunk
            assertEquals(chunk.sessionId, decodedChunk.sessionId)
            assertEquals(chunk.transferId, decodedChunk.transferId)
            assertEquals(chunk.sequenceIndex, decodedChunk.sequenceIndex)
            assertEquals(chunk.totalCount, decodedChunk.totalCount)
            assertArrayEquals(chunk.payload, decodedChunk.payload)
            if (chunk.finalDigest == null) {
                assertEquals(null, decodedChunk.finalDigest)
            } else {
                assertArrayEquals(chunk.finalDigest, decodedChunk.finalDigest)
            }
        }
    }

    @Test
    fun reassemblerCompletesOutOfOrderTransferOnlyWhenEveryChunkArrives() {
        val chunks = GattChunkCodec.chunk(1u, 2u, payload, 64)
        val reassembler = GattTransferReassembler(1u, 2u)
        var result: GattTransferReassembler.AddResult = GattTransferReassembler.AddResult.Accepted

        chunks.reversed().forEach { result = reassembler.add(it) }

        assertTrue(result is GattTransferReassembler.AddResult.Complete)
        assertArrayEquals(
            payload,
            (result as GattTransferReassembler.AddResult.Complete).transportPayload,
        )
    }

    @Test
    fun interruptedTransferNeverProducesPartialPayload() {
        val chunks = GattChunkCodec.chunk(1u, 2u, payload, 64)
        val reassembler = GattTransferReassembler(1u, 2u)

        val result = reassembler.add(chunks.first())
        reassembler.close()

        assertEquals(GattTransferReassembler.AddResult.Accepted, result)
    }

    @Test
    fun finalDigestRejectsTamperedPayload() {
        val chunks = GattChunkCodec.chunk(1u, 2u, payload, 64).toMutableList()
        val original = chunks.first()
        chunks[0] = original.copy(payload = original.payload.copyOf().also { it[0] = 99 })
        val reassembler = GattTransferReassembler(1u, 2u)
        var result: GattTransferReassembler.AddResult = GattTransferReassembler.AddResult.Accepted

        chunks.forEach { result = reassembler.add(it) }

        assertTrue(result is GattTransferReassembler.AddResult.Rejected)
    }

    @Test
    fun conflictingDuplicateTerminatesTransfer() {
        val first = GattChunkCodec.chunk(1u, 2u, payload, 64).first()
        val reassembler = GattTransferReassembler(1u, 2u)
        reassembler.add(first)

        val result = reassembler.add(first.copy(payload = first.payload.copyOf().also { it[0]++ }))

        assertTrue(result is GattTransferReassembler.AddResult.Rejected)
    }

    @Test
    fun decodeRejectsTruncatedPacket() {
        assertTrue(GattChunkCodec.decode(ByteArray(5)) is GattChunkCodec.DecodeResult.Invalid)
    }
}
