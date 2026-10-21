package com.canasnn.tvremote

import com.canasnn.tvremote.androidLib.remote.RemoteMessageManager
import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun testMessageManagerLengthAndCreate() {
        val manager = RemoteMessageManager()
        val message = byteArrayOf(1, 2, 3, 4, 5)
        val result = manager.addLengthAndCreate(message)

        // Expected varint size for length 5 is 1 byte.
        // Total expected size is 1 + 5 = 6 bytes.
        assertEquals(6, result.size)
        assertEquals(5, result[0].toInt()) // length prefix
        for (i in 0 until 5) {
            assertEquals(message[i], result[i + 1])
        }
    }

    @Test
    fun testMessageManagerLargeLengthAndCreate() {
        val manager = RemoteMessageManager()
        // Message of size 128 (varint size should be 2 bytes)
        val message = ByteArray(128) { it.toByte() }
        val result = manager.addLengthAndCreate(message)

        // Varint size for 128 is 2 bytes (0x80, 0x01)
        assertEquals(130, result.size)
        assertEquals(0x80.toByte(), result[0])
        assertEquals(0x01.toByte(), result[1])
        for (i in 0 until 128) {
            assertEquals(message[i], result[i + 2])
        }
    }
}