package com.golf5.edc16flasher.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityAccessTest {

    @Test
    fun mockAlgorithmCalculatesExpectedVectorAndIsVerified() {
        val seed = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        val expectedKey = byteArrayOf(
            0xB7.toByte(),
            0x91.toByte(),
            0xF3.toByte(),
            0xDD.toByte()
        )

        assertEquals("mock-xor-a5", MockSecurityAlgorithm.id)
        assertTrue(MockSecurityAlgorithm.verified)
        assertArrayEquals(expectedKey, MockSecurityAlgorithm.calculateKey(seed))
    }

    @Test
    fun legacyBlsAlgorithmIsExplicitlyUnverified() {
        assertEquals("legacy-bls-formula-v1", LegacyBlsSecurityAlgorithm.id)
        assertFalse(LegacyBlsSecurityAlgorithm.verified)
    }

    @Test
    fun legacyAlgorithmReturnsDeterministicKey() {
        val seed = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val key1 = LegacyBlsSecurityAlgorithm.calculateKey(seed)
        val key2 = LegacyBlsSecurityAlgorithm.calculateKey(seed)
        assertEquals(4, key1.size)
        assertArrayEquals(key1, key2)
    }
}
