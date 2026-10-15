package com.convoscribe.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [SseParser]. The parser is stateless so we
 * drive each line through it directly; no OkHttp, no Robolectric.
 * The cases below correspond 1:1 to the rules spelled out in
 * [SseParser]'s KDoc and to the acceptance criteria of TASK-23.
 */
class SseParserTest {

    @Test
    fun blankLineReturnsIgnore() {
        assertEquals(SseParser.Line.Ignore, SseParser.parse(""))
    }

    @Test
    fun commentLineReturnsIgnore() {
        // SSE comments start with ":" and are used as keep-alives
        // by some upstreams; we never act on them.
        assertEquals(SseParser.Line.Ignore, SseParser.parse(": keep-alive"))
        assertEquals(SseParser.Line.Ignore, SseParser.parse(":"))
    }

    @Test
    fun eventLineReturnsIgnore() {
        // The protocol carries `event:`, `id:`, `retry:` fields;
        // we only care about `data:`.
        assertEquals(SseParser.Line.Ignore, SseParser.parse("event: message"))
        assertEquals(SseParser.Line.Ignore, SseParser.parse("id: 42"))
        assertEquals(SseParser.Line.Ignore, SseParser.parse("retry: 1000"))
    }

    @Test
    fun doneSentinelReturnsDone() {
        // Exact wording, no surrounding whitespace; matches the
        // OpenAI streaming protocol's end-of-stream marker.
        assertEquals(SseParser.Line.Done, SseParser.parse("data: [DONE]"))
    }

    @Test
    fun deltaWithContentReturnsDeltaText() {
        val json = """{"choices":[{"delta":{"content":"Hel"}}]}"""
        val line = SseParser.parse("data: $json")
        assertTrue("expected Delta, was $line", line is SseParser.Line.Delta)
        assertEquals("Hel", (line as SseParser.Line.Delta).text)
    }

    @Test
    fun deltaWithNullContentReturnsIgnore() {
        // First chunk often carries only `role: "assistant"` with
        // no content yet. We must not emit anything for those.
        val json = """{"choices":[{"delta":{"role":"assistant"}}]}"""
        assertEquals(SseParser.Line.Ignore, SseParser.parse("data: $json"))
    }

    @Test
    fun deltaWithEmptyStringContentReturnsIgnore() {
        // Some servers send `content: ""` as a heartbeat. Treat
        // it the same as missing content so we don't emit empty
        // chunks to the UI.
        val json = """{"choices":[{"delta":{"content":""}}]}"""
        assertEquals(SseParser.Line.Ignore, SseParser.parse("data: $json"))
    }

    @Test
    fun deltaWithRoleOnlyOnFirstChunkReturnsIgnore() {
        // A realistic first-chunk JSON from OpenAI:
        //   {"choices":[{"delta":{"role":"assistant"},"index":0}]}
        // No `content` field — must be ignored, not turned into
        // a zero-length token.
        val json = """{"choices":[{"delta":{"role":"assistant"},"index":0}]}"""
        assertEquals(SseParser.Line.Ignore, SseParser.parse("data: $json"))
    }

    @Test
    fun malformedJsonOnDataLineReturnsIgnore() {
        // Garbage on a `data:` line should not blow up the stream;
        // we just skip it. Real servers rarely send this but a
        // buggy proxy might.
        assertEquals(SseParser.Line.Ignore, SseParser.parse("data: {not-json"))
    }

    @Test
    fun dataLineWithoutChoicesReturnsIgnore() {
        // Some servers (e.g. Azure) may send heartbeat frames
        // without a `choices` array. Drop them quietly.
        val json = """{"usage":{"prompt_tokens":1,"completion_tokens":1}}"""
        assertEquals(SseParser.Line.Ignore, SseParser.parse("data: $json"))
    }

    @Test
    fun errorObjectOnDataLineThrowsLlmException() {
        // Upstream error frames look like:
        //   {"error":{"message":"bad key","type":"..."}}
        // We surface `error.message` so the UI can show something
        // meaningful. The exception carries no httpCode because
        // the streaming protocol wraps it inside a 200 OK body.
        val json = """{"error":{"message":"bad key","type":"auth"}}"""
        val ex = assertThrows(LlmException::class.java) {
            SseParser.parse("data: $json")
        }
        assertEquals("bad key", ex.message)
    }

    @Test
    fun errorObjectWithMissingMessageFallsBackToRawJson() {
        // Defensive: if the server sends `{"error":"oops"}`
        // without a `message` field, surface whatever we got
        // rather than crash with an NPE deep in JSON parsing.
        val ex = assertThrows(LlmException::class.java) {
            SseParser.parse("data: {\"error\":\"oops\"}")
        }
        // The fallback includes the raw JSON element so the user
        // sees something even if the upstream is non-conformant.
        assertTrue(
            "expected fallback message, was '${ex.message}'",
            ex.message!!.contains("oops")
        )
    }

    @Test
    fun additionalUnknownFieldsAreIgnored() {
        // OpenAI's protocol regularly adds fields (`finish_reason`,
        // `index`, `logprobs`, ...) — the parser must accept them.
        val json =
            """
            {"id":"x","object":"chat.completion.chunk",
             "choices":[{"index":0,"delta":{"content":"lo"},"finish_reason":null}]}
            """.trimIndent().replace("\n", "")
        val line = SseParser.parse("data: $json")
        assertTrue(line is SseParser.Line.Delta)
        assertEquals("lo", (line as SseParser.Line.Delta).text)
    }
}
