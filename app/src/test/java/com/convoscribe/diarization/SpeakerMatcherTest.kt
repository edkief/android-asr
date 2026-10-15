package com.convoscribe.diarization

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for [SpeakerMatcher]:
 *  - cosine similarity corner cases (identical, orthogonal,
 *    zero vector, mismatched sizes);
 *  - the greedy name-assignment algorithm at its two threshold
 *    regimes (loose: both names used, tight: only the
 *    confident match).
 *
 * All vectors are 2-D so the maths is verifiable by hand: the
 * diagonal `unit(θ)` = (cos θ, sin θ), and cosine similarity
 * collapses to `cos(angle(a) - angle(b))`.
 */
class SpeakerMatcherTest {

    /**
     * Helper: 2-D unit vector at [degrees] from the x-axis. Used
     * to build embeddings whose pairwise cosine similarities are
     * predictable (cos(angle difference)).
     */
    private fun unit(degrees: Double): FloatArray {
        val rad = Math.toRadians(degrees)
        return floatArrayOf(Math.cos(rad).toFloat(), Math.sin(rad).toFloat())
    }

    @Test
    fun cosineOfIdenticalVectorsIsOne() {
        val v = floatArrayOf(0.6f, 0.8f)
        // Magnitude doesn't matter for cosine — only direction.
        assertEquals(1.0f, SpeakerMatcher.cosine(v, v), 1e-6f)
        // Scaling the vector must not change the result.
        assertEquals(1.0f, SpeakerMatcher.cosine(v, floatArrayOf(1.2f, 1.6f)), 1e-6f)
    }

