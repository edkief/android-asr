package com.convoscribe.asr

/**
 * Factory the rest of the app uses to obtain a [StreamingTranscriber]
 * without depending on sherpa-onnx directly. Implementations are
 * language-aware — `"en"` and `"fr"` are wired today — and choose
 * the right ASR model bundle from [com.convoscribe.models.ModelCatalog].
 *
 * Exists so the recording screen (TASK-11/12) can ask for a fresh
 * transcriber per session without knowing how to build one.
 */
fun interface TranscriberFactory {

    /**
     * Build a ready-to-use streaming transcriber for [language].
     * Implementations may throw [IllegalStateException] if the ASR
     * model for that language isn't installed yet.
     */
    fun create(language: String): StreamingTranscriber
}
