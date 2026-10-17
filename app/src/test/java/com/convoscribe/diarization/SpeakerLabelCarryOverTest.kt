package com.convoscribe.diarization

import com.convoscribe.data.SegmentEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-JVM tests for [SpeakerLabelCarryOver]. */
class SpeakerLabelCarryOverTest {

    private fun seg(id: Long, startMs: Long, endMs: Long, speaker: Int?) =
        SegmentEntity(id = id, sessionId = 1L, startMs = startMs, endMs = endMs, text = "t$id", speakerIndex = speaker)

    @Test
    fun customNameFollowsItsSpeakerWhenIndicesSwap() {
        val previous = listOf(seg(1, 0, 1_000, 0), seg(2, 1_000, 3_000, 1))
        val labels = mapOf(0 to "Alice", 1 to "Bob")
        // The re-run numbers them the other way round.
        val carried = SpeakerLabelCarryOver.carry(previous, labels, mapOf(1L to 1, 2L to 0))
        assertEquals(mapOf(1 to "Alice", 0 to "Bob"), carried)
    }

    @Test
    fun defaultLabelsAreNotCarried() {
        val previous = listOf(seg(1, 0, 1_000, 0), seg(2, 1_000, 2_000, 1))
        val labels = mapOf(0 to "Speaker 1", 1 to "Bob")
        val carried = SpeakerLabelCarryOver.carry(previous, labels, mapOf(1L to 0, 2L to 1))
        assertEquals(mapOf(1 to "Bob"), carried)
    }

    @Test
    fun newlyRecordedSegmentsDoNotAffectTheMapping() {
        // Segment 3 was recorded after the last diarization: no old label.
        val previous = listOf(seg(1, 0, 1_000, 0), seg(3, 5_000, 60_000, null))
        val carried = SpeakerLabelCarryOver.carry(previous, mapOf(0 to "Alice"), mapOf(1L to 0, 3L to 1))
        assertEquals(mapOf(0 to "Alice"), carried)
    }

    @Test
    fun strongestOverlapWinsAndEachNameIsUsedOnce() {
        // Old speaker 0 ("Alice") is split across new speakers 0 and 1;
        // most of her speech lands on new speaker 1.
        val previous = listOf(seg(1, 0, 1_000, 0), seg(2, 1_000, 5_000, 0))
        val carried = SpeakerLabelCarryOver.carry(previous, mapOf(0 to "Alice"), mapOf(1L to 0, 2L to 1))
        assertEquals(mapOf(1 to "Alice"), carried)
    }

    @Test
    fun twoOldSpeakersMergedIntoOneKeepTheStrongerName() {
        val previous = listOf(seg(1, 0, 4_000, 0), seg(2, 4_000, 5_000, 1))
        val labels = mapOf(0 to "Alice", 1 to "Bob")
        val carried = SpeakerLabelCarryOver.carry(previous, labels, mapOf(1L to 0, 2L to 0))
        assertEquals(mapOf(0 to "Alice"), carried)
    }
}
