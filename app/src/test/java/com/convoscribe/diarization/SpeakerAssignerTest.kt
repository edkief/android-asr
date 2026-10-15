package com.convoscribe.diarization

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM coverage for [SpeakerAssigner]. Every test exercises
 * the three rules from the PRD:
 *
 *  1. overlap-wins with earliest-turn tie-break,
 *  2. nearest-turn fallback capped at 1 s,
 *  3. first-appearance renumbering of raw engine ids.
 *
 * `SpeakerTurn` and `SegmentSpan` are simple value classes, so
 * the whole suite runs without Robolectric or a `Context`.
 */
class SpeakerAssignerTest {

    /**
     * Spec test 1: one segment `[0, 1000)`, turns `spk 1` on
     * `[0, 600)` and `spk 0` on `[600, 1000)`. The first turn
     * overlaps the segment by 600 ms; the second by 400 ms; the
     * speaker with the larger overlap (`spk 1`) wins and is
     * renumbered to `0` because it is the only assigned speaker.
     */
    @Test
    fun overlapWinsAndSpeakerIsRenumberedToZero() {
        val segments = listOf(SegmentSpan(id = 1L, startMs = 0L, endMs = 1000L))
        val turns = listOf(
            SpeakerTurn(startMs = 0L, endMs = 600L, speaker = 1),
            SpeakerTurn(startMs = 600L, endMs = 1000L, speaker = 0)
        )
        val result = SpeakerAssigner.assign(segments, turns)
        assertEquals(mapOf(1L to 0), result.speakerBySegment)
        assertEquals(1, result.speakerCount)
    }

    /**
     * Spec test 2: two segments with raw engine ids `3` then
     * `1`. After first-appearance renumbering they become `0`
     * then `1`, and `speakerCount == 2`. The second segment's
     * turn is the only one whose raw id is `1`, so it must end up
     * as renumbered `1` regardless of the input order.
     */
    @Test
    fun twoSegmentsFirstAppearanceRenumbering() {
        val segments = listOf(
            SegmentSpan(id = 10L, startMs = 0L, endMs = 500L),
            SegmentSpan(id = 20L, startMs = 600L, endMs = 1500L)
        )
        val turns = listOf(
            // Segment 10 covers the speaker-3 turn.
            SpeakerTurn(startMs = 0L, endMs = 500L, speaker = 3),
            // Segment 20 covers the speaker-1 turn.
            SpeakerTurn(startMs = 600L, endMs = 1500L, speaker = 1)
        )
        val result = SpeakerAssigner.assign(segments, turns)
        assertEquals(mapOf(10L to 0, 20L to 1), result.speakerBySegment)
        assertEquals(2, result.speakerCount)
    }

    /**
     * Spec test 3, case A: a segment `[5000, 6000)` with no
     * overlapping turns; the nearest turn ends at 4500 ms, i.e.
     * the gap from the segment start is `5000 - 4500 = 500 ms`
     * (≤ 1000 ms) — so the turn's speaker is assigned.
     */
    @Test
    fun nearestTurnWithinOneSecondIsAssigned() {
        val segments = listOf(SegmentSpan(id = 7L, startMs = 5000L, endMs = 6000L))
        val turns = listOf(
            SpeakerTurn(startMs = 3000L, endMs = 4500L, speaker = 2)
        )
        val result = SpeakerAssigner.assign(segments, turns)
        assertEquals(mapOf(7L to 0), result.speakerBySegment)
        assertEquals(1, result.speakerCount)
    }

    /**
     * Spec test 3, case B: same segment, but the nearest turn
     * ends at 3500 ms — gap is `5000 - 3500 = 1500 ms`, beyond
     * the 1 s budget, so the segment stays `null` and
     * `speakerCount` is `0`.
     */
    @Test
    fun nearestTurnBeyondOneSecondLeavesSegmentNull() {
        val segments = listOf(SegmentSpan(id = 8L, startMs = 5000L, endMs = 6000L))
        val turns = listOf(
            SpeakerTurn(startMs = 2000L, endMs = 3500L, speaker = 2)
        )
        val result = SpeakerAssigner.assign(segments, turns)
        assertEquals(mapOf(8L to null), result.speakerBySegment)
        assertEquals(0, result.speakerCount)
    }

