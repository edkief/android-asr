package com.convoscribe.diarization

import android.util.Log
import com.convoscribe.TAG
import com.convoscribe.audio.WavReader
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SessionStatus
import com.convoscribe.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Run the diarization pass that turns a `TRANSCRIBED` session
 * into a `DIARIZED` one: read the WAV the recorder wrote, ask the
 * engine for the speaker turns, map them onto the persisted
 * transcript segments through [SpeakerAssigner], and write the
 * assignment back to the database in one transaction (see
 * [SessionRepository.applyDiarization]).
 *
 * Wiring lives at [com.convoscribe.AppContainer.diarizeSessionUseCase];
 * the post-record pipeline (`RecordingService` after `controller.stop()`)
 * and the crash-recovery boot path both go through this class so
 * a session only ever has one diarization rule.
 *
 * ## Threading
 *
 * The heavy work — disk I/O, native engine, in-memory clustering
 * — happens on [Dispatchers.Default] so the caller's coroutine
 * (typically the service's `Main.immediate` scope) is never
 * blocked. The status flips happen on the same dispatcher so the
 * `DIARIZING` ↔ `DIARIZED` / `DIARIZATION_FAILED` transitions are
 * linearised.
 *
 * ## Failure handling
 *
 * Any throwable except [CancellationException] is swallowed: the
 * session status is moved to [SessionStatus.DIARIZATION_FAILED]
 * and the exception is logged with `Log.w` so the recording
 * service can stop foreground cleanly and the user can still see
 * the transcript (they may retry from the session list).
 *
 * @property repository the [SessionRepository] used to flip status
 *   and persist the per-segment assignment.
 * @property diarizerFactory builds a fresh [Diarizer] for the
 *   duration of the call; the engine is closed in a `use { }`
 *   block so the native memory is released even on failure.
 * @property settings the user's settings (the [numSpeakers] and
 *   [clusterThreshold] overrides are forwarded to the engine).
 * @property readSamples how to load the WAV into a float array;
 *   defaults to [WavReader.readMono16k]. Injected so tests can
 *   skip the WAV header parsing and feed the engine a
 *   `FloatArray(16000)` directly.
 */
class DiarizeSessionUseCase(
    private val repository: SessionRepository,
    private val diarizerFactory: DiarizerFactory,
    private val settings: SettingsRepository,
    private val readSamples: (File) -> FloatArray = WavReader::readMono16k
) {

    /**
     * Diarize the session identified by [sessionId]. [onProgress] is
     * invoked with the 0..1 fraction the engine reports and is the
     * one observability hook for the UI ("Identifying speakers…
     * 42%"). Returns once the session is `DIARIZED` (success, or
     * zero segments) or `DIARIZATION_FAILED` (any other exception).
     *
     * A session whose transcript has zero segments skips the
     * engine entirely and lands on [SessionStatus.DIARIZED]; the
     * diarizer factory is not invoked, the WAV is not read, and
     * no speaker rows are written.
     *
     * On success, an info-level line is written with the shared
     * [TAG] (`adb logcat -s ConvoScribe`) so the owner can confirm
     * in adb logcat that the post-record diarization step actually
     * ran. The line includes the session id, total wall-clock
     * duration, and the number of speakers the engine detected —
     * the three values the manual test checklist calls out as
     * worth recording.
     */
    suspend fun run(sessionId: Long, onProgress: (Float) -> Unit = {}) {
        val startedNanos = System.nanoTime()
        try {
            repository.setStatus(sessionId, SessionStatus.DIARIZING)
            val session = repository.getSession(sessionId)
                ?: run {
                    Log.w(TAG, "session $sessionId disappeared before diarization")
                    return
                }
            val segments = repository.getSegments(sessionId)
            if (segments.isEmpty()) {
                // No transcript = nothing to label. Land on
                // DIARIZED directly so the UI shows a stable
                // status without a transient DIARIZATION_FAILED.
                Log.i(TAG, "session $sessionId has zero segments; skipping diarization")
                repository.applyDiarization(
                    sessionId = sessionId,
                    assignments = emptyMap(),
                    speakerCount = 0
                )
                return
            }
            val samples = withContext(Dispatchers.Default) {
                readSamples(File(session.audioPath))
            }
            val speakerSettings = settings.settings.value
            val turns = withContext(Dispatchers.Default) {
                diarizerFactory.create().use { diarizer ->
                    diarizer.diarize(
                        samples = samples,
                        numSpeakers = speakerSettings.numSpeakers,
                        threshold = speakerSettings.clusterThreshold,
                        onProgress = onProgress
                    )
                }
            }
            val spans = segments.map { segment ->
                SegmentSpan(id = segment.id, startMs = segment.startMs, endMs = segment.endMs)
            }
            val assignment = SpeakerAssigner.assign(spans, turns)
            repository.applyDiarization(
                sessionId = sessionId,
                assignments = assignment.speakerBySegment,
                speakerCount = assignment.speakerCount
            )
            val durationMs = (System.nanoTime() - startedNanos) / 1_000_000L
            Log.i(
                TAG,
                "diarization completed session=$sessionId durationMs=$durationMs" +
                    " speakers=${assignment.speakerCount}"
            )
        } catch (ce: CancellationException) {
            // Cooperative cancellation: re-throw so structured
            // concurrency still sees the cancel and the service
            // shutdown path stays clean. Do not flip status to
            // DIARIZATION_FAILED — the recorder / recovery loop
            // will pick the session up again on next start.
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "Diarization failed for session $sessionId", t)
            repository.setStatus(sessionId, SessionStatus.DIARIZATION_FAILED)
        }
    }
}
