package com.convoscribe.llm

/**
 * Errors raised by [ChatClient] and friends. [httpCode] is `null`
 * when the failure happened before a response was received
 * (missing configuration, JSON parsing of an SSE error frame,
 * a thrown [java.io.IOException]); otherwise it is the status code
 * returned by the LLM endpoint.
 *
 * The message is the human-readable reason a user can be shown —
 * for HTTP failures it starts with `HTTP <code> from <url>: `
 * followed by at most 300 characters of the response body so long
 * stack traces from an upstream gateway do not leak into the UI;
 * for network failures it names the URL and the underlying
 * exception chain (see [describeFailure]).
 */
class LlmException(
    message: String,
    val httpCode: Int? = null,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * One-line description of [t] for the UI: the exception class and
 * message, followed by up to two causes, e.g.
 * `"UnknownHostException: Unable to resolve host "x"; caused by
 * GaiException: android_getaddrinfo failed: EAI_NODATA"`. Exceptions
 * such as `NetworkOnMainThreadException` carry no message, so the
 * class name is always included.
 */
fun describeFailure(t: Throwable): String {
    val parts = mutableListOf<String>()
    var current: Throwable? = t
    while (current != null && parts.size < 3) {
        val c: Throwable = current
        val message = c.message?.takeIf { it.isNotBlank() }
        val part = if (message != null) "${c.javaClass.simpleName}: $message" else c.javaClass.simpleName
        if (parts.lastOrNull() != part) parts += part
        current = c.cause?.takeIf { it !== c }
    }
    return parts.joinToString("; caused by ")
}

/**
 * Message a screen shows for a failed LLM call. [LlmException]
 * messages are already written for the user; anything else falls
 * back to [describeFailure] so the UI never shows a bare class name
 * or nothing at all.
 */
fun Throwable.toUserMessage(): String =
    if (this is LlmException) message ?: "HTTP ${httpCode ?: "?"}" else describeFailure(this)
