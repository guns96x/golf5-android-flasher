package com.golf5.edc16flasher.usb

import android.content.Context
import com.golf5.edc16flasher.protocol.KwpFrameCodec
import com.golf5.edc16flasher.security.MockSecurityAlgorithm
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue

data class MockFaults(
    var nrc78Count: Int = 0,
    var disconnectAtBlock: Int = -1,
    var voltageDropAtBlock: Int = -1,
    var droppedVoltage: Float = 11.5f,
)

data class UploadState(
    val start: Int,
    val size: Int,
    var cursor: Int,
    val blockSize: Int,
)

data class DownloadState(
    val start: Int,
    val size: Int,
    var cursor: Int,
    val blockSize: Int,
    var expectedSequence: Int,
    var bytesTransferred: Int,
)

/**
 * Built-in EDC16U34 & MPPS V18 Mock Transport
 * Stateful in-memory ECU emulator.
 */
class MockEdc16Transport(private val context: Context? = null) : IUsbTransport {

    private var isOpen = false
    private val rxQueue = ConcurrentLinkedQueue<Byte>()
    private var flashMemory: ByteArray = ByteArray(2097152) { 0xFF.toByte() }

    private var uploadState: UploadState? = null
    private var downloadState: DownloadState? = null
    private var securityUnlocked = false
    private var lastSeed = byteArrayOf(0x12, 0x34, 0x56, 0x78)
    private var currentVoltage = 13.8f
    private var faults = MockFaults()

    override val isConnected: Boolean
        get() = isOpen

    override val isPhysical: Boolean
        get() = false

    override val isMpps: Boolean
        get() = true

    override val transportName: String
        get() = "Емуляція EDC16U34 (Offline Тест)"

    override fun open(baudRate: Int): Boolean {
        if (context != null) {
            try {
                val assetStream = context.assets.open("03G906021QJ_stage1_refined_CS_OK.bin")
                flashMemory = assetStream.use { it.readBytes() }
            } catch (e: Exception) {
                flashMemory = ByteArray(2097152) { 0xFF.toByte() }
            }
        } else {
            flashMemory = ByteArray(2097152) { 0xFF.toByte() }
        }
        isOpen = true
        rxQueue.clear()
        uploadState = null
        downloadState = null
        securityUnlocked = false
        faults = MockFaults()
        return true
    }

    override fun close() {
        isOpen = false
        rxQueue.clear()
        uploadState = null
        downloadState = null
    }

    override fun getBatteryVoltage(): Float? {
        return if (isOpen) currentVoltage else null
    }

    override fun write(data: ByteArray, timeoutMs: Int) {
        if (!isOpen) throw IOException("Transport is not open")

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
        val resp = byteArrayOf(
            0x83.toByte(), 0xF1.toByte(), 0x01.toByte(),
            0xC1.toByte(), 0xEA.toByte(), 0x8F.toByte(), 0x89.toByte()
        )
        for (b in resp) rxQueue.add(b)
    }

    fun queryEcuIdentification(): String {
        return "03G906021QJ 391847 (Емуляція Bosch EDC16U34)"
    }

    internal fun snapshot(start: Int, size: Int): ByteArray {
        return flashMemory.copyOfRange(start, start + size)
    }

    internal fun setVoltageForTest(voltage: Float) {
        currentVoltage = voltage
    }

    internal fun configureFaults(faults: MockFaults) {
        this.faults = faults
    }

    private fun emitFrame(sid: Int, payload: ByteArray) {
        val dataLen = 1 + payload.size
        val headerLen = if (dataLen <= 63) 3 else 4
        val total = headerLen + dataLen + 1
        val frame = ByteArray(total)
        if (dataLen <= 63) {
            frame[0] = (0x80 or dataLen).toByte()
            frame[1] = 0xF1.toByte()
            frame[2] = 0x01.toByte()
            frame[3] = (sid and 0xFF).toByte()
            System.arraycopy(payload, 0, frame, 4, payload.size)
        } else {
            frame[0] = 0x80.toByte()
            frame[1] = 0xF1.toByte()
            frame[2] = 0x01.toByte()
            frame[3] = (dataLen and 0xFF).toByte()
            frame[4] = (sid and 0xFF).toByte()
            System.arraycopy(payload, 0, frame, 5, payload.size)
        }
        frame[total - 1] = KwpFrameCodec.computeChecksum(frame, total - 1)
        for (b in frame) rxQueue.add(b)
    }

