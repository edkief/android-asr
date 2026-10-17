package com.convoscribe.diarization

import com.convoscribe.data.SegmentEntity
import com.convoscribe.data.defaultSpeakerLabel

/**
 * Keeps the speaker names a user typed when a session is diarized
 * again (after continuing a recording, or "Diarize again").
 *
 * A re-run numbers speakers from scratch, so old index 0 may now be
 * index 1. Each old speaker with a custom name (anything other than
 * the default `"Speaker N"`) hands that name to the new speaker it
 * shares the most speech with, measured over the segments that had
 * an old label, weighted by segment duration. Each new speaker gets
 * at most one name and each name goes to at most one speaker; the
 * strongest overlaps are matched first.
 */
object SpeakerLabelCarryOver {

    /**
     * @param previousSegments the session's segments with the speaker
     *   indices from the previous diarization (`null` = unlabelled,
     *   which includes segments recorded since).
     * @param previousLabels old speaker index → label, as stored.
     * @param newAssignment segment id → new speaker index.
     * @return new speaker index → carried-over custom label.
     */
    fun carry(
        previousSegments: List<SegmentEntity>,
        previousLabels: Map<Int, String>,
        newAssignment: Map<Long, Int?>
    ): Map<Int, String> {
        val custom = previousLabels.filter { (index, label) -> label != defaultSpeakerLabel(index) }
        if (custom.isEmpty()) return emptyMap()

        // (newIndex, oldIndex) -> milliseconds of speech in common.
        val overlap = mutableMapOf<Pair<Int, Int>, Long>()
        for (segment in previousSegments) {
            val old = segment.speakerIndex ?: continue
            if (old !in custom) continue
            val new = newAssignment[segment.id] ?: continue
            val weight = (segment.endMs - segment.startMs).coerceAtLeast(1L)
            overlap.merge(new to old, weight, Long::plus)
        }

        val result = mutableMapOf<Int, String>()
        val usedOld = mutableSetOf<Int>()
        for ((pair, _) in overlap.entries.sortedByDescending { it.value }) {
            val (new, old) = pair
            if (new in result || old in usedOld) continue
            result[new] = custom.getValue(old)
            usedOld += old
        }
        return result
    }
}
