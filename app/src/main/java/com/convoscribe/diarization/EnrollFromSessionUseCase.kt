package com.convoscribe.diarization

import com.convoscribe.audio.AudioSpec
import com.convoscribe.audio.WavReader
import com.convoscribe.data.EnrolledSpeakerStore
import com.convoscribe.data.SessionRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v2 "save this session speaker as a known speaker" use case.
 *
 * A user who has just finished a recording can pick one of the
 * diarized speakers in the session-detail screen and promote
 * them to a known voice without going back to the dedicated
 * "Known speakers" screen. The use case pulls the
 * speaker's audio straight out of the recording, embeds it and
 * persists it through [EnrolledSpeakerStore] — the same path
 * [EnrollSpeakerUseCase.enroll] uses — so future recordings
 * label matched voices with the user's chosen name (goal 5).
 *
 * Flow:
 *
 *  1. Read the WAV the recorder wrote (delegated to
 *     [readSamples] for testability).
 *  2. Fetch the session's persisted segments through
 *     [SessionRepository.getSegments] and keep the ones whose
 *     `speakerIndex` matches [speakerIndex], ordered by
 *     `startMs` so the concatenated audio follows the speaker's
 *     natural flow.
 *  3. Concatenate the matching audio windows up to
 *     [MAX_AUDIO_MS] = 30 000 ms (480 000 samples). The cap is
 *     applied with a "prefer the suffix of the next window"
 *     rule (matching [SpeakerSampleCollector]) so a
 *     speaker who keeps talking past the budget is cut mid-
 *     window rather than dropped wholesale.
 *  4. If the concatenated audio is shorter than
 *     [MIN_AUDIO_MS] = 3 000 ms, throw
 *     [IllegalArgumentException] with the message the spec
 *     pins verbatim ("Not enough speech from this speaker
 *     (need 3 s)") — the screen surfaces that string in a
 *     snackbar so the user knows why the save was rejected.
 *  5. Build the embedding through the [SpeakerEmbedder]
 *     factory's `use { }` block (the sherpa extractor retains
 *     the model in native memory for its lifetime, so the
 *     embedder is short-lived) and persist via
 *     [EnrolledSpeakerStore.save] under the trimmed name.
 *  6. Rename the speaker in the session itself via
 *     [SessionRepository.renameSpeaker] so the transcript
 *     immediately shows the new label without a separate
 *     "Rename" tap.
 *
 * The 30 s cap matches the sherpa-onnx embedding model's
 * documented sweet spot and mirrors the ceiling the
 * post-diarization [SpeakerSampleCollector] applies so both
 * embedding code paths cap at the same number; the 3 s floor
 * matches what the model needs to produce a stable vector
 * (the same threshold the v1 enrollment use case enforces on
 * [EnrollSpeakerUseCase]).
 *
 * @param repository the [SessionRepository] we read segments
 *   from and rename the speaker in.
 * @param embedderFactory builds a [SpeakerEmbedder] for the
 *   short-lived embed call (same factory as the rest of the
 *   app so production wires [SherpaSpeakerEmbedderFactory]
 *   and tests can pass a fake).
 * @param store persistence façade for the enrolled speaker
 *   table; never touched when the audio is too short because
 *   the floor check throws first.
 * @param readSamples how to load the WAV into a float array;
 *   defaults to [WavReader.readMono16k] so the test suite can
 *   inject a trivial stub.
 */
