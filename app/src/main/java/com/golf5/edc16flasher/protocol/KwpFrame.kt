package com.golf5.edc16flasher.protocol

import java.io.IOException

data class KwpFrame(
    val target: Int,
    val source: Int,
    val serviceId: Int,
    val payload: ByteArray,
    val raw: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as KwpFrame
        if (target != other.target) return false
        if (source != other.source) return false
        if (serviceId != other.serviceId) return false
        if (!payload.contentEquals(other.payload)) return false
        if (!raw.contentEquals(other.raw)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = target
        result = 31 * result + source
        result = 31 * result + serviceId
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + raw.contentHashCode()
        return result
    }
}

class KwpFrameException(message: String) : IOException(message)

class KwpNegativeResponseException(val failedSid: Int, val nrc: Int) : IOException(
    "ECU negative response: SID 0x%02X NRC 0x%02X".format(failedSid, nrc)
)
