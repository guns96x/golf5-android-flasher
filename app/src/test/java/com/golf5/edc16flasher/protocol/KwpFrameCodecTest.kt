package com.golf5.edc16flasher.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class KwpFrameCodecTest {

    private fun computeChecksum(bytes: ByteArray): Byte {
        var sum = 0
        for (b in bytes) {
            sum = (sum + (b.toInt() and 0xFF)) and 0xFF
        }
        return sum.toByte()
    }

    @Test
    fun parsesShortPositiveFrame() {
        // Target 0xF1, Source 0x01, SID 0x5A, Payload [0x9B, 0x31, 0x32] -> dataLen = 4
        // Header: (0x80 or 4) = 0x84, 0xF1, 0x01
        val body = byteArrayOf(
            0x84.toByte(), 0xF1.toByte(), 0x01.toByte(),
            0x5A.toByte(), 0x9B.toByte(), 0x31.toByte(), 0x32.toByte()
        )
        val cs = computeChecksum(body)
        val frameBytes = body + byteArrayOf(cs)

        val frame = KwpFrameCodec.parseResponse(frameBytes)
        assertEquals(0xF1, frame.target)
        assertEquals(0x01, frame.source)
        assertEquals(0x5A, frame.serviceId)
        assertArrayEquals(byteArrayOf(0x9B.toByte(), 0x31.toByte(), 0x32.toByte()), frame.payload)
    }

    @Test
    fun parsesLongPositiveFrame() {
        // dataLen = 65 (SID + 64 payload bytes)
        val payload = ByteArray(64) { (it + 1).toByte() }
        val body = ByteArray(4 + 1 + 64)
        body[0] = 0x80.toByte()
        body[1] = 0xF1.toByte()
        body[2] = 0x01.toByte()
        body[3] = 65.toByte() // dataLen
        body[4] = 0x76.toByte() // SID (TransferData positive response)
        System.arraycopy(payload, 0, body, 5, 64)
        val cs = computeChecksum(body)
        val frameBytes = body + byteArrayOf(cs)

        val frame = KwpFrameCodec.parseResponse(frameBytes)
        assertEquals(0xF1, frame.target)
        assertEquals(0x01, frame.source)
        assertEquals(0x76, frame.serviceId)
        assertArrayEquals(payload, frame.payload)
    }

    @Test
    fun rejectsBadChecksum() {
        val body = byteArrayOf(
            0x84.toByte(), 0xF1.toByte(), 0x01.toByte(),
            0x5A.toByte(), 0x9B.toByte(), 0x31.toByte(), 0x32.toByte()
        )
        val badChecksum = 0x00.toByte()
        val frameBytes = body + byteArrayOf(badChecksum)

        assertThrows(KwpFrameException::class.java) {
            KwpFrameCodec.parseResponse(frameBytes)
        }
    }

    @Test
    fun rejectsDeclaredLengthMismatch() {
        // Header declares length 4, but payload provides fewer bytes
        val body = byteArrayOf(
            0x84.toByte(), 0xF1.toByte(), 0x01.toByte(),
            0x5A.toByte(), 0x9B.toByte() // missing 2 bytes
        )
        val cs = computeChecksum(body)
        val frameBytes = body + byteArrayOf(cs)

        assertThrows(KwpFrameException::class.java) {
            KwpFrameCodec.parseResponse(frameBytes)
        }
    }

    @Test
    fun preservesPayloadBytesContaining0x7f() {
        // Positive response 0x5A with payload containing 0x7F (which naive parsers misidentify as negative response)
        val body = byteArrayOf(
            0x84.toByte(), 0xF1.toByte(), 0x01.toByte(),
            0x5A.toByte(), 0x7F.toByte(), 0x00.toByte(), 0x12.toByte()
        )
        val cs = computeChecksum(body)
        val frameBytes = body + byteArrayOf(cs)

        val frame = KwpFrameCodec.parseResponse(frameBytes)
        assertEquals(0x5A, frame.serviceId)
        assertArrayEquals(byteArrayOf(0x7F.toByte(), 0x00.toByte(), 0x12.toByte()), frame.payload)
    }

    @Test
    fun removesOnlyExactLeadingEcho() {
        val tx = byteArrayOf(0x81.toByte(), 0x01.toByte(), 0xF1.toByte(), 0x10.toByte(), 0x83.toByte())
        val rx = byteArrayOf(0x81.toByte(), 0xF1.toByte(), 0x01.toByte(), 0x50.toByte(), 0xC3.toByte())

        // Buffer with exact echo followed by response
        val bufferWithEcho = tx + rx
        val stripped = KwpFrameCodec.stripLeadingEcho(bufferWithEcho, tx)
        assertArrayEquals(rx, stripped)

        // Buffer without matching echo should NOT be stripped
        val unrelatedBuffer = byteArrayOf(0x01, 0x02) + rx
        val notStripped = KwpFrameCodec.stripLeadingEcho(unrelatedBuffer, tx)
        assertArrayEquals(unrelatedBuffer, notStripped)
    }
}
