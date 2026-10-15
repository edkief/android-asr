package com.convoscribe.enginecheck

import com.convoscribe.asr.TranscriberFactory
import com.convoscribe.asr.TranscriptEvent

/**
 * One engine-check measurement: the recognizer was built (or
 * skipped) and the bundled sample wave was processed through it.
 *
 * @property engine `"npu"` or `"cpu"` — which engine was tested.
 * @property initMs wall-clock spent in [TranscriberFactory.create].
 *   `0L` when [error] is set.
 * @property firstRun `true` when the factory reported
 *   [TranscriberFactory.needsPreparation] for `"en"`; the report
 *   shows it so the user understands why the first NPU run was
 *   unusually slow.
 * @property audioMs duration of the audio that was actually
 *   processed, derived from `samples.size / 16` (mono 16 kHz ⇒
 *   16 samples / ms). Always set; `0L` for the synthetic
 *   "audioMs == 0" guard in [realTimeFactor].
 * @property processMs wall-clock spent feeding chunks and calling
 *   `finish()` on the transcriber. `0L` when [error] is set.
 * @property text all [TranscriptEvent.Final] texts joined by a
 *   single space; blank when there were no finals.
 * @property error message of the exception that aborted the run,
 *   or `null` on a clean run.
 */
data class EngineCheckResult(
    val engine: String,
    val initMs: Long,
    val firstRun: Boolean,
    val audioMs: Long,
    val processMs: Long,
    val text: String,
    val error: String? = null
) {
    /**
     * Real-time factor: `processMs / audioMs`. `0.0` when no audio
     * was processed (`audioMs == 0L`) so the caller never has to
     * defend against a divide-by-zero in the verdict or the report
     * formatter. A value `< 1.0` means the recognizer is faster
     * than real-time; `>= 1.0` means it cannot keep up with live
     * audio.
     */
    val realTimeFactor: Double get() =
        if (audioMs == 0L) 0.0 else processMs.toDouble() / audioMs
}

/**
 * Drives one engine-check pass for a single engine. Pure-Kotlin
 * except for the [TranscriberFactory] it depends on — every other
 * input (the audio samples and the wall clock) is a lambda so the
 * test suite can fake them without instantiating sherpa-onnx.
 *
 * @param factory builds the streaming transcriber. The check
 *   calls [TranscriberFactory.create] with `"en"` because that's
 *   the language the bundled sample wave (`test_wavs/0.wav`) is
 *   transcribed in.
 * @param loadSamples returns the audio the recognizer should
 *   chew on. In production this reads `ModelCatalog.asrFor("en",
 *   engine).test_wavs/0.wav` through `WavReader.readMono16k`; in
 *   tests it returns a hand-built `FloatArray`.
 * @param clock wall-clock source; injected so tests can pin the
 *   elapsed time per chunk.
 */
class EngineCheckRunner(
    private val factory: TranscriberFactory,
    private val loadSamples: () -> FloatArray,
    private val clock: () -> Long
) {

    /**
     * Run the engine check for [engine] (typically `"npu"` or
     * `"cpu"`) and return the result. Always closes the
     * transcriber it built; an exception anywhere in the pipeline
     * is funnelled into [EngineCheckResult.error] and the function
     * returns a clean result instead of throwing, so the UI can
     * show "FAILED" alongside the other result.
     */
    fun run(engine: String): EngineCheckResult {
        val firstRun = factory.needsPreparation("en")
        val samples = loadSamples()
        val audioMs: Long = samples.size.toLong() / 16L
        var transcriber: com.convoscribe.asr.StreamingTranscriber? = null
        return try {
            val initStart = clock()
            transcriber = factory.create("en")
            val initMs = clock() - initStart

            val processStart = clock()
            val finals = mutableListOf<String>()
            var offset = 0
            while (offset < samples.size) {
                val end = minOf(offset + 1600, samples.size)
                val chunk = samples.copyOfRange(offset, end)
                for (event in transcriber.accept(chunk)) {
                    if (event is TranscriptEvent.Final && event.text.isNotBlank()) {
                        finals += event.text
                    }
                }
                offset = end
            }
            for (event in transcriber.finish()) {
                if (event is TranscriptEvent.Final && event.text.isNotBlank()) {
                    finals += event.text
                }
            }
            val processMs = clock() - processStart

            EngineCheckResult(
                engine = engine,
                initMs = initMs,
                firstRun = firstRun,
                audioMs = audioMs,
                processMs = processMs,
                text = finals.joinToString(" ")
            )
        } catch (t: Throwable) {
            EngineCheckResult(
                engine = engine,
                initMs = 0L,
                firstRun = firstRun,
                audioMs = audioMs,
                processMs = 0L,
                text = "",
                error = t.message ?: t.toString()
            )
        } finally {
            runCatching { transcriber?.close() }
        }
    }
}
