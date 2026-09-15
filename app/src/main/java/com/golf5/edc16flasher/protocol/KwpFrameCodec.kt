package com.golf5.edc16flasher.protocol

object KwpFrameCodec {

    fun computeChecksum(bytes: ByteArray, length: Int = bytes.size): Byte {
        var sum = 0
        for (i in 0 until length) {
            sum = (sum + (bytes[i].toInt() and 0xFF)) and 0xFF
        }
        return sum.toByte()
    }

    fun encodeRequest(
        serviceId: Int,
        payload: ByteArray = ByteArray(0),
        target: Int = 0x01,
        source: Int = 0xF1,
    ): ByteArray {
        val dataLen = 1 + payload.size
        if (dataLen > 255) {
            throw IllegalArgumentException("Payload exceeds ISO 14230 single-byte length limit ($dataLen > 255)")
        }

        val headerLen = if (dataLen <= 63) 3 else 4
        val totalLen = headerLen + dataLen + 1
        val frame = ByteArray(totalLen)

        if (dataLen <= 63) {
            frame[0] = (0x80 or dataLen).toByte()
            frame[1] = (target and 0xFF).toByte()
            frame[2] = (source and 0xFF).toByte()
            frame[3] = (serviceId and 0xFF).toByte()
            System.arraycopy(payload, 0, frame, 4, payload.size)
        } else {
            frame[0] = 0x80.toByte()
            frame[1] = (target and 0xFF).toByte()
            frame[2] = (source and 0xFF).toByte()
            frame[3] = (dataLen and 0xFF).toByte()
            frame[4] = (serviceId and 0xFF).toByte()
            System.arraycopy(payload, 0, frame, 5, payload.size)
        }

        frame[totalLen - 1] = computeChecksum(frame, totalLen - 1)
        return frame
    }

    fun parseResponse(
        bytes: ByteArray,
        expectedTarget: Int = 0xF1,
        expectedSource: Int = 0x01,
    ): KwpFrame {
        if (bytes.size < 5) {
            throw KwpFrameException("Frame too short: ${bytes.size} bytes (minimum 5)")
        }

        val fmt = bytes[0].toInt() and 0xFF
        if ((fmt and 0xC0) != 0x80) {
            throw KwpFrameException("Invalid KWP format byte: 0x%02X".format(fmt))
        }

        val headerLen: Int
        val dataLen: Int
        if ((fmt and 0x3F) != 0) {
            dataLen = fmt and 0x3F
            headerLen = 3
        } else {
            if (bytes.size < 6) {
                throw KwpFrameException("Long frame too short: ${bytes.size} bytes (minimum 6)")
            }
            dataLen = bytes[3].toInt() and 0xFF
            headerLen = 4
        }

        val target = bytes[1].toInt() and 0xFF
        val source = bytes[2].toInt() and 0xFF

        if (target != expectedTarget || source != expectedSource) {
            throw KwpFrameException(
                "Unexpected target/source: target=0x%02X (expected 0x%02X), source=0x%02X (expected 0x%02X)"
                    .format(target, expectedTarget, source, expectedSource)
            )
        }

        val expectedTotalLen = headerLen + dataLen + 1
        if (bytes.size != expectedTotalLen) {
            throw KwpFrameException(
                "Frame length mismatch: expected $expectedTotalLen bytes, got ${bytes.size}"
            )
        }

        val expectedChecksum = computeChecksum(bytes, bytes.size - 1)
        val actualChecksum = bytes[bytes.size - 1]
        if (actualChecksum != expectedChecksum) {
            throw KwpFrameException(
                "Checksum mismatch: expected 0x%02X, got 0x%02X"
                    .format(expectedChecksum.toInt() and 0xFF, actualChecksum.toInt() and 0xFF)
            )
        }

        if (dataLen < 1) {
            throw KwpFrameException("dataLen must be at least 1 for serviceId")
        }

        val serviceId = bytes[headerLen].toInt() and 0xFF
        val payload = bytes.copyOfRange(headerLen + 1, headerLen + dataLen)
        return KwpFrame(target, source, serviceId, payload, bytes)
    }

    fun stripLeadingEcho(buffer: ByteArray, txBytes: ByteArray): ByteArray {
        if (txBytes.isEmpty() || buffer.size < txBytes.size) {
            return buffer
        }
        for (i in txBytes.indices) {
            if (buffer[i] != txBytes[i]) {
                return buffer
            }
        }
        return buffer.copyOfRange(txBytes.size, buffer.size)
    }

    /**
     * Attempts to extract one complete valid KWP frame from the start of [buffer].
     * Returns Pair(frame, remainingBytes) if a complete frame was parsed.
     * Returns Pair(null, buffer) if more bytes are needed or not enough header bytes.
     */
    fun extractFrame(
        buffer: ByteArray,
        expectedTarget: Int = 0xF1,
        expectedSource: Int = 0x01,
    ): Pair<KwpFrame?, ByteArray> {
        if (buffer.size < 5) return Pair(null, buffer)

        val fmt = buffer[0].toInt() and 0xFF
        if ((fmt and 0xC0) != 0x80) {
            // Unrecognized start byte; discard single invalid byte and try to synchronize
            return Pair(null, buffer)
        }

        val headerLen = if ((fmt and 0x3F) != 0) 3 else 4
        if (buffer.size < headerLen + 1) return Pair(null, buffer)

        val dataLen = if (headerLen == 3) (fmt and 0x3F) else (buffer[3].toInt() and 0xFF)
        val totalLen = headerLen + dataLen + 1

        if (buffer.size < totalLen) {
            // Frame is still incomplete in buffer; wait for more bytes
            return Pair(null, buffer)
        }

        val candidate = buffer.copyOfRange(0, totalLen)
        val frame = parseResponse(candidate, expectedTarget, expectedSource)
        val remaining = buffer.copyOfRange(totalLen, buffer.size)
        return Pair(frame, remaining)
    }
}
