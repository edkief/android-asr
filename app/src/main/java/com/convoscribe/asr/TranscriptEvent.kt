package com.convoscribe.asr

/**
 * One piece of streaming ASR output. Partial events carry the
 * best-so-far text for the current utterance and replace each other
 * (the UI may debounce or animate them). Final events carry text for
 * a closed utterance together with the start/end milliseconds
 * inside the recording; they never change once emitted.
 */
sealed interface TranscriptEvent {

    /**
     * Best-so-far text for the current utterance. The next event on
     * the same utterance will be either another [Partial] (with a
     * different text) or a [Final] (when the endpoint fires).
     */
    data class Partial(val text: String) : TranscriptEvent

    /**
     * Closed utterance ready to be persisted. [startMs] is
     * `samplesToMs(segmentStartSample)`, [endMs] is
     * `samplesToMs(totalSamples)` at the moment the endpoint fired
     * (or `finish()` was called).
     */
    data class Final(val text: String, val startMs: Long, val endMs: Long) : TranscriptEvent
}
