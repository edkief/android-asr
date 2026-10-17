package com.convoscribe.diarization

import com.convoscribe.asr.TimedToken
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-JVM tests for [SegmentSplitter]. */
class SegmentSplitterTest {

    /** Words as `(text, ms)`; each starts a new word (leading space). */
    private fun line(vararg words: Pair<String, Long>, startMs: Long = 0, endMs: Long = 4_000) = SplittableSegment(
        id = 1,
        startMs = startMs,
        endMs = endMs,
        text = words.joinToString(" ") { it.first },
        tokens = words.map { (w, ms) -> TimedToken(" $w", ms) }
    )

    private fun turn(startMs: Long, endMs: Long, speaker: Int) = SpeakerTurn(startMs, endMs, speaker)

    @Test
    fun lineSpanningTwoSpeakers_splitsAtTheTurnBoundary() {
        val segment = line("are" to 100, "you" to 300, "coming" to 500, "yes" to 1_500, "I" to 1_800, "am" to 2_000)
        val turns = listOf(turn(0, 1_200, 7), turn(1_300, 3_000, 3))

        val pieces = SegmentSplitter.split(segment, turns)

        assertEquals(listOf("Are you coming", "Yes I am"), pieces.map { it.text })
        assertEquals(listOf(7, 3), pieces.map { it.rawSpeaker })
        // The first piece keeps the line's start; the second starts at its first word.
        assertEquals(listOf(0L to 1_500L, 1_500L to 4_000L), pieces.map { it.startMs to it.endMs })
        assertEquals(listOf(" yes", " I", " am"), pieces[1].tokens.map { it.text })
    }

    @Test
    fun shortRunAtABoundary_isMergedIntoItsNeighbour() {
        // "so" (200 ms) falls in speaker 3's turn edge: jitter, not a reply.
        val segment = line("we" to 100, "agreed" to 400, "so" to 1_000, "then" to 1_200, "next" to 1_500)
        val turns = listOf(turn(0, 950, 7), turn(950, 1_150, 3), turn(1_150, 3_000, 7))

        val pieces = SegmentSplitter.split(segment, turns)

        assertEquals(1, pieces.size)
        assertEquals(7, pieces.single().rawSpeaker)
        assertEquals(segment.text, pieces.single().text)
    }

    @Test
    fun oneWordReplyLongerThanTheMinimum_isKept() {
        val segment = line("ready" to 100, "yes" to 1_000, "good" to 1_600)
        val turns = listOf(turn(0, 900, 7), turn(900, 1_500, 3), turn(1_500, 3_000, 7))

        val pieces = SegmentSplitter.split(segment, turns)

        assertEquals(listOf("Ready", "Yes", "Good"), pieces.map { it.text })
        assertEquals(listOf(7, 3, 7), pieces.map { it.rawSpeaker })
    }

    @Test
    fun splitHappensBetweenWords_neverInsideOne() {
        // "coming" is two tokens; its second piece falls in the next turn.
        val segment = SplittableSegment(
            id = 1,
            startMs = 0,
            endMs = 3_000,
            text = "coming now",
            tokens = listOf(TimedToken(" com", 100), TimedToken("ing", 1_400), TimedToken(" now", 1_600))
        )
        val turns = listOf(turn(0, 1_300, 7), turn(1_300, 3_000, 3))

        val pieces = SegmentSplitter.split(segment, turns)

        assertEquals(listOf("Coming", "Now"), pieces.map { it.text })
        assertEquals(listOf(7, 3), pieces.map { it.rawSpeaker })
    }

    @Test
    fun lineWithoutTokens_isLabelledWholeByOverlap() {
        val segment = SplittableSegment(id = 1, startMs = 0, endMs = 2_000, text = "older line", tokens = emptyList())
        val turns = listOf(turn(0, 500, 7), turn(500, 2_000, 3))

        val pieces = SegmentSplitter.split(segment, turns)

        assertEquals(1, pieces.size)
        assertEquals("older line", pieces.single().text)
        assertEquals(3, pieces.single().rawSpeaker)
    }

    @Test
    fun wordsBetweenTurns_takeANeighboursSpeaker() {
        // "um" at 5 s is more than 1 s from any turn.
        val segment = line("hello" to 100, "um" to 5_000, endMs = 6_000)
        val turns = listOf(turn(0, 1_000, 7))

        val pieces = SegmentSplitter.split(segment, turns)

        assertEquals(listOf(7), pieces.map { it.rawSpeaker })
    }
}
