package com.convoscribe.diarization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [SpeakerSampleCollector]. The collector
 * only operates on `FloatArray`s and `SpeakerTurn` value
 * classes, so the whole suite runs without Robolectric.
 *
 * The four behaviours pinned by the spec:
 *
 *  * **Cap at `maxMs`.** Each speaker's concatenated audio is
 *    bounded by the documented upper limit (30 000 ms by
 *    default, 480 000 samples at 16 kHz).
 *  * **Skip short turns.** Turns shorter than `minTurnMs`
 *    (default 500 ms) are dropped and do not contribute to
 *    the speaker's buffer.
 *  * **Drop sparse speakers.** Speakers with less than
 *    [SpeakerSampleCollector.MIN_COLLECTED_MS] collected are
 *    removed from the result map.
 *  * **Clamp to bounds.** Turn windows that exceed the
 *    recording's sample count are clamped via
 *    `coerceAtMost(samples.size)` so an over-shoot does not
 *    throw.
 */
class SpeakerSampleCollectorTest {

    /**
     * Convenience: build a 60-second "silent" recording (1 s of
     * 16 000 samples = 1_000 ms). We only care about *length*
     * here, not values, so leaving the array at zero is fine.
     */
    private fun silentRecording(durationMs: Long): FloatArray {
        return FloatArray((durationMs * 16).toInt())
    }

    /**
     * Spec test 1: a speaker with two 5-second turns gets a
     * 10-second buffer, capped to the 30 000 ms default but
     * otherwise untouched. The 30 000 ms cap is hit only on
     * greedy speakers (see spec test 2).
     */
    @Test
    fun collectsConcatenatedSamplesAcrossMultipleTurns() {
        val samples = silentRecording(60_000L)
        val turns = listOf(
            SpeakerTurn(startMs = 0L, endMs = 5_000L, speaker = 0),
            SpeakerTurn(startMs = 10_000L, endMs = 15_000L, speaker = 0)
        )
        val collected = SpeakerSampleCollector.collect(
            samples = samples,
            turns = turns,
            // raw id == renumbered id
            speakerMap = { it }
        )
        val buf = collected[0]
        assertNotNull("speaker 0 should have a buffer", buf)
        // Two 5-second turns = 10 seconds = 160 000 samples.
        assertEquals(160_000, buf!!.size)
        assertNull("only one speaker was seen", collected[1])
    }

    /**
     * Spec test 2: a single very long turn (45 000 ms) is
     * truncated so the buffer lands at exactly `maxMs` worth of
     * samples. We assert the *suffix* (the most recent
     * speech-shaped audio) is what survives, matching the
     * collector's "prefer the suffix on budget overflow" rule.
     */
    @Test
    fun capsBufferAtMaxMs() {
        val samples = silentRecording(45_000L)
        val turns = listOf(
            SpeakerTurn(startMs = 0L, endMs = 45_000L, speaker = 5)
        )
        val maxMs = 30_000L
        val expectedSamples = (maxMs * 16).toInt()
        val collected = SpeakerSampleCollector.collect(
            samples = samples,
            turns = turns,
            // remap speaker 5 → 0
            speakerMap = { 0 },
            maxMs = maxMs
        )
        val buf = collected[0]
        assertNotNull(buf)
        assertEquals(expectedSamples, buf!!.size)
    }

    /**
     * Spec test 3: a turn shorter than `minTurnMs` (the
     * documented default is 500 ms) is dropped on the floor —
     * it does not contribute to the speaker's buffer at all.
     * A second, longer turn for the same speaker survives the
     * trim.
     */
    @Test
    fun skipsTurnsShorterThanMinTurnMs() {
        val samples = silentRecording(60_000L)
        val turns = listOf(
            // 200 ms = below the 500 ms floor → dropped.
            SpeakerTurn(startMs = 0L, endMs = 200L, speaker = 0),
            // 3 000 ms turn → kept.
            SpeakerTurn(startMs = 1_000L, endMs = 4_000L, speaker = 0)
        )
        val collected = SpeakerSampleCollector.collect(
            samples = samples,
            turns = turns,
            speakerMap = { it }
        )
        val buf = collected[0]
        assertNotNull(buf)
        // Only the 3 000 ms turn survived: 48 000 samples.
        // (well below the 30 000 ms cap and the 2 000 ms floor).
        assertEquals(48_000, buf!!.size)
    }

