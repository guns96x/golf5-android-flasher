package com.golf5.edc16flasher.flashing

import com.golf5.edc16flasher.firmware.EcuFirmwareProfile
import com.golf5.edc16flasher.firmware.Edc16ChecksumEngine
import java.security.MessageDigest

/**
 * Stages of a flash transaction.
 *
 * Execution order: PREFLIGHT -> PROGRAM_SESSION -> SECURITY_ACCESS -> BACKUP ->
 * REQUEST_DOWNLOAD -> TRANSFER -> TRANSFER_EXIT -> READ_BACK_VERIFY -> RESET -> COMPLETE.
 *
 * Session and security access precede BACKUP because the ECU only serves RequestUpload
 * in an unlocked programming session. Neither is destructive; the backup is still
 * completed and persisted before the first destructive request (RequestDownload).
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
        /** ECU acknowledged SID 0x11. False means the user must cycle ignition manually. */
        val resetAcknowledged: Boolean = true,
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
 * Implementations: [com.golf5.edc16flasher.protocol.FlashProtocolAdapter] or test fakes.
 */
interface FlashProtocol {
    /** True only when the security algorithm used by [performSecurityAccess] is verified. */
    val securityAlgorithmVerified: Boolean

    /** Start diagnostic session (SID 0x10). Returns true on positive response. */
    fun startDiagnosticSession(mode: Byte): Boolean

    /** Perform security access (SID 0x27). Returns true when unlocked. */
    fun performSecurityAccess(): Boolean

    /**
     * Request download (SID 0x34).
     * @return block size in bytes to use for TransferData
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

    /** ECU reset (SID 0x11). Returns true when the ECU acknowledged the reset. */
    fun resetEcu(): Boolean
}

/** Largest TransferData payload accepted by the K-Line path. */
private const val MAX_TRANSFER_BLOCK = 128

/**
 * Orchestrates the complete write transaction for the EDC16U34 calibration region.
 *
 * @param preflight      immutable snapshot of all eligibility facts
 * @param imageBytes     full 2 MiB firmware image; its checksum is verified again here
 * @param protocol       protocol callbacks (real or fake)
 * @param onBackup       called with the full 512 KiB backup before the first destructive request;
 *                       the callback must persist the bytes; throwing aborts the transaction
 * @param voltageProbe   physical path only: read immediately before RequestDownload;
 *                       null or < [MIN_PROGRAMMING_VOLTAGE] refuses the write
 * @param onProgress     stage/percent/message callback for UI and logs
 */
