package com.convoscribe.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoscribe.data.SessionEntity
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SessionStatus
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One row in the session list. [title] is whatever was given when
 * recording started (the recorder currently passes `"Recording #N"`
 * but it is free text on the entity). [subtitle] is a pre-formatted
 * one-liner that combines the date, the duration and a status
 * suffix; computed by [sessionSubtitle] so the unit test does not
 * touch the framework.
 *
 * [status] is propagated so the row could tint itself (out of scope
 * for TASK-20; the formatting covers everything the spec calls for
 * today).
 */
data class SessionRow(
    val id: Long,
    val title: String,
    val subtitle: String,
    val status: SessionStatus
)

/**
 * ViewModel for the landing screen. Reads the session list from
 * [repository] (already ordered newest first by the DAO) and maps it
 * to [SessionRow]s; also exposes [delete] so the long-press
 * confirmation dialog can trigger removal.
 *
 * The list is a `StateFlow` with `WhileSubscribed(5000)` so the
 * upstream Room observer stops collecting when the screen is off the
 * back stack for more than five seconds — the same convention the
 * other ViewModels follow.
 */
class HomeViewModel(
    private val repository: SessionRepository
) : ViewModel() {

    val sessions: StateFlow<List<SessionRow>> = repository.observeAllSessions()
        .map { rows -> rows.map { it.toRow() } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /**
     * Delete [id] and its WAV. Called from the long-press
     * confirmation dialog. Idempotent — [SessionRepository.deleteSession]
     * is a no-op for unknown ids.
     */
    fun delete(id: Long) {
        viewModelScope.launch {
            repository.deleteSession(id)
        }
    }
}

private fun SessionEntity.toRow(): SessionRow = SessionRow(
    id = id,
    title = title,
    subtitle = sessionSubtitle(
        createdAt = createdAt,
        durationMs = durationMs,
        status = status,
        language = language
    ),
    status = status
)

/**
 * Format the second line under each session row: `<date>, <time> ·
 * <duration>[ · <status>][ · <language>]`.
 *
 * Examples (with [ZoneOffset.UTC][java.time.ZoneOffset]):
 *  - `sessionSubtitle(epoch, 754_000, TRANSCRIBED, UTC, "en") == "1 Jan 1970, 00:00 · 12:34"`;
 *  - `sessionSubtitle(now, 0, RECORDING, UTC, "en") == "<date>, <time> · Recording…"`;
 *  - `sessionSubtitle(now, 90_000, DIARIZING, UTC, "en") == "<date>, <time> · 01:30 · Identifying speakers…"`;
 *  - `sessionSubtitle(epoch, 754_000, TRANSCRIBED, UTC, "fr") == "1 Jan 1970, 00:00 · 12:34 · FR"`.
 *
 * Pinned to [Locale.UK] so the day/month order and the abbreviation
 * are stable regardless of the user's device locale. The status
 * suffix is appended verbatim from the spec, including the
 * horizontal ellipsis character — it is part of the user-facing
 * string so we are not free to swap it for "...". The language
 * suffix is only shown when [language] is not the English default
 * (`"en"`); other languages get a two-letter code uppercase to keep
 * the subtitle concise.
 */
private val DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.UK)

fun sessionSubtitle(
    createdAt: Long,
    durationMs: Long,
    status: SessionStatus,
    language: String = "en",
    zone: ZoneId = ZoneId.systemDefault()
): String {
    val stamp = LocalDateTime.ofInstant(Instant.ofEpochMilli(createdAt), zone)
        .format(DATE_FORMAT)
    val duration = formatDuration(durationMs)
    val base = if (duration.isEmpty()) stamp else "$stamp · $duration"
    val statusSuffix = when (status) {
        SessionStatus.RECORDING -> "Recording…"
        SessionStatus.DIARIZING -> "Identifying speakers…"
        SessionStatus.DIARIZATION_FAILED -> "Speaker detection failed"
        SessionStatus.TRANSCRIBED, SessionStatus.DIARIZED -> ""
    }
    val withStatus = if (statusSuffix.isEmpty()) base else "$base · $statusSuffix"
    val languageSuffix = if (language == "en" || language.isEmpty()) "" else " · ${language.uppercase(Locale.ROOT)}"
    return withStatus + languageSuffix
}

/**
 * Format a duration in milliseconds as `mm:ss` under an hour, or
 * `h:mm:ss` past an hour. Returns the empty string for a
 * non-positive duration so [sessionSubtitle] can skip the segment
 * for an in-flight recording (the recorder stamps the duration on
 * `finishRecording`, which is called from `RecordingController.stop`).
 */
private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return ""
    val totalSeconds = durationMs / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
