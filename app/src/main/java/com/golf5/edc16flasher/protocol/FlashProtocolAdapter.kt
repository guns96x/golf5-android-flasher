package com.golf5.edc16flasher.protocol

import com.golf5.edc16flasher.flashing.FlashProtocol
import com.golf5.edc16flasher.security.SecurityAccessAlgorithm
import java.io.IOException

/**
 * Bridges [Kwp2000Protocol] to the [FlashProtocol] surface consumed by
 * [com.golf5.edc16flasher.flashing.FlashTransaction].
 *
 * @param security      algorithm for SID 0x27; its [SecurityAccessAlgorithm.verified] flag is
 *                      reported to the transaction, which refuses to write when it is false
 * @param recoveryMode  re-run KWP fast init before entering the programming session
 *                      (same sequence as [Kwp2000Protocol.forceRecoverySession])
 */
class FlashProtocolAdapter(
    private val kwp: Kwp2000Protocol,
    private val security: SecurityAccessAlgorithm,
    private val recoveryMode: Boolean = false,
) : FlashProtocol {

    override val securityAlgorithmVerified: Boolean
        get() = security.verified

    override fun startDiagnosticSession(mode: Byte): Boolean {
        return if (recoveryMode) kwp.forceRecoverySession() else kwp.startDiagnosticSession(mode)
    }

    override fun performSecurityAccess(): Boolean {
        val seed = kwp.requestSecuritySeed()
        val key = security.calculateKey(seed)
        return kwp.sendSecurityKey(key)
    }

    override fun requestDownload(address: Int, size: Int): Int {
        if (!kwp.requestDownload(address, size)) throw IOException("RequestDownload rejected")
        return BLOCK_SIZE
    }

    override fun transferData(sequence: Byte, data: ByteArray): Boolean {
        return kwp.transferData(sequence, data)
    }

    override fun requestTransferExit(): Boolean {
        return kwp.requestTransferExit()
    }

    override fun requestUpload(address: Int, size: Int): Int {
        return kwp.requestUpload(address, size)
    }

    override fun readMemoryChunk(sequence: Byte, blockSize: Int): ByteArray {
        return kwp.readMemoryChunk(sequence, blockSize)
    }

    override fun resetEcu(): Boolean {
        return kwp.ecuReset()
    }

    private companion object {
        /** EDC16 K-Line TransferData payload limit enforced by [Kwp2000Protocol.transferData]. */
        const val BLOCK_SIZE = 128
    }
}
