package com.golf5.edc16flasher.protocol

import java.io.IOException

/**
 * Mobile MPPS v18 Protocol Engine
 * Full KWP2000 (ISO 14230-4) Implementation for Bosch EDC16U34:
 * - Identification (0x1A 0x9B / 0x21 0x80)
 * - Security Access (0x27 0x01 / 0x27 0x02)
 * - Calibration Read / Upload (0x35 RequestUpload & 0x36 TransferData)
 * - Calibration Write / Download (0x34 RequestDownload & 0x36 TransferData)
 * - Recovery Mode Fast Programming Session (0x10 0x85)
 * - Diagnostic Trouble Code Clearing (0x14 ClearDTC)
 * - K-Line Half-Duplex Echo Filtering & NRC 0x78 Response Pending Handling
 */
class Kwp2000Protocol(
    private val transport: KwpTransport,
    private val log: (String) -> Unit = {},
) {

    private val targetAddress: Byte = 0x01.toByte() // Engine ECU
    private val sourceAddress: Byte = 0xF1.toByte() // Diagnostic Tool

    fun sendRequest(serviceId: Int, payload: ByteArray = ByteArray(0), timeoutMs: Long = 6000L): KwpFrame {
        val txBytes = KwpFrameCodec.encodeRequest(
            serviceId = serviceId,
            payload = payload,
            target = targetAddress.toInt() and 0xFF,
            source = sourceAddress.toInt() and 0xFF
        )

        transport.write(txBytes, 2000)

        val startTime = System.currentTimeMillis()
        var rxBuffer = ByteArray(0)
        var lastParserError: String? = null
        val expectedPositiveSid = (serviceId and 0xFF) + 0x40

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val chunk = ByteArray(512)
            val readCount = try {
                transport.read(chunk, 2500)
            } catch (e: Exception) {
                0
            }
            if (readCount > 0) {
                rxBuffer += chunk.copyOfRange(0, readCount)
                rxBuffer = KwpFrameCodec.stripLeadingEcho(rxBuffer, txBytes)
            }

            if (rxBuffer.isNotEmpty()) {
                try {
                    val (frame, remaining) = KwpFrameCodec.extractFrame(
                        rxBuffer,
                        expectedTarget = sourceAddress.toInt() and 0xFF,
                        expectedSource = targetAddress.toInt() and 0xFF
                    )
                    if (frame != null) {
                        rxBuffer = remaining
                        if (frame.serviceId == 0x7F) {
                            if (frame.payload.size >= 2) {
                                val failedSid = frame.payload[0].toInt() and 0xFF
                                val nrc = frame.payload[1].toInt() and 0xFF
                                if (failedSid == (serviceId and 0xFF) && nrc == 0x78) {
                                    log("NRC 0x78 Response Pending for SID 0x%02X, waiting...".format(failedSid))
                                    Thread.sleep(50)
                                    continue
                                }
                                throw KwpNegativeResponseException(failedSid, nrc)
                            } else {
                                throw KwpFrameException("Malformed negative response: payload too short")
                            }
                        }

                        if (frame.serviceId == expectedPositiveSid) {
                            return frame
                        } else {
                            log("Ignoring response SID 0x%02X (expected 0x%02X)".format(frame.serviceId, expectedPositiveSid))
                        }
                    }
                } catch (e: KwpNegativeResponseException) {
                    throw e
                } catch (e: KwpFrameException) {
                    lastParserError = e.message
                    if (rxBuffer.isNotEmpty()) {
                        rxBuffer = rxBuffer.copyOfRange(1, rxBuffer.size)
                    }
                }
            }

            try { Thread.sleep(15) } catch (ignored: InterruptedException) {}
        }

        val details = if (lastParserError != null) " (last parser error: $lastParserError)" else ""
        throw IOException("Timeout waiting for positive response to SID 0x%02X after ${timeoutMs}ms$details".format(serviceId))
    }

    fun sendRequest(serviceId: Byte, payload: ByteArray = ByteArray(0), timeoutMs: Long = 6000L): KwpFrame {
        return sendRequest(serviceId.toInt() and 0xFF, payload, timeoutMs)
    }

    fun initKwpSession(): Boolean {
        log("Attempting KWP2000 Fast Init: Physical Engine 0x01 (81 01 F1 81 F4)...")
        val reqPhysical = byteArrayOf(0x81.toByte(), 0x01.toByte(), 0xF1.toByte(), 0x81.toByte(), 0xF4.toByte())
        transport.sendFastInit(25, reqPhysical)

        val buf = ByteArray(256)
        var count = try { transport.read(buf, 2000) } catch (e: Exception) { 0 }
        if (count > 0) {
            val hex = buf.copyOfRange(0, count).joinToString(" ") { String.format("%02X", it) }
            log("Fast Init (Physical 0x01) Response ($count bytes): $hex")
            if (buf.copyOfRange(0, count).any { (it.toInt() and 0xFF) == 0xC1 }) {
                log("Fast Init SUCCESS on Physical 0x01 (SID 0xC1 acknowledged)!")
                return true
            }
        }

        log("Attempting KWP2000 Fast Init: Functional 0x33 (C1 33 F1 81 66)...")
        try { Thread.sleep(500) } catch (ignored: InterruptedException) {}
        val reqFunctional = byteArrayOf(0xC1.toByte(), 0x33.toByte(), 0xF1.toByte(), 0x81.toByte(), 0x66.toByte())
        transport.sendFastInit(25, reqFunctional)

        count = try { transport.read(buf, 2000) } catch (e: Exception) { 0 }
        if (count > 0) {
            val hex = buf.copyOfRange(0, count).joinToString(" ") { String.format("%02X", it) }
            log("Fast Init (Functional 0x33) Response ($count bytes): $hex")
            if (buf.copyOfRange(0, count).any { (it.toInt() and 0xFF) == 0xC1 }) {
                log("Fast Init SUCCESS on Functional 0x33 (SID 0xC1 acknowledged)!")
                return true
            }
        }

        log("No positive 0xC1 response to Fast Init on K-Line (ECU may require CAN/TP2.0)")
        return false
    }

    fun startDiagnosticSession(sessionType: Byte = 0x85.toByte()): Boolean {
        val frame = sendRequest(0x10.toByte(), byteArrayOf(sessionType))
        return frame.serviceId == 0x50
    }

    fun forceRecoverySession(): Boolean {
        initKwpSession()
        try { Thread.sleep(100) } catch (ignored: Exception) {}
        return startDiagnosticSession(0x85.toByte())
    }

    fun readEcuIdentification(): String {
        initKwpSession()
        try { Thread.sleep(50) } catch (ignored: Exception) {}

        try {
            startDiagnosticSession(0x81.toByte())
        } catch (e: Exception) {
            try {
                startDiagnosticSession(0x85.toByte())
            } catch (ignored: Exception) {}
        }

        val frame = try {
            sendRequest(0x1A.toByte(), byteArrayOf(0x9B.toByte()))
        } catch (e1: Exception) {
            try {
                sendRequest(0x1A.toByte(), byteArrayOf(0x90.toByte()))
            } catch (e2: Exception) {
                sendRequest(0x21.toByte(), byteArrayOf(0x80.toByte()))
            }
        }
        return String(frame.payload.filter { it in 32..126 }.toByteArray())
    }

    fun requestSecuritySeed(): ByteArray {
        val frame = sendRequest(0x27.toByte(), byteArrayOf(0x01.toByte()))
        if (frame.serviceId != 0x67) {
            throw IOException("SecurityAccess 0x27 rejected: expected 0x67, got 0x%02X".format(frame.serviceId))
        }
        if (frame.payload.size >= 5 && (frame.payload[0].toInt() and 0xFF) == 0x01) {
            return frame.payload.copyOfRange(1, 5)
        }
        if (frame.payload.size >= 4) {
            return frame.payload.copyOfRange(frame.payload.size - 4, frame.payload.size)
        }
        throw IOException("Could not extract valid 4-byte seed from response (0x67 0x01)")
    }

    fun sendSecurityKey(key: ByteArray): Boolean {
        val payload = byteArrayOf(0x02.toByte()) + key
        val frame = sendRequest(0x27.toByte(), payload)
        return frame.serviceId == 0x67
    }

    fun requestDownload(startAddress: Int, uncompressedSize: Int): Boolean {
        require(startAddress in 0x180000..0x1FFFFF) { "Security violation: Start address out of bounds" }
        require(uncompressedSize in 1..0x080000) { "Download size exceeds EDC16 calibration sector" }

        val payload = byteArrayOf(
            0x00,
            ((startAddress shr 16) and 0xFF).toByte(),
            ((startAddress shr 8) and 0xFF).toByte(),
            (startAddress and 0xFF).toByte(),
            ((uncompressedSize shr 16) and 0xFF).toByte(),
            ((uncompressedSize shr 8) and 0xFF).toByte(),
            (uncompressedSize and 0xFF).toByte()
        )
        val frame = sendRequest(0x34.toByte(), payload)
        return frame.serviceId == 0x74
    }

    fun requestUpload(startAddress: Int, uncompressedSize: Int): Int {
        val payload = byteArrayOf(
            0x00,
            ((startAddress shr 16) and 0xFF).toByte(),
            ((startAddress shr 8) and 0xFF).toByte(),
            (startAddress and 0xFF).toByte(),
            ((uncompressedSize shr 16) and 0xFF).toByte(),
            ((uncompressedSize shr 8) and 0xFF).toByte(),
            (uncompressedSize and 0xFF).toByte()
        )
        val frame = sendRequest(0x35.toByte(), payload)
        if (frame.serviceId != 0x75) {
            throw IOException("RequestUpload 0x35 rejected: no positive 0x75 response from ECU")
        }

        val payloadBytes = frame.payload
        var blockSize = 128
        if (payloadBytes.size >= 2) {
            val msb = payloadBytes[0].toInt() and 0xFF
            val lsb = payloadBytes[1].toInt() and 0xFF
            val advertised = (msb shl 8) or lsb
            if (advertised in 32..512) {
                blockSize = advertised
            }
        } else if (payloadBytes.size == 1) {
            val len = payloadBytes[0].toInt() and 0xFF
            if (len in 32..255) {
                blockSize = len
            }
        }
        return blockSize
    }

    fun transferData(blockSeq: Byte, data: ByteArray): Boolean {
        require(data.size <= 128) { "Data block size exceeds safe 128-byte limit" }
        val payload = byteArrayOf(blockSeq) + data
        val frame = sendRequest(0x36.toByte(), payload)
        return frame.serviceId == 0x76
    }

    fun readMemoryChunk(blockSeq: Byte, expectedSize: Int = 128): ByteArray {
        val payload = byteArrayOf(blockSeq)
        val frame = sendRequest(0x36.toByte(), payload)
        if (frame.serviceId != 0x76) {
            throw IOException(String.format("Failed to read block 0x%02X: Invalid or missing 0x76 response", blockSeq))
        }
        val chunk = if (frame.payload.isNotEmpty() && frame.payload[0] == blockSeq) {
            frame.payload.copyOfRange(1, frame.payload.size)
        } else {
            frame.payload
        }
        if (chunk.isEmpty()) {
            throw IOException(String.format("Failed to read block 0x%02X: Empty data returned by ECU", blockSeq))
        }
        return chunk
    }

    fun requestTransferExit(): Boolean {
        val frame = sendRequest(0x37.toByte())
        return frame.serviceId == 0x77
    }

    fun clearDiagnosticTroubleCodes(): Boolean {
        return try {
            val frame = sendRequest(0x14.toByte(), byteArrayOf(0xFF.toByte(), 0x00.toByte()))
            frame.serviceId == 0x54
        } catch (e: Exception) {
            false
        }
    }

    /**
     * ECU Reset (SID 0x11, ResetType 0x01)
     * Returns true only if ECU acknowledged the reset with positive response 0x51.
     *
     * CRITICAL FIX (C4): Do NOT return true on exception — that would hide reset failures
     * and leave ECU in diagnostic session, potentially causing no-start condition.
     */
    fun ecuReset(): Boolean {
        return try {
            val frame = sendRequest(0x11.toByte(), byteArrayOf(0x01.toByte()), timeoutMs = 2000L)
            frame.serviceId == 0x51
        } catch (e: Exception) {
            log("ECU Reset failed: ${e.message}")
            false  // ← Return false, NOT true!
        }
    }

    companion object {
        private const val TAG = "EDC16_KWP"
    }
}