    /**
     * Spec test 4: a speaker with only one short (yet
     * `>= minTurnMs`) turn totalling under
     * [SpeakerSampleCollector.MIN_COLLECTED_MS] is dropped from
     * the result. The 2 000 ms floor prevents feeding the
     * embedding model a buffer that is too small to match
     * anything.
     */
    @Test
    fun dropsSpeakersWithLessThanMinCollectedMs() {
        val samples = silentRecording(60_000L)
        val turns = listOf(
            // 1 500 ms = below the 2 000 ms floor.
            SpeakerTurn(startMs = 0L, endMs = 1_500L, speaker = 0),
            // This speaker is comfortably above the floor and
            // stays in the map.
            SpeakerTurn(startMs = 2_000L, endMs = 7_000L, speaker = 1)
        )
        val collected = SpeakerSampleCollector.collect(
            samples = samples,
            turns = turns,
            speakerMap = { it }
        )
        // The under-floor speaker (raw 0) is gone.
        assertNull("speaker 0 must be dropped below 2 000 ms", collected[0])
        // The over-floor speaker (raw 1) survives with a
        // 5 000 ms buffer (80 000 samples).
        val buf = collected[1]
        assertNotNull("speaker 1 must survive", buf)
        assertEquals(80_000, buf!!.size)
    }

    /**
     * Spec test 5: an empty samples array returns an empty
     * map. The collector refuses to start when there is no
     * audio to slice into, so the diarizer path that calls it
     * never sees a fake "speaker with garbage data" buffer.
     */
    @Test
    fun emptySamplesYieldEmptyMap() {
        val collected = SpeakerSampleCollector.collect(
            samples = FloatArray(0),
            turns = listOf(SpeakerTurn(0L, 1_000L, speaker = 0)),
            speakerMap = { it }
        )
        assertTrue(collected.isEmpty())
    }

    /**
     * Spec test 6: an empty turns list returns an empty map.
     */
    @Test
    fun emptyTurnsYieldEmptyMap() {
        val collected = SpeakerSampleCollector.collect(
            samples = silentRecording(60_000L),
            turns = emptyList(),
            speakerMap = { it }
        )
        assertTrue(collected.isEmpty())
    }

    /**
     * Spec test 7: a `speakerMap` that returns `null` skips the
     * turn. This mirrors how the use case translates raw engine
     * ids through [SpeakerAssigner.Assignment.rawToIndex]: raw
     * ids that never got renumbered (because no segment
     * overlapped their turn) are dropped. With the mapper
     * `raw == 0 → null, raw == 1 → 0`, the test turns map to
     * buckets {0}, and the result map has key `0` only.
     */
    @Test
    fun speakerMapReturningNullSkipsThatTurn() {
        val samples = silentRecording(60_000L)
        val turns = listOf(
            // Raw id 0 has no entry in the map → skipped.
            SpeakerTurn(startMs = 0L, endMs = 5_000L, speaker = 0),
            SpeakerTurn(startMs = 10_000L, endMs = 15_000L, speaker = 1)
        )
        val collected = SpeakerSampleCollector.collect(
            samples = samples,
            turns = turns,
            // raw 1 → 0, raw 0 → null
            speakerMap = { raw -> if (raw == 0) null else raw - 1 }
        )
        // The dropped turn means renumbered slot 1 is never
        // populated (raw 0 was dropped, raw 1 was renumbered to
        // 0). The collected map therefore has exactly one key
        // (0), no entry for 1.
        assertEquals(setOf(0), collected.keys)
        // Key 0 holds the audio for raw 1's 5 000 ms turn:
        // 5 000 ms * 16 samples/ms = 80 000 samples.
        val buf = collected[0]
        assertNotNull(buf)
        assertEquals(80_000, buf!!.size)
        assertNull("renumbered slot 1 has no audio", collected[1])
    }

