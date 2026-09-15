package com.golf5.edc16flasher.usb

import java.io.IOException

/**
 * MPPS challenge/response authenticator.
 *
 * Only known-captured vectors are accepted as [MppsAuthResult.KnownResponse].
 * Any unrecognized challenge produces [MppsAuthResult.UnknownChallenge] and the
 * caller must NOT substitute a guessed arithmetic result.
 *
 * The generic fallback formula (0x78D3035C / 0x3F30029D) is intentionally NOT
 * included in the verified path — it is preserved only in git history for later
 * research. See commit history on MppsHardwareTransport.kt for the original code.
 */
object MppsAuthenticator {

    /** Result of a challenge lookup. */
    sealed interface MppsAuthResult {
        /** A captured, verified response for the given challenge. */
        data class KnownResponse(val bytes: ByteArray) : MppsAuthResult {
            override fun equals(other: Any?) =
                other is KnownResponse && bytes.contentEquals(other.bytes)
            override fun hashCode() = bytes.contentHashCode()
        }

        /** Challenge is not in the captured vector table — do not guess. */
        data class UnknownChallenge(val challengeHex: String) : MppsAuthResult
    }

    /**
     * Captured MPPS v18 challenge→response vectors from Windows USB trace.
     * Key: uppercase 8-hex-char string of the 4-byte challenge.
     * Value: 4-byte response.
     */
    private val knownVectors: Map<String, ByteArray> = mapOf(
        "1EB987D7" to byteArrayOf(0x65.toByte(), 0xE3.toByte(), 0xDB.toByte(), 0xEE.toByte()),
        "DB0B83ED" to byteArrayOf(0x51.toByte(), 0xD6.toByte(), 0xEC.toByte(), 0x90.toByte()),
    )

    /**
     * Returns [MppsAuthResult.KnownResponse] for a recognized 4-byte challenge, or
     * [MppsAuthResult.UnknownChallenge] for anything else.
     *
     * @param challenge  exactly 4 bytes from hardware
     * @param secNum     the 8-byte security number read from EEPROM 0x5000 (unused in
     *                   captured vectors; kept in signature for future research)
     */
    fun responseFor(challenge: ByteArray, secNum: ByteArray): MppsAuthResult {
        require(challenge.size >= 4) { "challenge must be at least 4 bytes" }
        val key = challenge.take(4).joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        val response = knownVectors[key]
        return if (response != null) {
            MppsAuthResult.KnownResponse(response.copyOf())
        } else {
            MppsAuthResult.UnknownChallenge(key)
        }
    }
}

/** Thrown by [MppsHardwareTransport] when [MppsAuthenticator] returns [MppsAuthenticator.MppsAuthResult.UnknownChallenge]. */
class MppsAuthenticationUnverifiedException(challengeHex: String) : IOException(
    "MPPS authentication challenge 0x$challengeHex is not in the verified vector table. " +
        "Physical write capability disabled. Capture the Windows USB trace and add the vector."
)