class FlashTransaction(
    private val preflight: FlashPreflight,
    private val imageBytes: ByteArray,
    private val protocol: FlashProtocol,
    private val onBackup: (ByteArray) -> Unit,
    private val voltageProbe: () -> Float? = { null },
    private val onProgress: (FlashStage, Int, String) -> Unit = { _, _, _ -> },
    private val profile: EcuFirmwareProfile = EcuFirmwareProfile.EDC16U34_03G906021QJ_391847,
) {
    private val calStart = profile.calibrationStart
    private val calSize = profile.calibrationSize

    fun execute(): FlashResult {
        // ── PREFLIGHT ──────────────────────────────────────────────────────
        onProgress(FlashStage.PREFLIGHT, 0, "Preflight: ${profile.id}")
        val reasons = mutableSetOf<FlashRefusalReason>()
        val eligibility = evaluateEligibility(preflight)
        if (eligibility is FlashEligibility.Refused) reasons += eligibility.reasons

        // Never trust caller-supplied facts alone: re-derive what can be derived here.
        if (imageBytes.size != profile.fullImageSize || imageBytes.size != preflight.imageSize) {
            reasons += FlashRefusalReason.IMAGE_SIZE_INVALID
        } else if (!Edc16ChecksumEngine.verify(imageBytes, profile).isValid) {
            reasons += FlashRefusalReason.CHECKSUM_INVALID
        }
        if (!protocol.securityAlgorithmVerified) reasons += FlashRefusalReason.SECURITY_ALGORITHM_UNVERIFIED

        if (reasons.isNotEmpty()) return FlashResult.Refused(reasons)

        val calibration = imageBytes.copyOfRange(calStart, calStart + calSize)
        val writtenSha = sha256Hex(calibration)

        // ── PROGRAM SESSION ───────────────────────────────────────────────
        onProgress(FlashStage.PROGRAM_SESSION, 2, "Programming session (0x10 0x85)")
        try {
            if (!protocol.startDiagnosticSession(0x85.toByte())) {
                return FlashResult.Failed(FlashStage.PROGRAM_SESSION, "startDiagnosticSession returned false")
            }
        } catch (e: Exception) {
            return FlashResult.Failed(FlashStage.PROGRAM_SESSION, e.message ?: "session failed")
        }

        // ── SECURITY ACCESS ───────────────────────────────────────────────
        onProgress(FlashStage.SECURITY_ACCESS, 4, "Security access (0x27)")
        try {
            if (!protocol.performSecurityAccess()) {
                return FlashResult.Failed(FlashStage.SECURITY_ACCESS, "performSecurityAccess returned false")
            }
        } catch (e: Exception) {
            return FlashResult.Failed(FlashStage.SECURITY_ACCESS, e.message ?: "security access failed")
        }

        // ── BACKUP ────────────────────────────────────────────────────────
        onProgress(FlashStage.BACKUP, 5, "Backup: reading 512 KiB calibration")
        val backupBytes = try {
            readCalibrationRegion(FlashStage.BACKUP, 5, 30)
        } catch (e: Exception) {
            return FlashResult.Failed(FlashStage.BACKUP, e.message ?: "backup failed")
        }
        val backupSha = sha256Hex(backupBytes)
        try {
            onBackup(backupBytes)
        } catch (e: Exception) {
            return FlashResult.Failed(FlashStage.BACKUP, "backup callback failed: ${e.message}")
        }
        onProgress(FlashStage.BACKUP, 30, "Backup stored, SHA-256 $backupSha")

        // ── VOLTAGE (immediately before the first destructive request) ────
        if (preflight.physical) {
            val v = try { voltageProbe() } catch (_: Exception) { null }
            when {
                v == null -> return FlashResult.Refused(setOf(FlashRefusalReason.VOLTAGE_UNAVAILABLE))
                v < MIN_PROGRAMMING_VOLTAGE -> return FlashResult.Refused(setOf(FlashRefusalReason.VOLTAGE_TOO_LOW))
            }
        }

        // ── REQUEST DOWNLOAD ──────────────────────────────────────────────
        onProgress(FlashStage.REQUEST_DOWNLOAD, 31, "RequestDownload 0x%06X..0x%06X".format(calStart, calStart + calSize))
        val blockSize = try {
            protocol.requestDownload(calStart, calSize)
        } catch (e: Exception) {
            return FlashResult.Failed(FlashStage.REQUEST_DOWNLOAD, e.message ?: "requestDownload failed")
        }
        if (blockSize !in 1..MAX_TRANSFER_BLOCK) {
            return FlashResult.Failed(FlashStage.REQUEST_DOWNLOAD, "invalid block size $blockSize")
        }

        // ── TRANSFER ──────────────────────────────────────────────────────
        val totalBlocks = (calSize + blockSize - 1) / blockSize
        var offset = 0
        var block = 0
        while (offset < calSize) {
            val len = minOf(blockSize, calSize - offset)
            val chunk = calibration.copyOfRange(offset, offset + len)
            val seq = blockSequence(block)
            try {
                if (!protocol.transferData(seq, chunk)) {
                    return FlashResult.Failed(FlashStage.TRANSFER, "transferData block ${block + 1}/$totalBlocks returned false")
                }
            } catch (e: Exception) {
                return FlashResult.Failed(FlashStage.TRANSFER, "transferData block ${block + 1}/$totalBlocks: ${e.message}")
            }
            offset += len
            block++
            if (block % 64 == 0 || offset == calSize) {
                onProgress(FlashStage.TRANSFER, 31 + block * 39 / totalBlocks, "Write $block/$totalBlocks")
            }
        }

        // ── TRANSFER EXIT ─────────────────────────────────────────────────
        onProgress(FlashStage.TRANSFER_EXIT, 70, "RequestTransferExit")
        try {
            if (!protocol.requestTransferExit()) {
                return FlashResult.Failed(FlashStage.TRANSFER_EXIT, "requestTransferExit returned false")
            }
        } catch (e: Exception) {
            return FlashResult.Failed(FlashStage.TRANSFER_EXIT, e.message ?: "transferExit failed")
        }

        // ── READ-BACK VERIFY ──────────────────────────────────────────────
        onProgress(FlashStage.READ_BACK_VERIFY, 71, "Read-back: 512 KiB")
        val readBack = try {
            readCalibrationRegion(FlashStage.READ_BACK_VERIFY, 71, 97)
        } catch (e: Exception) {
            return FlashResult.Failed(FlashStage.READ_BACK_VERIFY, e.message ?: "readback failed")
        }

        val readBackSha = sha256Hex(readBack)
        if (writtenSha != readBackSha) {
            return FlashResult.Failed(
                FlashStage.READ_BACK_VERIFY,
                "SHA-256 mismatch: written=$writtenSha readback=$readBackSha"
            )
        }
        onProgress(FlashStage.READ_BACK_VERIFY, 97, "Read-back SHA-256 OK: $readBackSha")

        // ── RESET ─────────────────────────────────────────────────────────
        onProgress(FlashStage.RESET, 98, "ECU reset (0x11 0x01)")
        val resetOk = try { protocol.resetEcu() } catch (_: Exception) { false }

        onProgress(FlashStage.COMPLETE, 100, "Verified write complete")
        return FlashResult.Success(backupSha256 = backupSha, writtenSha256 = writtenSha, resetAcknowledged = resetOk)
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun readCalibrationRegion(stage: FlashStage, fromPct: Int, toPct: Int): ByteArray {
        val blockSize = protocol.requestUpload(calStart, calSize)
        require(blockSize > 0) { "invalid upload block size $blockSize" }
        val totalBlocks = (calSize + blockSize - 1) / blockSize
        val result = ByteArray(calSize)
        var offset = 0
        var block = 0
        while (offset < calSize) {
            val chunk = protocol.readMemoryChunk(blockSequence(block), blockSize)
            if (chunk.isEmpty()) throw IllegalStateException("empty upload block ${block + 1} at offset $offset")
            val len = minOf(chunk.size, calSize - offset)
            System.arraycopy(chunk, 0, result, offset, len)
            offset += len
            block++
            if (block % 128 == 0 || offset == calSize) {
                onProgress(stage, fromPct + (toPct - fromPct) * offset / calSize, "Read $block/$totalBlocks")
            }
        }
        if (!protocol.requestTransferExit()) throw IllegalStateException("upload requestTransferExit returned false")
        return result
    }
}

/**
 * Block sequence counter: starts at 0x01 and wraps 0xFF -> 0x00
 * (same convention as the emulator and the previous K-Line write path).
 */
internal fun blockSequence(blockIndex: Int): Byte = ((blockIndex + 1) and 0xFF).toByte()

internal fun sha256Hex(data: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(data)
    return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
