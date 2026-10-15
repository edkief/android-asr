package com.convoscribe.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for [EmbeddingCodec]. Speaker embeddings
 * round-trip through the database as bytes, so a silent
 * precision loss here would corrupt every cosine score in the
 * pipeline — we pin both halves of the conversion.
 */
class EmbeddingCodecTest {

    @Test
    fun roundTripFloatsToBytesAndBack() {
        val original = floatArrayOf(0.1f, -0.2f, 3.14159f, 1.0e-6f, 1.0e6f, 0f, -0f)
        val bytes = original.toBytes()
        val restored = bytes.toFloats()
        assertArrayEquals(
            "FloatArray → ByteArray → FloatArray must round-trip exactly",
            original,
            restored,
            0.0f
        )
    }

    @Test
    fun roundTripPreservesZeroAndSignedZero() {
        // Signed zero survives the byte round-trip: +0f and -0f
        // are distinct under `equals` but encode to the same
        // bit pattern (0x00000000). We assert `==` (which treats
        // them as equal) rather than `equals` so we test the
        // codec, not the bit representation.
        val original = floatArrayOf(0f, -0f, 1f, -1f)
        val restored = original.toBytes().toFloats()
        assertTrue(original[0] == restored[0])
        assertTrue(original[1] == restored[1])
        assertArrayEquals(original, restored, 0.0f)
    }

    @Test
    fun toBytesProducesFourBytesPerFloat() {
        // The Room column is a BLOB; an unexpected length would
        // silently truncate the embedding. Pin the size so a
        // future "let's be clever with bulk conversion" refactor
        // can't sneak in a packing bug.
        val floats = floatArrayOf(1f, 2f, 3f, 4f, 5f)
        val bytes = floats.toBytes()
        assertEquals(EMBEDDING_FLOAT_BYTES * floats.size, bytes.size)
    }

    @Test
    fun emptyArrayRoundTripsToEmptyBytes() {
        val empty = FloatArray(0)
        assertEquals(0, empty.toBytes().size)
        assertEquals(0, ByteArray(0).toFloats().size)
    }

    @Test
    fun toBytesIsLittleEndian() {
        // 1.0f as little-endian float32 is 00 00 80 3f. We pin
        // the byte order so a JVM that defaults to big-endian
        // (none today, but the spec is portable) cannot corrupt
        // the on-disk format.
        val bytes = floatArrayOf(1.0f).toBytes()
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x3f), bytes)
    }
}
