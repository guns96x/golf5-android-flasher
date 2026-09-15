package com.golf5.edc16flasher.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Native Hardware Transport for MPPS v18 (AMT Flash clone, VID 0x1C43, PID 0x0500)
 * Communicates directly with the Silicon Labs C8051 MCU & FTDI FT232R interface:
 * - Vendor Control Transfers (0x90/0x91) for EEPROM & Baudrate
 * - Proprietary Challenge-Response Handshake & Asymmetric XOR Cipher
 * - KWP2000 Packet Encapsulation (\x25\x02) & 25ms Fast Init Wakeup Pulse
 * - Real-Time OBD2 Battery Voltage Readout (EEPROM 0x3000)
 */
class MppsHardwareTransport(
    private val context: Context,
    private val usbManager: UsbManager,
    private val device: UsbDevice
) : IUsbTransport {

    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null

    private var writeBitmask: Int = 0
    private var readBitmask: Int = 0
    private val rxQueue = ConcurrentLinkedQueue<Byte>()

    /** True only after a session was authenticated using a known captured vector. */
    @Volatile
    private var _mppsAuthVerified: Boolean = false

    /**
     * Indicates whether the current session's MPPS authentication was validated
     * by a known captured challenge/response vector.
     * Task 7 (FlashEligibility) consumes this fact to gate physical write capability.
     */
    val mppsAuthVerified: Boolean get() = _mppsAuthVerified


    override val isConnected: Boolean
        get() = connection != null

    override val isMpps: Boolean
        get() = true

    override val transportName: String
        get() = "MPPS v18 (AMT Flash)"

    override fun open(baudRate: Int): Boolean {
        try {
            close()
            Log.d(TAG, "Opening UsbDevice: ${device.deviceName} (0x${Integer.toHexString(device.vendorId)}:0x${Integer.toHexString(device.productId)})")
            val conn = usbManager.openDevice(device) ?: run {
                Log.e(TAG, "Failed to open UsbDevice: permission denied or device busy")
                return false
            }

            var iface: UsbInterface? = null
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null

            for (i in 0 until device.interfaceCount) {
                val candidate = device.getInterface(i)
                Log.i(TAG, "Interface $i: class=${candidate.interfaceClass} endpoints=${candidate.endpointCount}")
                for (e in 0 until candidate.endpointCount) {
                    val ep = candidate.getEndpoint(e)
                    val dirStr = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                    val typeStr = when (ep.type) {
                        UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                        UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
                        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
                        else -> "ISO"
                    }
                    Log.i(TAG, "  Endpoint $e: addr=0x${Integer.toHexString(ep.address)} num=${ep.endpointNumber} dir=$dirStr type=$typeStr maxPacket=${ep.maxPacketSize}")
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN && epIn == null) {
                            epIn = ep
                        } else if (ep.direction == UsbConstants.USB_DIR_OUT && epOut == null) {
                            epOut = ep
                        }
                    }
                }
                if (epIn != null && epOut != null) {
                    iface = candidate
                    break
                }
            }

            if (iface == null || epIn == null || epOut == null) {
                Log.e(TAG, "Bulk endpoints not found on device")
                conn.close()
                return false
            }

            if (!conn.claimInterface(iface, true)) {
                Log.e(TAG, "Failed to claim interface 0")
                conn.close()
                return false
            }

            connection = conn
            usbInterface = iface
            endpointIn = epIn
            endpointOut = epOut
            rxQueue.clear()

            // Step 1: Configure FTDI Hardware Parameters (reset + latency 10ms + baud 10400 + 8N1)
            resetDevice()
            try { Thread.sleep(20) } catch (ignored: InterruptedException) {}
            setLatencyTimer(10)
            setBaudrate(10400)
            setLineProperty(8)

            // Step 2: Full Hardware Handshake & Authentication (V18 firmware 0x55 00)
            performHandshake()

            val v = getBatteryVoltage() ?: 0.0f
            Log.i(TAG, String.format("MPPS v18 initialized successfully! OBD2 Voltage: %.2fV", v))
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "MPPS Open Exception: ${t.message}", t)
            close()
            return false
        }
    }

    override fun close() {
        try {
            connection?.let { conn ->
                usbInterface?.let { conn.releaseInterface(it) }
                conn.close()
            }
        } catch (ignored: Exception) {
        } finally {
            connection = null
            usbInterface = null
            endpointIn = null
            endpointOut = null
            rxQueue.clear()
            _mppsAuthVerified = false
        }
    }

    private val transportLock = Any()

    override fun write(data: ByteArray, timeoutMs: Int) = synchronized(transportLock) {
        // Encapsulate KWP2000 payload into MPPS K-Line framing:
        // 0x25 0x02 <len_LE_2B> 0x00 <data>
        val frame = ByteArray(5 + data.size)
        frame[0] = 0x25.toByte()
        frame[1] = 0x02.toByte()
        frame[2] = (data.size and 0xFF).toByte()
        frame[3] = ((data.size shr 8) and 0xFF).toByte()
        frame[4] = 0x00.toByte() // 0ms inter-byte delay
        System.arraycopy(data, 0, frame, 5, data.size)

        writeRaw(frame)
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int = synchronized(transportLock) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var pos = 0

        // Drain any previously buffered decrypted bytes
        while (pos < buffer.size && !rxQueue.isEmpty()) {
            val b = rxQueue.poll() ?: break
            buffer[pos++] = b
        }

        if (pos >= buffer.size) return pos

        val conn = connection ?: return pos
        val epIn = endpointIn ?: return pos
        val rawBuf = ByteArray(256)

        while (pos < buffer.size && System.currentTimeMillis() < deadline) {
            val remainingMs = maxOf(15, (deadline - System.currentTimeMillis()).toInt())
            val r = conn.bulkTransfer(epIn, rawBuf, rawBuf.size, remainingMs)
            if (r > 2) {
                // FTDI header: rawBuf[0] (modem status), rawBuf[1] (line status)
                val payloadLen = r - 2
                val hexRaw = (0 until payloadLen).map { String.format("%02X", rawBuf[2 + it]) }.joinToString(" ")
                val hexDec = (0 until payloadLen).map { String.format("%02X", (rawBuf[2 + it].toInt() and 0xFF) xor readBitmask) }.joinToString(" ")
                Log.i(TAG, "read KWP: received $payloadLen bytes [enc: $hexRaw] -> [dec: $hexDec]")
                for (i in 0 until payloadLen) {
                    val decrypted = ((rawBuf[2 + i].toInt() and 0xFF) xor readBitmask).toByte()
                    if (pos < buffer.size) {
                        buffer[pos++] = decrypted
                    } else {
                        rxQueue.add(decrypted)
                    }
                }
                if (pos > 0) return pos
            } else {
                try {
                    Thread.sleep(10)
                } catch (ignored: InterruptedException) {
                    break
                }
            }
        }
        return pos
    }

    override fun sendFastInit(pulseMs: Int, initialPayload: ByteArray) = synchronized(transportLock) {
        // Fast init command in MPPS C8051 MCU:
        // 0x25 0x01 <len_1B> <inter_byte_delay=0> <pulseMs> <initialPayload>
        val frame = ByteArray(5 + initialPayload.size)
        frame[0] = 0x25.toByte()
        frame[1] = 0x01.toByte()
        frame[2] = (initialPayload.size and 0xFF).toByte()
        frame[3] = 0x00.toByte() // 0ms inter-byte delay
        frame[4] = pulseMs.toByte() // typically 25ms
        if (initialPayload.isNotEmpty()) {
            System.arraycopy(initialPayload, 0, frame, 5, initialPayload.size)
        }
        Log.i(TAG, "sendFastInit: pulse=${pulseMs}ms payloadLen=${initialPayload.size} data=${initialPayload.joinToString(" ") { String.format("%02X", it) }}")
        writeRaw(frame)
    }

    override fun getBatteryVoltage(): Float? = synchronized(transportLock) {
        try {
            val buf = readEE(0x3000, 2)
            val rawVal = (buf[1].toInt() and 0xFF) or ((buf[0].toInt() and 0xFF) shl 8)
            // Calibrated empirical gain for MPPS v18 clone hardware: 25.76 counts/V (322 counts = 12.5V)
            rawVal / 25.76f
        } catch (e: Exception) {
            null
        }
    }

    fun resetCanController(): Boolean = synchronized(transportLock) {
        purgeRx()
        writeRaw(byteArrayOf(0x30, 0x01))
        val res = readRaw(1, 1000)
        val ok = res.isNotEmpty() && (res[0].toInt() and 0xFF) == 0x55
        Log.i(TAG, "CAN resetController: $ok (res=${res.joinToString { String.format("%02X", it) }})")
        return ok
    }

    fun enableCanController(): Boolean = synchronized(transportLock) {
        purgeRx()
        writeRaw(byteArrayOf(0x30, 0x09))
        val res = readRaw(1, 1000)
        val ok = res.isNotEmpty() && (res[0].toInt() and 0xFF) == 0x55
        Log.i(TAG, "CAN enableController: $ok (res=${res.joinToString { String.format("%02X", it) }})")
        return ok
    }

    fun setupCan(
        busTiming: Int = 7, // Mode 7 (500 kbps)
        acceptanceCode: Int = 0x00000000,
        acceptanceMask: Int = -1, // 0xFFFFFFFF (accept all)
        txCanId: Int = 0x200,
        rxFilterId: Int = 0x00000000,
        extendedFrame: Boolean = false,
        encapsulation: Int = 3 // Raw0 = 3
    ): Boolean = synchronized(transportLock) {
        resetCanController()

        val frame = ByteArray(22)
        var p = 0
        frame[p++] = 0x30
        frame[p++] = 0x10
        frame[p++] = busTiming.toByte()

        // acceptance_code LE
        frame[p++] = (acceptanceCode and 0xFF).toByte()
        frame[p++] = ((acceptanceCode shr 8) and 0xFF).toByte()
        frame[p++] = ((acceptanceCode shr 16) and 0xFF).toByte()
        frame[p++] = ((acceptanceCode shr 24) and 0xFF).toByte()

        // acceptance_mask LE
        frame[p++] = (acceptanceMask and 0xFF).toByte()
        frame[p++] = ((acceptanceMask shr 8) and 0xFF).toByte()
        frame[p++] = ((acceptanceMask shr 16) and 0xFF).toByte()
        frame[p++] = ((acceptanceMask shr 24) and 0xFF).toByte()

        // tx_can_identifier LE
        frame[p++] = (txCanId and 0xFF).toByte()
        frame[p++] = ((txCanId shr 8) and 0xFF).toByte()
        frame[p++] = ((txCanId shr 16) and 0xFF).toByte()
        frame[p++] = ((txCanId shr 24) and 0xFF).toByte()

        // rx_filter_can_identifier LE
        frame[p++] = (rxFilterId and 0xFF).toByte()
        frame[p++] = ((rxFilterId shr 8) and 0xFF).toByte()
        frame[p++] = ((rxFilterId shr 16) and 0xFF).toByte()
        frame[p++] = ((rxFilterId shr 24) and 0xFF).toByte()

        frame[p++] = if (extendedFrame) 1 else 0
        frame[p++] = 0 // normal transmission mode
        frame[p++] = encapsulation.toByte() // Raw0 = 3

        purgeRx()
        writeRaw(frame)
        val res = readRaw(1, 1000)
        val ok = res.isNotEmpty() && (res[0].toInt() and 0xFF) == 0x55
        Log.i(TAG, "CAN setup (Mode $busTiming, TX 0x${Integer.toHexString(txCanId)}): $ok (res=${res.joinToString { String.format("%02X", it) }})")
        if (ok) {
            enableCanController()
        }
        return ok
    }

    fun sendCanMessage(data: ByteArray): Boolean = synchronized(transportLock) {
        val frame = ByteArray(3 + data.size)
        frame[0] = 0x30
        frame[1] = 0x03
        frame[2] = data.size.toByte()
        System.arraycopy(data, 0, frame, 3, data.size)
        purgeRx()
        writeRaw(frame)
        return true
    }

    fun receiveCanMessage(timeoutMs: Int = 1000): ByteArray? = synchronized(transportLock) {
        purgeRx()
        writeRaw(byteArrayOf(0x30, 0x08))
        val res = readRaw(8, timeoutMs)
        return if (res.isNotEmpty()) res else null
    }

    fun setCanTxId(canId: Int): Boolean = synchronized(transportLock) {
        val frame = ByteArray(6)
        frame[0] = 0x30
        frame[1] = 0x0C
        frame[2] = (canId and 0xFF).toByte()
        frame[3] = ((canId shr 8) and 0xFF).toByte()
        frame[4] = ((canId shr 16) and 0xFF).toByte()
        frame[5] = ((canId shr 24) and 0xFF).toByte()
        purgeRx()
        writeRaw(frame)
        val res = readRaw(1, 1000)
        return res.isNotEmpty() && (res[0].toInt() and 0xFF) == 0x55
    }

    fun getCanStatus(): Int = synchronized(transportLock) {
        purgeRx()
        writeRaw(byteArrayOf(0x30, 0x05))
        val res = readRaw(1, 1000)
        return if (res.isNotEmpty()) res[0].toInt() and 0xFF else -1
    }

    fun getCanError(): Int = synchronized(transportLock) {
        purgeRx()
        writeRaw(byteArrayOf(0x30, 0x06))
        val res = readRaw(1, 1000)
        return if (res.isNotEmpty()) res[0].toInt() and 0xFF else -1
    }

    fun probeCanBus(): String = synchronized(transportLock) {
        val sb = StringBuilder()
        sb.appendLine("=== MPPS CAN Bus Diagnostic Probe ===")
        val setupOk = setupCan(busTiming = 7, txCanId = 0x200, encapsulation = 3)
        val status = getCanStatus()
        val error = getCanError()
        sb.appendLine(String.format("CAN Setup: %s | Status=0x%02X Error=0x%02X", if (setupOk) "OK" else "FAIL", status, error))

        // Probe 1: TP2.0 Channel Setup (CAN ID 0x200 -> 0x201)
        Log.i(TAG, "CAN Probe 1: Sending TP2.0 channel setup to 0x200...")
        val tpReq = byteArrayOf(0x01, 0xC0.toByte(), 0x00, 0x10, 0x00, 0x03, 0x01)
        sendCanMessage(tpReq)
        try { Thread.sleep(100) } catch (ignored: InterruptedException) {}
        val tpResp = receiveCanMessage(1000)
        if (tpResp != null && tpResp.isNotEmpty()) {
            val hex = tpResp.joinToString(" ") { String.format("%02X", it) }
            sb.appendLine("TP2.0 Setup (0x200): RECEIVED -> $hex")
            Log.i(TAG, "TP2.0 Setup response: $hex")
        } else {
            sb.appendLine("TP2.0 Setup (0x200): No response (timeout)")
        }

        // Probe 2: Standard OBD2 (CAN ID 0x7DF -> 0x7E8)
        Log.i(TAG, "CAN Probe 2: Sending OBD2 PID 00 to 0x7DF...")
        setCanTxId(0x7DF)
        val obdReq = byteArrayOf(0x02, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        sendCanMessage(obdReq)
        try { Thread.sleep(100) } catch (ignored: InterruptedException) {}
        val obdResp = receiveCanMessage(1000)
        if (obdResp != null && obdResp.isNotEmpty()) {
            val hex = obdResp.joinToString(" ") { String.format("%02X", it) }
            sb.appendLine("OBD2 Query (0x7DF): RECEIVED -> $hex")
            Log.i(TAG, "OBD2 response: $hex")
        } else {
            sb.appendLine("OBD2 Query (0x7DF): No response (timeout)")
        }

        return sb.toString()
    }

    private fun performHandshake() {
        Log.i(TAG, "Starting MPPS v18 2-Phase Authentication & Microcode Loading...")

        // Phase 1: Bootloader Handshake
        val magic = readEE(0x1000, 2)
        val m0 = magic[0].toInt() and 0xFF
        val m1 = magic[1].toInt() and 0xFF
        Log.i(TAG, String.format("MPPS EEPROM 0x1000 (Firmware): 0x%02X 0x%02X", m0, m1))

        val fwMinor = readEE(0x1100, 2)
        Log.i(TAG, String.format("MPPS EEPROM 0x1100: 0x%02X 0x%02X", fwMinor[0], fwMinor[1]))

        val bitmasks1 = readEE(0x2000, 2)
        writeBitmask = bitmasks1[0].toInt() and 0xFF
        readBitmask = bitmasks1[1].toInt() and 0xFF
        Log.i(TAG, String.format("MPPS Phase 1 Bitmasks: TX=0x%02X RX=0x%02X", writeBitmask, readBitmask))

        setLatencyTimer(10)
        setBaudrate(10400)
        setLineProperty(8)

        try { readEE(0x4000, 128) } catch (ignored: Throwable) {}
        val secNum = try { readEE(0x5000, 8) } catch (e: Throwable) { ByteArray(8) }
        Log.i(TAG, "MPPS Security Number: ${secNum.joinToString(" ") { String.format("%02X", it) }}")

        try {
            writeEE(0x5001, ByteArray(0))
            Log.i(TAG, "Phase 1 writeEE 0x5001 trigger OK")
        } catch (e: Throwable) {
            Log.w(TAG, "Phase 1 writeEE 0x5001: ${e.message}")
        }

        purgeRx()
        val unlockCmd = byteArrayOf(0x34, 0x01, 0xb4.toByte(), 0x3b, 0x14, 0x7c, 0x63, 0x63, 0x3f)
        writeRaw(unlockCmd)
        val ack1 = readRaw(1, 1500)
        val ack1Val = if (ack1.isNotEmpty()) ack1[0].toInt() and 0xFF else -1
        Log.i(TAG, String.format("Phase 1 Unlock ACK: 0x%02X (expected 0xED)", ack1Val))
        if (ack1.isEmpty() || !isAck(ack1[0])) {
            throw java.io.IOException(String.format("Phase 1 Unlock failed: ACK invalid or timed out (got 0x%02X)", ack1Val))
        }

        writeRaw(byteArrayOf(0xFD.toByte(), 0x7C))
        val challenge1 = readRaw(4, 2000)
        Log.i(TAG, "Phase 1 Challenge: ${challenge1.joinToString(" ") { String.format("%02X", it) }}")
        if (challenge1.size < 4) {
            throw java.io.IOException("Phase 1 Challenge failed: received less than 4 bytes")
        }
        val resp1 = computeChallengeResponse(challenge1, secNum)
        val respFrame1 = ByteArray(6).apply {
            this[0] = 0xFD.toByte()
            this[1] = 0x77.toByte()
            System.arraycopy(resp1, 0, this, 2, 4)
        }
        writeRaw(respFrame1)
        val ack2 = readRaw(1, 1500)
        val ack2Val = if (ack2.isNotEmpty()) ack2[0].toInt() and 0xFF else -1
        Log.i(TAG, String.format("Phase 1 Challenge Response ACK: 0x%02X (expected 0xED)", ack2Val))
        if (ack2.isEmpty() || !isAck(ack2[0])) {
            throw java.io.IOException(String.format("Phase 1 Challenge Response failed: ACK invalid or timed out (got 0x%02X)", ack2Val))
        }

        // Phase 2: Runtime Session Key Regeneration (packets 353-383 in Windows MPPS capture)
        try {
            writeEE(0x5000, ByteArray(0))
            Log.i(TAG, "Phase 2 writeEE 0x5000 (Session seed reset) OK")
        } catch (e: Throwable) {
            Log.w(TAG, "Phase 2 writeEE 0x5000: ${e.message}")
        }

        resetDevice()
        try { Thread.sleep(30) } catch (ignored: InterruptedException) {}

        // Read the freshly generated runtime session bitmasks!
        val bitmasks2 = readEE(0x2000, 2)
        writeBitmask = bitmasks2[0].toInt() and 0xFF
        readBitmask = bitmasks2[1].toInt() and 0xFF
        Log.i(TAG, String.format("MPPS Phase 2 Runtime Bitmasks: TX=0x%02X RX=0x%02X", writeBitmask, readBitmask))

        setLatencyTimer(10)
        setBaudrate(10400)
        setLineProperty(8)

        try { readEE(0x4000, 128) } catch (ignored: Throwable) {}
        try { readEE(0x5000, 8) } catch (ignored: Throwable) {}

        try {
            writeEE(0x5001, ByteArray(0))
            Log.i(TAG, "Phase 2 writeEE 0x5001 trigger OK")
        } catch (e: Throwable) {
            Log.w(TAG, "Phase 2 writeEE 0x5001: ${e.message}")
        }

        purgeRx()
        writeRaw(unlockCmd)
        val ack3 = readRaw(1, 1500)
        val ack3Val = if (ack3.isNotEmpty()) ack3[0].toInt() and 0xFF else -1
        Log.i(TAG, String.format("Phase 2 Unlock ACK: 0x%02X (expected 0xED)", ack3Val))
        if (ack3.isEmpty() || !isAck(ack3[0])) {
            throw java.io.IOException(String.format("Phase 2 Unlock failed: ACK invalid or timed out (got 0x%02X)", ack3Val))
        }

        writeRaw(byteArrayOf(0xFD.toByte(), 0x7C))
        val challenge2 = readRaw(4, 2000)
        Log.i(TAG, "Phase 2 Challenge: ${challenge2.joinToString(" ") { String.format("%02X", it) }}")
        if (challenge2.size < 4) {
            throw java.io.IOException("Phase 2 Challenge failed: received less than 4 bytes")
        }
        val resp2 = computeChallengeResponse(challenge2, secNum)
        val respFrame2 = ByteArray(6).apply {
            this[0] = 0xFD.toByte()
            this[1] = 0x77.toByte()
            System.arraycopy(resp2, 0, this, 2, 4)
        }
        writeRaw(respFrame2)
        val ack4 = readRaw(1, 1500)
        val ack4Val = if (ack4.isNotEmpty()) ack4[0].toInt() and 0xFF else -1
        Log.i(TAG, String.format("Phase 2 Challenge Response ACK: 0x%02X (expected 0xED)", ack4Val))
        if (ack4.isEmpty() || !isAck(ack4[0])) {
            throw java.io.IOException(String.format("Phase 2 Challenge Response failed: ACK invalid or timed out (got 0x%02X)", ack4Val))
        }

        // Phase 3: Driver Prep & Microcode Loading into MCU RAM
        try {
            writeRaw(byteArrayOf(0x93.toByte()))
            val mcuStatus = readRaw(20, 1000)
            Log.i(TAG, "MCU Driver Status (0x93): ${mcuStatus.joinToString(" ") { String.format("%02X", it) }}")
        } catch (e: Throwable) {
            Log.w(TAG, "Command 0x93: ${e.message}")
        }

        uploadMicrocodeDriver()

        // Phase 4: Vehicle Protocol Initialization
        initVehicleProtocol()

        Log.i(TAG, "MPPS v18 Hardware Handshake & Protocol Link successfully initialized!")
    }

    private fun computeChallengeResponse(challenge: ByteArray, secNum: ByteArray): ByteArray {
        return when (val result = MppsAuthenticator.responseFor(challenge, secNum)) {
            is MppsAuthenticator.MppsAuthResult.KnownResponse -> {
                _mppsAuthVerified = true
                result.bytes
            }
            is MppsAuthenticator.MppsAuthResult.UnknownChallenge -> {
                _mppsAuthVerified = false
                throw MppsAuthenticationUnverifiedException(result.challengeHex)
            }
        }
    }

    private fun isAck(b: Byte): Boolean {
        val v = b.toInt() and 0xFF
        return v == 0xED || v == 0xF9 || (v xor readBitmask) == 0xED
    }

    private fun uploadDriverChunk(chunk: ByteArray, passLabel: String): Boolean {
        val conn = connection ?: return false
        val epOut = endpointOut ?: return false
        Log.i(TAG, "Uploading Driver Chunk: $passLabel (${chunk.size} bytes)...")
        purgeRx()

        val encrypted = ByteArray(chunk.size)
        for (i in chunk.indices) {
            encrypted[i] = ((chunk[i].toInt() and 0xFF) xor writeBitmask).toByte()
        }
        val sent = conn.bulkTransfer(epOut, encrypted, encrypted.size, 2000)
        Log.i(TAG, "Chunk data sent: $sent of ${encrypted.size} bytes")

        // Allow C8051 MCU time to ingest the 8 USB packets into FIFO before ZLP (20ms as in Windows capture)
        try { Thread.sleep(25) } catch (ignored: InterruptedException) {}

        // Send Zero-Length Packet (ZLP) terminator
        conn.bulkTransfer(epOut, ByteArray(0), 0, 200)

        // Read ACK
        val ack = readRaw(1, 2500)
        val ok = ack.isNotEmpty() && isAck(ack[0])
        val ackHex = if (ack.isNotEmpty()) String.format("%02X", ack[0].toInt() and 0xFF) else "TIMEOUT"
        Log.i(TAG, "Driver Chunk $passLabel ACK: $ackHex (valid: $ok)")
        return ok
    }

    private fun uploadMicrocodeDriver() {
        val chunk1 = context.assets.open("mpps_drv_chunk1.bin").use { it.readBytes() }
        val chunk2 = context.assets.open("mpps_drv_chunk2.bin").use { it.readBytes() }

        // Pass 1: Chunk 1
        if (!uploadDriverChunk(chunk1, "Chunk 1 (Pass 1)")) {
            throw java.io.IOException("MPPS driver Chunk 1 (Pass 1) rejected or timed out")
        }
        try { Thread.sleep(30) } catch (ignored: InterruptedException) {}

        // Pass 2: Chunk 1 repeated (as captured in genuine MPPS V18 Windows trace)
        if (!uploadDriverChunk(chunk1, "Chunk 1 (Pass 2)")) {
            throw java.io.IOException("MPPS driver Chunk 1 (Pass 2) rejected or timed out")
        }
        try { Thread.sleep(30) } catch (ignored: InterruptedException) {}

        // Pass 3: Chunk 2
        if (!uploadDriverChunk(chunk2, "Chunk 2")) {
            throw java.io.IOException("MPPS driver Chunk 2 rejected or timed out")
        }
    }

    private fun initVehicleProtocol() {
        // Packet 6333: BULK OUT 36 53 -> ACK 0xED
        purgeRx()
        writeRaw(byteArrayOf(0x36, 0x53))
        val ackS = readRaw(1, 1000)
        Log.i(TAG, "Vehicle protocol 36 53 ACK: ${ackS.joinToString { String.format("%02X", it) }}")
        if (ackS.isEmpty() || !isAck(ackS[0])) {
            throw java.io.IOException("Vehicle protocol 36 53 initialization failed (no ACK)")
        }

        // Packet 6339: Set Baud 10400
        setBaudrate(10400)

        // Packet 6345: BULK OUT 3f 7c f2 <writeBitmask> d4 0c ca a1 0c
        purgeRx()
        val setupFrame = byteArrayOf(
            0x3F.toByte(), 0x7C.toByte(), 0xF2.toByte(),
            writeBitmask.toByte(),
            0xD4.toByte(), 0x0C.toByte(), 0xCA.toByte(), 0xA1.toByte(), 0x0C.toByte()
        )
        writeRaw(setupFrame)
        val resp1 = readRaw(7, 1000)
        Log.i(TAG, "Protocol setup resp: ${resp1.joinToString { String.format("%02X", it) }}")

        // Packet 6357: BULK OUT 36 43 -> ACK 0xED
        purgeRx()
        writeRaw(byteArrayOf(0x36, 0x43))
        val ackC = readRaw(1, 1000)
        Log.i(TAG, "Vehicle protocol 36 43 ACK: ${ackC.joinToString { String.format("%02X", it) }}")
        if (ackC.isEmpty() || !isAck(ackC[0])) {
            throw java.io.IOException("Vehicle protocol 36 43 initialization failed (no ACK)")
        }
    }

    fun queryEcuIdentification(): String = synchronized(transportLock) {
        val probes = listOf(
            byteArrayOf(0x3F, 0x77, 0x63, 0x7B, 0x77, 0x77, 0xA2.toByte(), 0x60),
            byteArrayOf(0x3F, 0x77, 0x63, 0x7B, 0x77, 0x77, 0xA2.toByte(), 0x81.toByte()),
            byteArrayOf(0x3F, 0x77, 0x63, 0x7B, 0x77, 0x77, 0xA2.toByte(), 0x14),
            byteArrayOf(0x3F, 0x77, 0x63, 0x7B, 0x77, 0x77, 0xA2.toByte(), 0xDE.toByte()),
            byteArrayOf(0x3F, 0x77, 0x63, writeBitmask.toByte(), 0x7C, 0xF2.toByte(), 0xAD.toByte(), 0x77, 0x16, 0x63),
            byteArrayOf(0x3F, 0x77, 0x63, writeBitmask.toByte(), 0x7C, 0xF2.toByte(), 0xAD.toByte(), 0x63, 0x16, 0x63),
            byteArrayOf(0x3F, 0x77, 0x63, 0x77, 0x77, 0x7C, 0x13)
        )

        val collected = mutableListOf<Byte>()
        for (probe in probes) {
            purgeRx()
            writeRaw(probe)
            Thread.sleep(60)
            val chunk = readRaw(32, 500)
            for (b in chunk) collected.add(b)
        }

        if (collected.isEmpty()) {
            return "Немає відповіді від ЕБУ (Таймаут)"
        }

        val allBytes = collected.toByteArray()
        val ascii = allBytes.map { b ->
            val v = b.toInt() and 0xFF
            if (v in 32..126) v.toChar() else '.'
        }.joinToString("")

        Log.i(TAG, "ECU ID Raw collected (${allBytes.size}B): ${allBytes.joinToString(" ") { String.format("%02X", it) }} | $ascii")
        return ascii
    }

    fun reinitHardwarePublic() = synchronized(transportLock) {
        performHandshake()
    }

    private fun readEE(addr: Int, size: Int): ByteArray {
        val conn = connection ?: throw IOException("Not connected")
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val chunkLen = minOf(2, size - offset)
            val temp = ByteArray(chunkLen)
            val res = conn.controlTransfer(0xC0, 0x90, 0, addr + offset, temp, chunkLen, 1000)
            if (res < chunkLen) {
                throw IOException("EEPROM read failed at 0x${Integer.toHexString(addr + offset)}")
            }
            System.arraycopy(temp, 0, result, offset, chunkLen)
            offset += chunkLen
        }
        return result
    }

    private fun writeEE(addr: Int, data: ByteArray) {
        val conn = connection ?: throw IOException("Not connected")
        val res = conn.controlTransfer(0x40, 0x91, 0, addr, data, data.size, 1000)
        if (res < 0) {
            throw IOException("EEPROM write failed at 0x${Integer.toHexString(addr)}")
        }
    }

    fun readEEPublic(addr: Int, size: Int): ByteArray = readEE(addr, size)
    fun writeEEPublic(addr: Int, data: ByteArray) = writeEE(addr, data)
    fun writeRawPublic(data: ByteArray, masked: Boolean = true) {
        val conn = connection ?: throw IOException("Not connected")
        val epOut = endpointOut ?: throw IOException("No OUT endpoint")
        val toSend = if (masked) {
            val enc = ByteArray(data.size)
            for (i in data.indices) {
                enc[i] = ((data[i].toInt() and 0xFF) xor writeBitmask).toByte()
            }
            enc
        } else {
            data
        }
        conn.bulkTransfer(epOut, toSend, toSend.size, 1000)
        conn.bulkTransfer(epOut, ByteArray(0), 0, 100) // ZLP terminator
    }
    fun readRawPublic(expectedSize: Int, timeoutMs: Int = 1000): ByteArray = readRaw(expectedSize, timeoutMs)
    fun controlTransfer(reqType: Int, req: Int, value: Int, index: Int, buffer: ByteArray?, length: Int, timeout: Int): Int {
        val conn = connection ?: throw IOException("Not connected")
        return conn.controlTransfer(reqType, req, value, index, buffer, length, timeout)
    }
    fun setBaudratePublic(baud: Int) = setBaudrate(baud)
    fun setDtrPublic(on: Boolean) = setDtr(on)
    fun setRtsPublic(on: Boolean) = setRts(on)

    private fun writeRaw(data: ByteArray) {
        val conn = connection ?: throw IOException("Not connected")
        val epOut = endpointOut ?: throw IOException("No OUT endpoint")
        val encrypted = ByteArray(data.size)
        for (i in data.indices) {
            encrypted[i] = ((data[i].toInt() and 0xFF) xor writeBitmask).toByte()
        }
        val sent = conn.bulkTransfer(epOut, encrypted, encrypted.size, 1000)
        conn.bulkTransfer(epOut, ByteArray(0), 0, 100) // ZLP terminator
        Log.i(TAG, String.format("writeRaw (EP 0x%02X): sent %d bytes [plain: %s] -> [enc: %s]",
            epOut.address, sent,
            data.joinToString(" ") { String.format("%02X", it) },
            encrypted.joinToString(" ") { String.format("%02X", it) }))
        if (sent < encrypted.size) {
            throw IOException("Bulk write failed: sent $sent of ${encrypted.size} bytes")
        }
    }

    private fun readRaw(expectedSize: Int, timeoutMs: Int = 2000): ByteArray {
        val result = ByteArray(expectedSize)
        var pos = 0
        val deadline = System.currentTimeMillis() + timeoutMs

        while (pos < expectedSize && !rxQueue.isEmpty()) {
            val b = rxQueue.poll() ?: break
            result[pos++] = b
        }

        val conn = connection ?: return result.copyOfRange(0, pos)
        val epIn = endpointIn ?: return result.copyOfRange(0, pos)
        val rawBuf = ByteArray(64)

        while (pos < expectedSize && System.currentTimeMillis() < deadline) {
            val remainingMs = maxOf(20, (deadline - System.currentTimeMillis()).toInt())
            val r = conn.bulkTransfer(epIn, rawBuf, rawBuf.size, remainingMs)
            if (r > 2) {
                val payloadLen = r - 2
                val hexRaw = (0 until payloadLen).map { String.format("%02X", rawBuf[2 + it]) }.joinToString(" ")
                val hexDec = (0 until payloadLen).map { String.format("%02X", (rawBuf[2 + it].toInt() and 0xFF) xor readBitmask) }.joinToString(" ")
                Log.i(TAG, String.format("readRaw (EP 0x%02X): received %d bytes [enc: %s] -> [dec: %s]", epIn.address, payloadLen, hexRaw, hexDec))
                for (i in 0 until payloadLen) {
                    val decrypted = ((rawBuf[2 + i].toInt() and 0xFF) xor readBitmask).toByte()
                    if (pos < expectedSize) {
                        result[pos++] = decrypted
                    } else {
                        rxQueue.add(decrypted)
                    }
                }
            } else {
                try {
                    Thread.sleep(10)
                } catch (ignored: InterruptedException) {
                    break
                }
            }
        }
        return result.copyOfRange(0, pos)
    }

    private fun purgeRx() {
        val conn = connection ?: return
        val epIn = endpointIn ?: return
        rxQueue.clear()
        val temp = ByteArray(64)
        for (i in 0 until 5) {
            val r = conn.bulkTransfer(epIn, temp, temp.size, 20)
            if (r <= 2) break
        }
    }

    private fun resetDevice() {
        val conn = connection ?: return
        conn.controlTransfer(0x40, 0x00, 0, 0, null, 0, 1000)
    }

    private fun setLatencyTimer(value: Int) {
        val conn = connection ?: return
        conn.controlTransfer(0x40, 0x09, value, 0, null, 0, 1000)
    }

    private fun setDtr(on: Boolean) {
        val conn = connection ?: return
        conn.controlTransfer(0x40, 0x01, if (on) 0x101 else 0x100, 0, null, 0, 1000)
    }

    private fun setRts(on: Boolean) {
        val conn = connection ?: return
        conn.controlTransfer(0x40, 0x01, if (on) 0x202 else 0x200, 0, null, 0, 1000)
    }

    private fun setLineProperty(dataBits: Int = 8) {
        val conn = connection ?: return
        conn.controlTransfer(0x40, 0x04, dataBits and 0x0F, 0, null, 0, 1000)
    }

    private fun setFlowControl() {
        val conn = connection ?: return
        conn.controlTransfer(0x40, 0x02, 0, 0, null, 0, 1000)
    }

    private fun setBaudrate(baudrate: Int) {
        val clock = 3000000
        val div8 = Math.round((8.0 * clock) / baudrate).toInt()
        var div = div8 shr 3
        div = div or (FRAC_DIV_CODE[div8 and 0x7] shl 14)
        if (div == 1) {
            div = 0
        } else if (div == 0x4001) {
            div = 1
        }
        val value = div and 0xFFFF
        val index = (div shr 16) and 0xFFFF
        val conn = connection ?: throw IOException("Not connected")
        val res = conn.controlTransfer(0x40, 0x03, value, index, null, 0, 1000)
        if (res < 0) throw IOException("Failed to set FTDI baudrate")
    }

    override fun runDiagnostic(): String = synchronized(transportLock) {
        val conn = connection ?: return "ERROR: UsbDeviceConnection is null"
        val epIn = endpointIn ?: return "ERROR: EndpointIn is null"
        val epOut = endpointOut ?: return "ERROR: EndpointOut is null"

        val sb = StringBuilder()
        sb.appendLine("=== MPPS V18 HARDWARE DIAGNOSTIC REPORT ===")

        // 1. Device Info
        sb.appendLine(String.format("USB Dev: EP_IN=0x%02X (MaxPKT=%d), EP_OUT=0x%02X (MaxPKT=%d)",
            epIn.address, epIn.maxPacketSize, epOut.address, epOut.maxPacketSize))

        // 2. Control Transfers (EEPROM / RAM reads)
        val regs = intArrayOf(0x0000, 0x1000, 0x2000, 0x3000, 0x4000, 0x5000, 0x6000)
        for (reg in regs) {
            try {
                val buf = readEE(reg, 4)
                val hex = buf.joinToString(" ") { String.format("%02X", it) }
                sb.appendLine(String.format("Reg 0x%04X: [%s]", reg, hex))
            } catch (e: Throwable) {
                sb.appendLine(String.format("Reg 0x%04X: FAIL (%s)", reg, e.message))
            }
        }

        // 3. FTDI Modem & Line Status via Request 0x06
        try {
            val statusBuf = ByteArray(2)
            val rStatus = conn.controlTransfer(0xC0, 0x06, 0, 0, statusBuf, 2, 1000)
            sb.appendLine(String.format("FTDI Modem/Line Status (Req 0x06): len=%d -> %02X %02X",
                rStatus, statusBuf[0], statusBuf[1]))
        } catch (e: Throwable) {
            sb.appendLine("FTDI Status Req 0x06: FAIL (${e.message})")
        }

        // 4. Latency Timer via Request 0x0A
        try {
            val latBuf = ByteArray(1)
            val rLat = conn.controlTransfer(0xC0, 0x0A, 0, 0, latBuf, 1, 1000)
            sb.appendLine(String.format("FTDI Latency Timer (Req 0x0A): len=%d -> %d ms",
                rLat, latBuf[0].toInt() and 0xFF))
        } catch (e: Throwable) {
            sb.appendLine("FTDI Latency Req 0x0A: FAIL (${e.message})")
        }

        // 5. Test Bulk IN without sending anything
        val rawBuf = ByteArray(64)
        val r0 = conn.bulkTransfer(epIn, rawBuf, rawBuf.size, 100)
        val r0Hex = if (r0 > 0) (0 until minOf(r0, 8)).map { String.format("%02X", rawBuf[it]) }.joinToString(" ") else "none"
        sb.appendLine(String.format("Bulk IN idle read: r=%d [%s]", r0, r0Hex))

        // 6. Test DTR / RTS variations
        for (dtr in listOf(false, true)) {
            setDtr(dtr)
            setRts(dtr)
            val r = conn.bulkTransfer(epIn, rawBuf, rawBuf.size, 50)
            val hex = if (r > 0) (0 until minOf(r, 8)).map { String.format("%02X", rawBuf[it]) }.joinToString(" ") else "none"
            sb.appendLine(String.format("DTR/RTS=%b: Bulk IN r=%d [%s]", dtr, r, hex))
        }

        // 7. Bulk Command Probes: Unmasked vs Masked
        val probes = listOf(
            "Unmasked 0x22 (Version)" to byteArrayOf(0x22),
            "Masked 0x22 (Version)" to byteArrayOf((0x22 xor writeBitmask).toByte()),
            "Unmasked 0x31 (Version Code)" to byteArrayOf(0x31),
            "Masked 0x31 (Version Code)" to byteArrayOf((0x31 xor writeBitmask).toByte()),
            "Unmasked 21 55 (Challenge)" to byteArrayOf(0x21, 0x55),
            "Masked 21 55 (Challenge)" to byteArrayOf((0x21 xor writeBitmask).toByte(), (0x55 xor writeBitmask).toByte()),
            "Unmasked 0x20 (Unlock)" to byteArrayOf(0x20),
            "Masked 0x20 (Unlock)" to byteArrayOf((0x20 xor writeBitmask).toByte())
        )

        for ((name, cmd) in probes) {
            // Drain stale
            while (conn.bulkTransfer(epIn, rawBuf, rawBuf.size, 20) > 2) {}

            val sent = conn.bulkTransfer(epOut, cmd, cmd.size, 500)
            val readBuf = ByteArray(256)
            val r = conn.bulkTransfer(epIn, readBuf, readBuf.size, 500)
            val rHex = if (r > 0) (0 until minOf(r, 16)).map { String.format("%02X", readBuf[it]) }.joinToString(" ") else "timeout"
            val payload = if (r > 2) {
                val pLen = r - 2
                (0 until minOf(pLen, 14)).map { String.format("%02X", (readBuf[2 + it].toInt() and 0xFF) xor readBitmask) }.joinToString(" ")
            } else "NO_PAYLOAD"

            sb.appendLine(String.format("Probe '%s' (sent %d B): raw IN len=%d [%s] -> dec=[%s]",
                name, sent, r, rHex, payload))
        }

        val report = sb.toString()
        Log.i(TAG, report)
        return report
    }

    companion object {
        private const val TAG = "MPPS_HARDWARE"
        private val FRAC_DIV_CODE = intArrayOf(0, 3, 2, 4, 1, 5, 6, 7)
    }
}
