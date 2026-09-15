package com.golf5.edc16flasher.firmware

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Edc16ChecksumEngineTest {

    private val profile = EcuFirmwareProfile.EDC16U34_03G906021QJ_391847

    private fun createSyntheticImage(): ByteArray {
        val image = ByteArray(profile.fullImageSize)
        // Fill image with deterministic non-zero pattern
        for (i in image.indices) {
            image[i] = ((i * 31 + 7) and 0xFF).toByte()
        }
        return image
    }

    @Test
    fun rejectsWrongImageSize() {
        val wrongSizeImage = ByteArray(1024)
        assertThrows(IllegalArgumentException::class.java) {
            Edc16ChecksumEngine.verify(wrongSizeImage, profile)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Edc16ChecksumEngine.fix(wrongSizeImage, profile)
        }
    }

    @Test
    fun fixDoesNotMutateInputArray() {
        val input = createSyntheticImage()
        val originalSnapshot = input.copyOf()

        val fixed = Edc16ChecksumEngine.fix(input, profile)

        // Input array must not be mutated
        assertArrayEquals(originalSnapshot, input)
        // Fixed array must be a different instance
        assertFalse(input === fixed)
    }

    @Test
    fun fixMakesBothDeclaredResiduesEqualD01FE500() {
        val input = createSyntheticImage()
        val fixed = Edc16ChecksumEngine.fix(input, profile)

        val verification = Edc16ChecksumEngine.verify(fixed, profile)
        assertTrue(verification.isValid)
        assertEquals(2, verification.blockResults.size)
        assertEquals(0xD01FE500L, verification.blockResults[0].observedResidue)
        assertEquals(0xD01FE500L, verification.blockResults[1].observedResidue)
        assertTrue(verification.blockResults[0].isValid)
        assertTrue(verification.blockResults[1].isValid)
    }

    @Test
    fun verifyFailsAfterOneCoveredByteIsChanged() {
        val input = createSyntheticImage()
        val fixed = Edc16ChecksumEngine.fix(input, profile)

        // Mutate one byte in Block 1
        fixed[0x180010] = (fixed[0x180010] + 1).toByte()

        val verification = Edc16ChecksumEngine.verify(fixed, profile)
        assertFalse(verification.isValid)
        assertFalse(verification.blockResults[0].isValid)
        assertTrue(verification.blockResults[1].isValid)
    }

    @Test
    fun bytesOutsideDeclaredChecksumBlocksAreUnchanged() {
        val input = createSyntheticImage()
        val fixed = Edc16ChecksumEngine.fix(input, profile)

        // Range before calibration: [0x0, 0x180000)
        assertArrayEquals(
            input.copyOfRange(0, 0x180000),
            fixed.copyOfRange(0, 0x180000)
        )
        // Range after checksum blocks: [0x1FE000, 0x200000)
        assertArrayEquals(
            input.copyOfRange(0x1FE000, profile.fullImageSize),
            fixed.copyOfRange(0x1FE000, profile.fullImageSize)
        )
    }
}
