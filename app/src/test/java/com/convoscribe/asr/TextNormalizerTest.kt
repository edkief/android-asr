package com.convoscribe.asr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [TextNormalizer]. No Android dependencies, no
 * recognizer instance, no Robolectric — these just exercise the
 * rules described in the class KDoc.
 */
class TextNormalizerTest {

    @Test
    fun normalize_allUppercaseCollapsesWhitespaceAndFixesCapitalisation() {
        // The example from the task acceptance criteria: trim,
        // collapse internal whitespace, lowercase, then put the
        // leading capital and the standalone "I" back.
        assertEquals("Hello I am here", TextNormalizer.normalize("  HELLO   I AM HERE "))
    }

    @Test
    fun normalize_mixedCaseOnlyCapitalisesFirstLetter() {
        // The second example from the criteria: accents stay put,
        // no lowercase letter exists for "À" so we do not enter the
        // all-uppercase branch.
        assertEquals("Bonjour à tous", TextNormalizer.normalize("bonjour à tous"))
    }

    @Test
    fun normalize_emptyStaysEmpty() {
        // The third example: empty in, empty out, no exceptions.
        assertEquals("", TextNormalizer.normalize(""))
    }

    @Test
    fun normalize_alreadyMixedCaseIsUnchanged() {
        // The fourth example: the input is already in the shape we
        // want, so we do nothing.
        assertEquals("Already Mixed", TextNormalizer.normalize("Already Mixed"))
    }

    @Test
    fun normalize_whitespaceOnlyCollapsesToEmpty() {
        // Whitespace-only strings are equivalent to empty in our
        // trim/collapse rules.
        assertEquals("", TextNormalizer.normalize("   \t \n  "))
    }

    @Test
    fun normalize_allUppercaseSingleLetterStaysSingle() {
        // Edge case for the all-uppercase branch — a single "I"
        // must round-trip through lowercase + standalone-\bi\b
        // replacement without becoming a different character.
        assertEquals("I", TextNormalizer.normalize("I"))
    }

    @Test
    fun normalize_preservesStandaloneILowercaseInMixedCase() {
        // Mixed-case text (has lowercase letters) skips the
        // standalone-\bi\b substitution so we don't accidentally
        // upper-case the "i" inside a word like "inline".
        assertEquals("Inline items", TextNormalizer.normalize("inline items"))
    }

    @Test
    fun normalize_noLettersStillCollapsesWhitespace() {
        // Numbers and punctuation get the whitespace treatment but
        // are not title-cased — there's no "first letter".
        assertEquals("1 2 3!", TextNormalizer.normalize("  1   2   3!  "))
    }
}
