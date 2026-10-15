package com.convoscribe.engine.sherpa

import com.convoscribe.asr.EndpointSegmenter
import com.convoscribe.asr.StreamingTranscriber
import com.convoscribe.asr.TranscriberFactory
import com.convoscribe.models.ModelCatalog
import com.convoscribe.models.ModelStore
import com.convoscribe.settings.SettingsRepository

/**
 * [TranscriberFactory] for the sherpa-onnx engine. Picks the
 * matching ASR bundle from [ModelCatalog.asrFor] using the user's
 * selected language and engine, refuses to build a transcriber if
 * the files aren't on disk yet and forwards the user's
 * [SettingsRepository.settings] thread count to the recognizer.
 *
 * The NPU bundle is not yet wired to a real backend (TASK-16/17
 * own the QNN runtime path); when the user has selected the NPU
 * engine, [create] throws [IllegalStateException] so a missing
 * implementation cannot silently fall back to CPU. Callers that do
 * not want a crash must first check `bundle.id` (or just call
 * [ModelCatalog.asrFor] themselves) and reject npu up front.
 *
 * Every call to [create] returns a fresh recognizer + stream pair
 * — sherpa-onnx does not safely share them across sessions.
 */
class SherpaTranscriberFactory(
    private val store: ModelStore,
    private val settings: SettingsRepository
) : TranscriberFactory {

    override fun create(language: String): StreamingTranscriber {
        val engine = settings.settings.value.asrEngine
        val bundle = ModelCatalog.asrFor(language, engine)
        val effective = ModelCatalog.effectiveEngine(language, engine)
        if (effective == "npu") {
            throw IllegalStateException("NPU engine not available yet")
        }
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
