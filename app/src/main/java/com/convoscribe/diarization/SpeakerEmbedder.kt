package com.convoscribe.diarization

import java.io.Closeable

/**
 * Engine-agnostic façade that turns a mono 16 kHz float PCM
 * voice sample into a fixed-length embedding vector. The v2
 * "enroll this speaker" use case feeds a recorded sample
 * through [embed] and stores the result via
 * [com.convoscribe.data.EnrolledSpeakerStore.save]; the
 * diarization post-processing step reads the recorded turns'
 * embeddings off the same engine so cosine comparisons are
 * meaningful.
 *
 * The interface is [Closeable] because every implementation
 * today holds a native model in memory
 * ([com.convoscribe.engine.sherpa.SherpaSpeakerEmbedder]
 * wraps sherpa-onnx's `SpeakerEmbeddingExtractor`, which
 * keeps the `wespeaker_en_voxceleb_resnet34_LM.onnx` model
 * resident for its lifetime).
 *
 * @see SherpaSpeakerEmbedder
 * @see EnrollSpeakerUseCase
 */
interface SpeakerEmbedder : Closeable {

    /**
     * Compute the speaker embedding for [samples] (mono 16 kHz
     * float PCM in `[-1.0, 1.0]`, the same shape the recorder
     * writes). The returned vector has a fixed size determined
     * by the underlying model (`wespeaker_en_voxceleb_resnet34_LM`
     * yields 256-dim embeddings on sherpa-onnx 1.13.8); the
     * exact length is opaque to callers, who only need the
     * `FloatArray` to store or compare.
     *
     * Implementations may throw `IllegalStateException("…")` if
     * the audio is too short to extract a usable embedding
     * (sherpa-onnx requires roughly 3 s of speech-shaped input).
     */
    fun embed(samples: FloatArray): FloatArray
}

/**
 * Builds a fresh [SpeakerEmbedder] when an enrollment sample
 * needs to be embedded. The factory is what
 * [EnrollSpeakerUseCase] holds a reference to: the embedder
 * itself is short-lived (one per enrollment) because
 * sherpa-onnx retains the embedding model in native memory for
 * the lifetime of the `SpeakerEmbeddingExtractor` instance.
 */
fun interface SpeakerEmbedderFactory {
    fun create(): SpeakerEmbedder
}
