package com.convoscribe.models

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * Reason a [ModelDownloader] stopped, surfaced verbatim to the UI.
 * Keeps [ModelDownloader] free of an Android dependency while
 * distinguishing network/IO failures (HTTP 5xx, DNS, timeout, partial
 * body) from programmer errors (illegal URL, null body).
 */
class ModelDownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Per-file progress and terminal event for [ModelDownloader.download].
 * `bytesDone` is cumulative across every file in the call; for a file
 * already installed with the exact size it counts as done at the
 * start. The UI subscribes to this flow to drive a progress bar.
 */
sealed interface DownloadEvent {
    data class Progress(
        val bytesDone: Long,
        val bytesTotal: Long,
        val currentFile: String
    ) : DownloadEvent

    data object Completed : DownloadEvent
}

/**
 * Indirection over [ModelDownloader] so the ViewModel layer can be
 * unit-tested with a fake without reaching for OkHttp's MockWebServer.
 * The production implementation is [ModelDownloader]; the interface
 * stays tiny so test fakes are short.
 */
interface ModelDownloads {
    fun download(bundles: List<ModelBundle>): Flow<DownloadEvent>
}

/**
 * HTTPS downloader for the model's [ModelBundle]s. Each call to
 * [download] resolves every file in every bundle.
 *
 * - Plain bundles: skip files already on disk at the exact size,
 *   otherwise stream the body to a `<target>.part` with an 64 KiB
 *   buffer, emit [DownloadEvent.Progress] at most every 256 KiB plus
 *   once at the end of each file, verify the byte count, then rename
 *   the part to the target atomically.
 * - Archive bundles ([ModelArchive] is set): a single HTTP request
 *   downloads [ModelArchive.url] and the body is streamed through
 *   bzip2 + tar decoders. Only entries whose path matches a listed
 *   [ModelFile.fileName] are written (each verified by size); all
 *   other entries are skipped. Already-installed archive bundles are
 *   skipped entirely — no HTTP request is made.
 *
 * A wrong byte count, a missing archive entry, or any other hard
 * failure leaves the partial file (or archive contents) on disk in a
 * removed state so the next run starts over.
 */