    /**
     * Spec test 4: two turns of *different* speakers both overlap
     * a single segment by exactly 400 ms; the tie-break (earliest
     * overlapping turn starts first) must give the segment to
     * `spk 0` (turn starts at 100, while `spk 1`'s turn starts
     * at 200). After renumbering, `spk 0` becomes `0`.
     */
    @Test
    fun equalOverlapTieGoesToTheTurnThatStartsFirst() {
        val segments = listOf(SegmentSpan(id = 42L, startMs = 0L, endMs = 1000L))
        val turns = listOf(
            // Each turn overlaps the segment by exactly 400 ms;
            // tie-break must give the segment to spk 0.
            SpeakerTurn(startMs = 100L, endMs = 500L, speaker = 0),
            SpeakerTurn(startMs = 200L, endMs = 600L, speaker = 1)
        )
        val result = SpeakerAssigner.assign(segments, turns)
        assertEquals(mapOf(42L to 0), result.speakerBySegment)
        assertEquals(1, result.speakerCount)
    }

    /**
     * Spec test 5: empty turn list ⇒ every segment is `null` and
     * `speakerCount == 0`. The map keys are still the segment
     * ids, so the caller can iterate without filtering.
     */
    @Test
    fun emptyTurnsYieldsAllNullsAndZeroSpeakerCount() {
        val segments = listOf(
            SegmentSpan(id = 1L, startMs = 0L, endMs = 500L),
            SegmentSpan(id = 2L, startMs = 1000L, endMs = 2000L),
            SegmentSpan(id = 3L, startMs = 3000L, endMs = 4000L)
        )
        val result = SpeakerAssigner.assign(segments, emptyList())
        assertEquals(mapOf(1L to null, 2L to null, 3L to null), result.speakerBySegment)
        assertEquals(0, result.speakerCount)
    }

    /**
     * Boundary of the 1 s nearest-turn rule: a gap of exactly
     * 1000 ms is *allowed* (the rule is "≤ 1000 ms"); a gap of
     * 1001 ms is not. This pins the comparison so a future
     * off-by-one fix can't silently widen the catch.
     */
    @Test
    fun nearestTurnGapBoundary() {
        val segments = listOf(SegmentSpan(id = 1L, startMs = 6000L, endMs = 7000L))
        // Gap exactly 1000 ms (turn ends at 5000).
        val resultAt = SpeakerAssigner.assign(
            segments,
            listOf(SpeakerTurn(startMs = 3000L, endMs = 5000L, speaker = 0))
        )
        assertEquals(mapOf(1L to 0), resultAt.speakerBySegment)

        // Gap 1001 ms (turn ends at 4999) — rejected.
        val resultOver = SpeakerAssigner.assign(
            segments,
            listOf(SpeakerTurn(startMs = 2000L, endMs = 4999L, speaker = 0))
        )
        assertEquals(mapOf(1L to null), resultOver.speakerBySegment)
    }

    /**
     * Empty segments are a no-op: nothing to assign, count is
     * zero, the returned map is empty. Prevents the caller from
     * having to defend against `Empty → throw`.
     */
    @Test
    fun emptySegmentsYieldsEmptyAssignment() {
        val result = SpeakerAssigner.assign(emptyList(), emptyList())
        assertEquals(emptyMap<Long, Int?>(), result.speakerBySegment)
        assertEquals(0, result.speakerCount)
    }

