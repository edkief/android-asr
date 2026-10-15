package com.convoscribe.engine.sherpa

import com.convoscribe.asr.EndpointSegmenter
import com.convoscribe.asr.StreamingTranscriber
import com.convoscribe.asr.TranscriberFactory
import com.convoscribe.models.ModelCatalog
import com.convoscribe.models.ModelStore
import com.convoscribe.settings.SettingsRepository

/**
 * [TranscriberFactory] for the sherpa-onnx engine. Picks the
 * matching ASR bundle from [ModelCatalog.asrFor] (today: English
 * or French zipformer), refuses to build a transcriber if the
 * files aren't on disk yet and forwards the user's
 * [SettingsRepository.settings] thread count to the recognizer.
 *
 * Every call to [create] returns a fresh recognizer + stream pair
 * — sherpa-onnx does not safely share them across sessions.
 */
class SherpaTranscriberFactory(
    private val store: ModelStore,
    private val settings: SettingsRepository
) : TranscriberFactory {

    override fun create(language: String): StreamingTranscriber {
        val bundle = ModelCatalog.asrFor(language)
        val asr = bundle.asr ?: error("ASR bundle ${bundle.id} has no AsrFiles")
        if (!store.isInstalled(bundle)) {
            throw IllegalStateException("Model not installed: ${bundle.id}")
        }
        val numThreads = settings.settings.value.numThreads
        val backend = SherpaRecognizerBackend(
            modelDir = store.bundleDir(bundle),
            asr = asr,
            numThreads = numThreads
        )
        return EndpointSegmenter(backend = backend)
    }
}
