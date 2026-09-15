package com.golf5.edc16flasher.usb

import com.golf5.edc16flasher.protocol.KwpTransport

interface IUsbTransport : KwpTransport {
    val isConnected: Boolean
    override val isPhysical: Boolean
        get() = true

    fun open(baudRate: Int = 10400): Boolean
    fun close()
    override fun getBatteryVoltage(): Float? = null
    override fun sendFastInit(pulseMs: Int, initialPayload: ByteArray) = Unit
    fun runDiagnostic(): String = "Not supported on this transport"
}