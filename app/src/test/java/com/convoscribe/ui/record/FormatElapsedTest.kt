package com.convoscribe.ui.record

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the pure [formatElapsed] formatter exposed by the
 * recording screen. No Android types are touched: the function lives
 * as a top-level `fun` in [com.convoscribe.ui.record.RecordViewModel]
 * so it can be called from plain JUnit without spinning up the
 * framework.
 *
 * The formatter must match the spec's acceptance criteria exactly:
 *
 *  - `formatElapsed(0) == "00:00"` — zero pad minutes and seconds
 *    while the total is under an hour;
 *  - `formatElapsed(61_000) == "01:01"` — both fields correctly
 *    zero-padded past the minute boundary;
 *  - `formatElapsed(3_600_000) == "1:00:00"` — the hour field is
 *    *not* zero-padded once the total crosses an hour, minutes and
 *    seconds stay zero-padded.
 */
class FormatElapsedTest {

    @Test
    fun zero_isZeroPaddedMinutesAndSeconds() {
        assertEquals("00:00", formatElapsed(0L))
    }

    @Test
    fun oneMinuteOneSecond_isTwoDigitMinutesAndSeconds() {
        assertEquals("01:01", formatElapsed(61_000L))
    }

    @Test
    fun oneHour_isSingleDigitHoursAndZeroPaddedMinutesAndSeconds() {
        assertEquals("1:00:00", formatElapsed(3_600_000L))
    }
}
