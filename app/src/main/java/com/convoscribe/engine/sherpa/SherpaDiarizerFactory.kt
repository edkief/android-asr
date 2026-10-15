package com.convoscribe.engine.sherpa

import com.convoscribe.diarization.DiarizerFactory
import com.convoscribe.models.ModelCatalog
import com.convoscribe.models.ModelStore
import com.convoscribe.settings.SettingsRepository
import java.io.File

/**
 * [DiarizerFactory] for sherpa-onnx. Resolves the segmentation and
 * embedding `.onnx` files from [store] using [ModelCatalog.SEGMENTATION]
 * (`model.int8.onnx`) and [ModelCatalog.EMBEDDING]
 * (`wespeaker_en_voxceleb_resnet34_LM.onnx`) and forwards the
 * user's `numThreads` setting to the underlying diarizer.
 *
 * The factory refuses to build a [com.convoscribe.diarization.Diarizer]
 * if either model file is missing — that's the same user-facing
 * "Model not installed: <id>" message the transcriber factory uses,
 * so the Settings screen can show the same download hint for
 * missing diarization models.
 *
 * Every [create] returns a fresh instance: sherpa-onnx's
 * `OfflineSpeakerDiarization` retains the segmentation and
 * embedding models in native memory, so reusing one across
 * recordings would just leak that memory.
 */
class SherpaDiarizerFactory(
    private val store: ModelStore,
    private val settings: SettingsRepository
) : DiarizerFactory {

    override fun create(): com.convoscribe.diarization.Diarizer {
        val segmentation: File = store.fileFor(
            ModelCatalog.SEGMENTATION,
            ModelCatalog.SEGMENTATION.files.single().fileName
        )
        val embedding: File = store.fileFor(
            ModelCatalog.EMBEDDING,
            ModelCatalog.EMBEDDING.files.single().fileName
        )
        if (!segmentation.exists()) {
            throw IllegalStateException("Model not installed: ${ModelCatalog.SEGMENTATION.id}")
        }
        if (!embedding.exists()) {
            throw IllegalStateException("Model not installed: ${ModelCatalog.EMBEDDING.id}")
        }
        return SherpaDiarizer(
            segmentationModel = segmentation,
            embeddingModel = embedding,
            numThreads = settings.settings.value.numThreads
        )
    }
}
