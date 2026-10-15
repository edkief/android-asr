package com.convoscribe.asr

import java.io.Closeable

/**
 * Adapter between [EndpointSegmenter] and a concrete streaming
 * recognizer (currently `sherpa-onnx`'s [com.k2fsa.sherpa.onnx.OnlineRecognizer],
 * but the segmenter is engine-agnostic so a test fake can stand in
 * without loading the native library).
 *
 * The segmenter owns the timing and partial/final state machine;
 * the backend owns the audio pipeline and the partial text. The
 * split exists so that all logic the segmenter applies (drop
 * duplicate partials, decide when an endpoint is "blank",
 * close-and-reset) is unit-testable with a plain Kotlin fake.
 *
 * Lifecycle: one [RecognizerBackend] backs one
 * [EndpointSegmenter]. Call [acceptWaveform] once per chunk,
 * `decodeAvailable` afterwards (the segmenter will do this for
 * you), then [currentText] / [isEndpoint] to read state. After an
 * endpoint the segmenter calls [reset]; on [StreamingTranscriber.finish]
 * it calls [inputFinished] instead.
 */
interface RecognizerBackend : Closeable {

    /**
     * Push one chunk of mono 16 kHz float PCM in `[-1.0, 1.0]`
     * into the backend's internal stream. Must be followed by
     * [decodeAvailable] before [currentText] / [isEndpoint] are
     * re-read.
     */
    fun acceptWaveform(samples: FloatArray)

    /**
     * Drain the backend until it has nothing more to decode. The
     * segmenter uses `while (recognizer.isReady(stream))
     * recognizer.decode(stream)` over the sherpa backend; the test
     * fake treats this as a no-op.
     */
    fun decodeAvailable()

    /**
     * Best-so-far text for the current utterance, *without*
     * normalisation. The segmenter applies [TextNormalizer]
     * afterwards.
     */
    fun currentText(): String

    /**
     * Whether the backend's endpoint detector says the current
     * utterance is closed. The segmenter reads this immediately
     * after [decodeAvailable]; sherpa implements it via
     * `OnlineRecognizer.isEndpoint`.
     */
    fun isEndpoint(): Boolean

    /**
     * Drop the partial text and the endpoint state, ready for the
     * next utterance. Does **not** affect the start-of-utterance
     * book-keeping — that lives in the segmenter because the
     * engine doesn't know about totalSamples.
     */
    fun reset()

    /**
     * Tell the backend no more audio will arrive (called from
     * [StreamingTranscriber.finish]). The segmenter then re-runs
     * [decodeAvailable] and may emit one final [TranscriptEvent].
     */
    fun inputFinished()
}
