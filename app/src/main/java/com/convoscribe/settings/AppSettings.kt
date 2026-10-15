package com.convoscribe.settings

/**
 * Persisted user preferences used by ASR, diarization and the LLM
 * client. [numSpeakers] == -1 means the diarizer should pick the
 * number of speakers automatically; any other value (1..10) pins
 * the count.
 *
 * [asrEngine] is the speech-recognition engine the user picked in
 * Settings: `"npu"` (Nemotron on the Hexagon NPU, default, English
 * only) or `"cpu"` (Kroko zipformer, English and French). An
 * unsupported value is rejected by [SettingsRepository.update] so a
 * bad write keeps the previous value.
 *
 * Field defaults match a fresh install on first launch; values are
 * written to a `SharedPreferences` file by [SettingsRepository] and
 * reloaded on construction.
 */
data class AppSettings(
    val llmBaseUrl: String = "",
    val llmModel: String = "",
    val maxTranscriptChars: Int = 24000,
    val asrLanguage: String = "en",
    val asrEngine: String = "npu",
    val numThreads: Int = 4,
    val numSpeakers: Int = -1,
    val clusterThreshold: Float = 0.5f,
    val speakerMatchThreshold: Float = 0.5f
)
