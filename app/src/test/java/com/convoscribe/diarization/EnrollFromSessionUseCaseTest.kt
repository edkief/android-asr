package com.convoscribe.diarization

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.EnrolledSpeakerEntity
import com.convoscribe.data.EnrolledSpeakerStore
import com.convoscribe.data.SegmentEntity
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SpeakerEntity
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pure-JVM unit tests for [EnrollFromSessionUseCase]. Robolectric
 * gives us a real [android.content.Context] so [androidx.room.Room]
 * can wire SQLite, and every other collaborator is faked:
 *
 *  * the [EnrolledSpeakerStore] is an in-memory implementation
 *    that records the saved rows;
 *  * the [SpeakerEmbedder] returns a tiny vector derived from
 *    the input size (the first slot holds the sample count);
 *  * the [readSamples] lambda is replaced with a function that
 *    returns a `FloatArray` of 60 s of fake audio, so the
 *    test does not have to write a WAV header.
 *
 * Every test builds a fresh in-memory Room database through
 * [setUp], so they never share state. The session is created
 * with a synthetic `audioPath` (the `readSamples` lambda ignores
 * the file argument) and the segments are inserted with the
 * same speaker indices the use case is asked to look up.
 */
@RunWith(RobolectricTestRunner::class)
class EnrollFromSessionUseCaseTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var recordingsDir: File
    private lateinit var repo: SessionRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        recordingsDir = File(context.filesDir, "recordings-test-${System.nanoTime()}")
        recordingsDir.mkdirs()
        repo = SessionRepository(db, recordingsDir)
    }

    @After
    fun tearDown() {
        db.close()
        if (recordingsDir.exists()) {
            recordingsDir.deleteRecursively()
        }
    }

    /**
     * Fake [SpeakerEmbedder] that returns a tiny embedding
     * derived from the input size (the first slot holds the
     * sample count). Mirrors the embedder fake used by the
     * v1 [EnrollSpeakerUseCase] tests so the same harness
     * style covers both code paths.
     */
    private class FakeSpeakerEmbedder : SpeakerEmbedder {
        var embedCalls: Int = 0
            private set
        var lastSamplesSize: Int = 0
            private set
        var closed: Boolean = false
            private set
        override fun embed(samples: FloatArray): FloatArray {
            embedCalls += 1
            lastSamplesSize = samples.size
            return floatArrayOf(samples.size.toFloat(), 1f)
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeSpeakerEmbedderFactory(private val embedder: SpeakerEmbedder) : SpeakerEmbedderFactory {
        var createCalls: Int = 0
            private set
        override fun create(): SpeakerEmbedder {
            createCalls += 1
            return embedder
        }
    }

    /**
     * In-memory fake [EnrolledSpeakerStore]. Records every
     * [save] call so the test can assert on what landed in the
     * table; [getAll] mirrors the production read shape (a
     * `List<Enrolled>`) so a future caller can iterate without
     * dispatching through Room.
     */
    private class FakeEnrolledSpeakerStore : EnrolledSpeakerStore {
        data class Row(val name: String, val embedding: FloatArray)
        val rows: MutableList<Row> = mutableListOf()

        override fun observeAll(): Flow<List<EnrolledSpeakerEntity>> = flow {
            emit(
                rows.map { row ->
                    EnrolledSpeakerEntity(
                        id = 0L,
                        name = row.name,
                        embedding = floatArrayToBytes(row.embedding),
                        createdAt = 0L
                    )
                }
            )
        }

        override suspend fun getAll(): List<SpeakerMatcher.Enrolled> = rows.map {
            SpeakerMatcher.Enrolled(it.name, it.embedding)
        }

        override suspend fun save(name: String, embedding: FloatArray) {
            rows.add(Row(name, embedding.copyOf()))
        }

        @Suppress("unused")
        override suspend fun delete(id: Long) {
            rows.clear()
        }
    }

    /**
     * Pack a `FloatArray` as little-endian float32 bytes. Used
     * by [FakeEnrolledSpeakerStore.observeAll] so a downstream
     * `EnrolledSpeakerEntity` round-trip looks the same as a
     * Room-backed one (no real Room cursor ever runs because
     * the fake never escapes the test).
     */
    private companion object {
        fun floatArrayToBytes(floats: FloatArray): ByteArray {
            val buf = java.nio.ByteBuffer.allocate(floats.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            buf.asFloatBuffer().put(floats)
            return buf.array()
        }
    }

    /**
     * Insert a [SegmentEntity] with the given speaker index so
     * the use case's `segments.speakerIndex == speakerIndex`
     * filter has something to match against. Helper to keep
     * the test bodies readable.
     */
    private suspend fun addSegment(
        sessionId: Long,
        startMs: Long,
        endMs: Long,
        speakerIndex: Int?,
        text: String = ""
    ): Long {
        val id = repo.addSegment(sessionId, startMs = startMs, endMs = endMs, text = text)
        if (speakerIndex != null) {
            repo.setSegmentSpeaker(id, speakerIndex)
        }
        return id
    }

    /**
     * Acceptance test 1: a speaker that owns 5 s of segments is
     * enrolled under the trimmed name, the embedding is saved
     * and the speaker label in the session is renamed to match.
     *
     * Setup: 1 s of "Speaker 0" + 1 s of "Speaker 1" + 1 s of
     * "Speaker 0" + 1 s of "Speaker 1" + 1 s of "Speaker 0" =
     * 3 s of "Speaker 0" audio (5 s overall, with the rest
     * belonging to Speaker 1). The use case must slice the
     * 3 s windows into one 3 s buffer for Speaker 0.
     */
    @Test
    fun run_withEnoughSpeech_enrollsAndRenames() = runTest {
        val sessionId = repo.createSession(title = "Test", language = "en")
        // In production the user only sees the rename dialog
        // after diarization has run, so a SpeakerEntity row for
        // speaker 0 already exists. Insert it directly so
        // `repository.renameSpeaker` has a row to update.
        db.speakerDao().upsert(
            listOf(
                SpeakerEntity(
                    sessionId = sessionId,
                    speakerIndex = 0,
                    label = "Speaker 1"
                )
            )
        )
        // 5 s of segments: 3 s owned by Speaker 0 (1 s, 1 s, 1 s)
        // and 2 s owned by Speaker 1 (1 s, 1 s). The use case
        // must select the Speaker 0 windows only.
        addSegment(sessionId, 0L, 1_000L, speakerIndex = 0)
        addSegment(sessionId, 1_000L, 2_000L, speakerIndex = 1)
        addSegment(sessionId, 2_000L, 3_000L, speakerIndex = 0)
        addSegment(sessionId, 3_000L, 4_000L, speakerIndex = 1)
        addSegment(sessionId, 4_000L, 5_000L, speakerIndex = 0)
        val store = FakeEnrolledSpeakerStore()
        val embedder = FakeSpeakerEmbedder()
        val useCase = EnrollFromSessionUseCase(
            repository = repo,
            embedderFactory = FakeSpeakerEmbedderFactory(embedder),
            store = store,
            // 60 s of fake audio — plenty of headroom for the
            // 30 s cap to bite in test 3.
            readSamples = { FloatArray(60 * 16_000) }
        )

        useCase.run(sessionId, speakerIndex = 0, name = " Alice ")

        // One enrollment was saved under the trimmed name.
        assertEquals(1, store.rows.size)
        val row = store.rows.single()
        assertEquals("Alice", row.name)
        // 3 s at 16 kHz = 48 000 samples — the Speaker 0 windows
        // are 0..1 000 ms, 2..3 000 ms, 4..5 000 ms, all 1 000
        // ms (= 16 000 samples) long, totaling 48 000 samples.
        assertEquals(48_000f, row.embedding[0], 0f)
        assertEquals(1f, row.embedding[1], 0f)
        assertEquals(48_000, embedder.lastSamplesSize)
        // The session's Speaker 0 row was renamed to "Alice".
        val speakers = repo.getSpeakers(sessionId)
        assertEquals(1, speakers.size)
        assertEquals(0, speakers[0].speakerIndex)
        assertEquals("Alice", speakers[0].label)
        // Embedder was created once and closed via `use { }`.
        assertEquals(1, embedder.embedCalls)
        assertTrue(embedder.closed)
    }

    /**
     * Acceptance test 2: a speaker that owns only 2 s of audio
     * throws [IllegalArgumentException] with the spec's
     * verbatim "Not enough speech from this speaker (need 3 s)"
     * message. Nothing is saved to the enrolled-speaker store
     * (the validation runs before the embed call).
     */
    @Test
    fun run_withTooLittleSpeech_throws_andDoesNotSave() = runTest {
        val sessionId = repo.createSession(title = "Test", language = "en")
        // 2 s total: 1 s of Speaker 0 + 1 s of Speaker 1. The
        // use case must reject the Speaker 0 windows because
        // their concatenated audio is below the 3 s floor.
        addSegment(sessionId, 0L, 1_000L, speakerIndex = 0)
        addSegment(sessionId, 1_000L, 2_000L, speakerIndex = 1)
        val store = FakeEnrolledSpeakerStore()
        val embedder = FakeSpeakerEmbedder()
        val useCase = EnrollFromSessionUseCase(
            repository = repo,
            embedderFactory = FakeSpeakerEmbedderFactory(embedder),
            store = store,
            readSamples = { FloatArray(60 * 16_000) }
        )

        val thrown = runCatching {
            useCase.run(sessionId, speakerIndex = 0, name = "Alice")
        }.exceptionOrNull()
        assertNotNull("Expected IllegalArgumentException, but run() returned normally", thrown)
        assertTrue(
            "expected IllegalArgumentException, got ${thrown!!::class.java.simpleName}",
            thrown is IllegalArgumentException
        )
        assertEquals(
            "Not enough speech from this speaker (need 3 s)",
            thrown.message
        )
        // No enrollment was saved.
        assertTrue(store.rows.isEmpty())
        // The embedder was never instantiated — the validation
        // happens before the engine is touched.
        assertEquals(0, embedder.embedCalls)
    }

    /**
     * Acceptance test 3: when a speaker owns more than 30 s of
     * segments, the audio handed to the embedder is capped at
     * 30 s = 480 000 samples. The fake embedder records the
     * sample count so we can pin the cap exactly.
     *
     * Setup: 40 s of segments, every second Speaker 0. The use
     * case must concatenate up to 30 s (= 480 000 samples) and
     * stop.
     */
    @Test
    fun run_capsAudioAt30Seconds() = runTest {
        val sessionId = repo.createSession(title = "Test", language = "en")
        // 40 s of Speaker 0 in 1 s chunks. The use case must
        // stop concatenating after 30 s.
        for (second in 0 until 40) {
            addSegment(
                sessionId,
                startMs = second * 1_000L,
                endMs = (second + 1) * 1_000L,
                speakerIndex = 0
            )
        }
        val store = FakeEnrolledSpeakerStore()
        val embedder = FakeSpeakerEmbedder()
        val useCase = EnrollFromSessionUseCase(
            repository = repo,
            embedderFactory = FakeSpeakerEmbedderFactory(embedder),
            store = store,
            readSamples = { FloatArray(60 * 16_000) }
        )

        useCase.run(sessionId, speakerIndex = 0, name = "Alice")

        // The embedder received exactly the 30 s cap
        // (480 000 samples). Anything more would mean the
        // cap is too loose; anything less would mean the
        // cap is too tight.
        assertEquals(480_000, embedder.lastSamplesSize)
        assertEquals(1, store.rows.size)
        assertEquals(480_000f, store.rows.single().embedding[0], 0f)
    }

    /**
     * Defensive: when the session has zero segments assigned to
     * the requested speaker, the use case returns a 0-sample
     * buffer, which trips the same floor as test 2 with the
     * same error message. The store is untouched.
     */
    @Test
    fun run_withNoMatchingSegments_throws_andDoesNotSave() = runTest {
        val sessionId = repo.createSession(title = "Test", language = "en")
        // Only Speaker 1 has any segments. Asking for Speaker 0
        // yields an empty selection.
        addSegment(sessionId, 0L, 1_000L, speakerIndex = 1)
        addSegment(sessionId, 1_000L, 2_000L, speakerIndex = 1)
        val store = FakeEnrolledSpeakerStore()
        val embedder = FakeSpeakerEmbedder()
        val useCase = EnrollFromSessionUseCase(
            repository = repo,
            embedderFactory = FakeSpeakerEmbedderFactory(embedder),
            store = store,
            readSamples = { FloatArray(60 * 16_000) }
        )

        try {
            useCase.run(sessionId, speakerIndex = 0, name = "Alice")
            fail("Expected IllegalArgumentException for empty speaker audio")
        } catch (e: IllegalArgumentException) {
            assertEquals("Not enough speech from this speaker (need 3 s)", e.message)
        }
        assertTrue(store.rows.isEmpty())
        assertEquals(0, embedder.embedCalls)
    }

    /**
     * The `extractSpeakerAudio` helper must produce the same
     * 30 s buffer when called directly with a synthetic segment
     * list, so future callers can use it without instantiating
     * the full use case (e.g. a future preview tool).
     */
    @Test
    fun extractSpeakerAudio_capsAt30Seconds() {
        val useCase = EnrollFromSessionUseCase(
            repository = repo,
            embedderFactory = FakeSpeakerEmbedderFactory(FakeSpeakerEmbedder()),
            store = FakeEnrolledSpeakerStore()
        )
        val samples = FloatArray(60 * 16_000)
        // 40 s of Speaker 0 in 1 s segments.
        val segments = (0 until 40).map { i ->
            SegmentEntity(
                id = i.toLong(),
                sessionId = 1L,
                startMs = i * 1_000L,
                endMs = (i + 1) * 1_000L,
                text = "",
                speakerIndex = 0
            )
        }
        val audio = useCase.extractSpeakerAudio(samples, segments, speakerIndex = 0)
        assertEquals(480_000, audio.size)
    }

    /**
     * `extractSpeakerAudio` returns 0 samples when no segment
     * matches. The caller then trips the floor in `run`, which
     * is the same behaviour test 4 sees end-to-end.
     */
    @Test
    fun extractSpeakerAudio_returnsEmptyForNoMatch() {
        val useCase = EnrollFromSessionUseCase(
            repository = repo,
            embedderFactory = FakeSpeakerEmbedderFactory(FakeSpeakerEmbedder()),
            store = FakeEnrolledSpeakerStore()
        )
        val segments = listOf(
            SegmentEntity(
                id = 1L,
                sessionId = 1L,
                startMs = 0L,
                endMs = 1_000L,
                text = "",
                speakerIndex = 1
            )
        )
        val audio = useCase.extractSpeakerAudio(FloatArray(16_000), segments, speakerIndex = 0)
        assertEquals(0, audio.size)
        assertNull(audio.firstOrNull())
    }

    /**
     * Sanity: the fake store round-trips a save / getAll pair
     * the way the production Room implementation would. This
     * pins the fake's behaviour so a future test can compose
     * enrollment with matching without rebuilding the fake.
     */
    @Test
    fun fakeStore_saveAndRetrieveWorks() = runBlocking {
        val store = FakeEnrolledSpeakerStore()
        store.save(name = "Bob", embedding = floatArrayOf(0.5f))
        val all = store.getAll()
        assertEquals(1, all.size)
        assertEquals("Bob", all[0].name)
        assertEquals(0.5f, all[0].embedding[0], 0f)
    }
}
