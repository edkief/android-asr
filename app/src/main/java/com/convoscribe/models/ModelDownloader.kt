package com.convoscribe.models

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request

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
 * HTTPS downloader for the model's [ModelBundle]s. Each call to
 * [download] resolves every file in every bundle: skips files already
 * on disk at the exact size, otherwise streams the body to a
 * `<target>.part` with an 64 KiB buffer, emits [DownloadEvent.Progress]
 * at most every 256 KiB plus once at the end of each file, then
 * renames the part to the target atomically. A wrong byte count is a
 * hard failure and the partial file is removed so the next run
 * re-downloads it.
 */
class ModelDownloader(
    private val client: OkHttpClient,
    private val store: ModelStore
) {

    /**
     * Fetch every file in [bundles], in input order, then emit
     * [DownloadEvent.Completed]. Already-installed files count toward
     * [DownloadEvent.Progress.bytesDone] but no HTTP request is made.
     * Cancellation propagates via [kotlinx.coroutines.ensureActive] in
     * the copy loop, so [Flow.collect] on a cancelled scope tears down
     * the in-flight request.
     */
    fun download(bundles: List<ModelBundle>): Flow<DownloadEvent> = flow {
        val bytesTotal = bundles.sumOf { bundle -> bundle.totalBytes }
        var bytesDone = 0L
        for (bundle in bundles) {
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

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val TICK_BYTES = 256L * 1024
    }
}
