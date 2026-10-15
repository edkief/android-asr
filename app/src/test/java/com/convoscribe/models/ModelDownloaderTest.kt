package com.convoscribe.models

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
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

    /**
     * Build a `.tar.bz2` archive whose entries are a `rootDir/`
     * followed by [entries], with each entry's bytes supplied by
     * [payloads]. Extra entries (e.g. a stray `extra.txt`) can be
     * mixed in to test the skip path. Returns the compressed body
     * ready to hand to [MockWebServer].
     */
    private fun buildTarBz2(rootDir: String, entries: List<String>, payloads: List<ByteArray>): ByteArray {
        require(entries.size == payloads.size)
        val sink = ByteArrayOutputStream()
        BZip2CompressorOutputStream(sink).use { bz ->
            TarArchiveOutputStream(bz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                for (i in entries.indices) {
                    val path = entries[i]
                    val bytes = payloads[i]
                    val entry = org.apache.commons.compress.archivers.tar.TarArchiveEntry(path)
                    entry.size = bytes.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
            }
        }
        return sink.toByteArray()
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

    @Test
    fun archiveBundle_withFilesAndExtra_extractsListedFiles_andSkipsExtra() = runTest {
        val aBytes = ByteArray(1_000) { (it % 251).toByte() }
        val bBytes = ByteArray(500) { ((it + 50) % 251).toByte() }
        val extraBytes = ByteArray(50) { (it % 251).toByte() }
        val archive = buildTarBz2(
            rootDir = "root",
            entries = listOf("root/a.bin", "root/sub/b.bin", "root/extra.txt"),
            payloads = listOf(aBytes, bBytes, extraBytes)
        )
        server.enqueue(MockResponse().setBody(Buffer().write(archive)))

        val bundle = ModelBundle(
            id = "npu",
            displayName = "NPU model",
            kind = ModelKind.ASR,
            language = "en",
            files = listOf(
                ModelFile("a.bin", "", aBytes.size.toLong()),
                ModelFile("sub/b.bin", "", bBytes.size.toLong())
            ),
            archive = ModelArchive(
                url = server.url("/npu.tar.bz2").toString(),
                sizeBytes = archive.size.toLong(),
                rootDir = "root"
            )
        )

        val events = downloader.download(listOf(bundle)).toList()

        // The flow terminates with Completed.
        assertEquals(DownloadEvent.Completed, events.last())
        // Both listed files were extracted to disk at exact sizes;
        // the unlisted entry was not.
        val aTarget = File(modelsRoot, "npu/a.bin")
        val bTarget = File(modelsRoot, "npu/sub/b.bin")
        val extraTarget = File(modelsRoot, "npu/extra.txt")
        assertTrue(aTarget.exists())
        assertEquals(aBytes.size.toLong(), aTarget.length())
        assertTrue(bTarget.exists())
        assertEquals(bBytes.size.toLong(), bTarget.length())
        assertTrue(!extraTarget.exists())
        // No .part files survived the success path.
        assertNull(File(modelsRoot, "npu/a.bin.part").takeIf { it.exists() })
        assertNull(File(modelsRoot, "npu/sub/b.bin.part").takeIf { it.exists() })
        // The bytes of the extracted files match the entries exactly.
        assertTrue(aTarget.readBytes().contentEquals(aBytes))
        assertTrue(bTarget.readBytes().contentEquals(bBytes))
        // And the server got exactly one archive request.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun archiveBundle_missingListedFile_throwsArchiveIsMissing() = runTest {
        // The archive contains a.bin and extra.txt, but the bundle
        // declares sub/b.bin too (which is not present in the
        // archive); the downloader must report that explicitly.
        val aBytes = ByteArray(1_000) { (it % 251).toByte() }
        val extraBytes = ByteArray(50) { (it % 251).toByte() }
        val archive = buildTarBz2(
            rootDir = "root",
            entries = listOf("root/a.bin", "root/extra.txt"),
            payloads = listOf(aBytes, extraBytes)
        )
        server.enqueue(MockResponse().setBody(Buffer().write(archive)))

        val bundle = ModelBundle(
            id = "npu",
            displayName = "NPU model",
            kind = ModelKind.ASR,
            language = "en",
            files = listOf(
                ModelFile("a.bin", "", aBytes.size.toLong()),
                ModelFile("sub/b.bin", "", 500L)
            ),
            archive = ModelArchive(
                url = server.url("/npu.tar.bz2").toString(),
                sizeBytes = archive.size.toLong(),
                rootDir = "root"
            )
        )

        val ex = assertThrows(ModelDownloadException::class.java) {
            kotlinx.coroutines.runBlocking {
                downloader.download(listOf(bundle)).toList()
            }
        }
        assertEquals("Archive is missing sub/b.bin", ex.message)
    }

    @Test
    fun archiveBundle_entrySizeMismatch_throwsSizeMismatch() = runTest {
        // a.bin in the archive is 1000 bytes, but the bundle
        // declares 999 bytes; the size check must catch it.
        val aBytes = ByteArray(1_000) { (it % 251).toByte() }
        val bBytes = ByteArray(500) { ((it + 50) % 251).toByte() }
        val archive = buildTarBz2(
            rootDir = "root",
            entries = listOf("root/a.bin", "root/sub/b.bin"),
            payloads = listOf(aBytes, bBytes)
        )
        server.enqueue(MockResponse().setBody(Buffer().write(archive)))

        val bundle = ModelBundle(
            id = "npu",
            displayName = "NPU model",
            kind = ModelKind.ASR,
            language = "en",
            files = listOf(
                ModelFile("a.bin", "", 999L),
                ModelFile("sub/b.bin", "", bBytes.size.toLong())
            ),
            archive = ModelArchive(
                url = server.url("/npu.tar.bz2").toString(),
                sizeBytes = archive.size.toLong(),
                rootDir = "root"
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
    }

    @Test
    fun archiveOnlyDownload_lastProgressHasBytesDoneEqualsBytesTotal() = runTest {
        val aBytes = ByteArray(1_000) { (it % 251).toByte() }
        val bBytes = ByteArray(500) { ((it + 50) % 251).toByte() }
        val archive = buildTarBz2(
            rootDir = "root",
            entries = listOf("root/a.bin", "root/sub/b.bin"),
            payloads = listOf(aBytes, bBytes)
        )
        server.enqueue(MockResponse().setBody(Buffer().write(archive)))

        val bundle = ModelBundle(
            id = "npu",
            displayName = "NPU model",
            kind = ModelKind.ASR,
            language = "en",
            files = listOf(
                ModelFile("a.bin", "", aBytes.size.toLong()),
                ModelFile("sub/b.bin", "", bBytes.size.toLong())
            ),
            archive = ModelArchive(
                url = server.url("/npu.tar.bz2").toString(),
                sizeBytes = archive.size.toLong(),
                rootDir = "root"
            )
        )

        val events = downloader.download(listOf(bundle)).toList()

        // Last event is Completed, the previous one is the trailing
        // progress with bytesDone == bytesTotal == archive length.
        assertEquals(DownloadEvent.Completed, events.last())
        val lastProgress = events.dropLast(1).filterIsInstance<DownloadEvent.Progress>().last()
        assertEquals(archive.size.toLong(), lastProgress.bytesDone)
        assertEquals(archive.size.toLong(), lastProgress.bytesTotal)
    }
}