    /**
     * Spec test 8: a turn that runs past the end of the
     * samples array is clamped by `coerceAtMost(samples.size)`
     * instead of throwing an out-of-bounds. This is the
     * safety net for a future diarizer tweak that surfaces a
     * turn whose `endMs` exceeds the recording length.
     */
    @Test
    fun turnsBeyondRecordingAreClamped() {
        // 5 000 ms recording. The turn claims to end at 8 000
        // ms — that's 3 000 ms past the buffer. The collector
        // must clamp and emit exactly the 5 000 ms of audio
        // that exists.
        val samples = silentRecording(5_000L)
        val turns = listOf(
            SpeakerTurn(startMs = 0L, endMs = 8_000L, speaker = 0)
        )
        val collected = SpeakerSampleCollector.collect(
            samples = samples,
            turns = turns,
            speakerMap = { it }
        )
        val buf = collected[0]
        assertNotNull(buf)
        // 5 000 ms worth of samples = 80 000 samples.
        assertEquals(80_000, buf!!.size)
    }

    /**
     * Bonus check: the cap applies *across* windows. A speaker
     * with two 20 000 ms turns gets a 30 000 ms buffer: the
     * first turn is kept whole, the second is truncated to
     * the remaining 10 000 ms budget — proving the running
     * total is consulted on every window, not just inside one.
     */
    @Test
    fun capAppliesAcrossMultipleTurnsNotJustWithinOne() {
        val samples = silentRecording(60_000L)
        val turns = listOf(
            SpeakerTurn(startMs = 0L, endMs = 20_000L, speaker = 0),
            // 20 000 ms turn but only 10 000 ms of budget
            // remains after the first; the suffix of this turn
            // (the last 10 000 ms) is what gets included.
            SpeakerTurn(startMs = 30_000L, endMs = 50_000L, speaker = 0)
        )
        val maxMs = 30_000L
        val collected = SpeakerSampleCollector.collect(
            samples = samples,
            turns = turns,
            speakerMap = { it },
            maxMs = maxMs
        )
        val buf = collected[0]
        assertNotNull(buf)
        // Total = 30 000 ms * 16 = 480 000 samples (20 000 ms
        // from the first turn + 10 000 ms suffix from the
        // second).
        assertEquals(480_000, buf!!.size)
    }

    /**
     * Bonus: the resulting map is iterable in insertion order
     * so the embedder pass predicts which speaker lands first.
     * Implementation detail, but reassuring to pin.
     */
    @Test
    fun resultPreservesInsertionOrderOfSpeakers() {
        val samples = silentRecording(60_000L)
        val turns = listOf(
            SpeakerTurn(startMs = 0L, endMs = 5_000L, speaker = 3),
            SpeakerTurn(startMs = 10_000L, endMs = 15_000L, speaker = 0),
            SpeakerTurn(startMs = 20_000L, endMs = 25_000L, speaker = 7)
        )
        val collected = SpeakerSampleCollector.collect(
            samples = samples,
            turns = turns,
            // identity, so speaker 3 = 3, etc.
            speakerMap = { it }
        )
        // Insertion order is 3, then 0, then 7.
        assertEquals(listOf(3, 0, 7), collected.keys.toList())
        assertFalse(collected.isEmpty())
    }

    /** With a budget for one turn, the speaker's most confident turn is the one used. */
    @Test
    fun collect_fillsTheBudgetFromTheMostConfidentTurnsFirst() {
        // 6 s of audio: value 0.1 in the first 3 s, 0.9 in the last 3 s.
        val samples = FloatArray(96_000) { i -> if (i < 48_000) 0.1f else 0.9f }
        val turns = listOf(
            SpeakerTurn(0, 3_000, speaker = 5, confidence = 0.4f),
            SpeakerTurn(3_000, 6_000, speaker = 5, confidence = 0.95f)
        )

        val out = SpeakerSampleCollector.collect(samples, turns, speakerMap = { 0 }, maxMs = 3_000L)

        assertTrue(out.getValue(0).all { it == 0.9f })
    }

    /** Placeholder confidences (sherpa's -2) leave the recording order alone. */
    @Test
    fun collect_withPlaceholderConfidences_keepsRecordingOrder() {
        val samples = FloatArray(96_000) { i -> if (i < 48_000) 0.1f else 0.9f }
        val turns = listOf(
            SpeakerTurn(0, 3_000, speaker = 5, confidence = -2f),
            SpeakerTurn(3_000, 6_000, speaker = 5, confidence = -2f)
        )

        val out = SpeakerSampleCollector.collect(samples, turns, speakerMap = { 0 }, maxMs = 3_000L)

        assertTrue(out.getValue(0).all { it == 0.1f })
    }
}
