package com.golf5.edc16flasher.usb

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import kotlin.concurrent.thread

object TermuxBridgeServer {
    private const val TAG = "TERMUX_BRIDGE"
    private const val PORT = 8888
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var running = false
    private var transport: MppsHardwareTransport? = null

    fun start(mppsTransport: MppsHardwareTransport) {
        transport = mppsTransport
        if (running) return
        running = true

        thread(name = "TermuxBridgeThread", isDaemon = true) {
            try {
                val bindAddr = java.net.InetAddress.getByName("127.0.0.1")
                serverSocket = ServerSocket(PORT, 50, bindAddr)
                Log.i(TAG, "Termux Bridge Server listening securely on 127.0.0.1:$PORT")
                while (running) {
                    val client = serverSocket?.accept() ?: break
                    thread(name = "TermuxClientThread", isDaemon = true) { handleClient(client) }
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Server error: ${e.message}")
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (ignored: Exception) {}
        serverSocket = null
        transport = null
    }

    private fun handleClient(client: Socket) {
        if (!client.inetAddress.isLoopbackAddress) {
            Log.w(TAG, "Rejected unauthorized non-loopback connection from ${client.inetAddress}")
            try { client.close() } catch (ignored: Exception) {}
            return
        }
        Log.i(TAG, "Client connected from loopback ${client.inetAddress}")
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val writer = PrintWriter(OutputStreamWriter(client.getOutputStream()), true)

            writer.println("OK MPPS_BRIDGE_V1_READY")
            writer.flush()

            while (running) {
                val line = reader.readLine() ?: break
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val parts = trimmed.split(" ")
                val cmd = parts[0].uppercase(Locale.US)

                val response = try {
                    executeCommand(cmd, parts)
                } catch (e: Exception) {
                    "ERR ${e.message}"
                }
                writer.println(response)
                writer.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Client connection ended: ${e.message}")
        } finally {
            try { client.close() } catch (ignored: Exception) {}
        }
    }

    private fun executeCommand(cmd: String, parts: List<String>): String {
        val t = transport ?: return "ERR TRANSPORT_NOT_CONNECTED"
        return when (cmd) {
            "PING" -> "PONG"
            "VOLTAGE" -> {
                val v = t.getBatteryVoltage() ?: 0.0f
                String.format(Locale.US, "OK %.2f", v)
            }
            "READ_EE" -> {
                val addr = parts[1].toInt(16)
                val len = if (parts.size > 2) parts[2].toInt() else 2
                val data = t.readEEPublic(addr, len)
                "OK " + data.joinToString("") { String.format("%02X", it) }
            }
            "WRITE_EE" -> {
                "ERR_DISABLED: EEPROM writing is disabled for safety"
            }
            "WRITE_RAW" -> {
                val bytes = parseHex(parts[1])
                t.writeRawPublic(bytes, masked = false)
                "OK ${bytes.size}"
            }
            "WRITE_MASKED" -> {
                val bytes = parseHex(parts[1])
                t.writeRawPublic(bytes, masked = true)
                "OK ${bytes.size}"
            }
            "READ_RAW" -> {
                val len = if (parts.size > 1) parts[1].toInt() else 64
                val timeout = if (parts.size > 2) parts[2].toInt() else 1000
                val data = t.readRawPublic(len, timeout)
                "OK " + data.joinToString("") { String.format("%02X", it) }
            }
            "CONTROL" -> {
                val reqType = parts[1].toInt(16)
                val req = parts[2].toInt(16)
                val value = parts[3].toInt(16)
                val index = parts[4].toInt(16)
                val len = parts[5].toInt()
                val isOut = (reqType and 0x80) == 0
                val buf = if (isOut && parts.size > 6) parseHex(parts[6]) else ByteArray(len)
                val res = t.controlTransfer(reqType, req, value, index, buf, len, 1000)
                if (isOut) {
                    "OK $res"
                } else {
                    if (res >= 0) {
                        "OK " + buf.copyOfRange(0, res).joinToString("") { String.format("%02X", it) }
                    } else {
                        "ERR CONTROL_FAIL_$res"
                    }
                }
            }
            "SET_BAUD" -> {
                val baud = parts[1].toInt()
                t.setBaudratePublic(baud)
                "OK"
            }
            "SET_DTR" -> {
                val on = parts[1] == "1" || parts[1].equals("true", true)
                t.setDtrPublic(on)
                "OK"
            }
            "SET_RTS" -> {
                val on = parts[1] == "1" || parts[1].equals("true", true)
                t.setRtsPublic(on)
                "OK"
            }
            "DIAG" -> {
                val report = t.runDiagnostic()
                "OK " + report.replace("\n", "[NL]")
            }
            "FAST_INIT" -> {
                val pulse = if (parts.size > 1) parts[1].toInt() else 25
                val hex = if (parts.size > 2) parts[2] else "8101F181F4"
                t.sendFastInit(pulse, parseHex(hex))
                "OK"
            }
            "CAN_SETUP" -> {
                val timing = if (parts.size > 1) parts[1].toInt() else 7
                val txId = if (parts.size > 2) parts[2].toInt(16) else 0x200
                val ok = t.setupCan(busTiming = timing, txCanId = txId)
                "OK $ok"
            }
            "CAN_SEND" -> {
                val data = parseHex(parts[1])
                val ok = t.sendCanMessage(data)
                "OK $ok"
            }
            "CAN_RECV" -> {
                val timeout = if (parts.size > 1) parts[1].toInt() else 1000
                val data = t.receiveCanMessage(timeout)
                if (data != null) "OK " + data.joinToString("") { String.format("%02X", it) } else "TIMEOUT"
            }
            "ECU_ID" -> {
                val idStr = t.queryEcuIdentification()
                "OK $idStr"
            }
            "REINIT" -> {
                t.reinitHardwarePublic()
                "OK"
            }
            else -> "ERR UNKNOWN_COMMAND $cmd"
        }
    }

    private fun parseHex(hex: String): ByteArray {
        val clean = hex.replace(" ", "").replace("0x", "")
        val len = clean.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
