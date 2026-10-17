package com.convoscribe.ui.session

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoscribe.TAG
import com.convoscribe.data.ChatMessageEntity
import com.convoscribe.data.MessageKind
import com.convoscribe.data.MessageRole
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.TranscriptFormatter
import com.convoscribe.llm.ChatClient
import com.convoscribe.llm.LlmMessage
import com.convoscribe.llm.PromptBuilder
import com.convoscribe.llm.toUserMessage
import com.convoscribe.settings.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * UI snapshot for the session Ask tab. The screen renders the
 * persisted Q&A history in [messages] as chat bubbles; the in-
 * flight streamed answer is in [streaming] and is appended to the
 * tail of the list once the model finishes.
 *
 * @property messages full Q&A history for the session, oldest
 *   first; mirrored from the Room observer so opening the tab
 *   immediately shows past exchanges without an extra round trip.
 * @property streaming partial answer currently streaming from the
 *   LLM. Empty when nothing is running; the screen renders this
 *   as a "live" assistant bubble anchored to the bottom of the
 *   list.
 * @property running `true` while a streaming request is in flight;
 *   the screen disables the send button and the text field is
 *   read-only so the user does not fire two requests at once.
 * @property error human-readable failure message; cleared on the
 *   next successful question. The previous USER message is kept in
 *   [messages] when an error happens, so the user can hit "Retry"
 *   without re-typing their question.
 * @property configured `false` when either the LLM base URL or
 *   the model is blank — the screen swaps to a "Set up a language
 *   model endpoint in Settings" placeholder in that case.
 */
data class AskUiState(
    val messages: List<ChatMessageEntity> = emptyList(),
    val streaming: String = "",
    val running: Boolean = false,
    val error: String? = null,
    val configured: Boolean = true
)

/**
 * ViewModel for the Ask tab. Drives the multi-round "ask the LLM
 * about this transcript" chat. Mirrors the [SummaryViewModel]
 * shape (one StateFlow, one in-flight `Job`, one streaming
 * `catch`/save pattern) but tracks full chat history so each new
 * question sees the previous exchanges as `assistant`/`user`
 * context for the model.
 *
 * Behaviour contract (mirrored by the unit tests):
 *
 *  - [ask] ignores the request when the question is blank, or
 *    when a previous run is still in flight.
 *  - The USER message is saved **first**, so a crash mid-stream
 *    leaves the question visible to the user and the
 *    [retryLast] flow has something to retry against.
 *  - The previous QA messages (before this question) are mapped
 *    to `LlmMessage("user"/"assistant", content)` and forwarded
 *    as history through
 *    [PromptBuilder.question].
 *  - On success an ASSISTANT row is appended, mirroring the
 *    streamed text verbatim.
 *  - On any failure the USER row stays in the DB, no
 *    ASSISTANT row is saved, the stack trace is logged and
 *    `error` is set to [com.convoscribe.llm.toUserMessage].
 *  - [retryLast] resends the last USER message without saving a
 *    second USER row.
 *  - [clearHistory] deletes every QA message for the session.
 *
 * @param sessionId primary key of the session to ask questions
 *   about.
 * @param repository façade over the database; provides the
 *   transcript and the persisted chat messages.
 * @param chatClient LLM streaming client — fakes are accepted
 *   here so the unit test can drive deterministic token sequences.
 * @param settings persisted settings; the QA budget comes from
 *   `maxTranscriptChars`, and `configured` is derived from
 *   `llmBaseUrl` / `llmModel`.
 */
