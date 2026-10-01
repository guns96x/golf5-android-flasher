package com.golf5.edc16flasher.usb

import com.golf5.edc16flasher.usb.BridgeCommandPolicy.Decision
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeCommandPolicyTest {

    private fun decide(line: String, dev: Boolean = false, busy: Boolean = false): Decision {
        val parts = line.trim().split(Regex("\\s+"))
        return BridgeCommandPolicy.authorize(parts[0].uppercase(), parts, dev, busy)
    }

    @Test
    fun hexParserAcceptsPlainPrefixedAndSpacedPairs() {
        val expected = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        assertArrayEquals(expected, BridgeCommandPolicy.parseHexStrict("AABBCC"))
        assertArrayEquals(expected, BridgeCommandPolicy.parseHexStrict("aabbcc"))
        assertArrayEquals(expected, BridgeCommandPolicy.parseHexStrict("0xAABBCC"))
        assertArrayEquals(expected, BridgeCommandPolicy.parseHexStrict("AA BB CC"))
    }

    @Test
    fun hexParserRejectsMalformedInput() {
        for (bad in listOf("ABC", "00GG", "", "AA0xBB", "0x0xAA", "A ABB", "-1")) {
            assertThrows("should reject '$bad'", InvalidHexException::class.java) {
                BridgeCommandPolicy.parseHexStrict(bad)
            }
        }
    }

    @Test
    fun readCommandsAreAvailableByDefault() {
        for (cmd in listOf("PING", "VOLTAGE", "READ_EE 1000 2", "READ_RAW 64", "DIAG", "ECU_ID")) {
            assertEquals(cmd, Decision.Allow, decide(cmd))
        }
    }

    @Test
    fun mutatingCommandsAreRefusedByDefault() {
        for (cmd in listOf("WRITE_RAW AABB", "WRITE_MASKED AABB", "SET_BAUD 9600", "FAST_INIT", "CAN_SEND 00", "REINIT")) {
            val d = decide(cmd)
            assertTrue(cmd, d is Decision.Deny && d.response.startsWith("ERR_READ_ONLY"))
        }
    }

    @Test
    fun outControlNeedsDeveloperModeButInControlDoesNot() {
        assertTrue(decide("CONTROL 40 01 0000 0000 0") is Decision.Deny)
        assertEquals(Decision.Allow, decide("CONTROL 40 01 0000 0000 0", dev = true))
        assertEquals(Decision.Allow, decide("CONTROL C0 05 0000 0000 2"))
    }

    @Test
    fun eepromWriteIsNeverAllowed() {
        assertTrue(decide("WRITE_EE 1000 AA", dev = true) is Decision.Deny)
    }

    @Test
    fun busyRefusesEverythingExceptPing() {
        assertEquals(Decision.Allow, decide("PING", busy = true))
        val d = decide("READ_RAW 64", busy = true)
        assertTrue(d is Decision.Deny && d.response.startsWith("ERR_BUSY"))
        assertTrue(decide("WRITE_RAW AA", dev = true, busy = true) is Decision.Deny)
    }
}
