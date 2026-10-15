package com.convoscribe.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoscribe.llm.ChatClient
import com.convoscribe.llm.LlmException
import com.convoscribe.settings.AppSettings
import com.convoscribe.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Editable form state captured by the Settings screen. The screen
 * hands each `OutlinedTextField` a string so the user can edit
 * even invalid intermediate values (for example an empty "Max
 * transcript characters"); the ViewModel rejects the bad value
 * on [SettingsViewModel.save] by falling back to the previous
 * `AppSettings.maxTranscriptChars` when the parsed int is `null`.
 *
 * The numeric sliders and segmented buttons populate `numThreads`,
 * `numSpeakers` and `clusterThreshold` directly because their
 * controls only produce valid values.
 *
 * `baseUrl` and `apiKey` are strings even though the repository
 * persists them as String; an empty base URL is the documented
 * "LLM not configured" sentinel.
 */
data class SettingsForm(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val maxChars: String = "24000",
    val numThreads: Int = 4,
    val numSpeakers: Int = -1,
    val clusterThreshold: Float = 0.5f
) {
    /**
     * Build a [SettingsForm] from a persisted [AppSettings]. The
     * API key lives in [com.convoscribe.settings.SecretStore], not
     * on the [AppSettings] object, so it is read separately and
     * pre-filled here for editing.
     */
    companion object {
        fun from(settings: AppSettings, apiKey: String): SettingsForm = SettingsForm(
            baseUrl = settings.llmBaseUrl,
            apiKey = apiKey,
            model = settings.llmModel,
            maxChars = settings.maxTranscriptChars.toString(),
            numThreads = settings.numThreads,
            numSpeakers = settings.numSpeakers,
            clusterThreshold = settings.clusterThreshold
        )
    }
}

/**
 * Snapshot rendered by [SettingsScreen]. The form is mutable
 * (every keystroke produces a new one); the rest are status
 * fields the screen consumes verbatim.
 *
 * @property form in-flight edits the user has typed; the source
 *   of truth for `Save` and `Test connection`.
 * @property asrEngine `"npu"` or `"cpu"` — kept separate from
 *   [SettingsForm] because the segmented button writes
 *   immediately on click (no "Save" round-trip).
 * @property testing `true` while [SettingsViewModel.testConnection]
 *   is in flight; the screen greys out the button and shows a
 *   spinner / "Testing…" line.
 * @property testResult last connection-test outcome, formatted
 *   for the UI: starts with `Connected – N models: …` on success
 *   or `Failed: …` on failure. `null` before the first test.
 * @property availableModels model ids returned by the most recent
 *   successful `listModels` call; the screen renders them as
 *   `SuggestionChip`s that fill the Model field on tap.
 * @property saved `true` for one snapshot after [SettingsViewModel.save]
 *   completes; the screen reads it once (via `LaunchedEffect`) to
 *   pop the toast/snackbar and reset it.
 */
data class SettingsUiState(
    val form: SettingsForm = SettingsForm(),
    val asrEngine: String = "npu",
    val testing: Boolean = false,
    val testResult: String? = null,
    val availableModels: List<String> = emptyList(),
    val saved: Boolean = false
)

/**
 * ViewModel for the Settings screen. Owns the in-flight form so a
 * configuration change does not lose what the user typed.
 *
 *  - [update] replaces the form wholesale — the screen does that
 *    on every text field;
 *  - [save] writes the form through [SettingsRepository.update]
 *    (the validator in the repo clamps numbers, trims the URL and
 *    rejects unknown ASR engine / language values), persists the
 *    API key via [SettingsRepository.setApiKey], and sets
 *    [SettingsUiState.saved] so the screen can toast;
 *  - [testConnection] saves first so the user is sure the values
 *    they see are what is tested, then calls
 *    [ChatClient.listModels] and stores either `"Connected – N
 *    models: a, b, c"` (first 5 ids) or `"Failed: <message>"`.
 *
 * [setEngine] updates the ASR engine immediately: the segmented
 * button has its own save-on-click semantics, so the user does
 * not have to remember to tap Save before running Engine check.
 *
 * @param repo persisted settings; written by `save`, read on
 *   construction to seed the form.
 * @param chatClient LLM client whose `listModels` powers the
 *   connection test. Injected so tests can pass a fake.
 */
class SettingsViewModel(
    private val repo: SettingsRepository,
    private val chatClient: ChatClient
) : ViewModel() {

    private val _state: MutableStateFlow<SettingsUiState>
    val state: StateFlow<SettingsUiState>

    init {
        val current = repo.settings.value
        val form = SettingsForm.from(current, repo.apiKey())
        _state = MutableStateFlow(
            SettingsUiState(
                form = form,
                asrEngine = current.asrEngine
            )
        )
        state = _state.asStateFlow()
    }

    /**
     * Replace the in-memory form with [form]. The next save
     * applies this snapshot; keystrokes call this every time.
     */
    fun update(form: SettingsForm) {
        _state.value = _state.value.copy(form = form)
    }

    /**
     * Save the chosen ASR engine to the repo immediately (the
     * segmented button is autonomous) and persist the current
     * form via [SettingsRepository.update] + the encrypted
     * `setApiKey`. The numeric `maxChars` is parsed with
     * `toIntOrNull() ?: <current persisted value>` so an empty
     * or non-numeric field does not blank it out.
     *
     * Sets `saved = true` on the resulting state so the screen
     * can show a "Saved" toast/snackbar once (the screen is
     * responsible for resetting it).
     */
    fun save() {
        val form = _state.value.form
        val current = repo.settings.value
        val parsedChars = form.maxChars.toIntOrNull() ?: current.maxTranscriptChars

        repo.update { previous ->
            previous.copy(
                llmBaseUrl = form.baseUrl,
                llmModel = form.model,
                maxTranscriptChars = parsedChars,
                numThreads = form.numThreads,
                numSpeakers = form.numSpeakers,
                clusterThreshold = form.clusterThreshold
            )
        }
        if (_state.value.asrEngine != current.asrEngine) {
            repo.update { previous ->
                previous.copy(asrEngine = _state.value.asrEngine)
            }
        }
        repo.setApiKey(form.apiKey)

        // Re-seed the form from the freshly persisted settings so
        // the screen shows canonical values (e.g. trailing-slash
        // stripped, out-of-range maxChars clamped).
        val refreshed = repo.settings.value
        _state.value = _state.value.copy(
            form = SettingsForm.from(refreshed, refreshed.let { repo.apiKey() }),
            saved = true
        )
    }

    /**
     * Mark the engine as `"npu"` or `"cpu"` and persist it
     * immediately so the user can hit Engine check without
     * touching Save. The update is delegated to
     * [SettingsRepository.update] which only accepts the two
     * documented values.
     */
    fun setEngine(engine: String) {
        if (engine != "npu" && engine != "cpu") return
        _state.value = _state.value.copy(asrEngine = engine)
        repo.update { previous -> previous.copy(asrEngine = engine) }
    }

    /**
     * Persist the current form, then call [ChatClient.listModels].
     * Records the outcome as `"Connected – N models: a, b, c"`
     * (first five ids, comma-separated) or `"Failed: <message>"`
     * (the [LlmException] message verbatim) on the [SettingsUiState.testResult]
     * field. `availableModels` is populated only on success.
     *
     * The `saved` flag is not affected — connection-test is a
     * read.
     */
    fun testConnection() {
        if (_state.value.testing) return
        save()
        _state.value = _state.value.copy(testing = true, testResult = null)
        viewModelScope.launch {
            val result = runCatching { chatClient.listModels() }
            _state.value = _state.value.copy(
                testing = false,
                testResult = result.fold(
                    onSuccess = { models ->
                        val first = models.take(5).joinToString(", ")
                        "Connected – ${models.size} models: $first"
                    },
                    onFailure = { failure ->
                        val message = when (failure) {
                            is LlmException ->
                                failure.message
                                    ?: "HTTP ${failure.httpCode ?: "?"}"
                            else ->
                                failure.message
                                    ?: failure.javaClass.simpleName
                        }
                        "Failed: $message"
                    }
                ),
                availableModels = result.getOrDefault(emptyList())
            )
        }
    }

    /**
     * Reset `saved` after the screen has shown its confirmation.
     * Called from the screen's `LaunchedEffect(true)` once.
     */
    fun acknowledgeSaved() {
        if (_state.value.saved) {
            _state.value = _state.value.copy(saved = false)
        }
    }
}
