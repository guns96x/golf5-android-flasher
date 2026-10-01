package com.golf5.edc16flasher.security

/**
 * Picks the SID 0x27 algorithm for a transport.
 *
 * The emulator-only [MockSecurityAlgorithm] is never returned for a physical transport:
 * physical ECUs get [LegacyBlsSecurityAlgorithm], which stays `verified = false` until
 * real seed/key vectors with provenance are committed.
 */
object SecurityAlgorithmSelector {
    fun forTransport(isPhysical: Boolean): SecurityAccessAlgorithm =
        if (isPhysical) LegacyBlsSecurityAlgorithm else MockSecurityAlgorithm
}
