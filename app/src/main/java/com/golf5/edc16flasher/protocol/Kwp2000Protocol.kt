package com.golf5.edc16flasher.protocol

import com.golf5.edc16flasher.usb.UsbSerialManager
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
class Kwp2000Protocol(private val usb: UsbSerialManager) {

    private val targetAddress: Byte = 0x01.toByte() // Engine ECU
    private val sourceAddress: Byte = 0xF1.toByte() // Diagnostic Tool

    fun sendRequest(serviceId: Byte, payload: ByteArray = ByteArray(0), timeoutMs: Long = 6000L): ByteArray {
        val dataLen = 1 + payload.size
        if (dataLen > 255) {
            throw IllegalArgumentException("Payload exceeds ISO 14230 single-byte length limit ($dataLen > 255)")
        }

        val packet = ArrayList<Byte>()
        if (dataLen <= 63) {
            packet.add((0x80 or dataLen).toByte())
            packet.add(targetAddress)
            packet.add(sourceAddress)
        } else {
            packet.add(0x80.toByte())
            packet.add(targetAddress)
            packet.add(sourceAddress)
            packet.add(dataLen.toByte())
        }
        packet.add(serviceId)
        for (b in payload) {
            packet.add(b)
        }

        // 8-bit checksum
        var checksum: Byte = 0
        for (b in packet) {
            checksum = (checksum + b).toByte()
        }
        packet.add(checksum)

        val txBytes = packet.toByteArray()
        usb.write(txBytes, 2000)

        // Read and parse response with NRC 0x78 (Response Pending) loop
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val rxBytes = readFrame(txBytes)
            if (rxBytes.isNotEmpty()) {
                val expectedPositiveSid = ((serviceId.toInt() and 0xFF) + 0x40).toByte()
                val isNegative = rxBytes.any { it == 0x7F.toByte() }

                if (isNegative) {
                    val idx = rxBytes.indexOf(0x7F.toByte())
                    if (idx != -1 && idx + 2 < rxBytes.size) {
                        val failedSid = rxBytes[idx + 1]
                        val nrc = rxBytes[idx + 2]
                        if (failedSid == serviceId && (nrc.toInt() and 0xFF) == 0x78) {
                            // ECU busy (erasing flash / calculating), continue waiting
                            Thread.sleep(50)
                            continue
                        } else if (failedSid == serviceId) {
                            throw IOException(String.format("ECU Negative Response: SID 0x%02X NRC 0x%02X", serviceId, nrc))
                        }
                    }
                }

                val hasPositiveSid = rxBytes.any { it == expectedPositiveSid }
                if (hasPositiveSid) {
                    return rxBytes
                }
            }
            Thread.sleep(15)
        }

