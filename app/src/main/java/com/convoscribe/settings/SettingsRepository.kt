package com.convoscribe.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Read/write façade for [AppSettings]. The plain preferences hold
 * every field except the API key; that lives in [SecretStore] (an
 * encrypted file in production).
 *
 * All writes go through [update] which clamps values to the ranges
 * the engines and the LLM client can actually use, then mirrors the
 * result into [settings] so observers re-render.
 */
class SettingsRepository(
    private val prefs: SharedPreferences,
    private val secrets: SecretStore
) {
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /**
     * Apply [transform] to the current settings, validate every
     * field and persist the result. The returned value is the new
     * state. The validation rules are:
     *  - `maxTranscriptChars` clamped to 2000..400000
     *  - `numThreads` clamped to 1..8
     *  - `numSpeakers` must be -1 or in 1..10; 0 or anything < -1
     *    becomes -1
     *  - thresholds clamped to 0.05..0.95
     *  - `llmBaseUrl` trimmed and trailing `/` removed
     *  - `asrLanguage` must be "en" or "fr"; anything else keeps
     *    the previous value (no exception, no overwrite)
     *  - `asrEngine` must be "npu" or "cpu"; anything else keeps
     *    the previous value (no exception, no overwrite)
     */
    fun update(transform: (AppSettings) -> AppSettings) {
        val current = _settings.value
        val raw = transform(current)
        val next = raw.validatedAgainst(current)
        prefs.edit().apply {
            putString(KEY_LLM_BASE_URL, next.llmBaseUrl)
            putString(KEY_LLM_MODEL, next.llmModel)
            putInt(KEY_MAX_TRANSCRIPT_CHARS, next.maxTranscriptChars)
            putString(KEY_ASR_LANGUAGE, next.asrLanguage)
            putString(KEY_ASR_ENGINE, next.asrEngine)
            putInt(KEY_NUM_THREADS, next.numThreads)
            putInt(KEY_NUM_SPEAKERS, next.numSpeakers)
            putFloat(KEY_CLUSTER_THRESHOLD, next.clusterThreshold)
            putFloat(KEY_SPEAKER_MATCH_THRESHOLD, next.speakerMatchThreshold)
        }.apply()
        _settings.value = next
    }

    fun apiKey(): String = secrets.getApiKey()

    fun setApiKey(value: String) {
        secrets.setApiKey(value)
    }

    private fun load(): AppSettings {
        val rawLang = prefs.getString(KEY_ASR_LANGUAGE, null) ?: "en"
        val raw = AppSettings(
            llmBaseUrl = prefs.getString(KEY_LLM_BASE_URL, "") ?: "",
            llmModel = prefs.getString(KEY_LLM_MODEL, "") ?: "",
            maxTranscriptChars = prefs.getInt(KEY_MAX_TRANSCRIPT_CHARS, 24000),
            asrLanguage = rawLang,
            asrEngine = prefs.getString(KEY_ASR_ENGINE, null) ?: "npu",
            numThreads = prefs.getInt(KEY_NUM_THREADS, 4),
            numSpeakers = prefs.getInt(KEY_NUM_SPEAKERS, -1),
            clusterThreshold = prefs.getFloat(KEY_CLUSTER_THRESHOLD, 0.5f),
            speakerMatchThreshold = prefs.getFloat(KEY_SPEAKER_MATCH_THRESHOLD, 0.5f)
        )
        // On load we don't have a "previous" value; if the persisted
        // asrLanguage is not en/fr we fall back to "en"; if the
        // persisted asrEngine is not "npu" or "cpu" we fall back to
        // "npu" (the documented default). Every other numeric field is
        // normalised in-place via [normaliseNumeric].
        val lang = if (raw.asrLanguage == "en" || raw.asrLanguage == "fr") raw.asrLanguage else "en"
        val engine = if (raw.asrEngine == "npu" || raw.asrEngine == "cpu") raw.asrEngine else "npu"
        return raw.copy(asrLanguage = lang, asrEngine = engine).normaliseNumeric().copy(
            llmBaseUrl = raw.llmBaseUrl.trim().trimEnd('/')
        )
    }

    private companion object {
        const val KEY_LLM_BASE_URL = "llmBaseUrl"
        const val KEY_LLM_MODEL = "llmModel"
        const val KEY_MAX_TRANSCRIPT_CHARS = "maxTranscriptChars"
        const val KEY_ASR_LANGUAGE = "asrLanguage"
        const val KEY_ASR_ENGINE = "asrEngine"
        const val KEY_NUM_THREADS = "numThreads"
        const val KEY_NUM_SPEAKERS = "numSpeakers"
        const val KEY_CLUSTER_THRESHOLD = "clusterThreshold"
        const val KEY_SPEAKER_MATCH_THRESHOLD = "speakerMatchThreshold"
    }
}

/**
 * Validate [this] against the previous [previous] settings. Numeric
 * fields are clamped; `llmBaseUrl` is trimmed and stripped of its
 * trailing `/`; `asrLanguage` keeps the previous value when it is
 * not "en" or "fr"; `asrEngine` keeps the previous value when it is
 * not "npu" or "cpu".
 */
private fun AppSettings.validatedAgainst(previous: AppSettings): AppSettings {
    val lang = if (asrLanguage == "en" || asrLanguage == "fr") asrLanguage else previous.asrLanguage
    val engine = if (asrEngine == "npu" || asrEngine == "cpu") asrEngine else previous.asrEngine
    return this.normaliseNumeric().copy(
        llmBaseUrl = llmBaseUrl.trim().trimEnd('/'),
        asrLanguage = lang,
        asrEngine = engine
    )
}

/**
 * Apply the numeric coercions shared by load() and update().
 * Public for tests so they can double-check the rules without
 * spinning up a [SharedPreferences].
 */
internal fun AppSettings.normaliseNumeric(): AppSettings {
    val speakers = when {
        numSpeakers == -1 -> -1
        numSpeakers < -1 -> -1
        numSpeakers == 0 -> -1
        else -> numSpeakers.coerceIn(1, 10)
    }
    return copy(
        maxTranscriptChars = maxTranscriptChars.coerceIn(2000, 400_000),
        numThreads = numThreads.coerceIn(1, 8),
        numSpeakers = speakers,
        clusterThreshold = clusterThreshold.coerceIn(0.05f, 0.95f),
        speakerMatchThreshold = speakerMatchThreshold.coerceIn(0.05f, 0.95f)
    )
}
