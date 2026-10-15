package com.convoscribe.models

import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end tests for [ModelDownloader]. Each test stands up a
 * [MockWebServer] whose URLs are baked into small [ModelBundle]s so we
 * can assert both the bytes on disk and the server-side request count.
 */
class ModelDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var store: ModelStore
    private lateinit var modelsRoot: File
    private lateinit var downloader: ModelDownloader

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        modelsRoot = tmp.newFolder("models")
        store = ModelStore(modelsRoot)
        downloader = ModelDownloader(OkHttpClient(), store)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * Build a one-bundle fixture with a single file served at
     * [bytes] bytes of [pattern]. Defaults are tuned so the file is
     * small enough to be quick but big enough to exercise the buffer
     * loop and the size check.
     */
    private fun singleFileBundle(
        fileName: String = "a.bin",
        bytes: Int = 4_096,
        pattern: (Int) -> Byte = { (it % 251).toByte() }
    ): Pair<ModelBundle, ByteArray> {
        val body = ByteArray(bytes) { idx -> pattern(idx) }
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
        val bundle = ModelBundle(
            id = "single-$fileName",
            displayName = "single",
            kind = ModelKind.SEGMENTATION,
            language = null,
            files = listOf(
                ModelFile(
                    fileName = fileName,
                    url = server.url("/$fileName").toString(),
                    sizeBytes = bytes.toLong()
                )
            )
        )
        return bundle to body
    }

    @Test
    fun twoFiles_bothInstalledWithCorrectSizes_andLastEventIsCompleted() = runTest {
        val bodyA = ByteArray(2_000) { (it % 251).toByte() }
        val bodyB = ByteArray(1_500) { ((it + 50) % 251).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(bodyA)))
        server.enqueue(MockResponse().setBody(Buffer().write(bodyB)))

        val bundle = ModelBundle(
            id = "two",
            displayName = "two",
            kind = ModelKind.SEGMENTATION,
            language = null,
            files = listOf(
                ModelFile("a.bin", server.url("/a.bin").toString(), 2_000),
                ModelFile("b.bin", server.url("/b.bin").toString(), 1_500)
            )
        )

        val events = downloader.download(listOf(bundle)).toList()

        // The flow ends with Completed.
        assertEquals(DownloadEvent.Completed, events.last())
        // Some event must be a Progress with bytesDone == bytesTotal == 3 500.
        val progress = events.filterIsInstance<DownloadEvent.Progress>().last()
        assertEquals(3_500L, progress.bytesDone)
        assertEquals(3_500L, progress.bytesTotal)
        // The two targets exist at the right size, no .part left behind.
        val targetA = File(modelsRoot, "two/a.bin")
        val targetB = File(modelsRoot, "two/b.bin")
        assertTrue(targetA.exists())
        assertEquals(2_000L, targetA.length())
        assertTrue(targetB.exists())
        assertEquals(1_500L, targetB.length())
        assertNull(File(modelsRoot, "two/a.bin.part").takeIf { it.exists() })
        assertNull(File(modelsRoot, "two/b.bin.part").takeIf { it.exists() })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun http404_throwsWithHttpCodeInMessage() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val bundle = ModelBundle(
            id = "missing",
            displayName = "missing",
            kind = ModelKind.SEGMENTATION,
            language = null,
            files = listOf(
                ModelFile("missing.bin", server.url("/missing.bin").toString(), 100)
            )
        )

        val ex = assertThrows(ModelDownloadException::class.java) {
            kotlinx.coroutines.runBlocking {
                downloader.download(listOf(bundle)).toList()
            }
        }
        assertTrue(
            "message '${ex.message}' should contain HTTP 404",
            ex.message?.contains("HTTP 404") == true
        )
    }

    @Test
    fun bodyShorterThanSize_throwsSizeMismatch_andDeletesPart() = runTest {
        val body = ByteArray(512) { (it % 251).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(body)))

        val bundle = ModelBundle(
            id = "short",
            displayName = "short",
            kind = ModelKind.SEGMENTATION,
            language = null,
            files = listOf(
                ModelFile("short.bin", server.url("/short.bin").toString(), 2_048)
            )
        )

        val ex = assertThrows(ModelDownloadException::class.java) {
            kotlinx.coroutines.runBlocking {
                downloader.download(listOf(bundle)).toList()
            }
        }
        assertTrue(
            "message '${ex.message}' should start with 'Size mismatch'",
            ex.message?.startsWith("Size mismatch") == true
        )

        val target = File(modelsRoot, "short/short.bin")
        assertTrue(!target.exists())
        val part = File(modelsRoot, "short/short.bin.part")
        assertTrue(!part.exists())
    }

    @Test
    fun alreadyInstalledFile_isNotRequested() = runTest {
        // Pre-install the only file with the exact size so the
        // downloader should skip the network entirely.
        val body = ByteArray(1_024) { (it % 251).toByte() }
        val bundle = ModelBundle(
            id = "pre",
            displayName = "pre",
            kind = ModelKind.SEGMENTATION,
            language = null,
            files = listOf(
                ModelFile(
                    fileName = "pre.bin",
                    url = server.url("/pre.bin").toString(),
                    sizeBytes = body.size.toLong()
                )
            )
        )
        val target = store.fileFor(bundle, "pre.bin")
        target.parentFile?.mkdirs()
        RandomAccessFile(target, "rw").use { it.write(body) }

        val events = downloader.download(listOf(bundle)).toList()

        assertEquals(DownloadEvent.Completed, events.last())
        assertEquals(0, server.requestCount)

        // Now flip the situation: pre-install one of two files and
        // ensure only the missing one is fetched.
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
        server.enqueue(MockResponse().setBody(Buffer().write(body)))

        val multi = ModelBundle(
            id = "mixed",
            displayName = "mixed",
            kind = ModelKind.SEGMENTATION,
            language = null,
            files = listOf(
                ModelFile("a.bin", server.url("/a.bin").toString(), body.size.toLong()),
                ModelFile("b.bin", server.url("/b.bin").toString(), body.size.toLong())
            )
        )
        val installed = store.fileFor(multi, "a.bin")
        installed.parentFile?.mkdirs()
        RandomAccessFile(installed, "rw").use { it.write(body) }

        val events2 = downloader.download(listOf(multi)).toList()

        assertEquals(DownloadEvent.Completed, events2.last())
        assertEquals(1, server.requestCount)
        assertTrue(store.fileFor(multi, "a.bin").exists())
        assertTrue(store.fileFor(multi, "b.bin").exists())
        // Spot-check the events collected in the first run too.
        assertNotNull(events)
    }
}
