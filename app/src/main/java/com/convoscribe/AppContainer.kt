package com.convoscribe

import android.content.Context
import com.convoscribe.models.ModelDownloader
import com.convoscribe.models.ModelStore
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

class AppContainer(val context: Context) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Where downloaded models live: `filesDir/models/<bundleId>/...`.
     * Constructed at startup so the downloader, recognizer and
     * diarizer all read paths from the same place.
     */
    val modelStore = ModelStore(File(context.filesDir, "models"))

    /**
     * Shared OkHttp client for the model downloader (and, later, the
     * LLM streaming client). Long read timeout because some model
     * downloads take minutes on slow networks; the 15 s connect timeout
     * still surfaces a dead server quickly.
     */
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Downloads the model bundles the user picked in Settings. Bound
     * to [modelStore] so the files land in the same directory the
     * recognizer and diarizer read from.
     */
    val modelDownloader = ModelDownloader(httpClient, modelStore)
}
