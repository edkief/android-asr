package com.convoscribe.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SessionStatus
import com.convoscribe.data.SpeakerEntity
import com.convoscribe.data.TranscriptFormatter
import com.convoscribe.data.TranscriptLine
import com.convoscribe.diarization.EnrollFromSessionUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Snapshot rendered by [SessionScreen]. Built by combining the
 * session row, the segment list and the speaker list; any of the
 * three sources changing triggers a new emission so renaming a
 * speaker in the DB re-renders the transcript labels without the
 * screen having to subscribe to each flow individually.
 *
 * @property title session title shown in the top bar.
 * @property status current [SessionStatus]; drives the
 *   "Identifying speakers…" / "Speaker detection failed" banner.
 * @property lines transcript lines already resolved against the
 *   current speaker labels, ordered by start time.
 * @property speakers per-session speaker list (1..5 entries
 *   usually); kept around so the screen can tint labels by index.
 * @property notFound `true` when the session id has no row. The
 *   screen renders a "Session not found" placeholder instead of
 *   the transcript tab.
 */
data class SessionUiState(
    val title: String = "",
    val status: SessionStatus? = null,
    val lines: List<TranscriptLine> = emptyList(),
    val speakers: List<SpeakerEntity> = emptyList(),
    val notFound: Boolean = false
)

/**
 * ViewModel for the session-detail screen. Exposes a single
 * [SessionUiState] `StateFlow` built by `combine`-ing
 * [SessionRepository.observeSession], [SessionRepository.observeSegments]
 * and [SessionRepository.observeSpeakers]. The three flows are
 * pulled together inside `flatMapLatest` so we only subscribe to
 * the segments/speakers streams for an id that actually has a
 * session row (unknown ids never start a child subscription).
 *
 * The StateFlow uses `WhileSubscribed(5000)` to match the rest of
 * the app — the upstream Room observers pause five seconds after
 * the screen leaves the back stack, but keep flowing while a
 * configuration change recomposes the composable.
 *
 * [transcriptText] is a non-suspending read of the latest cached
 * state; the share button calls it on the main thread because
 * building the share intent has to happen there.
 *
 * [snackbar] is a one-shot `Flow<String>` that the screen
 * collects in a `LaunchedEffect` to surface transient feedback
 * (the "remember this voice" enrollment either succeeds silently
 * or surfaces its error). Backed by a buffered [Channel] so a
 * message emitted while the screen is being recomposed is not
 * lost and never re-fires on rotation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModel(
    private val sessionId: Long,
    private val repository: SessionRepository,
    private val enrollFromSession: EnrollFromSessionUseCase? = null
) : ViewModel() {

    val uiState: StateFlow<SessionUiState> = repository.observeSession(sessionId)
        .flatMapLatest { session ->
            if (session == null) {
                flowOf(SessionUiState(notFound = true))
            } else {
                combine(
                    repository.observeSegments(sessionId),
                    repository.observeSpeakers(sessionId)
                ) { segments, speakers ->
                    SessionUiState(
                        title = session.title,
                        status = session.status,
                        lines = TranscriptFormatter.lines(segments, speakers),
                        speakers = speakers
                    )
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SessionUiState()
        )

    /**
     * Buffered channel of snackbar messages. Capacity `Channel.BUFFERED`
     * keeps the coroutine from suspending on emit (the screen may
     * be in the middle of a configuration change), and
     * [receiveAsFlow] guarantees each value is consumed exactly
     * once.
     */
    private val _snackbar = Channel<String>(Channel.BUFFERED)

    /** One-shot snackbar messages; the screen collects this in a [androidx.compose.runtime.LaunchedEffect]. */
    val snackbar: Flow<String> = _snackbar.receiveAsFlow()

    /**
     * Plain-text export of the current transcript. Reads the
     * cached [StateFlow.value] directly: the share button fires on
     * the main thread and we do not want to launch a coroutine
     * just to format a string. The state is always at least the
     * `SessionUiState()` initial (with empty `lines`), so
     * `toText` produces an empty string — which is what the
     * chooser would receive anyway on a not-found session.
     */
    fun transcriptText(): String = TranscriptFormatter.toText(uiState.value.lines)

    /**
     * Persist a new label for the speaker identified by
     * [index]. Delegates to [SessionRepository.renameSpeaker] on
     * [viewModelScope] so the screen can fire-and-forget from a
     * dialog callback. The repository trims the label and
     * ignores blank values, so the UI does not need to.
     * The speakers flow Room observes will re-emit, and the
     * `combine` block above will rebuild the transcript lines
     * with the new label automatically.
     */
    fun renameSpeaker(index: Int, label: String) {
        viewModelScope.launch {
            repository.renameSpeaker(sessionId = sessionId, speakerIndex = index, label = label)
        }
    }

    /**
     * Persist a new title for this session. Delegates to
     * [SessionRepository.renameSession] on [viewModelScope]. The
     * repository trims, ignores blanks and caps at
     * [SessionRepository.SESSION_TITLE_MAX_LEN]; the session
     * row's flow Room observes will re-emit so the top bar
     * title updates without any extra plumbing.
     */
    fun renameSession(title: String) {
        viewModelScope.launch {
            repository.renameSession(sessionId = sessionId, title = title)
        }
    }

    /**
     * Rename a speaker and, in the same call, enroll the
     * speaker's audio as a known voice so future recordings
     * label it automatically (TASK-34).
     *
     * The rename runs unconditionally — the screen's checkbox
     * gates whether this method is called in the first place —
     * and the use case handles the embedding + persistence. On
     * success, a positive snackbar message is emitted and the
     * transcript re-renders with the new label through the
     * speakers flow. On failure (typically the "not enough
     * speech from this speaker (need 3 s)" floor), the
     * exception's message is surfaced verbatim through the same
     * snackbar channel so the user can react to it without the
     * rename being undone (the rename still landed; the
     * enrollment was the failing step).
     *
     * No-op when [enrollFromSession] was not wired in (i.e. a
     * test that does not care about the snackbar / use case).
     */
    fun renameAndRemember(index: Int, name: String) {
        val useCase = enrollFromSession ?: return
        viewModelScope.launch {
            try {
                useCase.run(sessionId = sessionId, speakerIndex = index, name = name)
                _snackbar.trySend("Remembered \"${name.trim()}\" for future recordings")
            } catch (t: Throwable) {
                _snackbar.trySend(t.message ?: t.javaClass.simpleName)
            }
        }
    }
}