class AskViewModel(
    private val sessionId: Long,
    private val repository: SessionRepository,
    private val chatClient: ChatClient,
    private val settings: SettingsRepository
) : ViewModel() {

    private val _state: MutableStateFlow<AskUiState> =
        MutableStateFlow(initialState())
    val state: StateFlow<AskUiState> = _state.asStateFlow()

    /**
     * In-flight streaming job. Tracked so [retryLast] / future
     * cancellations can interrupt the streaming flow mid-answer.
     * `null` when nothing is running. The streaming flow's `catch`
     * block does the same bookkeeping on a failure,
     * so the field is always in sync with the UI.
     */
    private var job: Job? = null

    init {
        // Mirror the persisted QA rows into `state.messages` so
        // the user sees past exchanges immediately when the tab
        // opens. The Room observer re-emits whenever a new
        // USER / ASSISTANT row is inserted (i.e. after a
        // successful question, or after `clearHistory`), so we
        // never have to push the value ourselves.
        viewModelScope.launch {
            repository.observeMessages(sessionId, MessageKind.QA).collect { rows ->
                _state.value = _state.value.copy(messages = rows)
            }
        }
        // Mirror the configured flag from the repository. Settings
        // is a `StateFlow`, so any future edit (the user types a
        // URL in the Settings screen and pops back) flips the
        // placeholder off without a manual refresh.
        viewModelScope.launch {
            settings.settings.collect { s ->
                _state.value = _state.value.copy(
                    configured = s.llmBaseUrl.isNotBlank() && s.llmModel.isNotBlank()
                )
            }
        }
    }

    private fun initialState(): AskUiState {
        val s = settings.settings.value
        return AskUiState(
            configured = s.llmBaseUrl.isNotBlank() && s.llmModel.isNotBlank()
        )
    }

    /**
     * Drive a new question. No-op when the question is blank
     * (after trimming) or when a previous run is still in flight.
     * The USER message is persisted **first**, then the streaming
     * flow is collected. On completion the ASSISTANT row is
     * appended; on a failure the error is surfaced and the
     * USER row stays in [state.messages] so [retryLast] can
     * resend it.
     */
    fun ask(question: String) {
        val trimmed = question.trim()
        if (trimmed.isBlank()) return
        if (_state.value.running) return

        job = viewModelScope.launch {
            // Snapshot the history BEFORE we insert the new USER
            // row, so the prompt fed to the LLM is the previous
            // exchanges only — the new question is appended as the
            // trailing user turn by PromptBuilder.question itself.
            val history: List<LlmMessage> = _state.value.messages.mapNotNull { msg ->
                when (msg.role) {
                    MessageRole.USER -> LlmMessage(LlmMessage.ROLE_USER, msg.content)
                    MessageRole.ASSISTANT -> LlmMessage(LlmMessage.ROLE_ASSISTANT, msg.content)
                }
            }

            // Save the USER row first so the UI shows the
            // question bubble immediately and a crash mid-stream
            // leaves the question visible.
            repository.addMessage(
                sessionId = sessionId,
                kind = MessageKind.QA,
                role = MessageRole.USER,
                content = trimmed
            )

            val transcript = buildTranscript()
            val maxChars = settings.settings.value.maxTranscriptChars
            val messages = PromptBuilder.question(transcript, maxChars, history, trimmed)

            _state.value = _state.value.copy(
                streaming = "",
                running = true,
                error = null
            )

            val accumulated = StringBuilder()
            try {
                chatClient.stream(messages)
                    .onEach { token ->
                        accumulated.append(token)
                        _state.value = _state.value.copy(streaming = accumulated.toString())
                    }
                    .catch { failure ->
                        // Cancellation ("Stop") is not an error. Any
                        // other failure is shown, never rethrown: an
                        // exception escaping viewModelScope crashes the app.
                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                        Log.w(TAG, "ask: LLM request failed", failure)
                        _state.value = _state.value.copy(
                            running = false,
                            streaming = "",
                            error = failure.toUserMessage()
                        )
                    }
                    .collect()

                val full = accumulated.toString()
                // Persist the assistant answer only when the flow
                // completed normally and produced at least one
                // token. A cancelled or empty run leaves the USER
                // row alone so the user can retry.
                if (full.isNotEmpty()) {
                    repository.addMessage(
                        sessionId = sessionId,
                        kind = MessageKind.QA,
                        role = MessageRole.ASSISTANT,
                        content = full
                    )
                }
                _state.value = _state.value.copy(
                    running = false,
                    streaming = ""
                )
            } catch (_: kotlinx.coroutines.CancellationException) {
                _state.value = _state.value.copy(
                    running = false,
                    streaming = ""
                )
            }
        }
    }

    /**
     * Resend the last USER message without saving a second USER
     * row. No-op when:
     *  - a streaming call is already in flight (`running == true`);
     *  - there is no USER message in the persisted history (the
     *    user has never asked a question, or `clearHistory` has
     *    been called since).
     */
    fun retryLast() {
        if (_state.value.running) return
        val lastUser = _state.value.messages.lastOrNull { it.role == MessageRole.USER }
            ?: return
        // Clear any leftover error so the new attempt does not
        // show the old failure alongside the streaming answer.
        _state.value = _state.value.copy(error = null)
        sendWithoutSavingUser(lastUser.content)
    }

    /**
     * Internal helper: stream a question using the persisted
     * history but skip the `addMessage(USER)` step. Shares the
     * rest of the streaming pipeline with [ask] so both flows go
     * through the same `catch` / save / state-update path.
     */
    private fun sendWithoutSavingUser(question: String) {
        job = viewModelScope.launch {
            val history: List<LlmMessage> = _state.value.messages.mapNotNull { msg ->
                when (msg.role) {
                    MessageRole.USER -> LlmMessage(LlmMessage.ROLE_USER, msg.content)
                    MessageRole.ASSISTANT -> LlmMessage(LlmMessage.ROLE_ASSISTANT, msg.content)
                }
            }

            val transcript = buildTranscript()
            val maxChars = settings.settings.value.maxTranscriptChars
            val messages = PromptBuilder.question(transcript, maxChars, history, question)

            _state.value = _state.value.copy(
                streaming = "",
                running = true,
                error = null
            )

            val accumulated = StringBuilder()
            try {
                chatClient.stream(messages)
                    .onEach { token ->
                        accumulated.append(token)
                        _state.value = _state.value.copy(streaming = accumulated.toString())
                    }
                    .catch { failure ->
                        // Cancellation ("Stop") is not an error. Any
                        // other failure is shown, never rethrown: an
                        // exception escaping viewModelScope crashes the app.
                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                        Log.w(TAG, "ask: LLM request failed", failure)
                        _state.value = _state.value.copy(
                            running = false,
                            streaming = "",
                            error = failure.toUserMessage()
                        )
                    }
                    .collect()

                val full = accumulated.toString()
                if (full.isNotEmpty()) {
                    repository.addMessage(
                        sessionId = sessionId,
                        kind = MessageKind.QA,
                        role = MessageRole.ASSISTANT,
                        content = full
                    )
                }
                _state.value = _state.value.copy(
                    running = false,
                    streaming = ""
                )
            } catch (_: kotlinx.coroutines.CancellationException) {
                _state.value = _state.value.copy(
                    running = false,
                    streaming = ""
                )
            }
        }
    }

    /**
     * Cancel the in-flight question request. The underlying
     * OkHttp call in [OpenAiChatClient.stream] is cancelled
     * through the flow's `invokeOnCompletion` hook, so the
     * server stops producing tokens as soon as this returns. The
     * USER row (if any) is left untouched so the user can still
     * hit "Retry".
     */
    fun cancel() {
        job?.cancel()
        job = null
    }

    /**
     * Drop every QA message for this session. The persisted
     * SUMMARY row (if any) is unaffected — only `kind = QA`
     * rows are removed.
     */
    fun clearHistory() {
        viewModelScope.launch {
            repository.clearMessages(sessionId, MessageKind.QA)
        }
    }

    /**
     * Render the persisted segments + speakers as plain text.
     * Pulls both lists from the repository; runs on the
     * dispatcher [viewModelScope] picks (`Dispatchers.Main.immediate`
     * for tests with `setMain`).
     */
    private suspend fun buildTranscript(): String {
        val segments = repository.getSegments(sessionId)
        val speakers = repository.getSpeakers(sessionId)
        val lines = TranscriptFormatter.lines(segments, speakers)
        return TranscriptFormatter.toText(lines)
    }
}
