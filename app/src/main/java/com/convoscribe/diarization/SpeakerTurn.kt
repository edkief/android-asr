package com.convoscribe.diarization

/**
 * A single speaker turn produced by the diarization engine: the
 * half-open time interval `[startMs, endMs)` belongs to [speaker].
 *
 * `speaker` is the raw engine label (sherpa-onnx returns the
 * cluster id assigned by its `FastClusteringConfig`; it is *not*
 * the user-facing speaker number that the transcript renders).
 * Renumbering raw ids to user-facing `0, 1, 2, …` in the order
 * they first appear in the segment list is the job of
 * [SpeakerAssigner].
 */
data class SpeakerTurn(
    val startMs: Long,
    val endMs: Long,
    val speaker: Int
)
