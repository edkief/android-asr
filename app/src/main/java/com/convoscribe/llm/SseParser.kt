package com.convoscribe.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Stateless parser for one SSE line at a time. The chat client reads
 * the response body line by line with `okhttp3.ResponseBody.source()` and
 * feeds each non-null line through [parse]. The state machine
 * returns one of three outcomes:
 *
 *  - [Line.Delta] carrying the `choices[0].delta.content` string from
 *    a `data:` frame whose JSON object has a non-null, non-empty
 *    `content` field;
 *  - [Line.Done] for the well-known `data: [DONE]` sentinel;
 *  - [Line.Ignore] for blanks, SSE comments (lines starting with
 *    `:`), event/field lines without a `data:` payload, or
 *    `data:` payloads whose JSON has no usable content chunk.
 *
 * If the JSON object on a `data:` line carries an `error` key the
 * parser raises [LlmException] with `error.message` (or the raw JSON
 * when `message` is missing). That is how the OpenAI streaming
 * protocol surfaces upstream errors.
 */
object SseParser {
    /**
     * Outcome of parsing a single SSE line. The chat client collects
     * [Delta] values into the token stream and stops collecting on
     * the first [Done]; [Ignore] is a no-op.
     */
    sealed interface Line {
        data class Delta(val text: String) : Line
        data object Done : Line
        data object Ignore : Line
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parse one line from a `text/event-stream` body. The line
     * must be the raw UTF-8 row as returned by `BufferedSource.readUtf8Line()`,
     * without the trailing `\n` and without the implicit blank line
     * separator between events.
     *
     * Rules (see class docs):
     *
     *  - blank or starting with `:` → [Line.Ignore];
     *  - `data: [DONE]` → [Line.Done];
     *  - `data: <json>` → parse, look at `choices[0].delta.content`,
     *    throw [LlmException] if the JSON has an `error` key,
     *    return [Line.Delta] when the chunk has content, else
     *    [Line.Ignore];
     *  - anything else (e.g. `event:`, `id:`, `retry:`) →
     *    [Line.Ignore].
     */
    fun parse(line: String): Line {
        // Blank line (event separator) or SSE comment.
        if (line.isEmpty() || line.startsWith(":")) return Line.Ignore

        // We only act on `data:` frames; everything else is
        // metadata we do not care about.
        if (!line.startsWith("data:")) return Line.Ignore

        val payload = line.substring("data:".length).trimStart()
        if (payload == "[DONE]") return Line.Done

        val obj: JsonObject = try {
            json.parseToJsonElement(payload).jsonObject
        } catch (e: Exception) {
            // Malformed JSON on a data: frame: skip it rather than
            // crash the whole stream. Upstream sent garbage; we
            // treat it like an unknown line.
            return Line.Ignore
        }

        // Upstream error frame: {"error": {"message": "...", ...}}
        // or sometimes {"error": "string"}.
        val errorElement = obj["error"]
        if (errorElement != null) {
            val message = runCatching {
                errorElement.jsonObject["message"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
                ?: errorElement.toString()
            throw LlmException(message)
        }

        val choices = obj["choices"]?.jsonArray ?: return Line.Ignore
        val first = choices.firstOrNull()?.jsonObject ?: return Line.Ignore
        val delta = first["delta"]?.jsonObject ?: return Line.Ignore
        val content = delta["content"]?.jsonPrimitive?.contentOrNull
            ?: return Line.Ignore
        if (content.isEmpty()) return Line.Ignore
        return Line.Delta(content)
    }
}
