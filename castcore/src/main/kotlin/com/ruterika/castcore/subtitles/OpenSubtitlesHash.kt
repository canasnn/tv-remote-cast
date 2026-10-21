package com.ruterika.castcore.subtitles

import java.io.File
import java.io.RandomAccessFile

/**
 * The standard OpenSubtitles 64-bit "movie hash": file size + the sum of the first and last
 * 64 KiB of the file interpreted as little-endian 64-bit integers (overflow wraps), printed as
 * 16 lowercase hex digits.
 *
 * The reader-based [compute] works with any random-access source (local file, content URI
 * opened as a seekable descriptor, HTTP range requests...).
 */
object OpenSubtitlesHash {
    /** Size of the head and tail chunks that are hashed. */
    const val CHUNK_SIZE: Int = 64 * 1024

    /**
     * Computes the hash of a source of [sizeBytes] bytes.
     *
     * @param readHead reads `length` bytes starting at `offset`. Returning fewer bytes is
     *   tolerated (missing bytes count as zero), but should not happen for a valid source.
     * @return the hash as 16 lowercase hex characters.
     * @throws IllegalArgumentException if [sizeBytes] is not positive.
     */
    fun compute(sizeBytes: Long, readHead: (offset: Long, length: Int) -> ByteArray): String {
        require(sizeBytes > 0) { "sizeBytes must be positive" }
        val chunk = minOf(CHUNK_SIZE.toLong(), sizeBytes).toInt()
        var hash = sizeBytes
        hash += sumLongs(readHead(0L, chunk), chunk)
        hash += sumLongs(readHead(sizeBytes - chunk, chunk), chunk)
        return toHex(hash)
    }

    /** Computes the hash of a local [file]. */
    fun compute(file: File): String = RandomAccessFile(file, "r").use { raf ->
        compute(raf.length()) { offset, length ->
            val buf = ByteArray(length)
            raf.seek(offset)
            var read = 0
            while (read < length) {
                val n = raf.read(buf, read, length - read)
                if (n < 0) break
                read += n
            }
            buf
        }
    }

    private fun sumLongs(bytes: ByteArray, length: Int): Long {
        var sum = 0L
        var i = 0
        while (i < length) {
            var value = 0L
            for (b in 0 until 8) {
                val idx = i + b
                val byte = if (idx < length && idx < bytes.size) bytes[idx].toLong() and 0xFF else 0L
                value = value or (byte shl (8 * b))
            }
            sum += value
            i += 8
        }
        return sum
    }

    private fun toHex(value: Long): String = java.lang.Long.toHexString(value).padStart(16, '0')
}
