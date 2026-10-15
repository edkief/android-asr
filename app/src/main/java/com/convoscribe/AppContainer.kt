package com.convoscribe

import android.content.Context
import androidx.room.Room
import com.convoscribe.asr.TranscriberFactory
import com.convoscribe.audio.MicAudioSource
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.SessionRepository
import com.convoscribe.engine.sherpa.SherpaTranscriberFactory
import com.convoscribe.models.ModelDownloader
import com.convoscribe.models.ModelStore
import com.convoscribe.recording.RecordingController
import com.convoscribe.settings.EncryptedSecretStore
import com.convoscribe.settings.SettingsRepository
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

    /**
     * Persists [com.convoscribe.settings.AppSettings] in
     * `settings` SharedPreferences and the LLM API key in an
     * encrypted file. Read by ASR (language, thread count),
     * diarization (speaker count, thresholds) and the LLM client
     * (base URL, model, API key).
     */
    val settingsRepository = SettingsRepository(
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE),
        secrets = EncryptedSecretStore(context)
    )

    /**
     * Room database holding sessions, transcript segments, speaker
     * labels and LLM chat messages. The file lives at
     * `convoscribe.db` inside the app's data directory; the
     * generated schema is committed at `app/schemas/` for future
     * migrations.
     */
    val database: AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        AppDatabase.NAME
    ).build()

    /**
     * High-level session/store façade over [database]. Recordings
     * land in `filesDir/recordings/<id>.wav`; the repository owns
     * the lifetime of those files (created on session start,
     * removed on delete).
     */
    val sessionRepository: SessionRepository = SessionRepository(
        db = database,
        recordingsDir = File(context.filesDir, "recordings").apply { mkdirs() }
    )

    /**
     * Factory the recording screen will use (in TASK-12) to ask for
     * a fresh streaming transcriber per session. Wired to the
     * sherpa-onnx engine because that's the only ASR we ship
     * today; the fun interface keeps the recording screen engine-
     * agnostic.
     */
    val transcriberFactory: TranscriberFactory = SherpaTranscriberFactory(
        store = modelStore,
        settings = settingsRepository
    )

    /**
     * Drives a recording session: opens the mic, writes the WAV,
     * feeds the streaming ASR and persists each closed utterance
     * as it arrives. The screen (TASK-12) and the foreground
     * service (TASK-13) call [RecordingController.start] /
     * [RecordingController.stop]; they observe the live state
     * through [RecordingController.state]. A single-threaded
     * `limitedParallelism(1)` dispatcher keeps the recognizer's
     * native state sequential, matching the sherpa-onnx contract.
     */
    val recordingController: RecordingController = RecordingController(
        audioSource = MicAudioSource(),
        transcriberFactory = transcriberFactory,
        store = sessionRepository,
        language = { settingsRepository.settings.value.asrLanguage },
        asrDispatcher = Dispatchers.Default.limitedParallelism(1)
    )
}
