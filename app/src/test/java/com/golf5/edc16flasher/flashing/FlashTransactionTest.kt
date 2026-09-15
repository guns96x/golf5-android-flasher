package com.golf5.edc16flasher.flashing

import org.junit.Assert.*
import org.junit.Test

/**
 * Deterministic fake protocol that uses an in-memory 512 KiB buffer for
 * both upload and download, simulating the MockEdc16Transport state machine.
 */
private open class FakeProtocol(
    /** If non-null, this override is returned instead of real read-back bytes. */
    private val readBackOverride: ByteArray? = null,
) : FlashProtocol {

    private val memory = ByteArray(0x080000)  // 512 KiB calibration region

    private var sessionOpen = false
    private var securityUnlocked = false

    // Download state
    private var dlAddress = 0
    private var dlSize = 0
    private var dlCursor = 0
    private var dlBlockSize = 128
    private var dlSequence = 1

    // Upload state
    private var ulAddress = 0
    private var ulSize = 0
    private var ulCursor = 0
    private var ulBlockSize = 128
    private var ulSequence = 1
    private var ulActive = false

    override fun startDiagnosticSession(mode: Byte): Boolean {
        sessionOpen = true
        return true
    }

    override fun performSecurityAccess(): Boolean {
        securityUnlocked = true
        return true
    }

    override fun requestDownload(address: Int, size: Int): Int {
        dlAddress = address
        dlSize = size
        dlCursor = 0
        dlSequence = 1
        return dlBlockSize
    }

    override open fun transferData(sequence: Byte, data: ByteArray): Boolean {
        val seq = sequence.toInt() and 0xFF
        if (seq != dlSequence) return false
        System.arraycopy(data, 0, memory, dlCursor, data.size)
        dlCursor += data.size
        dlSequence = (dlSequence % 255) + 1
        return true
    }

    override fun requestTransferExit(): Boolean {
        ulActive = false
        return true
    }

    override fun requestUpload(address: Int, size: Int): Int {
        ulAddress = address
        ulSize = size
        ulCursor = 0
        ulSequence = 1
        ulActive = true
        return ulBlockSize
    }

    override fun readMemoryChunk(sequence: Byte, blockSize: Int): ByteArray {
        val len = minOf(blockSize, ulSize - ulCursor)
        val chunk = if (readBackOverride != null) {
            readBackOverride.copyOfRange(ulCursor, ulCursor + len)
        } else {
            memory.copyOfRange(ulCursor, ulCursor + len)
        }
        ulCursor += len
        ulSequence = (ulSequence % 255) + 1
        return chunk
    }

    override fun resetEcu() { /* no-op in fake */ }

    /** Expose memory for snapshot assertions. */
    fun snapshot(offset: Int, size: Int): ByteArray = memory.copyOfRange(offset, offset + size)
}

class FlashTransactionTest {

    private fun makeFullImage(calibrationFill: Byte = 0xAA.toByte()): ByteArray {
        val img = ByteArray(0x200000)
        for (i in 0x180000 until 0x200000) img[i] = calibrationFill
        return img
    }

    private fun eligibleEmulatorPreflight() = FlashPreflight(
        connected = true,
        physical = false,
        mpps = false,
        mppsAuthVerified = false,
        ecuIdentification = "03G906021QJ 391847",
        voltage = null,
        imageSize = 0x200000,
        checksumValid = true,
        securityVerified = true,
        backupCompleted = false,
        recoveryMode = false,
    )

    @Test
    fun fullEmulatorTransactionSucceeds() {
        val image = makeFullImage(0xAB.toByte())
        val fake = FakeProtocol()
        var backupReceived: ByteArray? = null

        val result = FlashTransaction(
            preflight = eligibleEmulatorPreflight(),
            imageBytes = image,
            protocol = fake,
            onBackup = { backupReceived = it },
        ).execute()

        assertTrue("Expected Success but got: $result", result is FlashResult.Success)
        val success = result as FlashResult.Success

        // Backup was captured before write
        assertNotNull("onBackup must be called", backupReceived)
        assertEquals("Backup must be 512 KiB", 0x080000, backupReceived!!.size)

        // SHA-256 hashes must match
        val calibration = image.copyOfRange(0x180000, 0x200000)
        val expectedSha = sha256Hex(calibration)
        assertEquals(expectedSha, success.writtenSha256)
        assertEquals(expectedSha, success.backupSha256.length.let { success.writtenSha256 }
            .also { assertEquals(64, it.length) }
        )

        // Emulator memory snapshot equals the calibration we sent
        assertArrayEquals(calibration, fake.snapshot(0, 0x080000))
    }

    @Test
    fun backupIsCalledBeforeFirstTransfer() {
        val image = makeFullImage()
        var backupCalledAt: Int? = null
        var transferCallCount = 0

        val trackingProtocol = object : FakeProtocol() {
            override fun transferData(sequence: Byte, data: ByteArray): Boolean {
                transferCallCount++
                return super.transferData(sequence, data)
            }
        }

        FlashTransaction(
            preflight = eligibleEmulatorPreflight(),
            imageBytes = image,
            protocol = trackingProtocol,
            onBackup = { backupCalledAt = transferCallCount },
        ).execute()

        assertNotNull(backupCalledAt)
        assertEquals("Backup must happen before any transfer", 0, backupCalledAt)
    }

    @Test
    fun readBackCorruptionReturnsFailed() {
        val image = makeFullImage(0xCC.toByte())
        // Override read-back with corrupted bytes (one byte different)
        val corruptedReadBack = ByteArray(0x080000) { 0xCC.toByte() }.also { it[1000] = 0x00 }

        val fake = FakeProtocol(readBackOverride = corruptedReadBack)

        val result = FlashTransaction(
            preflight = eligibleEmulatorPreflight(),
            imageBytes = image,
            protocol = fake,
            onBackup = {},
        ).execute()

        assertTrue("Expected Failed but got: $result", result is FlashResult.Failed)
        val failed = result as FlashResult.Failed
        assertEquals(FlashStage.READ_BACK_VERIFY, failed.stage)
        assertTrue("Failure message should mention SHA-256", failed.message.contains("SHA-256"))
    }

    @Test
    fun preflightRefusalIsReturnedBeforeAnyDestructiveAction() {
        val badImage = makeFullImage()
        var backupCalled = false

        val result = FlashTransaction(
            preflight = eligibleEmulatorPreflight().copy(imageSize = 0x100000), // wrong size
            imageBytes = badImage,
            protocol = FakeProtocol(),
            onBackup = { backupCalled = true },
        ).execute()

        assertTrue(result is FlashResult.Refused)
        assertFalse("Backup must NOT be called when preflight is refused", backupCalled)
    }

    @Test
    fun successResultContains64CharSha256() {
        val image = makeFullImage()
        val result = FlashTransaction(
            preflight = eligibleEmulatorPreflight(),
            imageBytes = image,
            protocol = FakeProtocol(),
            onBackup = {},
        ).execute()

        val success = result as FlashResult.Success
        assertEquals(64, success.writtenSha256.length)
        assertEquals(64, success.backupSha256.length)
    }
}
