package com.convoscribe.asr

import java.util.Locale

/**
 * Normalises the raw text a streaming recognizer returns into the
 * shape the UI displays and the database stores.
 *
 * The rules are deliberately simple and were chosen to handle the
 * one quirk that survives every zipformer streaming model we
 * tested: when the recognizer is confident the user just shouted
 * `HELLO`, it returns `HELLO` instead of `Hello`. Capitalising
 * the start of an utterance and the standalone pronoun `i` is the
 * minimum the resulting text needs before it can sit in a
 * transcript.
 *
 * Rule order is:
 *  1. trim leading/trailing whitespace and collapse runs of
 *     internal whitespace to a single space;
 *  2. if the text contains at least one letter *and* no
 *     lower-case letter (`text.none { it.isLowerCase() }`) it is
 *     treated as all-uppercase. Lower-case it (using
 *     [Locale.ROOT] so the result is stable across device
 *     locales — `lowercase()` on a Turkish device would, for
 *     instance, leave an `I` untouched), then capitalise the
 *     first character (also [Locale.ROOT]) and replace every
 *     standalone `i` with `I` (`\bi\b` -> `I`). The pronoun
 *     substitution is skipped for mixed-case text to avoid
 *     touching words like `inline`;
 *  3. otherwise capitalise the first character only;
 *  4. the empty string stays empty.
 *
 * The recognizer feeds each partial one chunk at a time, so this
 * function is called on the hot path; it does not allocate beyond
 * the result string and the regex compiled once in the companion
 * object.
 */
object TextNormalizer {

    private val STANDALONE_I: Regex = Regex("\\bi\\b")

    /**
     * Apply the normalisation rules described in the class KDoc to
     * [raw]. The input may be any string the recognizer produced,
     * including the empty string.
     */
    fun normalize(raw: String): String {
        val trimmed = raw.trim().replace(WHITESPACE_RUN, " ")
        if (trimmed.isEmpty()) return ""

        val hasLetters = trimmed.any { it.isLetter() }
        if (!hasLetters) {
            // No letters (digits / punctuation only): leave as-is
            // apart from the whitespace collapse/trim above.
            return trimmed
        }

        val allUpper = trimmed.none { it.isLowerCase() }
        val cased: String = if (allUpper) {
            // lower-case (Locale.ROOT), then put the initial capital +
            // standalone "i" -> "I" back. We anchor on the ASCII
            // word-boundary because the source strings come from a
            // limited English/French vocabulary where accented
            // letters never form a standalone "i".
            STANDALONE_I.replace(trimmed.lowercase(Locale.ROOT)) { it.value.uppercase() }
                .replaceFirstChar { ch -> ch.titlecase(Locale.ROOT) }
        } else {
            trimmed.replaceFirstChar { ch -> ch.titlecase(Locale.ROOT) }
        }
        return cased
    }

    private val WHITESPACE_RUN: Regex = Regex("\\s+")
}
