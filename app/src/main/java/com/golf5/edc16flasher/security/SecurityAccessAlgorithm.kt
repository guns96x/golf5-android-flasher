package com.golf5.edc16flasher.security

interface SecurityAccessAlgorithm {
    val id: String
    val verified: Boolean
    fun calculateKey(seed: ByteArray): ByteArray
}
