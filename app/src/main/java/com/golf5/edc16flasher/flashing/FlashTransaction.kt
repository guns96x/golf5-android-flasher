package com.golf5.edc16flasher.flashing

import java.security.MessageDigest

/**
 * Stages of a flash transaction, in execution order.
 */
enum class FlashStage {
    PREFLIGHT,
    BACKUP,
    PROGRAM_SESSION,
    SECURITY_ACCESS,
    REQUEST_DOWNLOAD,
    TRANSFER,
    TRANSFER_EXIT,
    READ_BACK_VERIFY,
    RESET,
    COMPLETE,
}

/**
 * Typed result of a [FlashTransaction].
 */
sealed interface FlashResult {
    /** Both SHA-256 digests match — the write was verified. */
    data class Success(
        val backupSha256: String,
        val writtenSha256: String,
    ) : FlashResult

    /** Preflight evaluation refused the operation before any destructive action. */
    data class Refused(val reasons: Set<FlashRefusalReason>) : FlashResult

    /** A destructive or verification step failed at [stage]. */
    data class Failed(val stage: FlashStage, val message: String) : FlashResult
}

// ---------------------------------------------------------------------------
// Protocol callbacks injected by the caller — no Android APIs here
// ---------------------------------------------------------------------------

/**
 * Minimal protocol surface consumed by [FlashTransaction].
 * Implementations: real [Kwp2000Protocol] or test fakes.
 */
interface FlashProtocol {
    /** Start diagnostic session (SID 0x10). Returns true on positive response. */
    fun startDiagnosticSession(mode: Byte): Boolean

    /** Perform security access (SID 0x27). Returns true when unlocked. */
    fun performSecurityAccess(): Boolean

    /**
     * Request download (SID 0x34).
     * @return negotiated block size in bytes
     */
    fun requestDownload(address: Int, size: Int): Int

    /**
     * Transfer one block (SID 0x36).
     * @return true on positive response
     */
    fun transferData(sequence: Byte, data: ByteArray): Boolean

    /** Request transfer exit (SID 0x37). Returns true on positive response. */
    fun requestTransferExit(): Boolean

    /**
     * Request upload (SID 0x35).
     * @return negotiated block size in bytes
     */
    fun requestUpload(address: Int, size: Int): Int

    /**
     * Read one chunk (SID 0x36 upload direction).
     * @return raw bytes for this chunk
     */
    fun readMemoryChunk(sequence: Byte, blockSize: Int): ByteArray

    /** ECU reset (SID 0x11). Fire-and-forget. */
    fun resetEcu()
}

// ---------------------------------------------------------------------------
// FlashTransaction
// ---------------------------------------------------------------------------

private const val CALIBRATION_START = 0x180000
private const val CALIBRATION_SIZE  = 0x080000   // 512 KiB

/**
 * Orchestrates the complete write transaction for the EDC16U34 calibration region.
 *
 * @param preflight      immutable snapshot of all eligibility facts
 * @param imageBytes     full 2 MiB firmware image (caller must have verified checksum)
 * @param protocol       protocol callbacks (real or fake)
 * @param onBackup       called with the full 512 KiB backup before first destructive request;
 *                       the callback should persist the bytes; may throw to abort
 */
