package com.convoscribe.enginecheck

import com.convoscribe.asr.StreamingTranscriber
import com.convoscribe.asr.TranscriberFactory
import com.convoscribe.asr.TranscriptEvent
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [EngineCheckRunner]. The factory and the
 * clock are fakes, the transcriber is a queue-driven stub so we
 * can pin the per-chunk wall-clock advance to predictable values
 * without loading sherpa-onnx.
 */
class EngineCheckRunnerTest {

    /**
     * Transcriber that yields a script of [TranscriptEvent] lists
     * per `accept` call and tracks whether [close] was invoked.
     * Mirrors `com.convoscribe.asr.StreamingTranscriber` closely
     * enough for the runner to consume it.
     */
    private class FakeTranscriber(
        private val acceptEvents: List<List<TranscriptEvent>>,
        private val finishEvents: List<TranscriptEvent> = emptyList()
    ) : StreamingTranscriber {
        var closed: Boolean = false
            private set
        var acceptCalls: Int = 0
            private set
        override fun accept(samples: FloatArray): List<TranscriptEvent> {
            val events = acceptEvents.getOrNull(acceptCalls) ?: emptyList()
            acceptCalls += 1
            return events
        }

        override fun finish(): List<TranscriptEvent> = finishEvents

        override fun close() {
            closed = true
        }
    }

    private class FakeFactory(
        private val transcriber: StreamingTranscriber,
        private val needsPrep: Boolean = false
    ) : TranscriberFactory {
        var createCalls: Int = 0
            private set
        override fun create(language: String): StreamingTranscriber {
            createCalls += 1
            return transcriber
        }

        override fun needsPreparation(language: String): Boolean = needsPrep
    }

    /**
     * Stepper clock: scripted values are returned first via
     * [scripted]; once empty, the underlying monotonic counter
     * keeps going so accidentally calling the clock twice in
     * succession always returns a non-decreasing value.
     */
    private class StepperClock : () -> Long {
        val scripted: ArrayDeque<Long> = ArrayDeque()
        private val counter = AtomicLong(0L)
        override fun invoke(): Long = scripted.removeFirstOrNull()
            ?: counter.incrementAndGet()
    }

    /**
     * Test 1 from the spec: with a fake clock where processing
     * 32 000 samples takes 400 ms, the result has audioMs 2 000,
     * RTF 0.2 and verdict starting with `OK`.
     */
    @Test
    fun run_fastRecogniser_rtfPointTwoAndVerdictOk() {
        // 32 000 samples = 2 000 ms (mono 16 kHz). The fake
        // transcriber ignores chunk size — the runner pushes
        // 1 600-sample slices but every "accept" produces one
        // event-list entry.
        val samples = FloatArray(32_000)
        val transcriber = FakeTranscriber(
            acceptEvents = listOf(
                emptyList(),
                listOf(TranscriptEvent.Final("Hello world", 1600L, 32000L))
            )
        )
        val factory = FakeFactory(transcriber, needsPrep = true)
        val clock = StepperClock()
        // Runner reads the clock four times in the happy path:
        //   1. create-start       → 0
        //   2. create-end         → 0   (initMs = 0)
        //   3. process-start      → 0
        //   4. process-end        → 400 (processMs = 400)
        clock.scripted.addLast(0L)
        clock.scripted.addLast(0L)
        clock.scripted.addLast(0L)
        clock.scripted.addLast(400L)
        val runner = EngineCheckRunner(
            factory = factory,
            loadSamples = { samples },
            clock = clock
        )

        val result = runner.run("npu")
        assertEquals("npu", result.engine)
        assertEquals(2_000L, result.audioMs)
        assertEquals(400L, result.processMs)
        assertEquals(0.2, result.realTimeFactor, 0.0001)
        assertEquals(0L, result.initMs)
        assertTrue(result.firstRun)
        assertEquals("Hello world", result.text)
        assertNull(result.error)
        val v = verdict(result)
        assertTrue("verdict was '$v'", v.startsWith("OK"))
    }

