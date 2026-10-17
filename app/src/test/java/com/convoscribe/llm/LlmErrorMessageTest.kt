package com.convoscribe.llm

import java.io.IOException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-JVM tests for [describeFailure] and [toUserMessage]. */
class LlmErrorMessageTest {

    @Test
    fun describeFailure_messagelessExceptionStillNamesItsClass() {
        assertEquals("IllegalStateException", describeFailure(IllegalStateException()))
    }

    @Test
    fun describeFailure_includesCauses() {
        val e = IOException("unexpected end of stream", UnknownHostException("Unable to resolve host \"llm\""))
        assertEquals(
            "IOException: unexpected end of stream; caused by UnknownHostException: Unable to resolve host \"llm\"",
            describeFailure(e)
        )
    }

    @Test
    fun toUserMessage_keepsLlmExceptionMessageVerbatim() {
        assertEquals("HTTP 401 from u: x", LlmException("HTTP 401 from u: x", httpCode = 401).toUserMessage())
    }

    @Test
    fun toUserMessage_describesOtherFailures() {
        assertEquals("IllegalStateException: boom", IllegalStateException("boom").toUserMessage())
    }
}