    @Test
    fun cosineOfOrthogonalVectorsIsZero() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0f, 1f)
        assertEquals(0.0f, SpeakerMatcher.cosine(a, b), 1e-6f)
    }

    @Test
    fun cosineOfZeroVectorIsZero() {
        val zero = FloatArray(3)
        val v = floatArrayOf(1f, 2f, 3f)
        // Any vector vs. zero has no defined direction → 0.
        assertEquals(0.0f, SpeakerMatcher.cosine(zero, v), 0.0f)
        assertEquals(0.0f, SpeakerMatcher.cosine(v, zero), 0.0f)
        assertEquals(0.0f, SpeakerMatcher.cosine(zero, zero), 0.0f)
    }

    @Test
    fun cosineOfMismatchedSizesIsZero() {
        // A bug here would surface as a divide-by-zero or an
        // out-of-bounds read; the spec says return 0 so the
        // caller's threshold filter drops the pair instead of
        // throwing mid-diarization.
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(1f, 2f)
        assertEquals(0.0f, SpeakerMatcher.cosine(a, b), 0.0f)
    }

    @Test
    fun cosineIsClampedToOneForNumericalDrift() {
        // Two identical vectors round up to slightly above 1
        // when the multiplication happens to land on a tie.
        // cos(0) is exactly 1 but the multiply may round the
        // last bit; we still want a finite `≤ 1` result.
        val v = floatArrayOf(0.70710678f, 0.70710678f)
        val score = SpeakerMatcher.cosine(v, v)
        assertTrue("cosine must stay ≤ 1, was $score", score <= 1.0f)
        assertTrue("cosine must stay ≥ -1, was $score", score >= -1.0f)
    }

    @Test
    fun assignNamesAtLooseThresholdGivesBothSpeakersAName() {
        // Build angles so the spec's exact scores come out and
        // no other pair leaks through the threshold filter:
        //   speaker 0 vs Alice: cos = 0.9   (angle diff ≈ 25.84°)
        //   speaker 1 vs Alice: cos = 0.8   (angle diff ≈ 36.87°)
        //   speaker 1 vs Bob:   cos = 0.6   (angle diff ≈ 53.13°)
        //   speaker 0 vs Bob:   cos ≈ -0.44 (orthogonal enough to
        //                                    stay below the 0.5
        //                                    threshold)
        // Choose Alice at 0°; speaker 0 at -25.84°, speaker 1 at
        // +36.87°; Bob at 90° (= 36.87° + 53.13°).
        val speaker0 = unit(-25.84)
        val speaker1 = unit(36.87)
        val alice = unit(0.0)
        val bob = unit(90.0)

        val embeddings = mapOf(
            0 to speaker0,
            1 to speaker1
        )
        val enrolled = listOf(
            SpeakerMatcher.Enrolled("Alice", alice),
            SpeakerMatcher.Enrolled("Bob", bob)
        )

        // Sanity check the math the test hinges on — if these
        // move, the threshold results below change too.
        assertEquals(0.9f, SpeakerMatcher.cosine(speaker0, alice), 1e-2f)
        assertEquals(0.8f, SpeakerMatcher.cosine(speaker1, alice), 1e-2f)
        assertEquals(0.6f, SpeakerMatcher.cosine(speaker1, bob), 1e-2f)
        assertTrue(
            "speaker 0 vs Bob must stay below threshold",
            SpeakerMatcher.cosine(speaker0, bob) < 0.5f
        )

        // Threshold 0.5: all three pairs clear the bar. Greedy
        // order is 0.9, 0.8, 0.6 — speaker 0 claims Alice, then
        // speaker 1's Alice claim loses (Alice is taken), and
        // speaker 1's Bob claim (0.6) wins.
        val result = SpeakerMatcher.assignNames(
            embeddings = embeddings,
            enrolled = enrolled,
            threshold = 0.5f
        )
        assertEquals(
            "loose threshold should assign both speakers",
            mapOf(0 to "Alice", 1 to "Bob"),
            result
        )
    }

    @Test
    fun assignNamesAtTightThresholdDropsWeakMatch() {
        // Same embeddings as the loose case; threshold 0.7 drops
        // the 0.6 speaker-1-vs-Bob pair and leaves only the 0.9
        // match (speaker 0 → Alice). The 0.8 pair (speaker 1 vs
        // Alice) is above 0.7 but loses on the greedy walk
        // because Alice is already claimed.
        val speaker0 = unit(-25.84)
        val speaker1 = unit(36.87)
        val alice = unit(0.0)
        val bob = unit(90.0)

        val result = SpeakerMatcher.assignNames(
            embeddings = mapOf(0 to speaker0, 1 to speaker1),
            enrolled = listOf(
                SpeakerMatcher.Enrolled("Alice", alice),
                SpeakerMatcher.Enrolled("Bob", bob)
            ),
            threshold = 0.7f
        )
        assertEquals(mapOf(0 to "Alice"), result)
    }

    @Test
    fun assignNamesEnforcesOneToOne() {
        // Two enrolled speakers with the *same* embedding (both
        // Alice). Both scores are 1.0 against speaker 0; the
        // greedy walk must claim the first enrolled index and
        // leave the second unused, because the spec says each
        // enrolled name is used at most once.
        val same = floatArrayOf(0.6f, 0.8f)
        val result = SpeakerMatcher.assignNames(
            embeddings = mapOf(0 to same),
            enrolled = listOf(
                SpeakerMatcher.Enrolled("Alice", same),
                SpeakerMatcher.Enrolled("Alice", same)
            ),
            threshold = 0.5f
        )
        assertEquals(1, result.size)
        assertEquals("Alice", result[0])
    }

    @Test
    fun assignNamesEmptyInputsYieldEmptyMap() {
        assertEquals(emptyMap<Int, String>(), SpeakerMatcher.assignNames(emptyMap(), emptyList(), 0f))
        assertEquals(
            emptyMap<Int, String>(),
            SpeakerMatcher.assignNames(mapOf(0 to floatArrayOf(1f, 0f)), emptyList(), 0f)
        )
        assertEquals(
            emptyMap<Int, String>(),
            SpeakerMatcher.assignNames(
                emptyMap(),
                listOf(SpeakerMatcher.Enrolled("Alice", floatArrayOf(1f, 0f))),
                0f
            )
        )
    }

    @Test
    fun assignNamesDropsScoresBelowThreshold() {
        // speaker at 90°, Alice at 0° → score 0. With any
        // threshold > 0 the pair is filtered out.
        val result = SpeakerMatcher.assignNames(
            embeddings = mapOf(0 to unit(90.0)),
            enrolled = listOf(SpeakerMatcher.Enrolled("Alice", unit(0.0))),
            threshold = 0.01f
        )
        assertEquals(emptyMap<Int, String>(), result)
    }

    @Test
    fun cosineAtKnownAngle() {
        // Pin the math: 60° apart in 2-D ⇒ cos = 0.5 exactly.
        val a = unit(0.0)
        val b = unit(60.0)
        assertEquals(0.5f, SpeakerMatcher.cosine(a, b), 1e-6f)
        // Sanity check on the helper itself: |unit(θ)| = 1.
        assertEquals(1.0, sqrt((a[0] * a[0] + a[1] * a[1]).toDouble()), 1e-6)
    }
}
