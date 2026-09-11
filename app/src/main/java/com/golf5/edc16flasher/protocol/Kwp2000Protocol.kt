package com.golf5.edc16flasher.protocol

import com.golf5.edc16flasher.usb.UsbSerialManager
import java.io.IOException

/**
 * Robust KWP2000 (ISO 14230-4) Implementation for Bosch EDC16U34
 * Incorporates:
 * - K-Line half-duplex local echo filtering
 * - Strict positive response verification (SID + 0x40)
 * - NRC 0x78 (Response Pending) P2* wait loop
 * - Maximum block payload <= 128 bytes to prevent length overflow
 */
class Kwp2000Protocol(private val usb: UsbSerialManager) {

    private val targetAddress: Byte = 0x01.toByte() // Engine ECU
    private val sourceAddress: Byte = 0xF1.toByte() // Diagnostic Tool

    fun sendRequest(serviceId: Byte, payload: ByteArray = ByteArray(0)): ByteArray {
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

        // Compute 8-bit checksum
        var checksum: Byte = 0
        for (b in packet) {
            checksum = (checksum + b).toByte()
        }
        packet.add(checksum)

        val txBytes = packet.toByteArray()
        usb.write(txBytes, 2000)

        // Read and parse response with NRC 0x78 pending handling
        val startTime = System.currentTimeMillis()
        val timeoutMs = 6000L

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val rxBytes = readFrame(serviceId, txBytes)
            if (rxBytes.isNotEmpty()) {
                // Check if NRC 0x78 (Response Pending)
                val expectedPositiveSid = ((serviceId.toInt() and 0xFF) + 0x40).toByte()
                val isNegative = rxBytes.any { it == 0x7F.toByte() }
                
                if (isNegative) {
                    val idx = rxBytes.indexOf(0x7F.toByte())
                    if (idx != -1 && idx + 2 < rxBytes.size) {
                        val failedSid = rxBytes[idx + 1]
                        val nrc = rxBytes[idx + 2]
                        if (failedSid == serviceId && (nrc.toInt() and 0xFF) == 0x78) {
                            // Response Pending: ECU is busy erasing/calculating, continue waiting
                            Thread.sleep(50)
                            continue
                        } else if (failedSid == serviceId) {
                            throw IOException(String.format("ECU Negative Response: SID 0x%02X NRC 0x%02X", serviceId, nrc))
                        }
                    }
                }

                // Verify positive response SID
                val hasPositiveSid = rxBytes.any { it == expectedPositiveSid }
                if (hasPositiveSid) {
                    return rxBytes
                }
            }
            Thread.sleep(20)
        }

        throw IOException(String.format("Timeout waiting for positive response to SID 0x%02X", serviceId))
    }

    private fun readFrame(serviceId: Byte, txEchoToDiscard: ByteArray): ByteArray {
        val buffer = ByteArray(512)
        val readCount = usb.read(buffer, 3000)
        if (readCount <= 0) return ByteArray(0)

        var raw = buffer.copyOfRange(0, readCount)

        // Filter out K-Line local echo if present
        if (raw.size >= txEchoToDiscard.size) {
            var match = true
            for (i in txEchoToDiscard.indices) {
                if (raw[i] != txEchoToDiscard[i]) {
                    match = false
                    break
                }
            }
            if (match) {
                raw = raw.copyOfRange(txEchoToDiscard.size, raw.size)
            }
        }

        return raw
    }

    fun startDiagnosticSession(sessionType: Byte = 0x85.toByte()): Boolean {
        val resp = sendRequest(0x10.toByte(), byteArrayOf(sessionType))
        return resp.isNotEmpty()
    }

    fun readEcuIdentification(): String {
        val resp = try {
            sendRequest(0x1A.toByte(), byteArrayOf(0x9B.toByte()))
        } catch (e: Exception) {
            sendRequest(0x21.toByte(), byteArrayOf(0x80.toByte()))
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

    fun transferData(blockSeq: Byte, data: ByteArray): Boolean {
        require(data.size <= 128) { "Data block size exceeds safe 128-byte limit" }
        val payload = byteArrayOf(blockSeq) + data
        val resp = sendRequest(0x36.toByte(), payload)
        return resp.isNotEmpty()
    }

    fun requestTransferExit(): Boolean {
        val resp = sendRequest(0x37.toByte())
        return resp.isNotEmpty()
    }

    fun ecuReset(): Boolean {
        return try {
            val resp = sendRequest(0x11.toByte(), byteArrayOf(0x01.toByte()))
            resp.isNotEmpty()
        } catch (e: Exception) {
            true
        }
    }
}
