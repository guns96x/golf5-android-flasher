package com.golf5.edc16flasher.usb

interface IUsbTransport {
    val isConnected: Boolean
    val isMpps: Boolean
    val transportName: String
    
    fun open(baudRate: Int = 10400): Boolean
    fun close()
    fun write(data: ByteArray, timeoutMs: Int = 2000)
    fun read(buffer: ByteArray, timeoutMs: Int = 2000): Int
    fun getBatteryVoltage(): Float? = null
    fun sendFastInit(pulseMs: Int = 25, initialPayload: ByteArray = ByteArray(0)) = Unit
    fun runDiagnostic(): String = "Not supported on this transport"
}