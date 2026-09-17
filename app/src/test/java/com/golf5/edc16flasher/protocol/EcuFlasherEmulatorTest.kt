package com.golf5.edc16flasher.protocol

import com.golf5.edc16flasher.firmware.Edc16ChecksumEngine
import com.golf5.edc16flasher.security.LegacyBlsSecurityAlgorithm
import com.golf5.edc16flasher.security.MockSecurityAlgorithm
import com.golf5.edc16flasher.usb.MockEdc16Transport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end emulator path used by MainActivity: identification, backup, write and read-back
 * through the real Kwp2000Protocol against the offline EDC16 emulator.
 */
class EcuFlasherEmulatorTest {

    private val calStart = 0x180000
    private val calSize = 0x080000

    private fun emulator(): Pair<MockEdc16Transport, Kwp2000Protocol> {
        val mock = MockEdc16Transport()
        mock.open()
        return mock to Kwp2000Protocol(mock)
    }

    private fun image(): ByteArray =
        Edc16ChecksumEngine.fix(ByteArray(0x200000) { (it * 31 + 7).toByte() })

    @Test
    fun emulatorWriteWithMockSecurityIsVerifiedByReadBack() = runBlocking {
        val (mock, protocol) = emulator()
        val image = image()
        val log = mutableListOf<String>()

        val ok = EcuFlasher(protocol, security = MockSecurityAlgorithm)
            .flashFirmware(image) { _, msg -> log += msg }

        assertTrue(log.joinToString("\n"), ok)
        assertArrayEquals(image.copyOfRange(calStart, calStart + calSize), mock.snapshot(calStart, calSize))
    }

    @Test
    fun emulatorBackupWithMockSecurityReturnsCalibration() = runBlocking {
        val (mock, protocol) = emulator()
        val before = mock.snapshot(calStart, calSize)

        val full = EcuFlasher(protocol, security = MockSecurityAlgorithm).readCalibration { _, _ -> }

        assertNotNull(full)
        assertArrayEquals(before, full!!.copyOfRange(calStart, calStart + calSize))
    }

    @Test
    fun emulatorRecoveryWriteIsVerifiedByReadBack() = runBlocking {
        val (mock, protocol) = emulator()
        val image = image()

        val ok = EcuFlasher(protocol, security = MockSecurityAlgorithm).recoveryFlash(image) { _, _ -> }

        assertTrue(ok)
        assertArrayEquals(image.copyOfRange(calStart, calStart + calSize), mock.snapshot(calStart, calSize))
    }

    @Test
    fun legacySecurityIsRejectedByEmulatorAndNothingIsWritten() = runBlocking {
        val (mock, protocol) = emulator()
        val before = mock.snapshot(calStart, calSize)

        val ok = EcuFlasher(protocol, security = LegacyBlsSecurityAlgorithm).flashFirmware(image()) { _, _ -> }

        assertFalse(ok)
        assertArrayEquals(before, mock.snapshot(calStart, calSize))
    }
}
