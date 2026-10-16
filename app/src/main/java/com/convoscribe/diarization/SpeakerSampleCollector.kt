package com.convoscribe.diarization

/**
 * Collects mono 16 kHz float PCM samples for each *renumbered*
 * diarized speaker out of a full recording, so the embedding
 * pass that runs after [SpeakerAssigner] has audio to feed the
 * [SpeakerEmbedder]. Pure Kotlin — the diarizer hands us the
 * turn list, this object carves out per-speaker windows,
 * concatenates them and bounds the total length.
 *
 * Three rules from the PRD govern the cut:
 *
 *  1. **Skip very short turns.** A turn shorter than
 *     `minTurnMs` (default 500 ms) carries too little speech
 *     to contribute a meaningful embedding; we drop it on the
 *     floor instead of polluting the buffer with silence or
 *     a half-phoneme clip.
 *  2. **Cap each speaker at `maxMs` total.** Even an
 *     enthusiastic talker may run several minutes — feeding
 *     the full stream into the embedding model adds latency
 *     and CPU without raising recall. The default 30 000 ms
 *     (= 480 000 samples) is the documented sweet spot from
 *     the model card.
 *  3. **Drop speakers under `MIN_COLLECTED_MS`.** A speaker
 *     who owns less than 2 000 ms of usable audio across all
 *     kept turns has nothing an embedding model can latch
 *     onto; including them would just add a low-confidence
 *     vector to the cosine pass. They get the default
 *     `"Speaker N"` label.
 *
 * The function is pure — no I/O, no native calls — and runs
 * on the caller's coroutine (the use case wraps it in
 * `Dispatchers.Default` already, so this stays plain
 * sequential code).
 *
 * @see com.convoscribe.diarization.DiarizeSessionUseCase
 */
object SpeakerSampleCollector {

    /**
     * Floor of embedded audio per speaker (ms). A speaker with
     * less than two seconds of kept turn-time is dropped from
     * the embedding map; the assignment step then falls back
     * to its default "Speaker N" label.
     */
    const val MIN_COLLECTED_MS: Long = 2_000L

    /**
     * Concatenate, per renumbered speaker index [Int], the
     * audio samples (mono 16 kHz float PCM, the same shape the
     * recorder writes) that fall inside any [SpeakerTurn]
     * assigned to that speaker. Turns are filtered by length
     * via [minTurnMs]; each speaker's buffer is capped at
     * [maxMs]. Speakers with less than `MIN_COLLECTED_MS` of
     * kept audio are removed from the result map.
     *
     * Indices and clipping:
     *  * [speakerMap] translates a turn's `raw` diarizer id to
     *    the `0..N` renumbered index (the same `raw → index`
     *    map produced by [SpeakerAssigner.Assignment.rawToIndex]).
     *    A `null` from the mapper (a turn whose speaker the
     *    assigner never claims) skips that turn.
     *  * The 16 samples-per-ms conversion assumes 16 kHz
     *    ([com.convoscribe.audio.AudioSpec.SAMPLE_RATE]).
     *    Out-of-bounds turn windows are clamped to the sample
     *    array (defensive: a future diarizer tweak could
     *    surface a turn whose `endMs` exceeds the recording
     *    length by a sample or two).
     *
     * @param samples mono 16 kHz float PCM recording
     * @param turns diarizer turns (each carries a raw speaker
     *   id, replaced via [speakerMap])
     * @param speakerMap `raw → renumbered index` for the
     *   assignment. Returns `null` to skip a turn.
     * @param maxMs total audio kept per speaker (post-trim).
     *   Default 30 000 ms.
     * @param minTurnMs turns shorter than this are skipped.
     *   Default 500 ms.
     * @return map of renumbered speaker index → concatenated
     *   samples (length ≤ `maxMs * SAMPLE_RATE / 1000`).
     *   Speakers with < `MIN_COLLECTED_MS` of collected audio
     *   are absent from the map.
     */
    fun collect(
        samples: FloatArray,
        turns: List<SpeakerTurn>,
        speakerMap: (Int) -> Int?,
        maxMs: Long = 30_000L,
        minTurnMs: Long = 500L
    ): Map<Int, FloatArray> {
        if (samples.isEmpty() || turns.isEmpty()) return emptyMap()

        val samplesPerMs = SAMPLE_RATE / 1000
        val maxSamples = (maxMs * samplesPerMs).toInt()
        val minTurnSamples = (minTurnMs * samplesPerMs).toInt()

        // Map of speaker → ArrayList<FloatArray> of kept windows;
        // we concatenate at the end so a single speaker that
        // owns several non-adjacent turns sees exactly the audio
        // they spoke, in the order they spoke it. We use a
        // [LinkedHashMap] so the result preserves the order the
        // speakers are first introduced in the turn list — the
        // embedding pass doesn't care about order, but tests
        // pin this property because it lets the caller
        // debug-print a stable speaker list.
        val buckets = LinkedHashMap<Int, ArrayList<FloatArray>>()
        // Tracks the running total per speaker so the cap is
        // applied across windows (e.g. two 15-s turns are
        // equivalent to one 30-s turn).
        val totals = HashMap<Int, Int>()

        for (turn in turns) {
            val speaker = speakerMap(turn.speaker) ?: continue
            // (1) Drop very short turns — half a second doesn't
            // contribute enough speech to be worth embedding.
            val turnLenMs = turn.endMs - turn.startMs
            if (turnLenMs < minTurnMs) continue

            // Convert the half-open turn window to sample
            // offsets. Clamp to the recording so an over-shoot
            // becomes a no-op instead of an OOB read.
            val startSample = (turn.startMs * samplesPerMs).toInt().coerceAtLeast(0)
            val turnEndSamples = (turn.endMs * samplesPerMs).toInt()
            val endSample = turnEndSamples.coerceAtMost(samples.size)
            if (endSample <= startSample) continue

            val windowSamples = endSample - startSample
            val alreadyCollected = totals[speaker] ?: 0
            val remainingBudget = maxSamples - alreadyCollected
            if (remainingBudget <= 0) continue
            // We want to *prefer* the suffix of the window
            // when there is not enough budget left — that
            // matches "30 000 ms of recent speech" and avoids
            // cutting the turn's final syllable half-off when
            // the speaker is mid-word at the budget edge.
            val copyStart: Int
            val copyEnd: Int
            if (windowSamples <= remainingBudget) {
                copyStart = startSample
                copyEnd = endSample
            } else {
                copyStart = endSample - remainingBudget
                copyEnd = endSample
            }

            val window = samples.copyOfRange(copyStart, copyEnd)
            val list = buckets.getOrPut(speaker) { ArrayList() }
            list.add(window)
            totals[speaker] = alreadyCollected + window.size
        }

        // (3) Flatten each bucket, drop speakers with too
        // little total audio. The MIN_COLLECTED_MS check is
        // post-concatenation, mirroring the documented rule.
        val minCollectedSamples = (MIN_COLLECTED_MS * samplesPerMs).toInt()
        val result = LinkedHashMap<Int, FloatArray>()
        for ((speaker, list) in buckets) {
            val total = totals[speaker] ?: 0
            if (total < minCollectedSamples) continue
            val flat = FloatArray(total)
            var offset = 0
            for (window in list) {
                window.copyInto(flat, destinationOffset = offset)
                offset += window.size
            }
            result[speaker] = flat
        }
        return result
    }

    /** Centralised so the spec's "16 samples per ms" rule lives in one place. */
    private const val SAMPLE_RATE: Int = 16_000
}