open class EnrollFromSessionUseCase(
    private val repository: SessionRepository,
    private val embedderFactory: SpeakerEmbedderFactory,
    private val store: EnrolledSpeakerStore,
    private val readSamples: (File) -> FloatArray = WavReader::readMono16k
) {

    /**
     * Run the enrollment. See the class kdoc for the full flow.
     *
     * @param sessionId session the speaker belongs to.
     * @param speakerIndex 0-based index of the speaker in the
     *   session's `speakers` table. Segments whose
     *   `speakerIndex` does not match are skipped.
     * @param name user-visible name to enrol under. Trimmed
     *   before the name is persisted; a blank name is rejected
     *   by [EnrolledSpeakerStore.save] with the standard
     *   `IllegalArgumentException("Speaker name must not be
     *   blank")` message (and the rename is still applied so
     *   the transcript shows the user's input even if the
     *   enrollment itself is rejected).
     *
     * @throws IllegalArgumentException when the concatenated
     *   audio is shorter than [MIN_AUDIO_MS]; the message is
     *   the spec's literal "Not enough speech from this speaker
     *   (need 3 s)".
     */
    open suspend fun run(sessionId: Long, speakerIndex: Int, name: String) {
        val session = repository.getSession(sessionId)
            ?: error("No session row for id $sessionId")
        val segments = repository.getSegments(sessionId)
        val samples = withContext(Dispatchers.Default) {
            readSamples(File(session.audioPath))
        }
        val audio = extractSpeakerAudio(samples, segments, speakerIndex)
        require(audio.size.toLong() >= MIN_AUDIO_SAMPLES) {
            "Not enough speech from this speaker (need 3 s)"
        }
        val embedding = withContext(Dispatchers.Default) {
            embedderFactory.create().use { it.embed(audio) }
        }
        store.save(name.trim(), embedding)
        repository.renameSpeaker(sessionId, speakerIndex, name)
    }

    /**
     * Slice [samples] down to the audio the speaker identified
     * by [speakerIndex] owns, concatenate it (in `startMs`
     * order) and cap at [MAX_AUDIO_MS]. Returns a fresh
     * `FloatArray`; the input is left untouched.
     *
     * Open so the unit test can exercise the capping logic
     * without a fake engine — the use case's `run` is the
     * integration path, this is the pure-data helper.
     */
    open fun extractSpeakerAudio(
        samples: FloatArray,
        segments: List<com.convoscribe.data.SegmentEntity>,
        speakerIndex: Int
    ): FloatArray {
        if (samples.isEmpty() || segments.isEmpty()) return FloatArray(0)
        val samplesPerMs = AudioSpec.SAMPLE_RATE / 1000
        val maxSamples = (MAX_AUDIO_MS * samplesPerMs).toInt()
        val matching = segments
            .filter { it.speakerIndex == speakerIndex }
            .sortedBy { it.startMs }
        if (matching.isEmpty()) return FloatArray(0)
        val buckets = ArrayList<FloatArray>(matching.size)
        var collected = 0
        for (segment in matching) {
            if (collected >= maxSamples) break
            val startSample = (segment.startMs * samplesPerMs).toInt().coerceAtLeast(0)
            val endSample = ((segment.endMs * samplesPerMs).toInt()).coerceAtMost(samples.size)
            if (endSample <= startSample) continue
            val windowSamples = endSample - startSample
            val remainingBudget = maxSamples - collected
            val copyStart: Int
            val copyEnd: Int
            if (windowSamples <= remainingBudget) {
                copyStart = startSample
                copyEnd = endSample
            } else {
                // Prefer the suffix of the window so a speaker
                // talking past the 30 s budget is cut mid-
                // window rather than dropped wholesale. Mirrors
                // the rule SpeakerSampleCollector applies during
                // the post-diarization embedding pass so both
                // code paths read consistently.
                copyStart = endSample - remainingBudget
                copyEnd = endSample
            }
            buckets.add(samples.copyOfRange(copyStart, copyEnd))
            collected += copyEnd - copyStart
        }
        if (buckets.isEmpty()) return FloatArray(0)
        val flat = FloatArray(collected)
        var offset = 0
        for (window in buckets) {
            window.copyInto(flat, destinationOffset = offset)
            offset += window.size
        }
        return flat
    }

    companion object {
        /**
         * Maximum audio kept per speaker (ms). 30 s = 480 000
         * samples at 16 kHz; matches the documented sweet spot
         * for sherpa-onnx's
         * `wespeaker_en_voxceleb_resnet34_LM` model and
         * mirrors the cap [SpeakerSampleCollector] applies
         * during the post-diarization embedding pass so both
         * code paths read consistently.
         */
        const val MAX_AUDIO_MS: Long = 30_000L

        /**
         * Minimum audio (ms) before a speaker can be enrolled.
         * 3 s = 48 000 samples at 16 kHz; below that the
         * embedding model cannot produce a stable vector, so
         * the use case rejects the request with a
         * human-readable error instead of saving a
         * low-quality reference. Matches
         * [EnrollSpeakerUseCase.MIN_SAMPLES] so the
         * "record a fresh sample" and "promote from session"
         * flows share a single floor.
         */
        const val MIN_AUDIO_MS: Long = 3_000L

        /**
         * Floor expressed in samples. Derived from
         * [MIN_AUDIO_MS] and [AudioSpec.SAMPLE_RATE] so a
         * future sample-rate change only has to be edited in
         * one place.
         */
        const val MIN_AUDIO_SAMPLES: Long = MIN_AUDIO_MS * AudioSpec.SAMPLE_RATE / 1000L
    }
}