        throw IOException(String.format("Timeout waiting for positive response to SID 0x%02X", serviceId))
    }

    private fun readFrame(txEchoToDiscard: ByteArray): ByteArray {
        val buffer = ByteArray(512)
        val readCount = try {
            usb.read(buffer, 2500)
        } catch (e: Exception) {
            0
        }
        if (readCount <= 0) return ByteArray(0)

        var raw = buffer.copyOfRange(0, readCount)
        val hexStr = raw.joinToString(" ") { String.format("%02X", it) }
        android.util.Log.d("EDC16_KWP", "RX ($readCount bytes): $hexStr")

        // Filter out K-Line local echo if present
        val echoIdx = indexOfSubarray(raw, txEchoToDiscard)
        if (echoIdx != -1) {
            val afterEcho = ByteArray(raw.size - txEchoToDiscard.size)
            System.arraycopy(raw, 0, afterEcho, 0, echoIdx)
            System.arraycopy(raw, echoIdx + txEchoToDiscard.size, afterEcho, echoIdx, raw.size - (echoIdx + txEchoToDiscard.size))
            raw = afterEcho
        }

        return raw
    }

    private fun indexOfSubarray(outer: ByteArray, target: ByteArray): Int {
        if (target.isEmpty() || outer.size < target.size) return -1
        for (i in 0..outer.size - target.size) {
            var found = true
            for (j in target.indices) {
                if (outer[i + j] != target[j]) {
                    found = false
                    break
                }
            }
            if (found) return i
        }
        return -1
    }

    fun initKwpSession(): Boolean {
        android.util.Log.i(TAG, "Attempting KWP2000 Fast Init: Physical Engine 0x01 (81 01 F1 81 F4)...")
        val reqPhysical = byteArrayOf(0x81.toByte(), 0x01.toByte(), 0xF1.toByte(), 0x81.toByte(), 0xF4.toByte())
        usb.sendFastInit(25, reqPhysical)

        val buf = ByteArray(256)
        var count = try { usb.read(buf, 2000) } catch (e: Exception) { 0 }
        if (count > 0) {
            val hex = buf.copyOfRange(0, count).joinToString(" ") { String.format("%02X", it) }
            android.util.Log.i(TAG, "Fast Init (Physical 0x01) Response ($count bytes): $hex")
            if (buf.copyOfRange(0, count).any { (it.toInt() and 0xFF) == 0xC1 }) {
                android.util.Log.i(TAG, "Fast Init SUCCESS on Physical 0x01 (SID 0xC1 acknowledged)!")
                return true
            }
        }

        // Secondary attempt: Functional OBD (Target 0x33)
        android.util.Log.i(TAG, "Attempting KWP2000 Fast Init: Functional 0x33 (C1 33 F1 81 66)...")
        try { Thread.sleep(500) } catch (ignored: InterruptedException) {}
        val reqFunctional = byteArrayOf(0xC1.toByte(), 0x33.toByte(), 0xF1.toByte(), 0x81.toByte(), 0x66.toByte())
        usb.sendFastInit(25, reqFunctional)

        count = try { usb.read(buf, 2000) } catch (e: Exception) { 0 }
        if (count > 0) {
            val hex = buf.copyOfRange(0, count).joinToString(" ") { String.format("%02X", it) }
            android.util.Log.i(TAG, "Fast Init (Functional 0x33) Response ($count bytes): $hex")
            if (buf.copyOfRange(0, count).any { (it.toInt() and 0xFF) == 0xC1 }) {
                android.util.Log.i(TAG, "Fast Init SUCCESS on Functional 0x33 (SID 0xC1 acknowledged)!")
                return true
            }
        }

        android.util.Log.w(TAG, "No positive 0xC1 response to Fast Init on K-Line (ECU may require CAN/TP2.0)")
        return false
    }

    fun startDiagnosticSession(sessionType: Byte = 0x85.toByte()): Boolean {
        val resp = sendRequest(0x10.toByte(), byteArrayOf(sessionType))
        return resp.isNotEmpty()
    }

    fun forceRecoverySession(): Boolean {
        // Send hardware fast-init pulse on K-Line to recover interrupted ECU session
        initKwpSession()
        try { Thread.sleep(100) } catch (ignored: Exception) {}
        return startDiagnosticSession(0x85.toByte())
    }

    fun readEcuIdentification(): String {
        // Step 1: Complete ISO 14230 Fast Init with embedded StartCommunication
        initKwpSession()
        try { Thread.sleep(50) } catch (ignored: Exception) {}

        // Step 2: Establish diagnostic session (default 0x81 first, then 0x85)
        try {
            startDiagnosticSession(0x81.toByte())
        } catch (e: Exception) {
            try {
                startDiagnosticSession(0x85.toByte())
            } catch (ignored: Exception) {}
        }

        // Step 3: Query identification
        val resp = try {
            sendRequest(0x1A.toByte(), byteArrayOf(0x9B.toByte()))
        } catch (e1: Exception) {
            try {
                sendRequest(0x1A.toByte(), byteArrayOf(0x90.toByte()))
            } catch (e2: Exception) {
                sendRequest(0x21.toByte(), byteArrayOf(0x80.toByte()))
            }
        }
        return String(resp.filter { it in 32..126 }.toByteArray())
    }

    fun requestSecuritySeed(): ByteArray {
        val resp = sendRequest(0x27.toByte(), byteArrayOf(0x01.toByte()))
        val idx = resp.indexOf(0x67.toByte())
        if (idx != -1 && idx + 5 <= resp.size) {
            return resp.copyOfRange(idx + 2, idx + 6)
        }
        throw IOException("Could not extract valid 4-byte seed from response (0x67 0x01)")
    }

    fun sendSecurityKey(key: ByteArray): Boolean {
        val payload = byteArrayOf(0x02.toByte()) + key
        val resp = sendRequest(0x27.toByte(), payload)
        return resp.isNotEmpty()
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
        val resp = sendRequest(0x34.toByte(), payload)
        return resp.isNotEmpty()
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
        val resp = sendRequest(0x35.toByte(), payload)
        val idx = resp.indexOf(0x75.toByte())
        if (idx == -1) {
            throw IOException("RequestUpload 0x35 rejected: no positive 0x75 response from ECU")
        }
        
        val payloadBytes = resp.copyOfRange(idx + 1, resp.size - 1)
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
        val resp = sendRequest(0x36.toByte(), payload)
        return resp.isNotEmpty()
    }

    fun readMemoryChunk(blockSeq: Byte, expectedSize: Int = 128): ByteArray {
        val payload = byteArrayOf(blockSeq)
        val resp = sendRequest(0x36.toByte(), payload)
        val idx = resp.indexOf(0x76.toByte())
        if (idx == -1 || idx + 2 >= resp.size) {
            throw IOException(String.format("Failed to read block 0x%02X: Invalid or missing 0x76 response", blockSeq))
        }
        val chunk = resp.copyOfRange(idx + 2, resp.size - 1)
        if (chunk.isEmpty()) {
            throw IOException(String.format("Failed to read block 0x%02X: Empty data returned by ECU", blockSeq))
        }
        return chunk
    }

    fun requestTransferExit(): Boolean {
        val resp = sendRequest(0x37.toByte())
        return resp.isNotEmpty()
    }

    fun clearDiagnosticTroubleCodes(): Boolean {
        return try {
            val resp = sendRequest(0x14.toByte(), byteArrayOf(0xFF.toByte(), 0x00.toByte()))
            resp.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    fun ecuReset(): Boolean {
        return try {
            val resp = sendRequest(0x11.toByte(), byteArrayOf(0x01.toByte()))
            resp.isNotEmpty()
        } catch (e: Exception) {
            true
        }
    }

    companion object {
        private const val TAG = "EDC16_KWP"
    }
}
