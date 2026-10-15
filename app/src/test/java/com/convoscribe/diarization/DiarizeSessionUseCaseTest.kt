package com.convoscribe.diarization

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SessionStatus
import com.convoscribe.settings.InMemorySecretStore
import com.convoscribe.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [DiarizeSessionUseCase]. Robolectric gives us a
 * real [android.content.Context] (so [androidx.room.Room] can
 * wire SQLite), and we inject fakes for everything else:
 *
 *  * [ScriptedDiarizerFactory] returns a diarizer that emits a
 *    scripted list of turns so we can assert on the assignment
 *    without any native code.
 *  * [ThrowingDiarizer] raises on [diarize] to verify the
 *    failure path lands the session in `DIARIZATION_FAILED` and
 *    leaves the segments untouched.
 *  * [readSamples] is replaced with a lambda that returns
 *    `FloatArray(16_000)` so the test does not have to lay out
 *    a WAV header just to feed the engine.
 *
 * Every test passes the same Robolectric-provided context to a
 * freshly built in-memory Room database, so the tests do not
 * share state.
 */
@RunWith(RobolectricTestRunner::class)
class DiarizeSessionUseCaseTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var recordingsDir: File
    private lateinit var repo: SessionRepository
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        recordingsDir = File(context.filesDir, "recordings-test-${System.nanoTime()}")
        recordingsDir.mkdirs()
        repo = SessionRepository(db, recordingsDir)
        val prefs = context.getSharedPreferences(
            "settings-test-${System.nanoTime()}",
            Context.MODE_PRIVATE
        )
        settings = SettingsRepository(prefs, InMemorySecretStore())
    }

    @After
    fun tearDown() {
        db.close()
        if (recordingsDir.exists()) {
            recordingsDir.deleteRecursively()
        }
    }

    /**
     * Simple [Diarizer] that returns the [turns] it was built with
     * on every [diarize] call. The factory controls whether the
     * diarizer has been "created" yet via its `created` counter.
     */
    private class ScriptedDiarizer(
        private val turns: List<SpeakerTurn>
    ) : Diarizer {
        var diarizeCount: Int = 0
            private set
        var lastNumSpeakers: Int = 0
            private set
        var lastThreshold: Float = 0f
            private set
        var lastSamplesSize: Int = 0
            private set
        override fun diarize(
            samples: FloatArray,
            numSpeakers: Int,
            threshold: Float,
            onProgress: (Float) -> Unit
        ): List<SpeakerTurn> {
            diarizeCount += 1
            lastNumSpeakers = numSpeakers
            lastThreshold = threshold
            lastSamplesSize = samples.size
            return turns
        }

        override fun close() {
            // No native resources to release in tests.
        }
    }

    private class ScriptedDiarizerFactory(
        private val diarizer: Diarizer
    ) : DiarizerFactory {
        var created: Int = 0
            private set
        override fun create(): Diarizer {
            created += 1
            return diarizer
        }
    }

    /**
     * [Diarizer] that always throws on [diarize]. Used by the
     * failure-path test to confirm the session lands in
     * `DIARIZATION_FAILED` without losing the existing segment
     * rows.
     */
    private class ThrowingDiarizer(val message: String = "engine exploded") : Diarizer {
        override fun diarize(
            samples: FloatArray,
            numSpeakers: Int,
            threshold: Float,
            onProgress: (Float) -> Unit
        ): List<SpeakerTurn> = throw IllegalStateException(message)

        override fun close() {
            // Nothing to release.
        }
    }

    /**
     * Acceptance test 1: a session with two segments and scripted
     * turns for two speakers ends up `DIARIZED`, the segments have
     * the correct speaker index, and the speakers table contains
     * "Speaker 1" / "Speaker 2".
     *
     * Setup: the segments are `[0, 1000)` and `[1000, 2000)` and
     * the engine returns a turn for speaker 0 on the first
     * segment and a turn for speaker 1 on the second. After
     * first-appearance renumbering the assignments are
     * `(segId1 -> 0, segId2 -> 1)` and the speaker count is `2`.
     */
    @Test
    fun run_withTwoSegmentsAndTwoSpeakers_resultsInDiarized() = runTest {
        val id = repo.createSession(title = "Test", language = "en")
        val seg1 = repo.addSegment(id, startMs = 0L, endMs = 1000L, text = "first")
        val seg2 = repo.addSegment(id, startMs = 1000L, endMs = 2000L, text = "second")
        val turns = listOf(
            SpeakerTurn(startMs = 0L, endMs = 1000L, speaker = 0),
            SpeakerTurn(startMs = 1000L, endMs = 2000L, speaker = 1)
        )
        val diarizer = ScriptedDiarizer(turns)
        val factory = ScriptedDiarizerFactory(diarizer)
        val useCase = DiarizeSessionUseCase(
            repository = repo,
            diarizerFactory = factory,
            settings = settings,
            readSamples = { FloatArray(16_000) }
        )

        useCase.run(id)

        val session = repo.getSession(id)!!
        assertEquals(SessionStatus.DIARIZED, session.status)

        val segments = repo.getSegments(id)
        assertEquals(listOf(0, 1), segments.map { it.speakerIndex })

        val speakers = repo.getSpeakers(id)
        assertEquals(2, speakers.size)
        assertEquals(
            listOf("Speaker 1" to 0, "Speaker 2" to 1),
            speakers.map { it.label to it.speakerIndex }
        )

        // The factory was called exactly once and the diarizer
        // got the user's numSpeakers / threshold settings.
        assertEquals(1, factory.created)
        assertEquals(1, diarizer.diarizeCount)
        assertEquals(settings.settings.value.numSpeakers, diarizer.lastNumSpeakers)
        assertEquals(settings.settings.value.clusterThreshold, diarizer.lastThreshold, 0.0001f)
        assertEquals(16_000, diarizer.lastSamplesSize)
    }

    /**
     * Acceptance test 2: a diarizer that throws leaves the session
     * in `DIARIZATION_FAILED` and the segment rows untouched (no
     * speaker index, no speaker rows created).
     */
    @Test
    fun run_withThrowingDiarizer_leavesSessionInDiarizationFailed() = runTest {
        val id = repo.createSession(title = "Test", language = "en")
        val seg1 = repo.addSegment(id, startMs = 0L, endMs = 1000L, text = "first")
        val seg2 = repo.addSegment(id, startMs = 1000L, endMs = 2000L, text = "second")
        val factory = ScriptedDiarizerFactory(ThrowingDiarizer("engine exploded"))
        val useCase = DiarizeSessionUseCase(
            repository = repo,
            diarizerFactory = factory,
            settings = settings,
            readSamples = { FloatArray(16_000) }
        )

        useCase.run(id)

        val session = repo.getSession(id)!!
        assertEquals(SessionStatus.DIARIZATION_FAILED, session.status)

        // Segments are still untouched: speaker index is null
        // because no assignment ever landed.
        val segments = repo.getSegments(id)
        assertEquals(listOf(null, null), segments.map { it.speakerIndex })
        // No speakers were ever written.
        assertTrue(repo.getSpeakers(id).isEmpty())
        // The factory was invoked exactly once; the use case
        // did not retry.
        assertEquals(1, factory.created)
    }

    /**
     * Acceptance test 3: a session with zero segments goes
     * straight to `DIARIZED` without the diarizer factory ever
     * being called.
     */
    @Test
    fun run_withZeroSegments_skipsDiarizer_andGoesStraightToDiarized() = runTest {
        val id = repo.createSession(title = "Test", language = "en")
        val factory = ScriptedDiarizerFactory(
            ScriptedDiarizer(turns = emptyList())
        )
        val useCase = DiarizeSessionUseCase(
            repository = repo,
            diarizerFactory = factory,
            settings = settings,
            readSamples = { FloatArray(16_000) }
        )

        useCase.run(id)

        val session = repo.getSession(id)!!
        assertEquals(SessionStatus.DIARIZED, session.status)
        // Factory was never created — the zero-segment shortcut
        // bypasses the engine entirely.
        assertEquals(0, factory.created)
        // No speakers written.
        assertTrue(repo.getSpeakers(id).isEmpty())
    }
}
