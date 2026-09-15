package com.golf5.edc16flasher.flashing

/**
 * All reasons a flash operation may be refused.
 * Task 7 / FlashEligibility spec.
 */
enum class FlashRefusalReason {
    NOT_CONNECTED,
    ECU_ID_MISMATCH,
    VOLTAGE_UNAVAILABLE,
    VOLTAGE_TOO_LOW,
    IMAGE_SIZE_INVALID,
    CHECKSUM_INVALID,
    SECURITY_ALGORITHM_UNVERIFIED,
    MPPS_AUTH_UNVERIFIED,
    BACKUP_REQUIRED,
}

/**
 * Immutable snapshot of all facts needed to decide flash eligibility.
 *
 * @param connected          transport is open and ready
 * @param physical           transport is physical hardware (not emulator)
 * @param mpps               transport is MPPS (required for physical write)
 * @param mppsAuthVerified   MPPS challenge/response was validated by a known captured vector
 * @param ecuIdentification  raw ECU identifier string as returned by SID 0x1A / 0x9B
 * @param voltage            battery voltage in volts, null if unavailable
 * @param imageSize          size of the firmware image to flash in bytes
 * @param checksumValid      the EDC16 additive checksum verification passed
 * @param securityVerified   [SecurityAccessAlgorithm.verified] is true for the injected algorithm
 * @param backupCompleted    a full calibration-region backup was completed this session
 * @param recoveryMode       user has explicitly requested recovery mode (ignores ECU ID mismatch)
 */
data class FlashPreflight(
    val connected: Boolean,
    val physical: Boolean,
    val mpps: Boolean,
    val mppsAuthVerified: Boolean,
    val ecuIdentification: String,
    val voltage: Float?,
    val imageSize: Int,
    val checksumValid: Boolean,
    val securityVerified: Boolean,
    val backupCompleted: Boolean,
    val recoveryMode: Boolean,
)

/**
 * Result of evaluating [FlashPreflight] against the profile constraints.
 */
sealed interface FlashEligibility {
    /** All gates pass — the operation may proceed. */
    data object Eligible : FlashEligibility

    /** One or more gates failed. */
    data class Refused(val reasons: Set<FlashRefusalReason>) : FlashEligibility
}

private const val FULL_IMAGE_SIZE = 0x200_000   // 2 MiB — EDC16U34 profile
private const val MIN_VOLTAGE = 12.2f           // Minimum physical programming voltage

/** Required ECU identifier substrings for EDC16U34_03G906021QJ_391847. */
private val REQUIRED_ECU_IDS = setOf("03G906021QJ", "391847")

/**
 * Pure function — evaluates [preflight] and returns [FlashEligibility].
 *
 * Rules (in order; all failures are collected before returning):
 * 1. Must be connected.
 * 2. Image size must equal [FULL_IMAGE_SIZE].
 * 3. Checksum must be valid.
 * 4. Security algorithm must be verified.
 * 5. Physical transports require: voltage available, voltage >= [MIN_VOLTAGE],
 *    MPPS auth verified, backup completed, and (unless recovery mode) matching ECU ID.
 * 6. Emulator transports skip MPPS auth and voltage gates.
 */
fun evaluateEligibility(preflight: FlashPreflight): FlashEligibility {
    val reasons = mutableSetOf<FlashRefusalReason>()

    if (!preflight.connected) reasons += FlashRefusalReason.NOT_CONNECTED
    if (preflight.imageSize != FULL_IMAGE_SIZE) reasons += FlashRefusalReason.IMAGE_SIZE_INVALID
    if (!preflight.checksumValid) reasons += FlashRefusalReason.CHECKSUM_INVALID
    if (!preflight.securityVerified) reasons += FlashRefusalReason.SECURITY_ALGORITHM_UNVERIFIED

    if (preflight.physical) {
        val v = preflight.voltage
        if (v == null) {
            reasons += FlashRefusalReason.VOLTAGE_UNAVAILABLE
        } else if (v < MIN_VOLTAGE) {
            reasons += FlashRefusalReason.VOLTAGE_TOO_LOW
        }

        if (!preflight.mppsAuthVerified) reasons += FlashRefusalReason.MPPS_AUTH_UNVERIFIED
        if (!preflight.backupCompleted) reasons += FlashRefusalReason.BACKUP_REQUIRED

        if (!preflight.recoveryMode) {
            val ecuId = preflight.ecuIdentification
            val matches = REQUIRED_ECU_IDS.all { required -> ecuId.contains(required) }
            if (!matches) reasons += FlashRefusalReason.ECU_ID_MISMATCH
        }
    }
    // Emulator path: no voltage / MPPS auth / backup gates (verified mock security is still required above)

    return if (reasons.isEmpty()) FlashEligibility.Eligible
    else FlashEligibility.Refused(reasons)
}
