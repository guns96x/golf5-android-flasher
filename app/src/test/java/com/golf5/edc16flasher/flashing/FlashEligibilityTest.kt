package com.golf5.edc16flasher.flashing

import org.junit.Assert.*
import org.junit.Test

private fun eligiblePhysicalPreflight(
    connected: Boolean = true,
    physical: Boolean = true,
    mpps: Boolean = true,
    mppsAuthVerified: Boolean = true,
    ecuIdentification: String = "03G906021QJ 391847 EDC16U34",
    voltage: Float? = 12.5f,
    imageSize: Int = 0x200000,
    checksumValid: Boolean = true,
    securityVerified: Boolean = true,
    backupCompleted: Boolean = true,
    recoveryMode: Boolean = false,
) = FlashPreflight(
    connected = connected,
    physical = physical,
    mpps = mpps,
    mppsAuthVerified = mppsAuthVerified,
    ecuIdentification = ecuIdentification,
    voltage = voltage,
    imageSize = imageSize,
    checksumValid = checksumValid,
    securityVerified = securityVerified,
    backupCompleted = backupCompleted,
    recoveryMode = recoveryMode,
)

private fun eligibleEmulatorPreflight(
    imageSize: Int = 0x200000,
    checksumValid: Boolean = true,
    securityVerified: Boolean = true,
) = FlashPreflight(
    connected = true,
    physical = false,
    mpps = false,
    mppsAuthVerified = false,   // emulator never has MPPS auth
    ecuIdentification = "03G906021QJ 391847 EDC16U34",
    voltage = null,             // emulator has no real voltage
    imageSize = imageSize,
    checksumValid = checksumValid,
    securityVerified = securityVerified,
    backupCompleted = false,    // emulator skips backup gate
    recoveryMode = false,
)

class FlashEligibilityTest {

    @Test
    fun eligiblePhysicalPasses() {
        val result = evaluateEligibility(eligiblePhysicalPreflight())
        assertEquals(FlashEligibility.Eligible, result)
    }

    @Test
    fun eligibleEmulatorPasses() {
        val result = evaluateEligibility(eligibleEmulatorPreflight())
        assertEquals(FlashEligibility.Eligible, result)
    }

    @Test
    fun notConnectedRefuses() {
        val result = evaluateEligibility(eligiblePhysicalPreflight(connected = false))
        val refused = result as FlashEligibility.Refused
        assertTrue(FlashRefusalReason.NOT_CONNECTED in refused.reasons)
    }

    @Test
    fun wrongImageSizeRefuses() {
        val result = evaluateEligibility(eligiblePhysicalPreflight(imageSize = 0x100000))
        val refused = result as FlashEligibility.Refused
        assertTrue(FlashRefusalReason.IMAGE_SIZE_INVALID in refused.reasons)
    }

    @Test
    fun invalidChecksumRefuses() {
        val result = evaluateEligibility(eligiblePhysicalPreflight(checksumValid = false))
        val refused = result as FlashEligibility.Refused
        assertTrue(FlashRefusalReason.CHECKSUM_INVALID in refused.reasons)
    }

    @Test
    fun unverifiedSecurityAlgorithmRefuses() {
        val result = evaluateEligibility(eligiblePhysicalPreflight(securityVerified = false))
        val refused = result as FlashEligibility.Refused
        assertTrue(FlashRefusalReason.SECURITY_ALGORITHM_UNVERIFIED in refused.reasons)
    }

    @Test
    fun voltageUnavailableRefusesPhysical() {
        val result = evaluateEligibility(eligiblePhysicalPreflight(voltage = null))
        val refused = result as FlashEligibility.Refused
        assertTrue(FlashRefusalReason.VOLTAGE_UNAVAILABLE in refused.reasons)
    }

    @Test
    fun voltageTooLowRefusesPhysical() {
        val result = evaluateEligibility(eligiblePhysicalPreflight(voltage = 11.9f))
        val refused = result as FlashEligibility.Refused
        assertTrue(FlashRefusalReason.VOLTAGE_TOO_LOW in refused.reasons)
    }

    @Test
    fun unverifiedMppsAuthRefusesPhysical() {
        val result = evaluateEligibility(eligiblePhysicalPreflight(mppsAuthVerified = false))
        val refused = result as FlashEligibility.Refused
        assertTrue(FlashRefusalReason.MPPS_AUTH_UNVERIFIED in refused.reasons)
    }

    @Test
    fun missingBackupRefusesPhysical() {
        val result = evaluateEligibility(eligiblePhysicalPreflight(backupCompleted = false))
        val refused = result as FlashEligibility.Refused
        assertTrue(FlashRefusalReason.BACKUP_REQUIRED in refused.reasons)
    }

    @Test
    fun ecuIdMismatchRefusesNormalMode() {
        val result = evaluateEligibility(eligiblePhysicalPreflight(ecuIdentification = "UNKNOWN_ECU"))
        val refused = result as FlashEligibility.Refused
        assertTrue(FlashRefusalReason.ECU_ID_MISMATCH in refused.reasons)
    }

    @Test
    fun ecuIdMismatchAllowedInRecoveryMode() {
        val result = evaluateEligibility(
            eligiblePhysicalPreflight(ecuIdentification = "UNKNOWN_ECU", recoveryMode = true)
        )
        // Should still be eligible if all other gates pass
        assertEquals(FlashEligibility.Eligible, result)
    }

    @Test
    fun recoveryModeStillEnforcesVoltage() {
        val result = evaluateEligibility(
            eligiblePhysicalPreflight(
                ecuIdentification = "UNKNOWN_ECU",
                recoveryMode = true,
                voltage = 10.0f
            )
        )
        val refused = result as FlashEligibility.Refused
        assertTrue(FlashRefusalReason.VOLTAGE_TOO_LOW in refused.reasons)
        assertFalse(FlashRefusalReason.ECU_ID_MISMATCH in refused.reasons)
    }

    @Test
    fun emulatorDoesNotRequireVoltageOrMppsOrBackup() {
        // Even with null voltage, no backup, and no mpps auth — emulator should be eligible
        val result = evaluateEligibility(eligibleEmulatorPreflight())
        assertEquals(FlashEligibility.Eligible, result)
    }

    @Test
    fun emulatorStillRequiresVerifiedSecurity() {
        val result = evaluateEligibility(eligibleEmulatorPreflight(securityVerified = false))
        val refused = result as FlashEligibility.Refused
        assertTrue(FlashRefusalReason.SECURITY_ALGORITHM_UNVERIFIED in refused.reasons)
    }

    @Test
    fun multipleFailuresAllCollected() {
        val result = evaluateEligibility(
            eligiblePhysicalPreflight(
                checksumValid = false,
                securityVerified = false,
                voltage = 11.0f,
            )
        )
        val refused = result as FlashEligibility.Refused
        assertTrue(FlashRefusalReason.CHECKSUM_INVALID in refused.reasons)
        assertTrue(FlashRefusalReason.SECURITY_ALGORITHM_UNVERIFIED in refused.reasons)
        assertTrue(FlashRefusalReason.VOLTAGE_TOO_LOW in refused.reasons)
    }
}
