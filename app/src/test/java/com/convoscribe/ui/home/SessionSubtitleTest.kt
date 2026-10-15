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
 *  - `sessionSubtitle(0L, 754_000, TRANSCRIBED, UTC) == "1 Jan 1970, 00:00 · 12:34"`
 *    — epoch in UTC, [ZoneOffset.UTC], 754 000 ms = 12 min 34 s;
 *  - DIARIZING appends `" · Identifying speakers…"` to the same
 *    base;
 *  - RECORDING appends `" · Recording…"`;
 *  - DIARIZATION_FAILED appends `" · Speaker detection failed"`;
 *  - TRANSCRIBED and DIARIZED have no status suffix.
 */
class SessionSubtitleTest {

    @Test
    fun epoch_in_utc_renders_1Jan1970_withDuration() {
        assertEquals(
            "1 Jan 1970, 00:00 · 12:34",
            sessionSubtitle(0L, 754_000L, SessionStatus.TRANSCRIBED, ZoneOffset.UTC)
        )
    }

    @Test
    fun diarizing_appendsIdentifyingSpeakersSuffix() {
        assertEquals(
            "1 Jan 1970, 00:00 · 12:34 · Identifying speakers…",
            sessionSubtitle(0L, 754_000L, SessionStatus.DIARIZING, ZoneOffset.UTC)
        )
    }

    @Test
    fun recording_appendsRecordingSuffix() {
        assertEquals(
            "1 Jan 1970, 00:00 · 01:30 · Recording…",
            sessionSubtitle(0L, 90_000L, SessionStatus.RECORDING, ZoneOffset.UTC)
        )
    }

    @Test
    fun diarizationFailed_appendsSpeakerDetectionFailedSuffix() {
        assertEquals(
            "1 Jan 1970, 00:00 · 01:30 · Speaker detection failed",
            sessionSubtitle(0L, 90_000L, SessionStatus.DIARIZATION_FAILED, ZoneOffset.UTC)
        )
    }

    @Test
    fun diarized_hasNoStatusSuffix() {
        assertEquals(
            "1 Jan 1970, 00:00 · 12:34",
            sessionSubtitle(0L, 754_000L, SessionStatus.DIARIZED, ZoneOffset.UTC)
        )
    }

    @Test
    fun zeroDuration_skipsTheDurationSlot() {
        // In-flight recording: no duration stamp yet.
        assertEquals(
            "1 Jan 1970, 00:00 · Recording…",
            sessionSubtitle(0L, 0L, SessionStatus.RECORDING, ZoneOffset.UTC)
        )
    }

    @Test
    fun hourOrMoreDuration_rendersHoursMinutesSeconds() {
        // 3 727 s = 1 h 2 m 7 s.
        assertEquals(
            "1 Jan 1970, 00:00 · 1:02:07",
            sessionSubtitle(0L, 3_727_000L, SessionStatus.TRANSCRIBED, ZoneOffset.UTC)
        )
    }
}