class FlashTransaction(
    private val preflight: FlashPreflight,
    private val imageBytes: ByteArray,
    private val protocol: FlashProtocol,
    private val onBackup: (ByteArray) -> Unit,
) {
    fun execute(): FlashResult {
        // ── PREFLIGHT ──────────────────────────────────────────────────────
        val eligibility = evaluateEligibility(preflight)
        if (eligibility is FlashEligibility.Refused) {
            return FlashResult.Refused(eligibility.reasons)
        }

        val calibration = imageBytes.copyOfRange(CALIBRATION_START, CALIBRATION_START + CALIBRATION_SIZE)

        // ── BACKUP ────────────────────────────────────────────────────────
        val backupBytes = try {
            readCalibrationRegion()
        } catch (e: Exception) {
            return FlashResult.Failed(FlashStage.BACKUP, e.message ?: "backup failed")
        }
        val backupSha = sha256Hex(backupBytes)
        try {
            onBackup(backupBytes)
        } catch (e: Exception) {
            return FlashResult.Failed(FlashStage.BACKUP, "backup callback failed: ${e.message}")
        }

        // ── PROGRAM SESSION ───────────────────────────────────────────────
        try {
            if (!protocol.startDiagnosticSession(0x85.toByte())) {
                return FlashResult.Failed(FlashStage.PROGRAM_SESSION, "startDiagnosticSession returned false")
            }
        } catch (e: Exception) {
            return FlashResult.Failed(FlashStage.PROGRAM_SESSION, e.message ?: "session failed")
        }

        // ── SECURITY ACCESS ───────────────────────────────────────────────
        try {
            if (!protocol.performSecurityAccess()) {
                return FlashResult.Failed(FlashStage.SECURITY_ACCESS, "performSecurityAccess returned false")
            }
        } catch (e: Exception) {
            return FlashResult.Failed(FlashStage.SECURITY_ACCESS, e.message ?: "security access failed")
        }

        // ── REQUEST DOWNLOAD ──────────────────────────────────────────────
        val blockSize = try {
            protocol.requestDownload(CALIBRATION_START, CALIBRATION_SIZE)
        } catch (e: Exception) {
            return FlashResult.Failed(FlashStage.REQUEST_DOWNLOAD, e.message ?: "requestDownload failed")
        }

        // ── TRANSFER ──────────────────────────────────────────────────────
        var offset = 0
        var seq = 1
        while (offset < CALIBRATION_SIZE) {
            val len = minOf(blockSize, CALIBRATION_SIZE - offset)
            val chunk = calibration.copyOfRange(offset, offset + len)
            try {
                if (!protocol.transferData(seq.toByte(), chunk)) {
                    return FlashResult.Failed(FlashStage.TRANSFER, "transferData seq $seq returned false")
                }
            } catch (e: Exception) {
                return FlashResult.Failed(FlashStage.TRANSFER, "transferData seq $seq: ${e.message}")
            }
            offset += len
            seq = (seq % 255) + 1
        }

        // ── TRANSFER EXIT ─────────────────────────────────────────────────
        try {
            if (!protocol.requestTransferExit()) {
                return FlashResult.Failed(FlashStage.TRANSFER_EXIT, "requestTransferExit returned false")
            }
        } catch (e: Exception) {
            return FlashResult.Failed(FlashStage.TRANSFER_EXIT, e.message ?: "transferExit failed")
        }

        // ── READ-BACK VERIFY ──────────────────────────────────────────────
        val readBack = try {
            readCalibrationRegion()
        } catch (e: Exception) {
            return FlashResult.Failed(FlashStage.READ_BACK_VERIFY, e.message ?: "readback failed")
        }

        val writtenSha = sha256Hex(calibration)
        val readBackSha = sha256Hex(readBack)
        if (writtenSha != readBackSha) {
            return FlashResult.Failed(
                FlashStage.READ_BACK_VERIFY,
                "SHA-256 mismatch: written=$writtenSha readback=$readBackSha"
            )
        }

        // ── RESET ─────────────────────────────────────────────────────────
        try {
            protocol.resetEcu()
        } catch (_: Exception) {
            // best-effort — reset failure is not a write failure
        }

        return FlashResult.Success(backupSha256 = backupSha, writtenSha256 = writtenSha)
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun readCalibrationRegion(): ByteArray {
        val blockSize = protocol.requestUpload(CALIBRATION_START, CALIBRATION_SIZE)
        val result = ByteArray(CALIBRATION_SIZE)
        var offset = 0
        var seq = 1
        while (offset < CALIBRATION_SIZE) {
            val chunk = protocol.readMemoryChunk(seq.toByte(), blockSize)
            val len = minOf(chunk.size, CALIBRATION_SIZE - offset)
            System.arraycopy(chunk, 0, result, offset, len)
            offset += len
            seq = (seq % 255) + 1
        }
        protocol.requestTransferExit()
        return result
    }
}

internal fun sha256Hex(data: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(data)
    return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
