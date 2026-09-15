package com.golf5.edc16flasher.security

object MockSecurityAlgorithm : SecurityAccessAlgorithm {
    override val id = "mock-xor-a5"
    override val verified = true
    override fun calculateKey(seed: ByteArray): ByteArray {
        require(seed.size == 4)
        return seed.map { ((it.toInt() and 0xFF) xor 0xA5).toByte() }.toByteArray()
    }
}
