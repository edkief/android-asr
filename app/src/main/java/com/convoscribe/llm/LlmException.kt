package com.convoscribe.llm

/**
 * Errors raised by [ChatClient] and friends. [httpCode] is `null`
 * when the failure happened before a response was received
 * (missing configuration, JSON parsing of an SSE error frame,
 * a thrown [java.io.IOException]); otherwise it is the status code
 * returned by the LLM endpoint.
 *
 * The message is the human-readable reason a user can be shown —
 * for HTTP failures it starts with `HTTP <code>: ` followed by at
 * most 300 characters of the response body so long stack traces
 * from an upstream gateway do not leak into the UI.
 */
class LlmException(
    message: String,
    val httpCode: Int? = null,
    cause: Throwable? = null
) : Exception(message, cause)
