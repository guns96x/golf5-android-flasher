package com.golf5.edc16flasher.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class KwpTransportSmokeTest {
    @Test
    fun fakeTransportCanRoundTripBytes() {
        val fake = object : KwpTransport {
            override val transportName = "fake"
            override val isPhysical = false
            override val isMpps = false
            private val queue = ArrayDeque<Byte>()
            override fun write(data: ByteArray, timeoutMs: Int) { data.forEach(queue::addLast) }
            override fun read(buffer: ByteArray, timeoutMs: Int): Int {
                var n = 0
                while (n < buffer.size && queue.isNotEmpty()) buffer[n++] = queue.removeFirst()
                return n
            }
            override fun getBatteryVoltage(): Float? = 13.8f
            override fun sendFastInit(pulseMs: Int, initialPayload: ByteArray) = Unit
        }
        fake.write(byteArrayOf(1, 2, 3))
        val out = ByteArray(3)
        assertEquals(3, fake.read(out))
        assertArrayEquals(byteArrayOf(1, 2, 3), out)
    }
}
