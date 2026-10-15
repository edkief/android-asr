package com.convoscribe.ui.home

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [HomeViewModel]. The repository is real — the
 * ViewModel's only job is reading Room through the fa&#231;ade and
 * mapping entities to rows — but Room is backed by an in-memory
 * database under Robolectric so we never touch the device.
 *
 * The ViewModel is collected with [Dispatchers.setMain] / `flow.first { ... }`
 * so the `stateIn(WhileSubscribed)` flow actually emits to a
 * subscriber. Reading `vm.sessions.value` directly would race the
 * upstream Room flow. We use `first { condition }` because the
 * StateFlow always emits its `emptyList()` initial value to a new
 * subscriber before the upstream Room query pushes the real
 * snapshot, so a single `awaitItem()` would see the empty initial.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeViewModelTest {

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
        recordingsDir = File(context.filesDir, "home-vm-test-${System.nanoTime()}")
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
    fun sessions_areEmittedNewestFirst() = runTest(dispatcher) {
        // Insert three rows in non-chronological order to prove the
        // DAO's "ORDER BY createdAt DESC" is what wins, not the
        // insert order. Sleep a few ms between calls so the
        // timestamps differ — `System.currentTimeMillis()` only
        // has ms precision and two calls in the same ms would
        // make the test order-dependent.
        val older = repo.createSession(title = "older", language = "en")
        Thread.sleep(5)
        val middle = repo.createSession(title = "middle", language = "en")
        Thread.sleep(5)
        val newest = repo.createSession(title = "newest", language = "en")

        val vm = HomeViewModel(repo)

        // Wait for the snapshot Room hands back via the ViewModel.
        // `first { ... }` skips the StateFlow's `emptyList()` initial.
        val rows = vm.sessions.first { it.size == 3 }
        assertEquals(newest, rows[0].id)
        assertEquals(middle, rows[1].id)
        assertEquals(older, rows[2].id)
        // Titles carry through verbatim.
        assertEquals("newest", rows[0].title)
        assertEquals("middle", rows[1].title)
        assertEquals("older", rows[2].title)
        // Every row has a non-empty subtitle (the date/duration
        // formatter always emits something).
        rows.forEach {
            assertTrue("subtitle for ${it.id} should be non-empty", it.subtitle.isNotEmpty())
        }
    }

    @Test
    fun sessions_inFlightHaveRecordingSuffix() = runTest(dispatcher) {
        repo.createSession(title = "live", language = "en")
        val vm = HomeViewModel(repo)

        val rows = vm.sessions.first { it.size == 1 }
        assertEquals(1, rows.size)
        assertEquals(SessionStatus.RECORDING, rows[0].status)
        assertTrue(
            "subtitle should end with the recording suffix, was '${rows[0].subtitle}'",
            rows[0].subtitle.endsWith("Recording…")
        )
    }

    @Test
    fun delete_removesTheRowFromTheNextEmission() = runTest(dispatcher) {
        val keep = repo.createSession(title = "keep", language = "en")
        Thread.sleep(5)
        val drop = repo.createSession(title = "drop", language = "en")
        val vm = HomeViewModel(repo)

        // Wait for the Room snapshot through the ViewModel before
        // issuing the delete.
        val baseline = vm.sessions.first { it.size == 2 }
        assertEquals(2, baseline.size)
        assertNotNull("drop should be present before delete", baseline.firstOrNull { it.id == drop })

        vm.delete(drop)

        // After the delete Room re-emits with one row; wait for
        // that snapshot to arrive.
        val afterDelete = vm.sessions.first { it.size == 1 }
        assertEquals(1, afterDelete.size)
        assertEquals(keep, afterDelete[0].id)
        assertNull("drop should no longer be visible", afterDelete.firstOrNull { it.id == drop })
    }

    @Test
    fun delete_isIdempotent_forUnknownId() = runTest(dispatcher) {
        val keep = repo.createSession(title = "keep", language = "en")
        val vm = HomeViewModel(repo)

        // Wait for the keep row to surface before issuing the no-op
        // delete; the flow re-emits its snapshot afterwards and the
        // keep row must still be in it.
        vm.sessions.first { it.size == 1 }

        vm.delete(9999L) // never existed

        val after = vm.sessions.first { it.size == 1 }
        assertNotNull(after.firstOrNull { it.id == keep })
    }
}
