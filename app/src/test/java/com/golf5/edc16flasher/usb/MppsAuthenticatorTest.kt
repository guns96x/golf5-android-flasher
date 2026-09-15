package com.golf5.edc16flasher.usb

import com.golf5.edc16flasher.usb.MppsAuthenticator.MppsAuthResult.KnownResponse
import com.golf5.edc16flasher.usb.MppsAuthenticator.MppsAuthResult.UnknownChallenge
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MppsAuthenticatorTest {

    private val auth = MppsAuthenticator
    private val dummySecNum = ByteArray(8)

    private fun hex(s: String): ByteArray {
        require(s.length % 2 == 0) { "hex string must have even length" }
        return ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    @Test
    fun vector1MatchesCapture() {
        val result = auth.responseFor(hex("1EB987D7"), dummySecNum)
        assertArrayEquals(
            hex("65E3DBEE"),
            (result as KnownResponse).bytes,
        )
    }

    @Test
    fun vector2MatchesCapture() {
        val result = auth.responseFor(hex("DB0B83ED"), dummySecNum)
        assertArrayEquals(
            hex("51D6EC90"),
            (result as KnownResponse).bytes,
        )
    }

    @Test
    fun unknownChallengeFailsClosed() {
        val result = auth.responseFor(hex("01020304"), dummySecNum)
        assertTrue("Expected UnknownChallenge for unknown vector", result is UnknownChallenge)
    }

    @Test
    fun unknownChallengeCarriesHex() {
        val result = auth.responseFor(hex("DEADBEEF"), dummySecNum) as UnknownChallenge
        assertTrue(result.challengeHex.equals("DEADBEEF", ignoreCase = true))
    }

    @Test
    fun knownResponseReturnsCopy() {
        val r1 = (auth.responseFor(hex("1EB987D7"), dummySecNum) as KnownResponse).bytes
        val r2 = (auth.responseFor(hex("1EB987D7"), dummySecNum) as KnownResponse).bytes
        // Must be equal content but not same reference (defensive copy)
        assertArrayEquals(r1, r2)
        r1[0] = 0x00
        assertArrayEquals(hex("65E3DBEE"), r2)
    }

    @Test
    fun genericFallbackFormulaNotPresent() {
        // Verify that the generic 0x78D3035C / 0x3F30029D fallback is NOT in the
        // verified path. We compute what it would produce for a known challenge and
        // confirm the authenticator does NOT return that value.
        val challenge = hex("CAFEBABE")
        val c0 = 0xCAL; val c1 = 0xFEL; val c2 = 0xBAL; val c3 = 0xBEL
        val cVal = c0 or (c1 shl 8) or (c2 shl 16) or (c3 shl 24)
        val fallback = ((cVal * 0x78D3035CL) + 0x3F30029DL) and 0xFFFFFFFFL
        val fallbackBytes = byteArrayOf(
            (fallback and 0xFF).toByte(),
            ((fallback shr 8) and 0xFF).toByte(),
            ((fallback shr 16) and 0xFF).toByte(),
            ((fallback shr 24) and 0xFF).toByte(),
        )

        val result = auth.responseFor(challenge, dummySecNum)
        assertTrue("Should be UnknownChallenge, not a fallback arithmetic result", result is UnknownChallenge)
        // Suppress unused warning for fallbackBytes — it's here to document the vector
        fallbackBytes.size
    }
}
