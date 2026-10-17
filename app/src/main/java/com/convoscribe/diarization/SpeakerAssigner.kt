package com.convoscribe.diarization

/**
 * A single transcript segment reduced to the three fields the
 * speaker assignment needs: a stable [id] (the room primary key
 * from `segments.id`), and the half-open time interval
 * `[startMs, endMs)` it covers.
 *
 * Deliberately not coupled to the Room entity so the assigner
 * stays a pure function and can be unit-tested without spinning
 * up the database.
 */
data class SegmentSpan(
    val id: Long,
    val startMs: Long,
    val endMs: Long
)

/**
 * Output of [SpeakerAssigner.assign]:
 *
 *  * [speakerBySegment] maps every input segment [SegmentSpan.id]
 *    to its user-facing speaker number (`0, 1, 2, …`) or `null`
 *    when no speaker turn was close enough to claim it.
 *  * [speakerCount] is the number of distinct assigned speakers
 *    across the whole map. The caller uses this to allocate
 *    "Speaker 1 / 2 / 3" labels in the UI without scanning the
 *    map twice.
 *  * [rawToIndex] maps a raw engine speaker id (sherpa-onnx's
 *    cluster label) to the renumbered index (`0, 1, 2, …`) the
 *    assigner chose for it. Used by the post-diarization
 *    embedding step to slice per-speaker audio: turn `speaker =
 *    7` resolves to renumbered index `0` (assuming that was the
 *    first speaker the assigner saw), etc.
 */
data class Assignment(
    val speakerBySegment: Map<Long, Int?>,
    val speakerCount: Int,
    val rawToIndex: Map<Int, Int> = emptyMap()
)

/**
 * Maps speaker turns produced by the diarizer onto transcript
 * segments produced by the ASR. The rules are:
 *
 *  1. **Overlap wins.** For each segment, sum the milliseconds of
 *     overlap with each speaker's turns; the speaker with the
 *     largest total overlap wins. Ties go to the speaker whose
 *     earliest overlapping turn starts first (i.e. started
 *     speaking earlier).
 *  2. **Nearest turn fallback.** When no turn overlaps the
 *     segment at all, look for the turn whose time gap to the
 *     segment is the smallest. If that gap is ≤ 1000 ms the
 *     nearest turn's speaker is assigned; otherwise the segment
 *     stays `null` (we never guess across a longer silence).
 *  3. **Renumber in first-appearance order.** The raw engine
 *     speaker ids (sherpa-onnx's cluster labels) are remapped to
 *     `0, 1, 2, …` in the order the assigned speakers first
 *     appear when the segments are sorted by their `startMs`.
 *     Renumbered ids are stable for a given input: the same
 *     `(segments, turns)` always yields the same `Assignment`.
 *     Turns that don't overlap any segment do not influence the
 *     renumbering.
 *
 * Pure Kotlin — no I/O, no native calls — so the whole class
 * lives under `app/src/test/`.
 */
object SpeakerAssigner {

    /**
     * Maximum gap (ms) between a segment and a turn for the
     * nearest-turn fallback to claim the segment. Mirrors what the
     * PRD calls "within a second of the speaker" — anything
     * farther is silence we don't want to attribute to anyone.
     */
    internal const val NEAREST_GAP_MS = 1000L

    fun assign(segments: List<SegmentSpan>, turns: List<SpeakerTurn>): Assignment {
        if (segments.isEmpty()) return Assignment(emptyMap(), 0)

        // Step 1+2: raw (engine-id) speaker for every segment.
        // We process segments in input order so the result map
        // mirrors the caller's list shape; the renumber below
        // re-sorts to enforce first-appearance order.
        val rawBySegmentId = mutableMapOf<Long, Int?>()
        for (segment in segments) {
            rawBySegmentId[segment.id] = assignRaw(segment, turns)
        }

        // Step 3: renumber in first-appearance order (segments
        // sorted by startMs). Segments with `null` contribute
        // nothing — a silent stretch is not a new speaker.
        val remap = renumber(segments.sortedBy { it.startMs }.map { rawBySegmentId[it.id] })

        val speakerBySegment = segments.associate { seg ->
            val raw = rawBySegmentId[seg.id]
            seg.id to raw?.let { remap[it] }
        }
        return Assignment(speakerBySegment, remap.size, remap)
    }