    /**
     * Test 2 from the spec: a factory that throws gives `error`
     * set and verdict `FAILED`; the transcriber is closed when
     * `accept` throws.
     */
    @Test
    fun run_acceptThrows_errorIsCaptured_andTranscriberIsClosed() {
        val transcriber = ThrowingTranscriber()
        val runner = EngineCheckRunner(
            factory = TranscriberFactory { transcriber },
            loadSamples = { FloatArray(32_000) },
            clock = StepperClock()
        )
        val result = runner.run("cpu")
        assertNotNull("error must be set when the recognizer throws", result.error)
        assertEquals("simulated accept failure", result.error)
        assertEquals("FAILED", verdict(result))
        assertTrue("transcriber.close() should run when accept throws", transcriber.closed)
        assertEquals(0L, result.processMs)
        assertEquals(0L, result.initMs)
    }

    /**
     * When the *factory itself* throws (model not installed, NPU
     * failed to start) the runner still produces a clean result
     * with `error` populated and the verdict `FAILED`.
     */
    @Test
    fun run_factoryThrowsBeforeCreate_errorIsCaptured() {
        val runner = EngineCheckRunner(
            factory = ThrowingFactory(IllegalStateException("Model not installed: asr-en-kroko-2025-08-06")),
            loadSamples = { FloatArray(32_000) },
            clock = StepperClock()
        )
        val result = runner.run("npu")
        assertEquals("Model not installed: asr-en-kroko-2025-08-06", result.error)
        assertEquals("FAILED", verdict(result))
    }

    /**
     * Test 3 from the spec: verdict boundaries — RTF 0.49 OK,
     * 0.5 MARGINAL, 1.0 TOO SLOW.
     */
    @Test
    fun verdict_boundaries() {
        val ok = EngineCheckResult("npu", 0L, false, 1000L, 490L, "")
        assertTrue(verdict(ok).startsWith("OK"))
        val marginal = EngineCheckResult("npu", 0L, false, 1000L, 500L, "")
        assertTrue(verdict(marginal).startsWith("MARGINAL"))
        val tooSlow = EngineCheckResult("npu", 0L, false, 1000L, 1000L, "")
        assertTrue(verdict(tooSlow).startsWith("TOO SLOW"))
    }

    /**
     * RTF with a zero-audio edge case: `audioMs == 0L` produces
     * `realTimeFactor == 0.0` rather than throwing. Verdict falls
     * into the OK bucket (0.0 < 0.5).
     */
    @Test
    fun run_zeroAudio_realTimeFactorIsZero() {
        val transcriber = FakeTranscriber(emptyList())
        val runner = EngineCheckRunner(
            factory = FakeFactory(transcriber),
            loadSamples = { FloatArray(0) },
            clock = StepperClock()
        )
        val result = runner.run("cpu")
        assertEquals(0L, result.audioMs)
        assertEquals(0.0, result.realTimeFactor, 0.0)
        assertTrue(verdict(result).startsWith("OK"))
        assertTrue("transcriber should still be closed on a clean run", transcriber.closed)
    }

    /**
     * Multiple chunks are fed one-by-one: a transcriber that
     * never emits during accept and only emits on finish must
     * still see every sample chunk.
     */
    @Test
    fun run_feedsAllChunksThenCallsFinish() {
        val transcriber = FakeTranscriber(
            acceptEvents = emptyList(),
            finishEvents = listOf(
                TranscriptEvent.Final("All done", 0L, 3_200L),
                TranscriptEvent.Partial("")
            )
        )
        val runner = EngineCheckRunner(
            factory = FakeFactory(transcriber),
            // 2 chunks of 1 600
            loadSamples = { FloatArray(3_200) },
            clock = StepperClock()
        )
        val result = runner.run("cpu")
        assertEquals(2, transcriber.acceptCalls)
        assertEquals("All done", result.text)
        assertEquals(200L, result.audioMs)
        assertTrue(transcriber.closed)
    }

    /**
     * Transcriber that throws on the first `accept` call and
     * tracks whether `close` was invoked.
     */
    private class ThrowingTranscriber : StreamingTranscriber {
        var closed: Boolean = false
            private set
        override fun accept(samples: FloatArray): List<TranscriptEvent> {
            throw RuntimeException("simulated accept failure")
        }

        override fun finish(): List<TranscriptEvent> = emptyList()

        override fun close() {
            closed = true
        }
    }

    /**
     * Factory that always throws from `create`. Mirrors a sherpa
     * factory that fails to build an NPU recognizer because the
     * model isn't installed.
     */
    private class ThrowingFactory(private val error: Throwable) : TranscriberFactory {
        override fun create(language: String): StreamingTranscriber {
            throw error
        }
    }
}
