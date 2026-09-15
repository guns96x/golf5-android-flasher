package com.golf5.edc16flasher.usb

import com.golf5.edc16flasher.protocol.Kwp2000Protocol
import com.golf5.edc16flasher.protocol.KwpNegativeResponseException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class MockEdc16TransportTest {

    private fun setupEmulator(): Pair<MockEdc16Transport, Kwp2000Protocol> {
        val mock = MockEdc16Transport()
        mock.open()
        val protocol = Kwp2000Protocol(mock)
        return Pair(mock, protocol)
    }

    @Test
    fun emulatorReportsIsPhysicalFalse() {
        val (mock, _) = setupEmulator()
        assertFalse(mock.isPhysical)
    }

    @Test
    fun requestDownloadDoesNotChangeMemoryUntilTransferData() {
        val (mock, protocol) = setupEmulator()

        val start = 0x180000
        val size = 256
        val before = mock.snapshot(start, size)

        val success = protocol.requestDownload(start, size)
        assertTrue(success)

        val after = mock.snapshot(start, size)
        assertArrayEquals(before, after)
    }

    @Test
    fun transferDataWritesBytesAtRequestedAddress() {
        val (mock, protocol) = setupEmulator()

        val start = 0x180000
        val size = 128
        protocol.requestDownload(start, size)

        val testData = ByteArray(128) { (it + 0x42).toByte() }
        val transferred = protocol.transferData(1.toByte(), testData)
        assertTrue(transferred)

        val written = mock.snapshot(start, size)
        assertArrayEquals(testData, written)
    }

    @Test
    fun wrongSequenceReturnsNegativeResponse() {
        val (_, protocol) = setupEmulator()

        val start = 0x180000
        val size = 256
        protocol.requestDownload(start, size)

        // Block 1 should be sequence 1. Sending 2 must return NRC 0x24 (Sequence Error)
        val exc = assertThrows(KwpNegativeResponseException::class.java) {
            protocol.transferData(2.toByte(), ByteArray(128))
        }
        assertEquals(0x36, exc.failedSid)
        assertEquals(0x24, exc.nrc)
    }

    @Test
    fun transferExitRejectsIncompleteDownload() {
        val (_, protocol) = setupEmulator()

        val start = 0x180000
        val size = 256
        protocol.requestDownload(start, size)

        // Only transfer 128 bytes out of 256
        protocol.transferData(1.toByte(), ByteArray(128))

        // Transfer exit should fail with NRC 0x22 (Conditions Not Correct)
        val exc = assertThrows(KwpNegativeResponseException::class.java) {
            protocol.requestTransferExit()
        }
        assertEquals(0x37, exc.failedSid)
        assertEquals(0x22, exc.nrc)
    }

    @Test
    fun completedDownloadCanBeReadBackByteForByte() {
        val (mock, protocol) = setupEmulator()

        val start = 0x180000
        val size = 256
        protocol.requestDownload(start, size)

        val chunk1 = ByteArray(128) { 0xAA.toByte() }
        val chunk2 = ByteArray(128) { 0x55.toByte() }
        assertTrue(protocol.transferData(1.toByte(), chunk1))
        assertTrue(protocol.transferData(2.toByte(), chunk2))
        assertTrue(protocol.requestTransferExit())

        // Read back via RequestUpload
        val blockSize = protocol.requestUpload(start, size)
        assertEquals(128, blockSize)

        val read1 = protocol.readMemoryChunk(1.toByte(), blockSize)
        val read2 = protocol.readMemoryChunk(2.toByte(), blockSize)
        assertTrue(protocol.requestTransferExit())

        assertArrayEquals(chunk1, read1)
        assertArrayEquals(chunk2, read2)

        val snapshot = mock.snapshot(start, size)
        assertArrayEquals(chunk1 + chunk2, snapshot)
    }

    @Test
    fun injectedNrc78EventuallySucceeds() {
        val (mock, protocol) = setupEmulator()
        // Inject 2 NRC 0x78 responses before successful response
        mock.configureFaults(MockFaults(nrc78Count = 2))

        val sessionSuccess = protocol.startDiagnosticSession(0x85.toByte())
        assertTrue(sessionSuccess)
    }

    @Test
    fun injectedDisconnectAtBlockStopsTransfer() {
        val (mock, protocol) = setupEmulator()

        val start = 0x180000
        val size = 256
        protocol.requestDownload(start, size)

        // Disconnect when block 2 is requested
        mock.configureFaults(MockFaults(disconnectAtBlock = 2))

        assertTrue(protocol.transferData(1.toByte(), ByteArray(128)))

        assertThrows(IOException::class.java) {
            protocol.transferData(2.toByte(), ByteArray(128))
        }
        assertFalse(mock.isConnected)
    }
}
