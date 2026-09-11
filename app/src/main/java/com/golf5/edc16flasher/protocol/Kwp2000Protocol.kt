package com.golf5.edc16flasher.protocol

import com.golf5.edc16flasher.usb.UsbSerialManager
import java.io.IOException

/**
 * KWP2000 (ISO 14230-4) Implementation for Bosch EDC16U34
 */
class Kwp2000Protocol(private val usb: UsbSerialManager) {

    private val targetAddress: Byte = 0x01.toByte() // Engine ECU (0x01)
    private val sourceAddress: Byte = 0xF1.toByte() // Diagnostic Tool (0xF1)

    fun sendRequest(serviceId: Byte, payload: ByteArray = ByteArray(0)): ByteArray {
        val length = 1 + payload.size
        val packet = ArrayList<Byte>()

        if (length <= 63) {
            packet.add((0x80 or length).toByte())
            packet.add(targetAddress)
            packet.add(sourceAddress)
        } else {
            packet.add(0x80.toByte())
            packet.add(targetAddress)
            packet.add(sourceAddress)
            packet.add(length.toByte())
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

        val rawOut = packet.toByteArray()
        usb.write(rawOut, 2000)

        // Read response
        val rxBuf = ByteArray(512)
        val bytesRead = usb.read(rxBuf, 3000)
        if (bytesRead < 4) {
            throw IOException("KWP2000 No response or response too short ($bytesRead bytes)")
        }

        val resp = rxBuf.copyOfRange(0, bytesRead)
        // Check for Negative Response (0x7F)
        for (i in 0 until resp.size - 2) {
            if (resp[i] == 0x7F.toByte() && resp[i + 1] == serviceId) {
                val nrc = resp[i + 2]
                throw IOException(String.format("ECU Negative Response: Service 0x%02X NRC 0x%02X", serviceId, nrc))
            }
        }
        return resp
    }

    fun startDiagnosticSession(sessionType: Byte = 0x85.toByte()): Boolean {
        // 0x10 = StartDiagnosticSession, 0x85 = Programming Session
        val resp = sendRequest(0x10.toByte(), byteArrayOf(sessionType))
        return resp.isNotEmpty()
    }

    fun readEcuIdentification(): String {
        // Service 0x1A 0x9B (Read ECU Identification) or 0x21 0x80
        val resp = try {
            sendRequest(0x1A.toByte(), byteArrayOf(0x9B.toByte()))
        } catch (e: Exception) {
            sendRequest(0x21.toByte(), byteArrayOf(0x80.toByte()))
        }
        return String(resp.filter { it in 32..126 }.toByteArray())
    }

    fun requestSecuritySeed(): ByteArray {
        // Service 0x27 0x01 (Request Seed)
        val resp = sendRequest(0x27.toByte(), byteArrayOf(0x01.toByte()))
        // Extract seed bytes
        return resp.takeLast(5).dropLast(1).toByteArray()
    }

    fun sendSecurityKey(key: ByteArray): Boolean {
        // Service 0x27 0x02 (Send Key)
        val payload = byteArrayOf(0x02.toByte()) + key
        val resp = sendRequest(0x27.toByte(), payload)
        return resp.isNotEmpty()
    }

    fun requestDownload(startAddress: Int, uncompressedSize: Int): Boolean {
        // Service 0x34 (RequestDownload)
        val payload = byteArrayOf(
            0x00, // Data format
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
        // Service 0x36 (TransferData)
        val payload = byteArrayOf(blockSeq) + data
        val resp = sendRequest(0x36.toByte(), payload)
        return resp.isNotEmpty()
    }

    fun requestTransferExit(): Boolean {
        // Service 0x37 (RequestTransferExit)
        val resp = sendRequest(0x37.toByte())
        return resp.isNotEmpty()
    }

    fun ecuReset(): Boolean {
        // Service 0x11 0x01 (ECUReset HardReset)
        val resp = sendRequest(0x11.toByte(), byteArrayOf(0x01.toByte()))
        return resp.isNotEmpty()
    }
}