class ModelDownloader(
    private val client: OkHttpClient,
    private val store: ModelStore
) : ModelDownloads {

    /**
     * Fetch every file in [bundles], in input order, then emit
     * [DownloadEvent.Completed]. Already-installed files count toward
     * [DownloadEvent.Progress.bytesDone] but no HTTP request is made.
     * Cancellation propagates via [kotlinx.coroutines.ensureActive] in
     * the copy loop, so [Flow.collect] on a cancelled scope tears down
     * the in-flight request.
     */
    override fun download(bundles: List<ModelBundle>): Flow<DownloadEvent> = flow {
        // The downloader's denominator matches what it actually pulls
        // over the network: `archive.sizeBytes` for archive bundles
        // (compressed body), `totalBytes` (sum of declared files) for
        // plain bundles.
        val bytesTotal = bundles.sumOf { bundle -> bundle.downloadBytes }
        var bytesDone = 0L
        for (bundle in bundles) {
            val archive = bundle.archive
            if (archive != null) {
                if (store.isInstalled(bundle)) {
                    // Skip the network entirely; an installed bundle
                    // is done before we start.
                    bytesDone += archive.sizeBytes
                    continue
                }
                bytesDone = downloadArchive(
                    bundle = bundle,
                    archive = archive,
                    startBytesDone = bytesDone,
                    bytesTotal = bytesTotal,
                    onTick = { delta, currentFile ->
                        emit(
                            DownloadEvent.Progress(
                                bytesDone = bytesDone + delta,
                                bytesTotal = bytesTotal,
                                currentFile = currentFile
                            )
                        )
                    }
                )
                emit(
                    DownloadEvent.Progress(
                        bytesDone = bytesDone,
                        bytesTotal = bytesTotal,
                        currentFile = bundle.id
                    )
                )
                continue
            }
            for (file in bundle.files) {
                val target = store.fileFor(bundle, file.fileName)
                if (target.exists() && target.length() == file.sizeBytes) {
                    // Skip the network; the file is already on disk at
                    // the right size. Count its bytes toward progress so
                    // `bytesDone == bytesTotal` after a no-op run.
                    bytesDone += file.sizeBytes
                    continue
                }
                bytesDone = downloadOne(
                    file = file,
                    target = target,
                    startBytesDone = bytesDone,
                    onTick = { delta, currentFile ->
                        emit(
                            DownloadEvent.Progress(
                                bytesDone = bytesDone + delta,
                                bytesTotal = bytesTotal,
                                currentFile = currentFile
                            )
                        )
                    }
                )
                // End-of-file progress, in addition to the 256 KiB ticks
                // emitted from inside `downloadOne`.
                emit(
                    DownloadEvent.Progress(
                        bytesDone = bytesDone,
                        bytesTotal = bytesTotal,
                        currentFile = file.fileName
                    )
                )
            }
        }
        emit(DownloadEvent.Completed)
    }.flowOn(Dispatchers.IO)

    /**
     * Streams [file.url] into `<target>.part`, verifies the byte count,
     * then renames it into place. [onTick] runs on the flow collector's
     * coroutine, so it can [emit] [DownloadEvent.Progress]. Returns the
     * new cumulative `bytesDone` for the caller.
     */
    private suspend fun downloadOne(
        file: ModelFile,
        target: File,
        startBytesDone: Long,
        onTick: suspend (delta: Long, currentFile: String) -> Unit
    ): Long {
        val partFile = File(target.parentFile, target.name + ".part")
        target.parentFile?.mkdirs()
        val response = try {
            client.newCall(Request.Builder().url(file.url).build()).execute()
        } catch (e: IOException) {
            throw ModelDownloadException(
                "Network error downloading ${file.fileName}: ${e.message}",
                e
            )
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw ModelDownloadException(
                    "HTTP ${resp.code} for ${file.fileName}"
                )
            }
            val body = resp.body
                ?: throw ModelDownloadException(
                    "Empty body for ${file.fileName}"
                )
            var fileBytes = 0L
            try {
                FileOutputStream(partFile).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var sinceLastTick = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            fileBytes += read
                            sinceLastTick += read
                            if (sinceLastTick >= TICK_BYTES) {
                                onTick(sinceLastTick, file.fileName)
                                sinceLastTick = 0L
                            }
                        }
                        // Flush any sub-tick bytes so the running
                        // total tracks the true file size after the loop.
                        if (sinceLastTick > 0L) {
                            onTick(sinceLastTick, file.fileName)
                            sinceLastTick = 0L
                        }
                    }
                }
            } catch (e: IOException) {
                partFile.delete()
                throw ModelDownloadException(
                    "IO error writing ${file.fileName}: ${e.message}",
                    e
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                partFile.delete()
                throw e
            }
            if (partFile.length() != file.sizeBytes) {
                val got = partFile.length()
                partFile.delete()
                throw ModelDownloadException(
                    "Size mismatch for ${file.fileName}: " +
                        "expected ${file.sizeBytes}, got $got"
                )
            }
            if (!partFile.renameTo(target)) {
                throw ModelDownloadException(
                    "Could not rename ${partFile.name} to ${target.name}"
                )
            }
            return startBytesDone + fileBytes
        }
    }

    /**
     * Streams [archive.url] through a bzip2 + tar pipeline, writes
     * only the entries listed in [bundle.files] (matching by fileName
     * with a leading `./` and `rootDir + "/"` stripped first) to
     * `<target>.part`, verifies each by size, then renames. Throws
     * [ModelDownloadException] for HTTP errors, size mismatches and
     * missing entries. Already-installed archive bundles must not be
     * passed here — the caller short-circuits.
     *
     * Returns the new cumulative `bytesDone` for the caller; the
     * outer loop is expected to emit the trailing [DownloadEvent.Progress]
     * whose `bytesDone` equals `bytesTotal`.
     */
    private suspend fun downloadArchive(
        bundle: ModelBundle,
        archive: ModelArchive,
        startBytesDone: Long,
        bytesTotal: Long,
        onTick: suspend (delta: Long, currentFile: String) -> Unit
    ): Long {
        val response = try {
            client.newCall(Request.Builder().url(archive.url).build()).execute()
        } catch (e: IOException) {
            throw ModelDownloadException(
                "Network error downloading ${archive.rootDir}: ${e.message}",
                e
            )
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw ModelDownloadException(
                    "HTTP ${resp.code} for ${archive.rootDir}"
                )
            }
            val body = resp.body
                ?: throw ModelDownloadException(
                    "Empty body for ${archive.rootDir}"
                )

            val listedByName = bundle.files.associateBy { it.fileName }
            val writtenFiles = mutableSetOf<String>()
            val partFiles = mutableListOf<File>()

            // A counting stream on top of the body so progress ticks
            // can be reported in compressed-bytes terms (matching the
            // declared `archive.sizeBytes`).
            var compressedRead = 0L
            var tickBasis = 0L

            val counting = object : FilterInputStream(body.byteStream()) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) compressedRead += n
                    return n
                }

                override fun read(): Int {
                    val b = super.read()
                    if (b >= 0) compressedRead += 1
                    return b
                }
            }

            try {
                TarArchiveInputStream(
                    BufferedInputStream(
                        BZip2CompressorInputStream(counting),
                        BUFFER_SIZE
                    )
                ).use { tar ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry: TarArchiveEntry = tar.nextEntry ?: break
                        val rawName = entry.name
                        val stripped = stripArchivePrefix(rawName, archive.rootDir)
                        if (stripped == null || entry.isDirectory) {
                            // Skipped entry; still emit any compressed
                            // bytes we've accumulated since the last
                            // tick while the bzip2 layer worked through
                            // its headers / data.
                            emitTickIfThreshold(
                                compressedRead = compressedRead,
                                tickBasis = tickBasis,
                                threshold = TICK_BYTES,
                                currentFile = bundle.id,
                                onTick = onTick
                            )?.let { tickBasis = it }
                            continue
                        }
                        val listed = listedByName[stripped] ?: continue
                        val target = store.fileFor(bundle, stripped)
                        val partFile = File(target.parentFile, target.name + ".part")
                        target.parentFile?.mkdirs()
                        partFiles.add(partFile)
                        val expectedSize = listed.sizeBytes
                        val entryBytes = writeTarEntry(tar, partFile)
                        if (entryBytes != expectedSize) {
                            for (p in partFiles) p.delete()
                            throw ModelDownloadException(
                                "Size mismatch for ${listed.fileName}: " +
                                    "expected $expectedSize, got $entryBytes"
                            )
                        }
                        if (!partFile.renameTo(target)) {
                            for (p in partFiles) p.delete()
                            throw ModelDownloadException(
                                "Could not rename ${partFile.name} to ${target.name}"
                            )
                        }
                        writtenFiles.add(stripped)
                        tickBasis = emitTickIfThreshold(
                            compressedRead = compressedRead,
                            tickBasis = tickBasis,
                            threshold = TICK_BYTES,
                            currentFile = stripped,
                            onTick = onTick
                        ) ?: tickBasis
                    }
                }
            } catch (e: IOException) {
                for (p in partFiles) p.delete()
                throw ModelDownloadException(
                    "IO error extracting ${archive.rootDir}: ${e.message}",
                    e
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                for (p in partFiles) p.delete()
                throw e
            }

            // The contract requires every listed file to be present
            // in the archive. A short archive yields a hard error.
            for (file in bundle.files) {
                if (!writtenFiles.contains(file.fileName)) {
                    for (p in partFiles) p.delete()
                    throw ModelDownloadException(
                        "Archive is missing ${file.fileName}"
                    )
                }
            }

            // Drain any remaining compressed bytes as a final tick so
            // the absolute `bytesDone` reported via [onTick] tracks
            // the archived bytes consumed in this call. The outer
            // caller emits the trailing Progress event with
            // bytesDone == bytesTotal == archive.sizeBytes.
            val tail = compressedRead - tickBasis
            if (tail > 0L) {
                onTick(tail, bundle.id)
            }
            return startBytesDone + archive.sizeBytes
        }
    }

    /**
     * Emits [onTick] with the delta of compressed bytes accumulated
     * since the previous tick when [threshold] is non-positive or
     * the delta has reached [threshold]; returns the new basis (or
     * `null` if nothing was emitted).
     */
    private suspend fun emitTickIfThreshold(
        compressedRead: Long,
        tickBasis: Long,
        threshold: Long,
        currentFile: String,
        onTick: suspend (delta: Long, currentFile: String) -> Unit
    ): Long? {
        val delta = compressedRead - tickBasis
        if (delta <= 0L) return null
        if (threshold <= 0L || delta >= threshold) {
            onTick(delta, currentFile)
            return compressedRead
        }
        return null
    }

    /**
     * Reads the rest of [tar]'s current entry into [part] and returns
     * the byte count written.
     */
    private fun writeTarEntry(tar: TarArchiveInputStream, part: File): Long {
        var bytes = 0L
        FileOutputStream(part).use { out ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = tar.read(buffer)
                if (read == -1) break
                out.write(buffer, 0, read)
                bytes += read
            }
        }
        return bytes
    }

    /**
     * Strip a leading `./` and a leading `<rootDir>/` from a tar
     * entry name. Returns the relative file name if it matches the
     * expected root, `null` otherwise. Directory markers (trailing
     * `/`) are also rejected.
     */
    private fun stripArchivePrefix(rawName: String, rootDir: String): String? {
        var name = rawName
        if (name.startsWith("./")) name = name.substring(2)
        if (name.endsWith("/")) return null
        val prefix = "$rootDir/"
        if (name.startsWith(prefix)) {
            name = name.substring(prefix.length)
        } else if (name == rootDir) {
            return null
        }
        return name
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val TICK_BYTES = 256L * 1024
    }
}
