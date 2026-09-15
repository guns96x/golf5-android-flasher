package com.golf5.edc16flasher.usb

import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException

/**
 * Fallback Hardware Transport for Standard K-Line OBD2 Adapters (FTDI FT232, CH340, CP2102)
 */
class SerialHardwareTransport(
    private val port: UsbSerialPort,
    private val connection: UsbDeviceConnection
) : IUsbTransport {

    override val isConnected: Boolean
        get() = port.isOpen

    override val isMpps: Boolean
        get() = false

    override val transportName: String
        get() = "K-Line Serial (${port.driver.javaClass.simpleName.replace("SerialDriver", "")})"

    override fun open(baudRate: Int): Boolean {
        return try {
            port.open(connection)
            try {
                port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            } catch (e: Throwable) {
                try {
                    port.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                } catch (ignored: Throwable) {}
            }
            try { port.dtr = true } catch (ignored: Throwable) {}
            try { port.rts = true } catch (ignored: Throwable) {}
            true
        } catch (t: Throwable) {
            t.printStackTrace()
            false
        }
    }

    override fun close() {
        try {
            port.close()
        } catch (ignored: Exception) {}
    }

    override fun write(data: ByteArray, timeoutMs: Int) {
        if (!port.isOpen) throw IOException("Serial port is not open")
        port.write(data, timeoutMs)
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        if (!port.isOpen) throw IOException("Serial port is not open")
        return port.read(buffer, timeoutMs)
    }

    override fun sendFastInit(pulseMs: Int, initialPayload: ByteArray) {
        try {
            port.setBreak(true)
            Thread.sleep(pulseMs.toLong())
            port.setBreak(false)
            if (initialPayload.isNotEmpty()) {
                Thread.sleep(25)
                write(initialPayload, 1000)
            }
        } catch (ignored: Throwable) {}
    }

    override fun getBatteryVoltage(): Float? = null
}
