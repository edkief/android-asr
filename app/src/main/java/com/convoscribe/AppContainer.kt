package com.convoscribe

import android.content.Context
import com.convoscribe.models.ModelStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(val context: Context) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Where downloaded models live: `filesDir/models/<bundleId>/...`.
     * Constructed at startup so the downloader, recognizer and
     * diarizer all read paths from the same place.
     */
    val modelStore = ModelStore(File(context.filesDir, "models"))
}
