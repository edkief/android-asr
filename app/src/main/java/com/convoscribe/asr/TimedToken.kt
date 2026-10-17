package com.convoscribe.asr

/**
 * One recognised token (a sentencepiece piece such as `" the"` or
 * `"ing"`) and when it was spoken.
 *
 * @property text the piece as sherpa-onnx returns it; a word start
 *   carries a leading space (or `▁`), see [isWordStart].
 * @property ms time of the token in milliseconds. Relative to the
 *   start of the utterance when it comes from a [RecognizerBackend];
 *   absolute within the recording once [EndpointSegmenter] and the
 *   recorder have added their offsets.
 */
data class TimedToken(val text: String, val ms: Long) {

    /** `true` when this token begins a new word. */
    val isWordStart: Boolean get() = text.startsWith(" ") || text.startsWith("▁")
}
