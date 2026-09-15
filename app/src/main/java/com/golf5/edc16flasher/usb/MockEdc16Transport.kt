package com.golf5.edc16flasher.usb

import android.content.Context
import android.util.Log
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Built-in EDC16U34 & MPPS V18 Mock Transport
 * Emulates the complete Bosch EDC16U34 KWP2000 protocol (ISO 14230) in memory
 * using the embedded 2 MiB 03G906021QJ flash binary.
 * 
 * Allows 100% offline verification of:
 * - Identification (ECU ID: 03G906021QJ / SW 391847)
 * - Security Access (Seed/Key 0x27)
 * - 512 KB Calibration Reading (RequestUpload 0x35 + 4,096 blocks of 0x36)
 * - Checksum Verification & Backup File Saving
 */
class MockEdc16Transport(private val context: Context) : IUsbTransport {

    private var isOpen = false
    private val rxQueue = ConcurrentLinkedQueue<Byte>()
    private var flashMemory: ByteArray = ByteArray(2097152)

    private var uploadActive = false
    private var uploadOffset = 0x180000
    private var uploadBlockSize = 128
    private var lastSeed = byteArrayOf(0x12, 0x34, 0x56, 0x78)

    override val isConnected: Boolean
        get() = isOpen

    override val isMpps: Boolean
        get() = true

    override val transportName: String
        get() = "Емуляція EDC16U34 (Offline Тест)"

    override fun open(baudRate: Int): Boolean {
        try {
            val assetStream = context.assets.open("03G906021QJ_stage1_refined_CS_OK.bin")
            flashMemory = assetStream.use { it.readBytes() }
            Log.i(TAG, "MockEdc16Transport: Loaded asset flash binary (${flashMemory.size} bytes)")
        } catch (e: Exception) {
            Log.w(TAG, "MockEdc16Transport: Could not load asset binary, using blank 2MB memory: ${e.message}")
            flashMemory = ByteArray(2097152) { 0xFF.toByte() }
        }
        isOpen = true
        rxQueue.clear()
        uploadActive = false
        uploadOffset = 0x180000
        Log.i(TAG, "MockEdc16Transport opened successfully. Virtual OBD2 Voltage: 13.8V")
        return true
    }

    override fun close() {
        isOpen = false
        rxQueue.clear()
        uploadActive = false
        Log.i(TAG, "MockEdc16Transport closed.")
    }

    override fun getBatteryVoltage(): Float? {
        return if (isOpen) 13.8f else null
    }

    override fun write(data: ByteArray, timeoutMs: Int) {
        if (!isOpen) throw IOException("Transport is not open")

        // Check if data is encapsulated in MPPS K-Line framing: 0x25 0x02 <len_LE_2B> 0x00 <kwp_data>
        val kwpData = if (data.size >= 5 && data[0] == 0x25.toByte() && data[1] == 0x02.toByte()) {
            data.copyOfRange(5, data.size)
        } else {
            data
        }

        processKwpRequest(kwpData)
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        if (!isOpen) throw IOException("Transport is not open")

        val deadline = System.currentTimeMillis() + timeoutMs
        var pos = 0

        while (pos < buffer.size && System.currentTimeMillis() < deadline) {
            val b = rxQueue.poll()
            if (b != null) {
                buffer[pos++] = b
            } else {
                if (pos > 0) return pos
                try { Thread.sleep(5) } catch (ignored: InterruptedException) { break }
            }
        }
        return pos
    }

    override fun sendFastInit(pulseMs: Int, initialPayload: ByteArray) {
        if (!isOpen) return
        rxQueue.clear()
        // Respond with KWP fast-init positive response: 83 F1 01 C1 EA 8F 89
        val resp = byteArrayOf(0x83.toByte(), 0xF1.toByte(), 0x01.toByte(), 0xC1.toByte(), 0xEA.toByte(), 0x8F.toByte(), 0x89.toByte())
        for (b in resp) rxQueue.add(b)
    }

    fun queryEcuIdentification(): String {
        return "03G906021QJ 391847 (Емуляція Bosch EDC16U34)"
    }

