package com.golf5.edc16flasher.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException

class UsbSerialManager(private val context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbPort: UsbSerialPort? = null

    val isConnected: Boolean
        get() = usbPort != null && usbPort!!.isOpen

    fun findSupportedDevices(): List<UsbSerialDriver> {
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        return availableDrivers
    }

    fun requestPermission(driver: UsbSerialDriver, onPermissionRequested: () -> Unit) {
        val flags = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val permissionIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION), flags
        )
        usbManager.requestPermission(driver.device, permissionIntent)
        onPermissionRequested()
    }

    fun open(driver: UsbSerialDriver, baudRate: Int = 10400): Boolean {
        val connection = usbManager.openDevice(driver.device) ?: return false
        val port = driver.ports[0]
        try {
            port.open(connection)
            // K-Line standard for VAG EDC16: 10400 baud, 8 data bits, 1 stop bit, no parity
            port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port.dtr = true
            port.rts = true
            usbPort = port
            return true
        } catch (e: IOException) {
            e.printStackTrace()
            return false
        }
    }

    fun close() {
        try {
            usbPort?.close()
        } catch (ignored: Exception) {
        } finally {
            usbPort = null
        }
    }

    fun write(data: ByteArray, timeoutMs: Int = 2000) {
        usbPort?.write(data, timeoutMs) ?: throw IOException("USB Port is not open")
    }

    fun read(buffer: ByteArray, timeoutMs: Int = 2000): Int {
        return usbPort?.read(buffer, timeoutMs) ?: throw IOException("USB Port is not open")
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.golf5.edc16flasher.USB_PERMISSION"
    }
}
