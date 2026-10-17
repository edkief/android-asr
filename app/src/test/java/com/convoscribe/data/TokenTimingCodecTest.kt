package com.convoscribe.data

import com.convoscribe.asr.TimedToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for [TokenTimingCodec]. */
class TokenTimingCodecTest {

    @Test
    fun roundTrip_keepsTextAndTimes_includingSpecialCharacters() {
        val tokens = listOf(
            TimedToken(" hello", 0),
            TimedToken("\u2581wor", 420),
            TimedToken("tab\there", 900),
            TimedToken("new\nline \\ slash", 1_250)
        )
        assertEquals(tokens, TokenTimingCodec.decode(TokenTimingCodec.encode(tokens)))
    }

    @Test
    fun emptyList_encodesToNull_andNullDecodesToEmpty() {
        assertNull(TokenTimingCodec.encode(emptyList()))
        assertTrue(TokenTimingCodec.decode(null).isEmpty())
    }

    @Test
    fun corruptEntries_areSkipped() {
        assertEquals(listOf(TimedToken(" ok", 5)), TokenTimingCodec.decode("garbage\nx\t1\n5\t ok"))
    }
}
