package com.convoscribe.ui.home

import com.convoscribe.data.SessionStatus
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JUnit tests for [sessionSubtitle]. No Android, no Room; the
 * formatter is a top-level `fun` deliberately so this is possible.
 *
 * Acceptance criteria from the spec:
 *  - `sessionSubtitle(0L, 754_000, TRANSCRIBED, "en", UTC) == "1 Jan 1970, 00:00 · 12:34"`
 *    — epoch in UTC, [ZoneOffset.UTC], 754 000 ms = 12 min 34 s;
 *  - DIARIZING appends `" · Identifying speakers…"` to the same
 *    base;
 *  - RECORDING appends `" · Recording…"`;
 *  - DIARIZATION_FAILED appends `" · Speaker detection failed"`;
 *  - TRANSCRIBED and DIARIZED have no status suffix;
 *  - a non-English [language] ("fr") appends `" · FR"` after
 *    the status suffix; English leaves the subtitle unchanged.
 *
 * The named parameters (`createdAt = …`, `language = …`, …) are
 * used throughout so the test reads independently of the
 * signature order of [sessionSubtitle] — important because TASK-28
 * added a `language` argument between `status` and `zone`.
 */
class SessionSubtitleTest {

    @Test
    fun epoch_in_utc_renders_1Jan1970_withDuration() {
        assertEquals(
            "1 Jan 1970, 00:00 · 12:34",
            sessionSubtitle(
                createdAt = 0L,
                durationMs = 754_000L,
                status = SessionStatus.TRANSCRIBED,
                language = "en",
                zone = ZoneOffset.UTC
            )
        )
    }

    @Test
    fun diarizing_appendsIdentifyingSpeakersSuffix() {
        assertEquals(
            "1 Jan 1970, 00:00 · 12:34 · Identifying speakers…",
            sessionSubtitle(
                createdAt = 0L,
                durationMs = 754_000L,
                status = SessionStatus.DIARIZING,
                language = "en",
                zone = ZoneOffset.UTC
            )
        )
    }

    @Test
    fun recording_appendsRecordingSuffix() {
        assertEquals(
            "1 Jan 1970, 00:00 · 01:30 · Recording…",
            sessionSubtitle(
                createdAt = 0L,
                durationMs = 90_000L,
                status = SessionStatus.RECORDING,
                language = "en",
                zone = ZoneOffset.UTC
            )
        )
    }

    @Test
    fun diarizationFailed_appendsSpeakerDetectionFailedSuffix() {
        assertEquals(
            "1 Jan 1970, 00:00 · 01:30 · Speaker detection failed",
            sessionSubtitle(
                createdAt = 0L,
                durationMs = 90_000L,
                status = SessionStatus.DIARIZATION_FAILED,
                language = "en",
                zone = ZoneOffset.UTC
            )
        )
    }

    @Test
    fun diarized_hasNoStatusSuffix() {
        assertEquals(
            "1 Jan 1970, 00:00 · 12:34",
            sessionSubtitle(
                createdAt = 0L,
                durationMs = 754_000L,
                status = SessionStatus.DIARIZED,
                language = "en",
                zone = ZoneOffset.UTC
            )
        )
    }

    @Test
    fun zeroDuration_skipsTheDurationSlot() {
        // In-flight recording: no duration stamp yet.
        assertEquals(
            "1 Jan 1970, 00:00 · Recording…",
            sessionSubtitle(
                createdAt = 0L,
                durationMs = 0L,
                status = SessionStatus.RECORDING,
                language = "en",
                zone = ZoneOffset.UTC
            )
        )
    }

    @Test
    fun hourOrMoreDuration_rendersHoursMinutesSeconds() {
        // 3 727 s = 1 h 2 m 7 s.
        assertEquals(
            "1 Jan 1970, 00:00 · 1:02:07",
            sessionSubtitle(
                createdAt = 0L,
                durationMs = 3_727_000L,
                status = SessionStatus.TRANSCRIBED,
                language = "en",
                zone = ZoneOffset.UTC
            )
        )
    }

    @Test
    fun frenchSession_appendsUppercaseLanguageSuffix() {
        // TASK-28: a French session must end with " · FR"; the
        // suffix is absent for English (the default) and for an
        // empty/unknown language value.
        assertEquals(
            "1 Jan 1970, 00:00 · 12:34 · FR",
            sessionSubtitle(
                createdAt = 0L,
                durationMs = 754_000L,
                status = SessionStatus.TRANSCRIBED,
                language = "fr",
                zone = ZoneOffset.UTC
            )
        )
    }

    @Test
    fun englishSession_hasNoLanguageSuffix() {
        // The default for the [language] parameter is "en"; even
        // when it is passed explicitly the subtitle must not gain
        // a trailing " · EN".
        assertEquals(
            "1 Jan 1970, 00:00 · 12:34",
            sessionSubtitle(
                createdAt = 0L,
                durationMs = 754_000L,
                status = SessionStatus.TRANSCRIBED,
                language = "en",
                zone = ZoneOffset.UTC
            )
        )
    }

    @Test
    fun frenchSessionWithRecordingStatus_appendsFrAfterStatus() {
        // The status suffix comes before the language suffix; the
        // separator and order are part of the user-visible spec.
        assertEquals(
            "1 Jan 1970, 00:00 · 01:30 · Recording… · FR",
            sessionSubtitle(
                createdAt = 0L,
                durationMs = 90_000L,
                status = SessionStatus.RECORDING,
                language = "fr",
                zone = ZoneOffset.UTC
            )
        )
    }

    @Test
    fun emptyLanguage_doesNotAddLanguageSuffix() {
        // Empty language (defensive: should not happen in
        // production) is treated the same as English.
        assertEquals(
            "1 Jan 1970, 00:00 · 12:34",
            sessionSubtitle(
                createdAt = 0L,
                durationMs = 754_000L,
                status = SessionStatus.TRANSCRIBED,
                language = "",
                zone = ZoneOffset.UTC
            )
        )
    }
}
