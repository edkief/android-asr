package com.convoscribe.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TranscriptTruncator.fit].
 *
 * The interesting case is the acceptance-criterion shape: 10 000
 * characters of 100-character lines fitted to a 2 000-character
 * budget. The fitted string must
 *  - stay at or below 2 000 characters;
 *  - contain the literal `characters omitted`;
 *  - start with the first line of the input and end with the last
 *    line of the input, both intact (no line is cut mid-way);
 *  - keep a head and a tail each trimmed to a newline boundary.
 */
class TranscriptTruncatorTest {

    @Test
    fun fit_shortTranscriptReturnedUnchanged() {
        val short = "Hello, world.\n"
        // Identity matters here: when the input already fits the
        // call must not allocate a new String.
        assertSame(short, TranscriptTruncator.fit(short, 100))
        assertSame(
            "",
            TranscriptTruncator.fit("", 10)
        )
    }

    @Test
    fun fit_exactLengthIsReturnedUnchanged() {
        // The contract says "if length ≤ maxChars return it
        // unchanged" — equality on the boundary must hold.
        val text = "abc"
        assertSame(text, TranscriptTruncator.fit(text, text.length))
    }

    @Test
    fun fit_tenThousandCharsFittedToTwoThousandKeepsHeadAndTailAndStaysInBudget() {
        // 100 lines × 100 chars (99 content chars + trailing '\n')
        // = exactly 10 000 chars. The line prefix `L001:` … `L100:`
        // makes each line uniquely identifiable in the assertions.
        val transcript: String = buildString {
            for (i in 1..100) {
                val prefix = "L%03d:".format(i) // 5 chars
                val padding = "x".repeat(94) // 94 chars of fill
                append(prefix)
                append(padding)
                append('\n') // 1 char -> 100 chars per line
            }
        }
        assertEquals(10_000, transcript.length)

        val firstLine: String = transcript.substring(0, 100).trimEnd('\n')
        val lastLine: String = transcript.substring(99 * 100, 100 * 100).trimEnd('\n')

        val fitted: String = TranscriptTruncator.fit(transcript, 2_000)

        assertTrue(
            "fitted length must be ≤ maxChars (got ${fitted.length})",
            fitted.length <= 2_000
        )
        assertTrue(
            "fitted output must mention the omission marker (got: $fitted)",
            fitted.contains("characters omitted")
        )
        assertTrue(
            "fitted output must start with the first line (got: ${fitted.take(firstLine.length)})",
            fitted.startsWith(firstLine)
        )
        assertTrue(
            "fitted output must end with the last line (got: …${fitted.takeLast(lastLine.length)})",
            fitted.endsWith(lastLine)
        )

        // No line is cut mid-way: the head must end at a line
        // boundary (the char before the omission marker is NOT a
        // newline; the head has been trimmed back to exclude the
        // last '\n'), and the tail must start at a line boundary
        // (the char right after the omission marker is the first
        // char of a complete line, NOT a newline).
        val headEnd: Int = fitted.indexOf("\n[… ")
        val tailStart: Int = fitted.lastIndexOf("…]\n") + "…]\n".length
        assertTrue("expected to find the omission marker", headEnd >= 0)
        assertTrue(
            "head must not end with a newline (would mean a line was cut mid-way)",
            fitted[headEnd - 1] != '\n'
        )
        assertTrue(
            "tail must not start with a newline (would mean a line was cut mid-way)",
            fitted[tailStart] != '\n'
        )
    }

    @Test
    fun fit_omittedCountMatchesWhatWasDropped() {
        // 100 lines of "line-XX: " + 80 'x's + '\n' ≈ 9 200 chars,
        // comfortably above the 1 000-char budget so the truncator
        // is exercised end-to-end.
        val transcript: String = buildString {
            for (i in 1..100) {
                append("L%03d:".format(i))
                append("x".repeat(80))
                append('\n')
            }
        }
        assertTrue(transcript.length > 1_000)

        val fitted = TranscriptTruncator.fit(transcript, 1_000)
        // Reconstruct the marker mid-string. The marker is
        // `\n[… N characters omitted …]\n`; we look for the
        // markerStart at the position of the `[` (the leading
        // `\n` is the last char of the head) and the markerEnd at
        // the position of the space right before "characters".
        val markerStart = fitted.indexOf("[… ")
        val markerEnd = fitted.indexOf(" characters omitted …]", markerStart)
        assertTrue("expected to find the omission marker", markerStart >= 0 && markerEnd > markerStart)

        val declaredDropped: Int = fitted
            .substring(markerStart + "[… ".length, markerEnd)
            .toInt()
        // The head occupies every char before the leading '\n' of
        // the marker, so head.length = markerStart - 1. The tail
        // starts at the char right after the trailing '\n', so
        // tail.length = fitted.length - (markerEnd + 23).
        val keptHeadLen: Int = markerStart - 1
        val keptTailLen: Int = fitted.length - markerEnd - 23
        assertEquals(
            "omitted count must equal transcript length minus head minus tail",
            transcript.length - keptHeadLen - keptTailLen,
            declaredDropped
        )
    }

    @Test
    fun fit_singleLongLineKeepsTheSliceWhenNoNewlineIsPresent() {
        // A 500-char line with no newline at all. The head and tail
        // are whole-string slices in this degenerate case.
        val text = "a".repeat(500)
        val fitted = TranscriptTruncator.fit(text, 200)
        assertTrue(fitted.length <= 200)
        assertTrue(fitted.contains("characters omitted"))
        assertTrue(fitted.startsWith("a"))
        assertTrue(fitted.endsWith("a"))
    }

    @Test
    fun fit_neverExceedsMaxCharsAcrossManyBudgets() {
        val transcript = buildString {
            for (i in 1..50) {
                append("line-%02d: %s\n".format(i, "x".repeat(80)))
            }
        }

        for (budget in 100..2_000 step 100) {
            val fitted = TranscriptTruncator.fit(transcript, budget)
            assertTrue(
                "budget=$budget produced ${fitted.length} chars",
                fitted.length <= budget
            )
        }
    }
}
