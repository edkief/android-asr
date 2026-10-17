package com.convoscribe.data

import com.convoscribe.asr.TimedToken

/**
 * Text encoding of a transcript line's [TimedToken]s for
 * [SegmentEntity.tokens]: one `"<ms>\t<text>"` entry per line, with
 * `\`, tab and newline in the token text escaped. Compact, readable in
 * a database dump, and free of a serialization dependency.
 */
object TokenTimingCodec {

    /** Encode [tokens]; `null` for an empty list so the column stays NULL. */
    fun encode(tokens: List<TimedToken>): String? {
        if (tokens.isEmpty()) return null
        return tokens.joinToString("\n") { "${it.ms}\t${escape(it.text)}" }
    }

    /** Decode a value written by [encode]; `null`, blank or corrupt input gives an empty list. */
    fun decode(encoded: String?): List<TimedToken> {
        if (encoded.isNullOrEmpty()) return emptyList()
        return encoded.split('\n').mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@mapNotNull null
            val ms = line.substring(0, tab).toLongOrNull() ?: return@mapNotNull null
            TimedToken(unescape(line.substring(tab + 1)), ms)
        }
    }

    private fun escape(text: String): String = buildString {
        for (c in text) {
            when (c) {
                '\\' -> append("\\\\")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                else -> append(c)
            }
        }
    }

    private fun unescape(text: String): String = buildString {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (text[i + 1]) {
                    't' -> append('\t')
                    'n' -> append('\n')
                    else -> append(text[i + 1])
                }
                i += 2
            } else {
                append(c)
                i += 1
            }
        }
    }
}
