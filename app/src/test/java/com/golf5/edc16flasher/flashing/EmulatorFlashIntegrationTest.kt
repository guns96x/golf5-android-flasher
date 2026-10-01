package com.golf5.edc16flasher.flashing

import com.golf5.edc16flasher.firmware.Edc16ChecksumEngine
import com.golf5.edc16flasher.protocol.FlashProtocolAdapter
import com.golf5.edc16flasher.protocol.Kwp2000Protocol
import com.golf5.edc16flasher.security.LegacyBlsSecurityAlgorithm
import com.golf5.edc16flasher.security.MockSecurityAlgorithm
import com.golf5.edc16flasher.usb.MockEdc16Transport
import com.golf5.edc16flasher.usb.MockFaults
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end: FlashTransaction -> FlashProtocolAdapter -> Kwp2000Protocol (real frame codec)
 * -> MockEdc16Transport (stateful in-memory ECU). No fakes in between.
 */
class EmulatorFlashIntegrationTest {

    private val calStart = 0x180000
    private val calSize = 0x080000

    private fun factoryImage(): ByteArray {
        val img = ByteArray(0x200000) { 0xFF.toByte() }
        for (i in calStart until calStart + calSize) img[i] = (i * 7 + 3).toByte()
        return Edc16ChecksumEngine.fix(img)
    }

    private fun targetImage(): ByteArray {
        val img = ByteArray(0x200000) { 0xFF.toByte() }
        for (i in calStart until calStart + calSize) img[i] = (i * 13 + 0x5A).toByte()
        return Edc16ChecksumEngine.fix(img)
    }

    private fun emulatorPreflight() = FlashPreflight(
        connected = true,
        physical = false,
        mpps = true,
        mppsAuthVerified = false,
        ecuIdentification = "03G906021QJ 391847",
        voltage = 13.8f,
        imageSize = 0x200000,
        checksumValid = true,
        securityVerified = true,
        backupCompleted = false,
        recoveryMode = false,
    )

    private fun openEmulator(): Pair<MockEdc16Transport, Kwp2000Protocol> {
        val mock = MockEdc16Transport()
        assertTrue(mock.open())
        mock.loadImageForTest(factoryImage())
        return mock to Kwp2000Protocol(mock)
    }

    @Test
    fun fullBackupWriteReadBackVerifyAgainstEmulator() {
        val (mock, kwp) = openEmulator()
        val factoryCal = mock.snapshot(calStart, calSize)
        val target = targetImage()
        val stages = mutableListOf<FlashStage>()
        var backup: ByteArray? = null
        var snapshotAtBackup: ByteArray? = null

        val result = FlashTransaction(
            preflight = emulatorPreflight(),
            imageBytes = target,
            protocol = FlashProtocolAdapter(kwp, MockSecurityAlgorithm),
            onBackup = {
                backup = it
                snapshotAtBackup = mock.snapshot(calStart, calSize)
            },
            onProgress = { stage, _, _ -> if (stages.lastOrNull() != stage) stages += stage },
        ).execute()

        assertTrue("Expected Success but got $result", result is FlashResult.Success)
        result as FlashResult.Success

        // Backup is the untouched factory calibration, captured before any write.
        assertNotNull(backup)
        assertArrayEquals(factoryCal, backup)
        assertArrayEquals(factoryCal, snapshotAtBackup)
        assertEquals(sha256Hex(factoryCal), result.backupSha256)

        // Emulator flash now holds exactly the intended calibration.
        val intended = target.copyOfRange(calStart, calStart + calSize)
        assertArrayEquals(intended, mock.snapshot(calStart, calSize))
        assertEquals(sha256Hex(intended), result.writtenSha256)
        assertTrue(result.resetAcknowledged)

        // Stage order: backup strictly before download, read-back before reset.
        assertTrue(stages.indexOf(FlashStage.BACKUP) < stages.indexOf(FlashStage.REQUEST_DOWNLOAD))
        assertTrue(stages.indexOf(FlashStage.READ_BACK_VERIFY) < stages.indexOf(FlashStage.RESET))
        assertEquals(FlashStage.COMPLETE, stages.last())
    }

    @Test
    fun readBackCorruptionInEmulatorFailsVerification() {
        val (mock, kwp) = openEmulator()
        val adapter = FlashProtocolAdapter(kwp, MockSecurityAlgorithm)
        var downloadExited = false
        // Corrupt one flash byte after the download completes, before read-back.
        val corrupting = object : FlashProtocol by adapter {
            override fun requestDownload(address: Int, size: Int): Int {
                downloadExited = false
                return adapter.requestDownload(address, size).also { downloadExited = true }
            }
            override fun requestTransferExit(): Boolean {
                val ok = adapter.requestTransferExit()
                if (downloadExited) {
                    mock.corruptByteForTest(calStart + 0x1234)
                    downloadExited = false
                }
                return ok
            }
        }

        val result = FlashTransaction(
            preflight = emulatorPreflight(),
            imageBytes = targetImage(),
            protocol = corrupting,
            onBackup = {},
        ).execute()

        assertTrue("Expected Failed but got $result", result is FlashResult.Failed)
        assertEquals(FlashStage.READ_BACK_VERIFY, (result as FlashResult.Failed).stage)
    }

    @Test
    fun legacyUnverifiedSecurityNeverReachesEmulatorFlash() {
        val (mock, kwp) = openEmulator()
        val before = mock.snapshot(calStart, calSize)
        val result = FlashTransaction(
            preflight = emulatorPreflight(),
            imageBytes = targetImage(),
            protocol = FlashProtocolAdapter(kwp, LegacyBlsSecurityAlgorithm),
            onBackup = {},
        ).execute()

        assertTrue(result is FlashResult.Refused)
        assertTrue(FlashRefusalReason.SECURITY_ALGORITHM_UNVERIFIED in (result as FlashResult.Refused).reasons)
        assertArrayEquals(before, mock.snapshot(calStart, calSize))
    }

    @Test
    fun disconnectDuringTransferFailsAtTransferStage() {
        val (mock, kwp) = openEmulator()
        mock.configureFaults(MockFaults(disconnectAtBlock = 3))
        val result = FlashTransaction(
            preflight = emulatorPreflight(),
            imageBytes = targetImage(),
            protocol = FlashProtocolAdapter(kwp, MockSecurityAlgorithm),
            onBackup = {},
        ).execute()

        assertTrue("Expected Failed but got $result", result is FlashResult.Failed)
        assertEquals(FlashStage.TRANSFER, (result as FlashResult.Failed).stage)
        assertFalse(mock.isConnected)
    }

    @Test
    fun recoveryModeSessionAlsoCompletesVerifiedWrite() {
        val (mock, kwp) = openEmulator()
        val target = targetImage()
        val result = FlashTransaction(
            preflight = emulatorPreflight().copy(recoveryMode = true, ecuIdentification = ""),
            imageBytes = target,
            protocol = FlashProtocolAdapter(kwp, MockSecurityAlgorithm, recoveryMode = true),
            onBackup = {},
        ).execute()

        assertTrue("Expected Success but got $result", result is FlashResult.Success)
        assertArrayEquals(target.copyOfRange(calStart, calStart + calSize), mock.snapshot(calStart, calSize))
    }
}