    /**
     * Two turns of the *same* speaker overlap the segment; the
     * rule sums them before comparing. 300 ms + 200 ms = 500 ms
     * of `spk 1` vs 200 ms of `spk 2` ⇒ `spk 1` wins.
     */
    @Test
    fun overlapIsSummedAcrossMultipleTurnsOfTheSameSpeaker() {
        val segments = listOf(SegmentSpan(id = 1L, startMs = 0L, endMs = 1000L))
        val turns = listOf(
            SpeakerTurn(startMs = 0L, endMs = 300L, speaker = 1),
            SpeakerTurn(startMs = 400L, endMs = 600L, speaker = 1),
            SpeakerTurn(startMs = 800L, endMs = 1000L, speaker = 2)
        )
        val result = SpeakerAssigner.assign(segments, turns)
        assertEquals(mapOf(1L to 0), result.speakerBySegment)
        assertEquals(1, result.speakerCount)
    }

    /**
     * Segment [a, b) with a turn that *touches* it at the edge
     * counts as overlap (turn.startMs == segment.startMs ⇒ 0 ms
     * of intersection, but a turn whose end equals the segment's
     * start contributes the full [a, b) overlap). The case
     * here: turn [0, 1000) and segment [1000, 2000) — touching,
     * so the overlap rule returns 0 ms and we fall back to
     * nearest-turn. Gap = 0 (touching) ⇒ within 1 s ⇒ assigned.
     */
    @Test
    fun touchingTurnIsTreatedAsZeroGapAndAssigned() {
        val segments = listOf(SegmentSpan(id = 1L, startMs = 1000L, endMs = 2000L))
        val turns = listOf(SpeakerTurn(startMs = 0L, endMs = 1000L, speaker = 4))
        val result = SpeakerAssigner.assign(segments, turns)
        assertEquals(mapOf(1L to 0), result.speakerBySegment)
    }

    /**
     * First-appearance order must follow `startMs`, not the
     * input order. The earlier-starting segment carries `spk 7`
     * (→ `0`); the later one carries `spk 3` (→ `1`). We pass
     * the segments in *reverse* start order to prove the sort
     * happens.
     */
    @Test
    fun firstAppearanceFollowsStartMsNotInputOrder() {
        val segments = listOf(
            // Input order: late segment first.
            SegmentSpan(id = 20L, startMs = 1500L, endMs = 2000L),
            SegmentSpan(id = 10L, startMs = 0L, endMs = 500L)
        )
        val turns = listOf(
            SpeakerTurn(startMs = 1500L, endMs = 2000L, speaker = 3),
            SpeakerTurn(startMs = 0L, endMs = 500L, speaker = 7)
        )
        val result = SpeakerAssigner.assign(segments, turns)
        // Sorted by startMs: id=10 (spk 7) is first ⇒ renumbered 0;
        // id=20 (spk 3) is second ⇒ renumbered 1.
        assertEquals(mapOf(20L to 1, 10L to 0), result.speakerBySegment)
        assertEquals(2, result.speakerCount)
    }

    /**
     * Turns that overlap *no* segment must not influence the
     * renumbering. The "ghost" turn carries a raw id (`9`) we
     * would otherwise never see; if the assigner counted it, the
     * first assigned speaker would shift to `1` instead of `0`.
     */
    @Test
    fun turnsNotOverlappingAnySegmentDoNotInfluenceRenumbering() {
        val segments = listOf(
            SegmentSpan(id = 1L, startMs = 0L, endMs = 100L)
        )
        val turns = listOf(
            SpeakerTurn(startMs = 0L, endMs = 100L, speaker = 5),
            // Ghost turn far away from any segment.
            SpeakerTurn(startMs = 10_000L, endMs = 11_000L, speaker = 9)
        )
        val result = SpeakerAssigner.assign(segments, turns)
        assertEquals(mapOf(1L to 0), result.speakerBySegment)
        // The ghost speaker (raw id 9) must not appear in the
        // renumbered map and must not bump the count to 2.
        assertEquals(0, result.speakerBySegment[1L])
        assertEquals(1, result.speakerCount)
    }
}
