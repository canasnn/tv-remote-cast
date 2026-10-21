package com.ruterika.castcore.subtitles

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

class OpenSubtitlesHashTest {

    /** Straightforward reference implementation using ByteBuffer. */
    private fun reference(data: ByteArray): String {
        val size = data.size.toLong()
        val chunk = minOf(65536, data.size)
        fun sum(from: Int): Long {
            val padded = ByteArray((chunk + 7) / 8 * 8)
            System.arraycopy(data, from, padded, 0, chunk)
            val buf = ByteBuffer.wrap(padded).order(ByteOrder.LITTLE_ENDIAN)
            var s = 0L
            while (buf.hasRemaining()) s += buf.long
            return s
        }
        val hash = size + sum(0) + sum(data.size - chunk)
        return String.format("%016x", hash)
    }

    private fun reader(data: ByteArray): (Long, Int) -> ByteArray = { offset, length ->
        data.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    @Test
    fun zeroFileHashIsItsSize() {
        val data = ByteArray(200_000)
        assertEquals("0000000000030d40", OpenSubtitlesHash.compute(data.size.toLong(), reader(data)))
    }

    @Test
    fun matchesReferenceForRandomData() {
        val random = Random(42)
        for (size in listOf(131_072, 200_003, 1_000_000, 70_000, 65_536, 1_000, 13)) {
            val data = random.nextBytes(size)
            assertEquals("size $size", reference(data), OpenSubtitlesHash.compute(size.toLong(), reader(data)))
        }
    }

    @Test
    fun overflowWraps() {
        val data = ByteArray(300_000) { 0xFF.toByte() }
        val hash = OpenSubtitlesHash.compute(data.size.toLong(), reader(data))
        assertEquals(reference(data), hash)
        assertEquals(16, hash.length)
    }

    @Test
    fun onlyHeadAndTailAreRead() {
        val reads = mutableListOf<Pair<Long, Int>>()
        val size = 10L * 1024 * 1024 * 1024 // 10 GiB, never materialized
        OpenSubtitlesHash.compute(size) { offset, length ->
            reads += offset to length
            ByteArray(length)
        }
        assertEquals(listOf(0L to 65536, size - 65536 to 65536), reads)
    }

    @Test
    fun shortReadsCountAsZero() {
        val hash = OpenSubtitlesHash.compute(100_000) { _, _ -> ByteArray(3) { 1 } }
        // Each chunk contributes 0x010101 (three low bytes set) once.
        assertEquals(String.format("%016x", 100_000L + 2 * 0x010101L), hash)
    }

    @Test
    fun fileOverloadMatchesReader() {
        val data = Random(7).nextBytes(150_000)
        val file = File.createTempFile("oshash", ".bin")
        try {
            file.writeBytes(data)
            assertEquals(reference(data), OpenSubtitlesHash.compute(file))
        } finally {
            file.delete()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptySourceRejected() {
        OpenSubtitlesHash.compute(0) { _, _ -> ByteArray(0) }
    }
}
