package com.golf5.edc16flasher.protocol

interface KwpTransport {
    val transportName: String
    val isPhysical: Boolean
    val isMpps: Boolean

    fun write(data: ByteArray, timeoutMs: Int = 2000)
    fun read(buffer: ByteArray, timeoutMs: Int = 2000): Int
    fun getBatteryVoltage(): Float?
    fun sendFastInit(pulseMs: Int = 25, initialPayload: ByteArray = ByteArray(0))
}
