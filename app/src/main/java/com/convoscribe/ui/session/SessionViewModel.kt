package com.convoscribe.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SessionStatus
import com.convoscribe.data.SpeakerEntity
import com.convoscribe.data.TranscriptFormatter
import com.convoscribe.data.TranscriptLine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

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
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModel(
    private val sessionId: Long,
    private val repository: SessionRepository
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
     * Plain-text export of the current transcript. Reads the
     * cached [StateFlow.value] directly: the share button fires on
     * the main thread and we do not want to launch a coroutine
     * just to format a string. The state is always at least the
     * `SessionUiState()` initial (with empty `lines`), so
     * `toText` produces an empty string — which is what the
     * chooser would receive anyway on a not-found session.
     */
    fun transcriptText(): String = TranscriptFormatter.toText(uiState.value.lines)
}
