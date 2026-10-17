package com.convoscribe.diarization

import com.convoscribe.asr.TextNormalizer
import com.convoscribe.asr.TimedToken

/**
 * A transcript line to split, reduced to what [SegmentSplitter] needs.
 *
 * @property tokens the line's tokens with absolute times; empty for
 *   lines recorded before token timings were kept.
 */
data class SplittableSegment(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val tokens: List<TimedToken>
)

/**
 * One part of a split line. [rawSpeaker] is the diarizer's cluster
 * id (renumbered later by the caller), or `null` if no turn is near.
 */
data class SplitPiece(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val tokens: List<TimedToken>,
    val rawSpeaker: Int?
)

/**
 * Splits a transcript line where the speaker changes, using the
 * line's token times and the diarizer's turns. Before this, a line
 * (cut by the ASR only after 0.8 s of silence) went wholesale to the
 * speaker with the most overlap, so in a quick exchange the other
 * speaker's words were mislabelled.
 *
 * Rules:
 *  1. Each word (a run of tokens starting at a word-start token) gets
 *     the speaker of the turn containing its first token, or of the
 *     nearest turn within [SpeakerAssigner.NEAREST_GAP_MS]. Words
 *     between turns take a neighbouring word's speaker.
 *  2. A run of words by one speaker shorter than [MIN_RUN_MS] is
 *     merged into the previous run (or the next, for the first one):
 *     too short to be a real turn, it is jitter at a turn boundary.
 *     This matches the diarizer's own `minDurationOn` (0.3 s).
 *  3. A line splits only between words, never inside one.
 *  4. A line that stays whole keeps its original text; split pieces
 *     rebuild theirs from their tokens.
 *
 * Lines without tokens are left whole and labelled by
 * [SpeakerAssigner.assignRaw], as before.
 *
 * Mirrored in `scripts/eval/eval_speakers.py` (`split_line`); change
 * both together.
 */
object SegmentSplitter {

    /** Shortest run of one speaker's words that survives smoothing (ms). */
    const val MIN_RUN_MS: Long = 300L

    fun split(segment: SplittableSegment, turns: List<SpeakerTurn>): List<SplitPiece> {
        val whole = {
            listOf(
                SplitPiece(
                    startMs = segment.startMs,
                    endMs = segment.endMs,
                    text = segment.text,
                    tokens = segment.tokens,
                    rawSpeaker = SpeakerAssigner.assignRaw(
                        SegmentSpan(segment.id, segment.startMs, segment.endMs),
                        turns
                    )
                )
            )
        }
        if (segment.tokens.isEmpty() || turns.isEmpty()) return whole()

        val words = words(segment.tokens)
        val speakers = words.map { speakerAt(it.first().ms, turns) }.toMutableList()
        fillGaps(speakers)
        smooth(words, speakers, segment.endMs)

        val runs = runs(speakers)
        if (runs.size == 1) return whole().map { it.copy(rawSpeaker = speakers.first()) }

        return runs.mapIndexed { r, run ->
            val runTokens = words.subList(run.first, run.last + 1).flatten()
            SplitPiece(
                startMs = if (r == 0) segment.startMs else runTokens.first().ms,
                endMs = if (r == runs.lastIndex) segment.endMs else words[runs[r + 1].first].first().ms,
                text = textOf(runTokens),
                tokens = runTokens,
                rawSpeaker = speakers[run.first]
            )
        }
    }

    /** Group tokens into words: a new word starts at each word-start token. */
    private fun words(tokens: List<TimedToken>): List<List<TimedToken>> {
        val words = mutableListOf<MutableList<TimedToken>>()
        for (token in tokens) {
            if (words.isEmpty() || token.isWordStart) words += mutableListOf(token) else words.last() += token
        }
        return words
    }

    /** The turn containing [ms], else the nearest within [SpeakerAssigner.NEAREST_GAP_MS]. */
    private fun speakerAt(ms: Long, turns: List<SpeakerTurn>): Int? {
        turns.firstOrNull { ms >= it.startMs && ms < it.endMs }?.let { return it.speaker }
        val point = SegmentSpan(0L, ms, ms)
        val nearest = turns.minWithOrNull(
            compareBy<SpeakerTurn> { SpeakerAssigner.gapMs(point, it) }.thenBy { it.startMs }
        ) ?: return null
        return if (SpeakerAssigner.gapMs(point, nearest) <= SpeakerAssigner.NEAREST_GAP_MS) nearest.speaker else null
    }

    /** Words with no nearby turn take the previous word's speaker (or the next, at the start). */
    private fun fillGaps(speakers: MutableList<Int?>) {
        for (i in 1 until speakers.size) {
            if (speakers[i] == null) speakers[i] = speakers[i - 1]
        }
        for (i in speakers.size - 2 downTo 0) {
            if (speakers[i] == null) speakers[i] = speakers[i + 1]
        }
    }

    /** Merge runs shorter than [MIN_RUN_MS] into a neighbour until none is left. */
    private fun smooth(words: List<List<TimedToken>>, speakers: MutableList<Int?>, segmentEndMs: Long) {
        while (true) {
            val runs = runs(speakers)
            if (runs.size < 2) return
            val short = runs.indexOfFirst { run ->
                val start = words[run.first].first().ms
                val end = if (run.last + 1 < words.size) words[run.last + 1].first().ms else segmentEndMs
                end - start < MIN_RUN_MS
            }
            if (short < 0) return
            val target = if (short > 0) speakers[runs[short - 1].first] else speakers[runs[short + 1].first]
            for (i in runs[short]) speakers[i] = target
        }
    }

    /** Consecutive index ranges of equal speakers. */
    private fun runs(speakers: List<Int?>): List<IntRange> {
        val runs = mutableListOf<IntRange>()
        var start = 0
        for (i in 1..speakers.size) {
            if (i == speakers.size || speakers[i] != speakers[start]) {
                runs += start until i
                start = i
            }
        }
        return runs
    }

    private fun textOf(tokens: List<TimedToken>): String =
        TextNormalizer.normalize(tokens.joinToString("") { it.text }.replace('▁', ' '))
}
