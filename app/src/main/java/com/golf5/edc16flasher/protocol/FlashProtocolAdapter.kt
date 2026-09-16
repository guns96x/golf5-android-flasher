package com.golf5.edc16flasher.protocol

import com.golf5.edc16flasher.flashing.FlashProtocol

/**
 * Adapter that bridges Kwp2000Protocol to FlashProtocol interface.
 * Used by MainActivity to migrate from EcuFlasher to FlashTransaction (C6 fix).
 */
class FlashProtocolAdapter(private val kwp: Kwp2000Protocol) : FlashProtocol {

    override fun startDiagnosticSession(mode: Byte): Boolean {
        return try {
            kwp.startDiagnosticSession(mode)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun performSecurityAccess(): Boolean {
        return try {
            val seed = kwp.requestSecuritySeed()
            val key = Edc16Security.calculateKey(seed)
            kwp.sendSecurityKey(key)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun requestDownload(address: Int, size: Int): Int {
        kwp.requestDownload(address, size)
        return 128  // EDC16 standard block size
    }

    override fun transferData(sequence: Byte, data: ByteArray): Boolean {
        return kwp.transferData(sequence, data)
    }

    override fun requestTransferExit(): Boolean {
        return try {
            kwp.requestTransferExit()
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun requestUpload(address: Int, size: Int): Int {
        return kwp.requestUpload(address, size)
    }

    override fun readMemoryChunk(sequence: Byte, blockSize: Int): ByteArray {
        return kwp.readMemoryChunk(sequence, blockSize)
    }

    override fun resetEcu() {
        kwp.ecuReset()
    }
}
