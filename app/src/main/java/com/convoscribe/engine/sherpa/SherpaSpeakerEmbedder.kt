package com.convoscribe.engine.sherpa

import com.convoscribe.diarization.SpeakerEmbedder
import com.convoscribe.models.ModelCatalog
import com.convoscribe.models.ModelStore
import com.convoscribe.settings.SettingsRepository
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File

/**
 * [SpeakerEmbedder] backed by sherpa-onnx's
 * `SpeakerEmbeddingExtractor`. Loads
 * `wespeaker_en_voxceleb_resnet34_LM.onnx` (the same file the
 * diarizer uses for clustering, so embeddings are comparable
 * across diarization and enrollment) into a native extractor
 * and computes one embedding per [embed] call.
 *
 * The extractor keeps the model in native memory for the
 * lifetime of this instance, so the class is `Closeable` and
 * the factory gives every enrollment its own. Reusing one
 * across enrollments would just leak that memory and the
 * sherpa-onnx API does not support parallel embedding streams
 * on a single extractor.
 *
 * @param embeddingModel absolute path to
 *   `wespeaker_en_voxceleb_resnet34_LM.onnx`.
 * @param numThreads forwarded to the extractor's
 *   `SpeakerEmbeddingExtractorConfig.numThreads`. Today only
 *   the CPU backend exists for this model.
 */
class SherpaSpeakerEmbedder(
    embeddingModel: File,
    numThreads: Int
) : SpeakerEmbedder {

    private val extractor: SpeakerEmbeddingExtractor = SpeakerEmbeddingExtractor(
        assetManager = null,
        config = SpeakerEmbeddingExtractorConfig(
            model = embeddingModel.absolutePath,
            numThreads = numThreads
        )
    )

    override fun embed(samples: FloatArray): FloatArray {
        // sherpa-onnx 1.13.8's API: build an `OnlineStream`,
        // hand it the PCM (16 kHz mono), mark the input as
        // finished, ask the extractor whether it is ready, and
        // pull the embedding. `isReady` returning false means
        // the audio is shorter than what the model needs (the
        // WeSpeaker ResNet34 LM model typically wants roughly
        // 3 s of speech); we surface that as an
        // `IllegalStateException` so the enrollment UI can
        // tell the user to try again with a longer sample.
        val stream: OnlineStream = extractor.createStream()
        try {
            stream.acceptWaveform(samples, AUDIO_SPEC_SR)
            stream.inputFinished()
            check(extractor.isReady(stream)) {
                "Audio too short for a voice sample"
            }
            return extractor.compute(stream)
        } finally {
            stream.release()
        }
    }

    override fun close() {
        extractor.release()
    }

    private companion object {
        // sherpa-onnx's API takes the sample rate as a separate
        // `Int` rather than reading it from a config — ConvoScribe
        // is mono 16 kHz end-to-end, so the value is pinned here
        // to keep the call site readable.
        const val AUDIO_SPEC_SR: Int = com.convoscribe.audio.AudioSpec.SAMPLE_RATE
    }
}

/**
 * [com.convoscribe.diarization.SpeakerEmbedderFactory] for the
 * sherpa-onnx engine. Resolves the embedding `.onnx` file from
 * [store] (the `wespeaker_en_voxceleb_resnet34_LM.onnx`
 * tracked by [ModelCatalog.EMBEDDING]), refuses to build an
 * embedder if the file is missing, and forwards the user's
 * `numThreads` setting to the underlying extractor.
 *
 * The factory throws the same `IllegalStateException("Model
 * not installed: …")` message the transcriber and diarizer
 * factories use, so the Settings screen shows one consistent
 * download hint for every model kind.
 *
 * Every [create] returns a fresh instance: the underlying
 * `SpeakerEmbeddingExtractor` retains the model in native
 * memory, so reusing one across enrollments would just leak
 * that memory.
 */
class SherpaSpeakerEmbedderFactory(
    private val store: ModelStore,
    private val settings: SettingsRepository
) : com.convoscribe.diarization.SpeakerEmbedderFactory {

    override fun create(): com.convoscribe.diarization.SpeakerEmbedder {
        val embedding: File = store.fileFor(
            ModelCatalog.EMBEDDING,
            ModelCatalog.EMBEDDING.files.single().fileName
        )
        if (!embedding.exists()) {
            throw IllegalStateException("Model not installed: ${ModelCatalog.EMBEDDING.id}")
        }
        return SherpaSpeakerEmbedder(
            embeddingModel = embedding,
            numThreads = settings.settings.value.numThreads
        )
    }
}
