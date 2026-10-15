package com.convoscribe.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the tiny helpers in [Pcm]. These are pure functions
 * so no temporary folder or coroutine plumbing is required.
 */
class PcmTest {

    @Test
    fun samplesToMs_16000SamplesAt16kIsOneSecond() {
        assertEquals(1000L, samplesToMs(16_000))
    }

    @Test
    fun samplesToMs_chunkSizeIsExactlyOneHundredMs() {
        // CHUNK_SAMPLES (1 600) is the cadence the recorder hands
        // the ASR; one chunk must be 100 ms, not 99 or 101.
        assertEquals(100L, samplesToMs(AudioSpec.CHUNK_SAMPLES.toLong()))
    }

    @Test
    fun samplesToMs_zeroIsZero() {
        assertEquals(0L, samplesToMs(0))
    }

    @Test
    fun toFloatSamples_positiveHalfScaleIsHalf() {
        val out = ShortArray(1) { 16_384 }.toFloatSamples()
        assertEquals(0.5f, out[0], 0.0001f)
    }

    @Test
    fun toFloatSamples_fullScaleNegativeIsMinusOne() {
        val out = ShortArray(1) { -32_768 }.toFloatSamples()
        assertEquals(-1.0f, out[0], 0.0001f)
    }

    @Test
    fun toFloatSamples_zeroStaysZero() {
        val out = ShortArray(1) { 0 }.toFloatSamples()
        assertEquals(0.0f, out[0], 0.0001f)
    }

    @Test
    fun toFloatSamples_preservesLength() {
        val src = ShortArray(2_000) { (it % 32_768).toShort() }
        val out = src.toFloatSamples()
        assertEquals(src.size, out.size)
    }

    @Test
    fun toFloatSamples_returnsANewArray() {
        val src = ShortArray(4) { 1 }
        val out = src.toFloatSamples()
        assertNotSame(src, out)
        // The original is left untouched.
        for (s in src) {
            assertEquals(1, s.toInt())
        }
    }

    @Test
    fun toFloatSamples_matchesManualScaling() {
        // Spot-check: every value is the input divided by 32 768f.
        val src = shortArrayOf(0, 1, -1, 12_345, -12_345, 32_767, -32_768)
        val out = src.toFloatSamples()
        for (i in src.indices) {
            assertEquals(src[i] / 32_768f, out[i], 0.0001f)
        }
        // The convention is approximate `[-1, 1]`: the positive end
        // rounds to just under 1 because 32 767 < 32 768.
        assertTrue(out[5] in 0.9999f..1.0f)
        assertTrue(out[6] in -1.0f..-0.9999f)
    }
}
