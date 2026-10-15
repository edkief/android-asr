package com.convoscribe.ui.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SessionStatus
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [SessionViewModel]. The repository is real (the
 * ViewModel's only job is to fan-in the three Room flows) but the
 * database is an in-memory Room database under Robolectric, so
 * nothing touches the device.
 *
 * The `stateIn(WhileSubscribed)` flow only emits once a collector
 * subscribes — `vm.uiState.first { condition }` waits for the
 * real Room snapshot and skips the `SessionUiState()` initial.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SessionViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var recordingsDir: File
    private lateinit var repo: SessionRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        recordingsDir = File(context.filesDir, "session-vm-test-${System.nanoTime()}")
        recordingsDir.mkdirs()
        repo = SessionRepository(db, recordingsDir)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
        if (recordingsDir.exists()) {
            recordingsDir.deleteRecursively()
        }
    }

    @Test
    fun unknownSessionId_yieldsNotFound() = runTest(dispatcher) {
        // No sessions inserted — the id `9999` is unknown.
        val vm = SessionViewModel(sessionId = 9999L, repository = repo)

        val state = vm.uiState.first { it.notFound }
        assertTrue(state.notFound)
        assertEquals("", state.title)
        assertNull(state.status)
        assertTrue(state.lines.isEmpty())
        assertTrue(state.speakers.isEmpty())
    }

    @Test
    fun transcriptLinesResolveSpeakerLabels() = runTest(dispatcher) {
        val id = repo.createSession(title = "demo", language = "en")
        repo.addSegment(sessionId = id, startMs = 0, endMs = 1_000, text = "Hello")
        repo.addSegment(sessionId = id, startMs = 3_000, endMs = 4_000, text = "Hi")
        // Apply a diarization so two speakers appear with default
        // labels. The assignments map is keyed by the segment ids
        // we just got back from `addSegment`.
        val segments = repo.getSegments(id)
        repo.applyDiarization(
            sessionId = id,
            assignments = segments.associate { it.id to it.text.hashCode().let { h -> h and 1 } },
            speakerCount = 2
        )

        val vm = SessionViewModel(sessionId = id, repository = repo)
        val state = vm.uiState.first { it.lines.size == 2 }
        assertEquals("demo", state.title)
        assertEquals(SessionStatus.DIARIZED, state.status)
        assertEquals(2, state.lines.size)
        assertEquals(0L, state.lines[0].startMs)
        assertEquals("Hello", state.lines[0].text)
        // Labels came from the speakers table; "Speaker 1" / "Speaker 2"
        // are the defaults produced by `applyDiarization`.
        assertEquals("Speaker 1", state.lines[0].speaker)
        assertEquals("Speaker 2", state.lines[1].speaker)
        assertEquals(2, state.speakers.size)
    }

    @Test
    fun transcriptLinesAreEmptyWhenSessionHasNoSegments() = runTest(dispatcher) {
        val id = repo.createSession(title = "empty", language = "en")
        val vm = SessionViewModel(sessionId = id, repository = repo)

        val state = vm.uiState.first { it.title == "empty" && !it.notFound }
        assertEquals("empty", state.title)
        assertTrue(state.lines.isEmpty())
    }

    @Test
    fun renamingSpeaker_updatesUiStateLinesLabel() = runTest(dispatcher) {
        val id = repo.createSession(title = "rename", language = "en")
        repo.addSegment(sessionId = id, startMs = 0, endMs = 1_000, text = "Hello")
        val segments = repo.getSegments(id)
        repo.applyDiarization(
            sessionId = id,
            assignments = mapOf(segments[0].id to 0),
            speakerCount = 1
        )

        val vm = SessionViewModel(sessionId = id, repository = repo)
        // First emission carries the default label "Speaker 1".
        val before = vm.uiState.first { it.lines.size == 1 && it.lines[0].speaker == "Speaker 1" }
        assertEquals("Speaker 1", before.lines[0].speaker)

        // Rename the speaker; the ViewModel must re-emit because
        // the speakers flow Room observes is part of the
        // `combine` block.
        repo.renameSpeaker(sessionId = id, speakerIndex = 0, label = "Alice")

        val after = vm.uiState.first { it.lines[0].speaker == "Alice" }
        assertEquals("Alice", after.lines[0].speaker)
        // Text content is unchanged.
        assertEquals("Hello", after.lines[0].text)
    }

    @Test
    fun unlabelledSegmentAppearsWithoutSpeakerLabel() = runTest(dispatcher) {
        val id = repo.createSession(title = "unlabelled", language = "en")
        repo.addSegment(sessionId = id, startMs = 0, endMs = 1_000, text = "Hello")
        // Deliberately do not run diarization — `speakerIndex` stays
        // null on the only segment.
        val vm = SessionViewModel(sessionId = id, repository = repo)

        val state = vm.uiState.first { it.lines.size == 1 }
        assertNull(state.lines[0].speaker)
        assertEquals("Hello", state.lines[0].text)
        assertTrue(state.speakers.isEmpty())
    }

    @Test
    fun transcriptTextExposesShareReadyString() = runTest(dispatcher) {
        val id = repo.createSession(title = "share", language = "en")
        repo.addSegment(sessionId = id, startMs = 0, endMs = 1_000, text = "Hello")
        repo.addSegment(sessionId = id, startMs = 3_000, endMs = 4_000, text = "Hi")
        val vm = SessionViewModel(sessionId = id, repository = repo)
        // Wait for the lines to be available before reading the
        // cached value.
        vm.uiState.first { it.lines.size == 2 }

        // Unlabelled segments (no diarization has been applied yet)
        // are exported with the timestamp prefix only.
        assertEquals(
            "[00:00] Hello\n[00:03] Hi",
            vm.transcriptText()
        )
        assertFalse(vm.transcriptText().isBlank())
    }

    @Test
    fun statusBannerInputs_coverDiarizingAndFailureStates() = runTest(dispatcher) {
        // DIARIZING: session row exists but no speakers/segments
        // have been re-rendered yet. The screen keys the banner off
        // `status`, so we only need the session row to land.
        val diarizingId = repo.createSession(title = "running", language = "en")
        repo.setStatus(diarizingId, SessionStatus.DIARIZING)
        val diarizingVm = SessionViewModel(sessionId = diarizingId, repository = repo)
        val diarizingState = diarizingVm.uiState.first { it.status == SessionStatus.DIARIZING }
        assertEquals(SessionStatus.DIARIZING, diarizingState.status)

        // DIARIZATION_FAILED: status flips after a diarizer error.
        val failedId = repo.createSession(title = "failed", language = "en")
        repo.setStatus(failedId, SessionStatus.DIARIZATION_FAILED)
        val failedVm = SessionViewModel(sessionId = failedId, repository = repo)
        val failedState = failedVm.uiState.first { it.status == SessionStatus.DIARIZATION_FAILED }
        assertEquals(SessionStatus.DIARIZATION_FAILED, failedState.status)
    }

    @Test
    fun renameSpeaker_updatesLinesLabel() = runTest(dispatcher) {
        val id = repo.createSession(title = "rename", language = "en")
        repo.addSegment(sessionId = id, startMs = 0, endMs = 1_000, text = "Hello")
        val segments = repo.getSegments(id)
        repo.applyDiarization(
            sessionId = id,
            assignments = mapOf(segments[0].id to 0),
            speakerCount = 1
        )

        val vm = SessionViewModel(sessionId = id, repository = repo)
        // Wait for the default label to land.
        vm.uiState.first { it.lines.size == 1 && it.lines[0].speaker == "Speaker 1" }

        // Rename through the ViewModel — this is the path the
        // screen exercises. Surrounding whitespace must not
        // survive; the speakers flow re-emits with "Alice".
        vm.renameSpeaker(index = 0, label = "  Alice ")
        val state = vm.uiState.first { it.lines[0].speaker == "Alice" }
        assertEquals("Alice", state.lines[0].speaker)
    }

    @Test
    fun renameSession_updatesTitle() = runTest(dispatcher) {
        val id = repo.createSession(title = "before", language = "en")
        repo.addSegment(sessionId = id, startMs = 0, endMs = 1_000, text = "x")
        val segments = repo.getSegments(id)
        repo.applyDiarization(
            sessionId = id,
            assignments = mapOf(segments[0].id to 0),
            speakerCount = 1
        )

        val vm = SessionViewModel(sessionId = id, repository = repo)
        vm.uiState.first { it.title == "before" }

        // Same trim rule as the repository — the ViewModel
        // delegates the validation, so the test only has to
        // prove the round-trip.
        vm.renameSession(title = "  Standup  ")
        val state = vm.uiState.first { it.title == "Standup" }
        assertEquals("Standup", state.title)
    }
}
