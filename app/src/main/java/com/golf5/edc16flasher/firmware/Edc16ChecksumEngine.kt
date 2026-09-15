package com.golf5.edc16flasher.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class BlockVerification(
    val block: ChecksumBlock,
    val observedResidue: Long,
    val expectedResidue: Long,
    val isValid: Boolean,
)

data class ChecksumVerification(
    val profile: EcuFirmwareProfile,
    val blockResults: List<BlockVerification>,
    val isValid: Boolean,
)

object Edc16ChecksumEngine {

    fun verify(
        image: ByteArray,
        profile: EcuFirmwareProfile = EcuFirmwareProfile.EDC16U34_03G906021QJ_391847,
    ): ChecksumVerification {
        if (image.size != profile.fullImageSize) {
            throw IllegalArgumentException(
                "Image size mismatch: expected ${profile.fullImageSize} bytes, got ${image.size}"
            )
        }

        val buf = ByteBuffer.wrap(image).order(ByteOrder.BIG_ENDIAN)
        val results = profile.checksumBlocks.map { block ->
            var sum = 0L
            for (addr in block.start until block.endExclusive step 4) {
                sum = (sum + (buf.getInt(addr).toLong() and 0xFFFFFFFFL)) and 0xFFFFFFFFL
            }
            BlockVerification(
                block = block,
                observedResidue = sum,
                expectedResidue = block.expectedResidue,
                isValid = sum == block.expectedResidue,
            )
        }

        val allValid = results.all { it.isValid }
        return ChecksumVerification(profile, results, allValid)
    }

    fun fix(
        image: ByteArray,
        profile: EcuFirmwareProfile = EcuFirmwareProfile.EDC16U34_03G906021QJ_391847,
    ): ByteArray {
        if (image.size != profile.fullImageSize) {
            throw IllegalArgumentException(
                "Image size mismatch: expected ${profile.fullImageSize} bytes, got ${image.size}"
            )
        }

        val copy = image.copyOf()
        val buf = ByteBuffer.wrap(copy).order(ByteOrder.BIG_ENDIAN)

        for (block in profile.checksumBlocks) {
            var bodySum = 0L
            for (addr in block.start until block.endExclusive step 4) {
                if (addr != block.patchWordOffset) {
                    bodySum = (bodySum + (buf.getInt(addr).toLong() and 0xFFFFFFFFL)) and 0xFFFFFFFFL
                }
            }
            val neededWord = ((block.expectedResidue - bodySum) and 0xFFFFFFFFL).toInt()
            buf.putInt(block.patchWordOffset, neededWord)
        }

        val verification = verify(copy, profile)
        if (!verification.isValid) {
            throw IllegalStateException("Checksum fix failed verification: $verification")
        }

        return copy
    }
}
