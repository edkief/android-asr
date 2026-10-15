package com.convoscribe.llm

/**
 * Fits a long conversation transcript into a character budget while
 * keeping whole lines.
 *
 * The shape of a fitted string is:
 * ```
 * <first N whole lines>
 * [… K characters omitted …]
 * <last M whole lines>
 * ```
 * where the head budget is `maxChars / 2` and the tail budget is
 * `maxChars / 2 - 80`. Eighty characters is the room the omission
 * marker needs for a number up to five digits long (`[… 99999 characters omitted …]`
 * is 31 characters); 49 characters of slack remain for the actual
 * head + tail content, so the result is always ≤ `maxChars`.
 *
 * The cuts land on newline boundaries so no line in the original
 * transcript is cut mid-way: the head is trimmed back to its last
 * newline (exclusive) and the tail is cut forward from its first
 * newline (exclusive). When the candidate slice has no newline —
 * a single very long line — the whole slice is kept verbatim.
 */
object TranscriptTruncator {

    /**
     * The marker placed between the head and the tail. The leading
     * and trailing newlines are part of the marker; the head and
     * tail deliberately do not include a newline on the side facing
     * the marker so we never end up with `\n\n` next to the `…`.
     */
    private const val OMISSION_FORMAT: String = "\n[… %d characters omitted …]\n"

    /**
     * Return [transcript] verbatim if it already fits in [maxChars];
     * otherwise return a head + marker + tail version that:
     *
     *  - keeps the first `maxChars / 2` characters, trimmed back
     *    to the last `\n` inside (or kept verbatim when there is
     *    no newline);
     *  - keeps the last `maxChars / 2 - 80` characters, cut
     *    forward from the first `\n` inside (or kept verbatim
     *    when there is no newline);
     *  - joins the two with a single `\n[… N characters omitted …]\n`
     *    marker where `N` is the number of characters dropped.
     *
     * The result is always ≤ `maxChars` characters.
     */
    fun fit(transcript: String, maxChars: Int): String {
        if (transcript.length <= maxChars) return transcript

        val headBudget: Int = maxChars / 2
        // -80 leaves room for the omission marker; clamp at 0 so a
        // very small maxChars does not produce a negative start index
        // for the tail slice.
        val tailBudget: Int = maxOf(0, maxChars / 2 - 80)

        val rawHead: String = transcript.substring(0, headBudget)
        val lastNewlineInHead: Int = rawHead.lastIndexOf('\n')
        val head: String = if (lastNewlineInHead >= 0) {
            rawHead.substring(0, lastNewlineInHead)
        } else {
            rawHead
        }

        val rawTail: String = transcript.substring(transcript.length - tailBudget)
        val firstNewlineInTail: Int = rawTail.indexOf('\n')
        val tailWithNewline: String = if (firstNewlineInTail >= 0) {
            rawTail.substring(firstNewlineInTail + 1)
        } else {
            rawTail
        }
        // Drop a trailing newline so the fitted output ends with
        // the content of the last line rather than a stray '\n'
        // (the text-file convention is for every line — including
        // the last — to end with '\n'; we strip exactly one).
        val tail: String = if (tailWithNewline.endsWith('\n')) {
            tailWithNewline.substring(0, tailWithNewline.length - 1)
        } else {
            tailWithNewline
        }

        val omitted: Int = transcript.length - head.length - tail.length
        val marker: String = OMISSION_FORMAT.format(omitted)

        return head + marker + tail
    }
}
