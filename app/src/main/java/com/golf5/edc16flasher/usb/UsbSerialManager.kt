package com.golf5.edc16flasher.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.ProlificSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.golf5.edc16flasher.protocol.KwpTransport
import java.io.IOException

/**
 * Universal Diagnostic Hardware Manager:
 * Seamlessly auto-detects and drives MPPS v18 Hardware (0x1C43:0x0500)
 * and generic K-Line KKL interfaces (FTDI, CH340, CP2102).
 */
class UsbSerialManager(private val context: Context) : KwpTransport {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var activeTransport: IUsbTransport? = null

    val isConnected: Boolean
        get() = activeTransport != null && activeTransport!!.isConnected

    override val isPhysical: Boolean
        get() = activeTransport?.isPhysical ?: false

    override val isMpps: Boolean
        get() = activeTransport?.isMpps == true

    override val transportName: String
        get() = activeTransport?.transportName ?: "Не підключено"

    fun getRawDevices(): Collection<UsbDevice> = usbManager.deviceList.values

    fun isMppsDevice(dev: UsbDevice): Boolean {
        if (dev.vendorId == 0x1C43 && dev.productId == 0x0500) return true
        val name = try { dev.productName ?: "" } catch (e: Throwable) { "" }
        val mfg = try { dev.manufacturerName ?: "" } catch (e: Throwable) { "" }
        return name.contains("Amt Flash", ignoreCase = true) || mfg.contains("AMT", ignoreCase = true)
    }

    fun findSupportedDevices(): List<UsbDevice> {
        val list = mutableListOf<UsbDevice>()
        for (dev in usbManager.deviceList.values) {
            if (isMppsDevice(dev)) {
                list.add(0, dev) // Prioritize MPPS
            } else if (dev.vendorId in listOf(0x0403, 0x1A86, 0x10C4, 0x067B)) {
                list.add(dev)
            }
        }
        return list
    }

    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    fun requestPermission(device: UsbDevice, onPermissionRequested: () -> Unit) {
        if (usbManager.hasPermission(device)) return
        val flags = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            `package` = context.packageName
        }
        val permissionIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
        usbManager.requestPermission(device, permissionIntent)
        onPermissionRequested()
    }

    fun open(device: UsbDevice, baudRate: Int = 10400): Boolean {
        close()
        Log.i(TAG, "Opening adapter: VID=0x${Integer.toHexString(device.vendorId)} PID=0x${Integer.toHexString(device.productId)}")

        if (isMppsDevice(device)) {
            Log.i(TAG, "Instantiating Native MPPS v18 Hardware Transport...")
            val mppsTransport = MppsHardwareTransport(context, usbManager, device)
            if (mppsTransport.open(baudRate)) {
                activeTransport = mppsTransport
                TermuxBridgeServer.start(mppsTransport)
                return true
            }
            Log.w(TAG, "Native MPPS open failed, attempting fallback...")
        }

        // Standard KKL Serial Fallback
        val customTable = ProbeTable()
        when (device.vendorId) {
            0x0403, 0x1C43 -> customTable.addProduct(device.vendorId, device.productId, FtdiSerialDriver::class.java)
            0x1A86 -> customTable.addProduct(device.vendorId, device.productId, Ch34xSerialDriver::class.java)
            0x10C4 -> customTable.addProduct(device.vendorId, device.productId, Cp21xxSerialDriver::class.java)
            0x067B -> customTable.addProduct(device.vendorId, device.productId, ProlificSerialDriver::class.java)
            else -> customTable.addProduct(device.vendorId, device.productId, CdcAcmSerialDriver::class.java)
        }
        val prober = UsbSerialProber(customTable)
        val driver = prober.probeDevice(device) ?: run {
            Log.e(TAG, "No compatible USB serial driver found for device")
            return false
        }

        val conn = usbManager.openDevice(device) ?: run {
            Log.e(TAG, "Could not open connection to UsbDevice")
            return false
        }

        val port = driver.ports.firstOrNull() ?: run {
            conn.close()
            return false
        }

        val serialTransport = SerialHardwareTransport(port, conn)
        if (serialTransport.open(baudRate)) {
            activeTransport = serialTransport
            return true
        } else {
            serialTransport.close()
            try { conn.close() } catch (ignored: Exception) {}
        }

        return false
    }

    fun close() {
        TermuxBridgeServer.stop()
        try {
            activeTransport?.close()
        } catch (ignored: Exception) {
        } finally {
            activeTransport = null
        }
    }

    override fun write(data: ByteArray, timeoutMs: Int) {
        activeTransport?.write(data, timeoutMs) ?: throw IOException("USB Transport is not open")
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        return activeTransport?.read(buffer, timeoutMs) ?: throw IOException("USB Transport is not open")
    }

    override fun sendFastInit(pulseMs: Int, initialPayload: ByteArray) {
        activeTransport?.sendFastInit(pulseMs, initialPayload)
    }

    override fun getBatteryVoltage(): Float? {
        return activeTransport?.getBatteryVoltage()
    }

    fun openMockEmulator(): Boolean {
        close()
        Log.i(TAG, "Opening in-memory EDC16U34 Mock Emulator...")
        val mock = MockEdc16Transport(context)
        if (mock.open()) {
            activeTransport = mock
            return true
        }
        return false
    }

    fun probeCanBus(): String? {
        val mpps = activeTransport as? MppsHardwareTransport ?: return null
        return mpps.probeCanBus()
    }

    fun queryEcuIdentificationMpps(): String {
        val mock = activeTransport as? MockEdc16Transport
        if (mock != null) return mock.queryEcuIdentification()
        val mpps = activeTransport as? MppsHardwareTransport ?: return "Не MPPS адаптер"
        return mpps.queryEcuIdentification()
    }

    fun runDiagnostic(): String {
        return activeTransport?.runDiagnostic() ?: "USB Transport is not active"
    }

    companion object {
        private const val TAG = "USB_MGR"
        const val ACTION_USB_PERMISSION = "com.golf5.edc16flasher.USB_PERMISSION"
    }
}