    /**
     * Map raw engine speaker ids to `0, 1, 2, …` in first-appearance
     * order. [rawInTimeOrder] is the raw speaker of each transcript
     * line (or split piece) sorted by start time; `null`s are skipped.
     */
    fun renumber(rawInTimeOrder: List<Int?>): Map<Int, Int> {
        val remap = LinkedHashMap<Int, Int>()
        for (raw in rawInTimeOrder) {
            if (raw != null && raw !in remap) remap[raw] = remap.size
        }
        return remap
    }

    /**
     * Pick the raw engine-id speaker (or `null`) for one
     * segment, following the overlap / nearest-turn rules above.
     * Also used by [SegmentSplitter] for lines without token times.
     */
    internal fun assignRaw(segment: SegmentSpan, turns: List<SpeakerTurn>): Int? {
        if (turns.isEmpty()) return null

        // (1) total overlap per speaker + earliest overlapping
        // turn start per speaker (the tie-breaker).
        val overlapMs = HashMap<Int, Long>()
        val earliestStart = HashMap<Int, Long>()
        var best: Int? = null
        var bestOverlap = -1L
        var bestEarliest = Long.MAX_VALUE
        for (turn in turns) {
            val overlap = overlapMs(segment, turn)
            if (overlap <= 0) continue
            val speaker = turn.speaker
            val newTotal = (overlapMs[speaker] ?: 0L) + overlap
            overlapMs[speaker] = newTotal
            val prevEarliest = earliestStart[speaker]
            val turnEarliest = if (prevEarliest == null || turn.startMs < prevEarliest) {
                turn.startMs
            } else {
                prevEarliest
            }
            earliestStart[speaker] = turnEarliest
            // Compare against the running best; ties on overlap
            // are resolved by smaller earliest startMs (i.e. the
            // turn that began earlier wins).
            if (newTotal > bestOverlap ||
                (newTotal == bestOverlap && turnEarliest < bestEarliest)
            ) {
                best = speaker
                bestOverlap = newTotal
                bestEarliest = turnEarliest
            }
        }
        if (best != null) return best

        // (2) No overlaps — find the nearest turn. We sort by
        // (gap, turn.startMs) so the tie-breaker on equal gaps is
        // deterministic and prefers the earlier-spoken turn.
        var nearest: SpeakerTurn? = null
        var nearestGap = Long.MAX_VALUE
        for (turn in turns) {
            val gap = gapMs(segment, turn)
            if (gap < nearestGap ||
                (gap == nearestGap && turn.startMs < (nearest?.startMs ?: Long.MAX_VALUE))
            ) {
                nearest = turn
                nearestGap = gap
            }
        }
        return if (nearest != null && nearestGap <= NEAREST_GAP_MS) nearest.speaker else null
    }

    /**
     * Milliseconds of intersection of [segment] and [turn]. Two
     * half-open intervals `[a, b)` and `[c, d)` overlap on
     * `[max(a, c), min(b, d))`; we return `max(0, length)` so
     * non-overlapping inputs cleanly fall through to `0`.
     */
    private fun overlapMs(segment: SegmentSpan, turn: SpeakerTurn): Long {
        val start = maxOf(segment.startMs, turn.startMs)
        val end = minOf(segment.endMs, turn.endMs)
        return maxOf(0L, end - start)
    }

    /**
     * Gap (ms) between two half-open intervals. `0` when they
     * overlap or touch; otherwise the distance from the earlier
     * interval's end to the later interval's start.
     */
    internal fun gapMs(segment: SegmentSpan, turn: SpeakerTurn): Long {
        if (segment.endMs <= turn.startMs) return turn.startMs - segment.endMs
        if (turn.endMs <= segment.startMs) return segment.startMs - turn.endMs
        return 0L
    }
}