    private fun emitNegative(sid: Int, nrc: Int) {
        emitFrame(0x7F, byteArrayOf((sid and 0xFF).toByte(), (nrc and 0xFF).toByte()))
    }

    private fun processKwpRequest(frame: ByteArray) {
        if (frame.size < 5) return

        val fmt = frame[0].toInt() and 0xFF
        val headerLen = if ((fmt and 0x3F) != 0) 3 else 4
        if (headerLen >= frame.size - 1) return

        val sid = frame[headerLen].toInt() and 0xFF
        val payload = frame.copyOfRange(headerLen + 1, frame.size - 1)

        if (faults.nrc78Count > 0) {
            val count = faults.nrc78Count
            faults.nrc78Count = 0
            for (i in 0 until count) {
                emitNegative(sid, 0x78)
            }
        }

        when (sid) {
            0x10 -> { // Start Diagnostic Session
                val sub = if (payload.isNotEmpty()) payload[0] else 0x81.toByte()
                emitFrame(0x50, byteArrayOf(sub, 0x00, 0x32, 0x01, 0xF4.toByte()))
            }
            0x1A -> { // Read ECU Identification
                val sub = if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else 0x9B
                val idStr = when (sub) {
                    0x9B -> "03G906021QJ "
                    0x97 -> "391847"
                    0x90 -> "WVWZZZ1KZ7P123456"
                    else -> "EDC16U34"
                }
                emitFrame(0x5A, byteArrayOf(sub.toByte()) + idStr.toByteArray())
            }
            0x21 -> { // Read Data By Local Identifier
                val sub = if (payload.isNotEmpty()) payload[0] else 0x80.toByte()
                emitFrame(0x61, byteArrayOf(sub) + "03G906021QJ 391847 EDC16U34".toByteArray())
            }
            0x27 -> { // Security Access
                val sub = if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else 0x01
                if (sub == 0x01) {
                    emitFrame(0x67, byteArrayOf(0x01.toByte()) + lastSeed)
                } else if (sub == 0x02) {
                    val key = payload.copyOfRange(1, payload.size)
                    val expectedKey = MockSecurityAlgorithm.calculateKey(lastSeed)
                    if (key.contentEquals(expectedKey)) {
                        securityUnlocked = true
                        emitFrame(0x67, byteArrayOf(0x02.toByte()))
                    } else {
                        emitNegative(0x27, 0x35) // Invalid Key
                    }
                } else {
                    emitNegative(0x27, 0x12) // Subfunction Not Supported
                }
            }
            0x34 -> { // Request Download
                if (payload.size < 7) {
                    emitNegative(0x34, 0x13) // Incorrect Message Length
                    return
                }
                val startAddress = ((payload[1].toInt() and 0xFF) shl 16) or
                        ((payload[2].toInt() and 0xFF) shl 8) or
                        (payload[3].toInt() and 0xFF)
                val uncompressedSize = ((payload[4].toInt() and 0xFF) shl 16) or
                        ((payload[5].toInt() and 0xFF) shl 8) or
                        (payload[6].toInt() and 0xFF)

                if (startAddress < 0x180000 || startAddress + uncompressedSize > 0x200000 || uncompressedSize <= 0) {
                    emitNegative(0x34, 0x31) // Request Out Of Range
                    return
                }

                downloadState = DownloadState(
                    start = startAddress,
                    size = uncompressedSize,
                    cursor = startAddress,
                    blockSize = 128,
                    expectedSequence = 1,
                    bytesTransferred = 0,
                )
                emitFrame(0x74, byteArrayOf(0x00, 0x80.toByte()))
            }
            0x35 -> { // Request Upload
                if (payload.size < 7) {
                    emitNegative(0x35, 0x13)
                    return
                }
                val startAddress = ((payload[1].toInt() and 0xFF) shl 16) or
                        ((payload[2].toInt() and 0xFF) shl 8) or
                        (payload[3].toInt() and 0xFF)
                val uncompressedSize = ((payload[4].toInt() and 0xFF) shl 16) or
                        ((payload[5].toInt() and 0xFF) shl 8) or
                        (payload[6].toInt() and 0xFF)

                uploadState = UploadState(
                    start = startAddress,
                    size = uncompressedSize,
                    cursor = startAddress,
                    blockSize = 128,
                )
                emitFrame(0x75, byteArrayOf(0x00, 0x80.toByte()))
            }
            0x36 -> { // Transfer Data
                if (downloadState != null) {
                    val currentDownload = downloadState!!
                    if (faults.disconnectAtBlock == currentDownload.expectedSequence) {
                        close()
                        return
                    }
                    if (faults.voltageDropAtBlock == currentDownload.expectedSequence) {
                        currentVoltage = faults.droppedVoltage
                    }
                    val blockSeq = if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else -1
                    if (blockSeq != currentDownload.expectedSequence) {
                        emitNegative(0x36, 0x24) // Sequence Error
                        return
                    }

                    val dataChunk = payload.copyOfRange(1, payload.size)
                    if (currentDownload.bytesTransferred + dataChunk.size > currentDownload.size) {
                        emitNegative(0x36, 0x31) // Request Out Of Range
                        return
                    }

                    System.arraycopy(dataChunk, 0, flashMemory, currentDownload.cursor, dataChunk.size)
                    currentDownload.cursor += dataChunk.size
                    currentDownload.bytesTransferred += dataChunk.size
                    currentDownload.expectedSequence = (currentDownload.expectedSequence + 1) and 0xFF

                    emitFrame(0x76, byteArrayOf(blockSeq.toByte()))
                } else if (uploadState != null) {
                    val currentUpload = uploadState!!
                    val blockSeq = if (payload.isNotEmpty()) payload[0] else 0x01.toByte()
                    val remaining = currentUpload.start + currentUpload.size - currentUpload.cursor
                    val chunkSize = minOf(currentUpload.blockSize, remaining)
                    val chunk = if (chunkSize > 0 && currentUpload.cursor + chunkSize <= flashMemory.size) {
                        flashMemory.copyOfRange(currentUpload.cursor, currentUpload.cursor + chunkSize)
                    } else {
                        ByteArray(chunkSize) { 0xFF.toByte() }
                    }
                    currentUpload.cursor += chunkSize
                    emitFrame(0x76, byteArrayOf(blockSeq) + chunk)
                } else {
                    emitNegative(0x36, 0x22) // Conditions Not Correct
                }
            }
            0x37 -> { // Request Transfer Exit
                if (downloadState != null) {
                    val currentDownload = downloadState!!
                    if (currentDownload.bytesTransferred != currentDownload.size) {
                        emitNegative(0x37, 0x22) // Conditions Not Correct (Incomplete)
                    } else {
                        downloadState = null
                        emitFrame(0x77, ByteArray(0))
                    }
                } else if (uploadState != null) {
                    uploadState = null
                    emitFrame(0x77, ByteArray(0))
                } else {
                    emitFrame(0x77, ByteArray(0))
                }
            }
            0x14 -> { // Clear DTC
                emitFrame(0x54, ByteArray(0))
            }
            0x11 -> { // ECU Reset
                emitFrame(0x51, byteArrayOf(0x01))
            }
            else -> {
                emitNegative(sid, 0x11) // Service Not Supported
            }
        }
    }

    override fun runDiagnostic(): String {
        return "=== MOCK EDC16U34 DIAGNOSTIC REPORT ===\n" +
                "Status: VIRTUAL_EMULATOR_ACTIVE\n" +
                "Model: Bosch EDC16U34 (VW Golf 5 1.9 TDI BLS)\n" +
                "VAG SW: 03G906021QJ | Bosch SW: 391847\n" +
                "Calibration: 0x180000..0x200000 (512 KB)\n" +
                "Virtual Battery: ${currentVoltage}V"
    }
}
