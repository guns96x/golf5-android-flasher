package com.golf5.edc16flasher.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Sa2BlsSecurityAlgorithmTest {

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "")
        val result = ByteArray(clean.length / 2)
        for (i in result.indices) {
            result[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }

    @Test
    fun algorithmMetadataIsCorrect() {
        assertEquals("sa2-sgm-0x0a221289", Sa2BlsSecurityAlgorithm.id)
        assertTrue(Sa2BlsSecurityAlgorithm.verified)
    }

    @Test
    fun crosscheckSgmFactoryVectors() {
        val testVectors = listOf(
            "00000000" to "00000000",
            "00000001" to "00000020",
            "7FFFFFFF" to "98011618",
            "80000000" to "A2212880",
            "FFFFFFFF" to "3A203E98",
            "12345678" to "52CEEA10",
            "1A1B1C1D" to "5D05B438",
        )

        for ((seedHex, expectedKeyHex) in testVectors) {
            val seed = hexToBytes(seedHex)
            val expected = hexToBytes(expectedKeyHex)
            val actual = Sa2BlsSecurityAlgorithm.calculateKey(seed)
            assertArrayEquals("Failed for seed $seedHex", expected, actual)
        }
    }

    @Test
    fun shortSeedReturnsZeroes() {
        val seed = byteArrayOf(0x01, 0x02)
        val expected = byteArrayOf(0x00, 0x00, 0x00, 0x00)
        assertArrayEquals(expected, Sa2BlsSecurityAlgorithm.calculateKey(seed))
    }
}
