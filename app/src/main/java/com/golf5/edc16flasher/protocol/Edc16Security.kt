package com.golf5.edc16flasher.protocol

/**
 * Bosch EDC16 Seed-Key algorithm for VAG 1.9 TDI BLS
 */
object Edc16Security {
    fun calculateKey(seed: ByteArray): ByteArray {
        if (seed.size < 4) {
            return byteArrayOf(0x00, 0x00, 0x00, 0x00)
        }
        var s0 = (seed[0].toInt() and 0xFF)
        var s1 = (seed[1].toInt() and 0xFF)
        var s2 = (seed[2].toInt() and 0xFF)
        var s3 = (seed[3].toInt() and 0xFF)

        var seedVal = (s0 shl 24) or (s1 shl 16) or (s2 shl 8) or s3
        
        // VAG EDC16 KWP2000 polynomial transformation
        val poly = 0x4F73A1B2
        var keyVal = seedVal xor poly
        keyVal = Integer.rotateLeft(keyVal, 5) xor 0x35A9C2E1

        return byteArrayOf(
            ((keyVal shr 24) and 0xFF).toByte(),
            ((keyVal shr 16) and 0xFF).toByte(),
            ((keyVal shr 8) and 0xFF).toByte(),
            (keyVal and 0xFF).toByte()
        )
    }
}
