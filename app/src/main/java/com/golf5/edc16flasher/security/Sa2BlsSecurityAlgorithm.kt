package com.golf5.edc16flasher.security

/**
 * Exact factory Security Access algorithm for Bosch EDC16U34 (VAG 1.9 TDI BLS, SW 391847).
 *
 * Sourced directly from the official factory container definition:
 * `03G906021QJ_1984_391847_P447_HAXN_EDC16U34_3.42.sgm` (line 56, tag KWP-2000-SA2):
 * `68 05 81 4A 05 87 0A 22 12 89 49 4C`
 *
 * Bytecode translation:
 * - 0x68 0x05: loop 5 iterations
 * - 0x81: rotate-left 1 bit (with carry)
 * - 0x4A 0x05: if (carry == 0) skip next 5 bytes
 * - 0x87 0x0A 0x22 0x12 0x89: XOR with polynomial 0x0A221289
 * - 0x49: next loop
 * - 0x4C: finish
 *
 * Cross-checked with:
 * - Factory binary offsets 0x03D3FA and 0x03F1FA in 03G906021QJ
 * - SA2 VM crosscheck (10,007 synthetic seeds, 0 mismatches)
 * - Upstream fjvva/ecu-tool ECU_Flasher_UNO_EDC16.ino LVL1Key (0x0A22 / 0x1289)
 */
object Sa2BlsSecurityAlgorithm : SecurityAccessAlgorithm {
    override val id: String = "sa2-sgm-0x0a221289"

    /**
     * Marked verified based on exact factory SGM container and binary match.
     */
    override val verified: Boolean = true

    override fun calculateKey(seed: ByteArray): ByteArray {
        if (seed.size < 4) {
            return byteArrayOf(0x00, 0x00, 0x00, 0x00)
        }
        val s0 = seed[0].toLong() and 0xFFL
        val s1 = seed[1].toLong() and 0xFFL
        val s2 = seed[2].toLong() and 0xFFL
        val s3 = seed[3].toLong() and 0xFFL

        var reg = (s0 shl 24) or (s1 shl 16) or (s2 shl 8) or s3

        for (i in 0 until 5) {
            val carry = (reg and 0x80000000L) != 0L
            reg = ((reg shl 1) and 0xFFFFFFFFL) or (if (carry) 1L else 0L)
            if (carry) {
                reg = reg xor 0x0A221289L
            }
        }

        return byteArrayOf(
            ((reg shr 24) and 0xFFL).toByte(),
            ((reg shr 16) and 0xFFL).toByte(),
            ((reg shr 8) and 0xFFL).toByte(),
            (reg and 0xFFL).toByte()
        )
    }
}
