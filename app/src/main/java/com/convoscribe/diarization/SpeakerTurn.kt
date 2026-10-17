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
 *
 * [confidence] is how sure the clustering is that the turn belongs to
 * [speaker], in `(0, 1]`. sherpa-onnx returns a negative placeholder
 * when it did not compute one (a single cluster, for instance), so
 * use [hasConfidence] before comparing.
 */
data class SpeakerTurn(
    val startMs: Long,
    val endMs: Long,
    val speaker: Int,
    val confidence: Float = 1f
) {
    /** `true` when [confidence] is a real score rather than a placeholder. */
    val hasConfidence: Boolean get() = confidence > 0f && confidence <= 1f
}
