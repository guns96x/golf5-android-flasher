package com.golf5.edc16flasher.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class Kwp2000ProtocolTest {

    private class ScriptedKwpTransport : KwpTransport {
        override val transportName: String = "scripted"
        override val isPhysical: Boolean = false
        override val isMpps: Boolean = false

        val writtenPackets = mutableListOf<ByteArray>()
        private val readChunks = ArrayDeque<ByteArray>()

        fun enqueueRead(chunk: ByteArray) {
            readChunks.addLast(chunk)
        }

        override fun write(data: ByteArray, timeoutMs: Int) {
            writtenPackets.add(data.copyOf())
        }

        override fun read(buffer: ByteArray, timeoutMs: Int): Int {
            if (readChunks.isEmpty()) return 0
            val chunk = readChunks.removeFirst()
            val toCopy = minOf(buffer.size, chunk.size)
            System.arraycopy(chunk, 0, buffer, 0, toCopy)
            if (chunk.size > toCopy) {
                // put remainder back
                readChunks.addFirst(chunk.copyOfRange(toCopy, chunk.size))
            }
            return toCopy
        }

        override fun getBatteryVoltage(): Float? = 13.5f
        override fun sendFastInit(pulseMs: Int, initialPayload: ByteArray) = Unit
    }

    private fun buildFrame(target: Int, source: Int, sid: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val dataLen = 1 + payload.size
        val headerLen = if (dataLen <= 63) 3 else 4
        val total = headerLen + dataLen + 1
        val frame = ByteArray(total)
        if (dataLen <= 63) {
            frame[0] = (0x80 or dataLen).toByte()
            frame[1] = target.toByte()
            frame[2] = source.toByte()
            frame[3] = sid.toByte()
            System.arraycopy(payload, 0, frame, 4, payload.size)
        } else {
            frame[0] = 0x80.toByte()
            frame[1] = target.toByte()
            frame[2] = source.toByte()
            frame[3] = dataLen.toByte()
            frame[4] = sid.toByte()
            System.arraycopy(payload, 0, frame, 5, payload.size)
        }
        frame[total - 1] = KwpFrameCodec.computeChecksum(frame, total - 1)
        return frame
    }

    @Test
    fun nrc78ThenPositiveResponseReturnsPositiveFrame() {
        val transport = ScriptedKwpTransport()
        val protocol = Kwp2000Protocol(transport)

        // Request: SID 0x10, Subfunction 0x85 -> Expected positive SID 0x50
        // Enqueue 1: NRC 0x78 (Response Pending) for SID 0x10 -> Target 0xF1, Source 0x01, SID 0x7F, Payload [0x10, 0x78]
        val nrc78Frame = buildFrame(0xF1, 0x01, 0x7F, byteArrayOf(0x10.toByte(), 0x78.toByte()))
        // Enqueue 2: Positive response 0x50, Payload [0x85]
        val posFrame = buildFrame(0xF1, 0x01, 0x50, byteArrayOf(0x85.toByte()))

        transport.enqueueRead(nrc78Frame)
        transport.enqueueRead(posFrame)

        val result = protocol.sendRequest(0x10, byteArrayOf(0x85.toByte()), timeoutMs = 2000)
        assertEquals(0x50, result.serviceId)
        assertArrayEquals(byteArrayOf(0x85.toByte()), result.payload)
        // Ensure request was written exactly once (no retransmission after NRC 0x78)
        assertEquals(1, transport.writtenPackets.size)
    }

    @Test
    fun nonPendingNrcThrowsTypedException() {
        val transport = ScriptedKwpTransport()
        val protocol = Kwp2000Protocol(transport)

        // Negative response NRC 0x22 (Conditions Not Correct) for SID 0x27
        val nrc22Frame = buildFrame(0xF1, 0x01, 0x7F, byteArrayOf(0x27.toByte(), 0x22.toByte()))
        transport.enqueueRead(nrc22Frame)

        val exc = assertThrows(KwpNegativeResponseException::class.java) {
            protocol.sendRequest(0x27, byteArrayOf(0x01.toByte()), timeoutMs = 1000)
        }
        assertEquals(0x27, exc.failedSid)
        assertEquals(0x22, exc.nrc)
    }

    @Test
    fun timeoutIncludesLastParserFailure() {
        val transport = ScriptedKwpTransport()
        val protocol = Kwp2000Protocol(transport)

        // Enqueue malformed bytes (bad format byte)
        transport.enqueueRead(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04))

        val exc = assertThrows(IOException::class.java) {
            protocol.sendRequest(0x10, ByteArray(0), timeoutMs = 300)
        }
        assertTrue(exc.message?.contains("Timeout") == true || exc.message?.contains("0x10") == true)
    }

    @Test
    fun partialReadsAreAccumulatedUntilOneFrameIsComplete() {
        val transport = ScriptedKwpTransport()
        val protocol = Kwp2000Protocol(transport)

        val fullFrame = buildFrame(0xF1, 0x01, 0x5A, byteArrayOf(0x9B.toByte(), 0x01.toByte()))
        // Split fullFrame into two chunks
        val half = fullFrame.size / 2
        val chunk1 = fullFrame.copyOfRange(0, half)
        val chunk2 = fullFrame.copyOfRange(half, fullFrame.size)

        transport.enqueueRead(chunk1)
        transport.enqueueRead(chunk2)

        val result = protocol.sendRequest(0x1A, byteArrayOf(0x9B.toByte()), timeoutMs = 2000)
        assertEquals(0x5A, result.serviceId)
        assertArrayEquals(byteArrayOf(0x9B.toByte(), 0x01.toByte()), result.payload)
    }
}
