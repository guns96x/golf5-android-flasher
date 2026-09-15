package com.golf5.edc16flasher.protocol

import com.golf5.edc16flasher.security.LegacyBlsSecurityAlgorithm

/**
 * Legacy Bosch EDC16 Seed-Key algorithm for VAG 1.9 TDI BLS.
 * Delegates to [LegacyBlsSecurityAlgorithm], which is explicitly marked unverified.
 */
object Edc16Security {
    fun calculateKey(seed: ByteArray): ByteArray {
        return LegacyBlsSecurityAlgorithm.calculateKey(seed)
    }
}