    private fun processKwpRequest(frame: ByteArray) {
        if (frame.size < 4) return

        val fmt = frame[0].toInt() and 0xFF
        val pos = if (fmt == 0x80) 4 else 3
        if (pos >= frame.size - 1) return

        val sid = frame[pos].toInt() and 0xFF
        val payload = frame.copyOfRange(pos + 1, frame.size - 1)

        val respSid = (sid + 0x40) and 0xFF
        val respPayload = mutableListOf<Byte>()

        when (sid) {
            // 0x10: Start Diagnostic Session
            0x10 -> {
                val sub = if (payload.isNotEmpty()) payload[0] else 0x81.toByte()
                respPayload.add(sub)
                respPayload.addAll(listOf(0x00, 0x32, 0x01, 0xF4.toByte()))
            }
            // 0x1A: Read ECU Identification
            0x1A -> {
                val sub = if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else 0x9B
                respPayload.add(sub.toByte())
                val idStr = when (sub) {
                    0x9B -> "03G906021QJ "
                    0x97 -> "391847"
                    0x90 -> "WVWZZZ1KZ7P123456"
                    else -> "EDC16U34"
                }
                for (b in idStr.toByteArray()) respPayload.add(b)
            }
            // 0x21: Read Data By Local Identifier
            0x21 -> {
                val sub = if (payload.isNotEmpty()) payload[0] else 0x80.toByte()
                respPayload.add(sub)
                for (b in "03G906021QJ 391847 EDC16U34".toByteArray()) respPayload.add(b)
            }
            // 0x27: Security Access
            0x27 -> {
                val sub = if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else 0x01
                respPayload.add(sub.toByte())
                if (sub == 0x01) {
                    // Return 4-byte seed
                    for (b in lastSeed) respPayload.add(b)
                }
            }
            // 0x35: Request Upload (Reading calibration)
            0x35 -> {
                uploadActive = true
                uploadOffset = 0x180000
                uploadBlockSize = 128
                // Return block size 128 (0x00 0x80)
                respPayload.add(0x00)
                respPayload.add(0x80.toByte())
            }
            // 0x36: Transfer Data
            0x36 -> {
                val blockSeq = if (payload.isNotEmpty()) payload[0] else 0x01.toByte()
                respPayload.add(blockSeq)
                if (uploadActive) {
                    // Serve 128 bytes from flashMemory at uploadOffset
                    val chunk = if (uploadOffset + uploadBlockSize <= flashMemory.size) {
                        flashMemory.copyOfRange(uploadOffset, uploadOffset + uploadBlockSize)
                    } else {
                        ByteArray(uploadBlockSize) { 0xFF.toByte() }
                    }
                    uploadOffset += uploadBlockSize
                    for (b in chunk) respPayload.add(b)
                }
            }
            // 0x34: Request Download (Writing calibration)
            0x34 -> {
                respPayload.add(0x00)
                respPayload.add(0x80.toByte())
            }
            // 0x37: Request Transfer Exit
            0x37 -> {
                uploadActive = false
            }
            // 0x14: Clear DTC
            0x14 -> {
            }
            // 0x11: ECU Reset
            0x11 -> {
                respPayload.add(0x01)
            }
            else -> {
                respPayload.add(0x00)
            }
        }

        // Frame building
        val dataLen = 1 + respPayload.size
        val respFrame = mutableListOf<Byte>()
        if (dataLen <= 63) {
            respFrame.add((0x80 or dataLen).toByte())
            respFrame.add(0xF1.toByte()) // To tester
            respFrame.add(0x01.toByte()) // From ECU
        } else {
            respFrame.add(0x80.toByte())
            respFrame.add(0xF1.toByte())
            respFrame.add(0x01.toByte())
            respFrame.add(dataLen.toByte())
        }
        respFrame.add(respSid.toByte())
        respFrame.addAll(respPayload)

        var cs: Byte = 0
        for (b in respFrame) {
            cs = (cs + b).toByte()
        }
        respFrame.add(cs)

        for (b in respFrame) {
            rxQueue.add(b)
        }
    }

    override fun runDiagnostic(): String {
        return "=== MOCK EDC16U34 DIAGNOSTIC REPORT ===\n" +
                "Status: VIRTUAL_EMULATOR_ACTIVE\n" +
                "Model: Bosch EDC16U34 (VW Golf 5 1.9 TDI BLS)\n" +
                "VAG SW: 03G906021QJ | Bosch SW: 391847\n" +
                "Calibration: 0x180000..0x200000 (512 KB)\n" +
                "Checksums: 0xD01FE500 [VALID]\n" +
                "Virtual Battery: 13.8V"
    }

    companion object {
        private const val TAG = "MOCK_EDC16"
    }
}
