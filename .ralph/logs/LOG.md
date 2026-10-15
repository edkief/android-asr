# Log

<!-- The agent adds an entry after each task: date, task id, brief summary. Newest first. -->

## 2026-10-15 — TASK-12 — Foreground RecordingService (microphone type) with notification

Added `service/RecordingNotificationText.kt` with the pure formatter
`fun recordingNotificationText(elapsedMs: Long): String` returning `"Recording – mm:ss"`
under an hour and `"Recording – h:mm:ss"` once the elapsed time crosses one hour (hours
single-digit, minutes and seconds always two digits, `Locale.US` so the locale never
inverts the colon); negative input clamps to 0 so a transient timer glitch cannot throw
in the notification builder. Notification text refresh is throttled to at most once per
second in the service via `SystemClock.elapsedRealtime()`.

Added `service/RecordingService.kt` (`class RecordingService : Service()`,
`onBind` returns null, `private val scope = CoroutineScope(SupervisorJob()
+ Dispatchers.Main.immediate)`). Companion: `ACTION_START = "com.convoscribe.START"` /
`ACTION_STOP = "com.convoscribe.STOP"`, plus `fun startIntent(context)` /
`fun stopIntent(context)` factories. `onStartCommand` is a switch over the action:
`ACTION_START` creates the `recording` notification channel (`IMPORTANCE_LOW`, name
"Recording"), calls
`ServiceCompat.startForeground(this, 1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)`
(ServiceCompat itself swallows the constant below API 30, which doesn't apply since
`minSdk = 29` but the call site stays future-proof if `minSdk` is ever lowered),
acquires a partial `PowerManager.WakeLock` tagged `ConvoScribe:recording` with a 1 h
safety timeout, then `controller.start(scope)`. `ACTION_STOP` runs
`scope.launch { controller.stop(); releaseWakeLock(); ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf() }`. Returns
`START_NOT_STICKY` in every branch (an unknown action with IDLE state also stops self).

Notification built by `buildNotification(elapsedMs)`: small icon `R.drawable.ic_launcher_foreground`,
title `"Recording"`, content text from `recordingNotificationText(elapsedMs)`,
ongoing + only-alert-once + no `showWhen`, category `SERVICE`, content intent is a
`PendingIntent.getActivity(MainActivity, FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT)` with
`FLAG_ACTIVITY_SINGLE_TOP or FLAG_ACTIVITY_CLEAR_TOP`, action "Stop" attached as
`PendingIntent.getService(stopIntent(this), FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT)`.
After `startForeground` the service observes `controller.state` (launched in the
service scope) and, while a session is active, refreshes the notification text at most
once a second; if the controller emits `phase == IDLE` with a non-null `error` while
the service is alive (e.g. the controller's own catch arm failed during start), the
observer releases the wake lock, calls `ServiceCompat.stopForeground(this, STOP_FOREGROUND_REMOVE)`
and `stopSelf()` so a dead foreground notification can't outlive the service.
`onDestroy` releases the wake lock and cancels the scope (which cancels the state observer).

Updated `AndroidManifest.xml`: added `<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />`,
`FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`, `WAKE_LOCK` (the existing
`RECORD_AUDIO` already covered the mic itself), and declared
`<service android:name=".service.RecordingService" android:exported="false" android:foregroundServiceType="microphone" />`
inside `<application>`. Added five strings to `res/values/strings.xml`:
`notification_channel_recording`, `notification_channel_recording_description`,
`notification_recording_title`, `notification_recording_text`,
`notification_recording_action_stop`. The container is read with
`(application as ConvoApp).container.recordingController`.

Tests under `app/src/test/java/com/convoscribe/service/RecordingNotificationTextTest.kt`:
8 cases covering the two acceptance examples (65 000 ms → `"Recording – 01:05"`,
3 725 000 ms → `"Recording – 1:02:05"`), sub-minute zero-padding, the 59:59 → 59:59 boundary,
the 1 h == `1:00:00` crossover into `h:mm:ss`, the 2 h padding invariant ("2:03:04"), zero,
and a negative-input fallback.

Verify command `source scripts/env.sh && ./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug`
exits 0 (the merged manifest has the typed service and all five permissions;
lintDebug reports 0 errors — the only foreground-service-related lint findings are
informational `NewApi` warnings for the typed-foreground constant and the typed
`stopForeground(int)` overload, both expected since `minSdk = 29` and explained in
the source). ktlint, 81 unit tests, lint, and assembleDebug all pass.

## 2026-10-15 — TASK-11 — RecordingController: wire audio, WAV, transcriber and database
Added `recording/RecordingState.kt` with `enum RecordingPhase { IDLE, LOADING, RECORDING, STOPPING }`
and `data class RecordingState(phase, sessionId, elapsedMs, partialText, finalLines,
lastCompletedSessionId, error)`. Added `recording/RecordingStore.kt` as the persistence façade
the controller talks to (`createSession` / `audioPath` / `addSegment` / `finishRecording`); made
`SessionRepository` implement it with the new `audioPath(sessionId)` reading the `audioPath`
column stamped by `createSession` (throws if the session was deleted mid-recording). Added
`recording/RecordingController.kt`: the constructor takes `(audioSource, transcriberFactory,
store, language, asrDispatcher, clock = System::currentTimeMillis)` and exposes `state:
StateFlow<RecordingState>`. `start(scope)` is a no-op unless `phase == IDLE`; it sets
`LOADING`, then launches a coroutine in `scope` that (1) builds the transcriber with
`withContext(asrDispatcher) { transcriberFactory.create(language()) }`, (2) creates the
session with title `"Recording yyyy-MM-dd HH:mm"`, (3) opens `WavWriter(File(store.audioPath(id)))`,
(4) stashes the transcriber + WAV writer in fields so `stop()` can call `finish()` + `close()`,
and (5) flips to `RECORDING`. The collector writes each chunk to the WAV, feeds it to
`transcriber.accept(chunk.toFloatSamples())` on `asrDispatcher`, persists every `Final` via
`store.addSegment` (suspend work is done outside the `_state.update` lambda because it isn't
suspend), updates `partialText` / `finalLines` and refreshes `elapsedMs = samplesToMs(wav.samplesWritten)`.
`stop()` returns `null` unless `phase == RECORDING`; otherwise it sets `STOPPING`,
`cancelAndJoin`s the launched job, calls `transcriber.finish()` on `asrDispatcher`, closes
both handles, calls `store.finishRecording(id, samplesToMs(wav.samplesWritten))`, publishes
`RecordingState(phase = IDLE, lastCompletedSessionId = id)` and returns `id`. Errors in the
launched body (except `CancellationException`) close what was opened, call
`store.finishRecording(id, samplesToMs(wav.samplesWritten))` if a session was created, and set
`RecordingState(phase = IDLE, error = e.message ?: "Recording failed")`.
`consumeCompleted()` clears `lastCompletedSessionId`. `AppContainer` now builds
`recordingController = RecordingController(MicAudioSource(), transcriberFactory, sessionRepository,
{ settingsRepository.settings.value.asrLanguage }, Dispatchers.Default.limitedParallelism(1))`.
Added `recording/RecordingControllerTest.kt` (plain JVM, no Robolectric): a `FakeRecordingStore`
backed by the `TemporaryFolder` (auto-incrementing session ids, segments + finishCalls lists),
a `ChannelAudioSource` backed by a `Channel<ShortArray>`, an `EveryThirdChunkTranscriber` that
emits one `Final` per 3 `accept` calls (text `"line-<n>"`, timestamps `((n-3)*100, n*100)`),
and a `FakeTranscriberFactory` that records `createCount` and optionally throws. The harness
shares one `StandardTestDispatcher(testScheduler)` between the start scope (the test body's
own scope — `backgroundScope` is documented to be skipped by `advanceUntilIdle` in
kotlinx-coroutines-test 1.9.0, so the test instead uses the test body scope and calls
`controller.stop()` at the end of each test that doesn't otherwise stop) and `asrDispatcher`,
then calls `advanceUntilIdle()` after each step. Six tests cover: start → LOADING → RECORDING
with sessionId + 6 chunks → 2 finals persisted and visible in `finalLines`; stop returns the id,
phase IDLE, `lastCompletedSessionId` set, `finishRecording(id, 600)` recorded, WAV file
exactly `44 + 6*1600*2` bytes, transcriber closed; transcriber factory throwing lands in IDLE
with that message in `error` and no `finishRecording`; `start` called twice while RECORDING
does not create a second session and `stop()` while IDLE returns `null`; `consumeCompleted()`
clears `lastCompletedSessionId`; `elapsedMs` tracks `samplesToMs(samplesWritten)` accurately
across multiple chunk rounds. Verify command
`source scripts/env.sh && ./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug`
exits 0 (recording package: 6 new tests + all pre-existing 67 = 73 unit tests pass).

## 2026-10-15 — TASK-9 — Audio capture, PCM conversion, WAV writer and reader
Added `audio/AudioFormat.kt` with `object AudioSpec { SAMPLE_RATE = 16_000, CHUNK_SAMPLES = 1_600 }`
(suppressed the `standard:filename` ktlint rule because the spec pins the file name).
`audio/Pcm.kt` provides `ShortArray.toFloatSamples()` (each value / 32 768f) and
`samplesToMs(samples: Long): Long` (samples * 1000 / SAMPLE_RATE). `audio/WavWriter.kt` is a
`Closeable` `RandomAccessFile`-backed streaming writer: lays out a 44-byte PCM16 mono 16 kHz
header on init (sizes zeroed), `write(ShortArray)` appends little-endian samples past the
header, `samplesWritten` counter, `close()` seeks back to offsets 4 and 40 and patches the RIFF
chunk size (36 + dataBytes) and the data chunk size separately (they are not contiguous and a
single 8-byte write would clobber the "WAVE" fourcc). `companion object { repairHeader(file) }`
recomputes both sizes from the file length for crash-recovered files. `audio/WavReader.kt`
walks RIFF sub-chunks rather than assuming the data chunk starts at offset 44, validates
format=1, channels=1, sample rate=16 000, bits=16 with clear `IllegalArgumentException`
messages, and falls back to the bytes remaining on disk when the data size field is 0 or larger
than the available bytes. `audio/AudioSource.kt` declares `interface AudioSource { fun chunks():
Flow<ShortArray> }` and `class MicAudioSource` (`@SuppressLint("MissingPermission")` + comment
explaining the caller is responsible for the permission) using `AudioRecord(
MediaRecorder.AudioSource.VOICE_RECOGNITION, 16 kHz, MONO, PCM16, max(getMinBufferSize, CHUNK_SAMPLES*2*4))`
inside a `flow { }.flowOn(Dispatchers.IO)`; throws `IllegalStateException("Microphone unavailable")`
if `state != STATE_INITIALIZED` and surfaces `AudioRecord.read` error codes. Added
`<uses-permission android:name="android.permission.RECORD_AUDIO" />` to the manifest. Tests
under `app/src/test/java/com/convoscribe/audio/`: `PcmTest` covers `samplesToMs(16 000) == 1000`,
`CHUNK_SAMPLES` -> 100 ms, zero, all-extreme PCM16 conversions, length preservation, and
"returns a new array"; `WavRoundTripTest` (TemporaryFolder) covers the 16 000-sample file size
and the two little-endian size fields, sample round-trip (16 384 -> 0.5f, -32 768 -> -1.0f),
chunked writes reassemble in order, an empty writer produces a valid 44-byte WAV, a crash-recovered
file (zero sizes, flushed via a separate `RandomAccessFile`) is read correctly and `repairHeader`
fixes the sizes (and a second read returns the same samples), `repairHeader` throws on a
sub-header file, and `readMono16k` throws on stereo, 44.1 kHz, 24-bit, non-RIFF, and
sub-header files. Verify command
`source scripts/env.sh && ./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug`
exits 0 (67 unit tests, all pass; ktlint, lint, assembleDebug clean).

## 2026-10-15 — TASK-8 — Room database: sessions, segments, speakers, chat messages
Added `data/Enums.kt` (SessionStatus, MessageKind, MessageRole), `data/Converters.kt` (TypeConverters
that round-trip enums through their `name`), the four entities
(`SessionEntity`/`SegmentEntity`/`SpeakerEntity` with composite PK
`sessionId+speakerIndex` / `ChatMessageEntity`), four DAOs (all
non-Flow methods `suspend`, SpeakerDao.upsert is `@Upsert`),
`AppDatabase` (version 1, exportSchema = true, name `convoscribe.db`,
`@TypeConverters(Converters::class)`) and `SessionRepository` with
`createSession` (insert then stamp `audioPath = <dir>/<id>.wav`),
`addSegment`, `finishRecording` (status TRANSCRIBED, stamps
durationMs), `deleteSession` (removes the WAV file then the row,
cascades segments/speakers/chat), and `applyDiarization` wrapped in
`db.withTransaction { }` (set every segment's speaker, delete old
speakers, upsert "Speaker 1..N" rows, set status DIARIZED). Added
`ksp { arg("room.schemaLocation", "$projectDir/schemas") }` to
`app/build.gradle.kts` and committed the generated
`app/schemas/com.convoscribe.data.AppDatabase/1.json`. Wired
`AppContainer.database = Room.databaseBuilder(...).build()` and
`AppContainer.sessionRepository = SessionRepository(db,
filesDir/recordings)`. `data/SessionRepositoryTest.kt` (Robolectric,
`Room.inMemoryDatabaseBuilder(...).allowMainThreadQueries()`)
covers: createSession id/RECORDING/`/<id>.wav` audioPath;
observeForSession ordered by startMs (Turbine `test { }` and
`first()`); applyDiarization writes the speaker index, creates
`Speaker 1`..`Speaker N`, sets DIARIZED; re-running applyDiarization
replaces the speakers; deleteSession cascades segments/speakers/
chat and removes the WAV file (also: unknown id is a no-op);
finishRecording sets durationMs and TRANSCRIBED; observeAllSessions
orders by createdAt DESC. Verify command
`source scripts/env.sh && ./gradlew ktlintCheck testDebugUnitTest
lintDebug assembleDebug` exits 0 (47 unit tests, all pass; ktlint,
lint, assembleDebug clean).

## 2026-10-15 — TASK-7 — Settings repository (prefs + encrypted API key)
Added `settings/AppSettings.kt` (data class with the documented defaults), `settings/SecretStore.kt`
declaring `interface SecretStore { getApiKey(); setApiKey(value) }` plus `EncryptedSecretStore`
(lazy `EncryptedSharedPreferences.create("secrets", AES256_GCM master key,
PrefKeyEncryptionScheme.AES256_SIV, PrefValueEncryptionScheme.AES256_GCM)`, key `api_key`) and
`InMemorySecretStore` for tests/previews. `settings/SettingsRepository.kt` exposes
`val settings: StateFlow<AppSettings>` loaded at construction, `update { transform }` that runs the
coercions (`maxTranscriptChars` 2000..400000, `numThreads` 1..8, `numSpeakers` -1 or 1..10 with
0/<-1 collapsed to -1, thresholds 0.05..0.95, `llmBaseUrl` trimmed and trailing `/` stripped,
`asrLanguage` "en"/"fr" else keeps previous) then writes every field to prefs with `apply()` and
publishes the result, and `apiKey()`/`setApiKey(value)` delegating to the injected `SecretStore`.
Wired `AppContainer.settingsRepository = SettingsRepository(
context.getSharedPreferences("settings", MODE_PRIVATE), EncryptedSecretStore(context))`;
`MainActivity` now reads
`container.settingsRepository.settings.value.asrLanguage` instead of the hard-coded "en".
Added `settings/SettingsRepositoryTest.kt` (Robolectric, `ApplicationProvider`-supplied context,
unique prefs file per test, `InMemorySecretStore`) covering defaults on empty prefs, full
round-trip via `update` + fresh repository, every coercion (`numThreads` 20→8,
`maxTranscriptChars` 10→2000, `numSpeakers` 0→-1 and -42→-1 and 999→10, `clusterThreshold`
1.5→0.95, `speakerMatchThreshold` 0.001→0.05, baseUrl " http://x:8000/v1/ "→"http://x:8000/v1" and
trailing "///"→stripped, `asrLanguage` "de" after "fr" keeps "fr"), API key goes through the
`SecretStore` and never lands in the plain prefs (keys/values scanned), and the StateFlow.value
updates after `update`. Verify command
`source scripts/env.sh && ./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug` exits 0
(38 unit tests, all pass).

## 2026-10-15 — TASK-6 — First-run model setup screen and start-destination routing
Added `interface ModelDownloads { fun download(bundles: List<ModelBundle>): Flow<DownloadEvent> }` in
`models/ModelDownloader.kt`, with `ModelDownloader` now implementing it (the public
`download(...)` method picked up an `override`). Created
`ui/setup/ModelSetupViewModel.kt` exposing `data class SetupUiState(totalBytes, doneBytes, running,
error, finished)` as a `StateFlow`; `start()` is idempotent while running, collects
`downloader.download(store.missing(bundles))`, maps `Progress` → updated `doneBytes`, maps
`Completed` → `finished=true, running=false, doneBytes=totalBytes`, and catches
`ModelDownloadException` into `error` with `running=false` (so a retry is a normal
`start()` again). Created `ui/setup/ModelSetupScreen.kt` reading from
`(LocalContext.current.applicationContext as ConvoApp).container` via
`viewModel(factory = viewModelFactory { initializer { ... } })`; renders the title, an
"About N MB will be downloaded..." body, a `LinearProgressIndicator(progress = { done/total })`
and "x MB / y MB" while running, and a `Download` button (idle) or `Retry` button (error, with the
error text in `MaterialTheme.colorScheme.error`). A `LaunchedEffect(state.finished)`
invokes `onFinished()` once. `ui/AppNavHost.kt` now takes a `startDestination: String = "home"`
parameter and adds a `setup` route whose `onFinished` navigates to `home` with
`popUpTo("setup") { inclusive = true }`. `MainActivity.onCreate` computes
`if (container.modelStore.missing(ModelCatalog.requiredFor("en")).isEmpty()) "home" else "setup"`
(the language becomes a setting in a later task). Added strings
`setup_title`/`setup_body`/`setup_progress`/`setup_download`/`setup_retry` to
`res/values/strings.xml`. Wrote `ui/setup/ModelSetupViewModelTest.kt` with
`Dispatchers.setMain(StandardTestDispatcher())` in `@Before` and `resetMain()` in `@After`, using
Turbine to collect the StateFlow and a `RecordingDownloads` fake to count `download(...)` calls.
Three tests cover: (a) Progress(50,100) then Completed → intermediate `doneBytes == 50`, final
`finished == true`/`running == false`; (b) `ModelDownloadException("HTTP 500 for x")` → error
set, second `start()` clears the error and runs again; (c) `start()` called twice while
running only invokes the downloader once. Verify command
`source scripts/env.sh && ./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug`
exits 0 (26 unit tests, all pass; APK builds).

## 2026-10-15 — TASK-5 — Model downloader with progress, size verification and atomic rename
Added `<uses-permission android:name="android.permission.INTERNET" />` to `AndroidManifest.xml`,
created `models/ModelDownloader.kt` with `sealed interface DownloadEvent { Progress(bytesDone,
bytesTotal, currentFile); Completed }`, `class ModelDownloadException(message, cause)` and
`class ModelDownloader(client, store).download(bundles): Flow<DownloadEvent>` built with
`flow { }.flowOn(Dispatchers.IO)`. Streams each file to `<target>.part` with a 64 KiB buffer,
emits `Progress` at most every 256 KiB plus once at end-of-file, verifies `partFile.length()
== file.sizeBytes` (deletes on mismatch), and renames `.part` → target via `renameTo`.
Pre-installed files at exact size skip the network. Wraps `IOException` in
`ModelDownloadException`, throws `"HTTP <code> for <name>"` on non-2xx, and calls
`currentCoroutineContext().ensureActive()` each loop iteration so cancellation tears down the
in-flight request. `AppContainer` now exposes `httpClient = OkHttpClient.Builder()
.connectTimeout(15s).readTimeout(120s).build()` and `modelDownloader = ModelDownloader(httpClient,
modelStore)`. `models/ModelDownloaderTest.kt` uses `MockWebServer` + `TemporaryFolder` for four
cases: two-file install → both targets right size, no `.part`, last event `Completed`, last
Progress `bytesDone == bytesTotal`; HTTP 404 → `ModelDownloadException` with `HTTP 404`; short
body → `Size mismatch` and `.part` deleted; pre-installed file not requested
(`server.requestCount` matches missing files). Verify command
`source scripts/env.sh && ./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug`
exits 0 (23 unit tests, all pass).

## 2026-10-15 — TASK-4 — Model catalog and on-disk model store
Added `models/ModelCatalog.kt` with `ModelKind` enum,
`ModelFile`/`AsrFiles`/`ModelBundle` data classes and an `object
ModelCatalog` listing every downloadable bundle (English/French Kroko
streaming ASR, pyannote segmentation, WeSpeaker embedding) with their
exact file URLs and byte sizes. Helpers `asrFor(language)` (throws on
unsupported language) and `requiredFor(language)` (ASR + segmentation +
embedding) drive the downloader and engine-check flows. Added
`models/ModelStore.kt` exposing `bundleDir`/`fileFor`/`isInstalled`/
`missing`/`delete` over `filesDir/models/<bundleId>/`. Wired
`AppContainer.modelStore = ModelStore(File(context.filesDir, "models"))`
so the downloader, recognizer and diarizer share one store.
Unit tests: `ModelCatalogTest` (requiredFor ordering, total bytes per
language, every URL is HTTPS, `asrFor("de")` throws) and
`ModelStoreTest` (JUnit `TemporaryFolder`, files created via
`RandomAccessFile.setLength`, covers missing/wrong-size/installed/
`missing()`/`delete()`/sub-directory file names).
`source scripts/env.sh && ./gradlew ktlintCheck testDebugUnitTest
lintDebug assembleDebug` exits 0.

## 2026-10-15 — TASK-3 — sherpa-onnx 1.13.8 native libs (QNN + diarization) and Kotlin API jar
Compiled `libsherpa-onnx-jni.so` from k2-fsa/sherpa-onnx v1.13.8 with
`SHERPA_ONNX_ENABLE_QNN=ON` and `SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=ON`,
shipped the matching `libonnxruntime.so` + QNN 2.40 HTP runtime
(`libQnnHtp.so`, `libQnnSystem.so`, `libQnnHtpPrepare.so`,
`libQnnHtpV{68,69,73,75,79,81}{Skel,Stub}.so`) into
`app/src/main/jniLibs/arm64-v8a/`, extracted `classes.jar` from the
v1.13.8 AAR (sha256 pinned) into
`app/libs/sherpa-onnx-classes-1.13.8.jar`. `app/build.gradle.kts`
declares the jar + jni .so with a fail-fast check, sets
`abiFilters = [arm64-v8a]` and references the new
`app/proguard-rules.pro` (`-keep com.k2fsa.sherpa.onnx.** { *; }`).
`.gitignore` excludes `app/src/main/jniLibs/` and `app/libs/*.jar`.
Added `engine/sherpa/SherpaInfo.kt` (`VERSION` pin +
`defaultFeatureConfig()`) and a 'Native engine (sherpa-onnx + Qualcomm
QNN)' section to the README. `scripts/build-sherpa-onnx.sh` is
idempotent and self-contained (wget shim, QNN tarball layout, sha256
check on the AAR, `set -o pipefail` + `grep -q` avoided via process
substitution). `scripts/setup-toolchain.sh` now calls the sherpa build
at the end. Verify command
`source scripts/env.sh && ./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug`
exits 0; the debug APK ships `lib/arm64-v8a/libQnnHtpV73Skel.so` and no
other ABI. Second `bash scripts/build-sherpa-onnx.sh` prints
`sherpa-onnx native libs OK` and exits 0.

## 2026-10-15 — TASK-2 — Gradle/Compose skeleton with ktlint, lint and unit tests
Scaffolded the single-module `:app` Android project (Kotlin 2.0.21, AGP 8.7.3, Compose BOM 2024.12.01),
including the pinned `gradle/libs.versions.toml`, `settings.gradle.kts` (rootProject.name = "ConvoScribe",
FAIL_ON_PROJECT_REPOS), Gradle wrapper 8.11.1, `ConvoApp : Application` + manual `AppContainer`,
`MainActivity` with `enableEdgeToEdge()` + `ConvoScribeTheme` + `AppNavHost` → placeholder `HomeScreen`
("ConvoScribe" top bar, "No sessions yet" body), AndroidManifest with `<uses-native-library>` for
`libcdsprpc.so`, adaptive launcher icon (microphone vector), ktlint config (`android_studio`,
max 120 cols, Composable function-name ignore), and `SmokeTest` (`1 + 1 == 2`). Verify command
`source scripts/env.sh && ./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug` exits 0;
`app/build/outputs/apk/debug/app-debug.apk` is produced.

## 2026-10-15 — TASK-1 — Idempotent toolchain setup scripts
Added `scripts/check-prereqs.sh`, `scripts/env.sh`, and
`scripts/setup-toolchain.sh`. The latter installs JDK 17 (Adoptium),
Gradle 8.11.1, Android command-line tools, `platform-tools`,
`platforms;android-35`, `build-tools;35.0.0`, and `ndk;27.2.12479018`
under `$HOME/.local/share/convoscribe-toolchain` and `$HOME/Android/Sdk`,
plus CMake 3.27.9 (needed later by sherpa-onnx and the prereqs check).
Every step is skipped on a re-run; second run exits 0 with no curl
downloads. Acceptance: all eight hosts OK, `javac 17`, `Gradle 8.11.1`,
`sdkmanager --list_installed` shows the four packages, `$ANDROID_NDK/ndk-build` is executable.

## 2026-10-15 — TASK-10 — Streaming transcriber: endpoint segmenter, text normalizer and sherpa backend

Added `asr/TranscriptEvent.kt` (sealed interface with `Partial(text)` and `Final(text, startMs, endMs)`),
`asr/RecognizerBackend.kt` (`interface : Closeable` with `acceptWaveform/decodeAvailable/currentText/isEndpoint/reset/inputFinished`),
`asr/TextNormalizer.kt` (`object` whose `normalize(raw)` trims + collapses whitespace, lower-cases all-uppercase text and
fixes the leading capital plus the standalone `\bi\b` -> `I` substitutions, otherwise only capitalises the first
character; empty in / empty out),
`asr/StreamingTranscriber.kt` (interface + `class EndpointSegmenter(backend, sampleRate=16000)` carrying
`totalSamples` (running counter, exposed for tests) + `segmentStartSample`, suppressing consecutive identical
`Partial`s, treating blank-text endpoints as "reset + empty Partial", and emitting a Final on `finish()` when any
text is pending),
`engine/sherpa/SherpaRecognizerBackend.kt` (sherpa-onnx 1.13.8 `OnlineRecognizer(assetManager=null, OnlineRecognizerConfig(...))`
+ `recognizer.createStream()`, mapping acceptWaveform / decodeAvailable(currentText/isEndpoint/reset/inputFinished/close
to the documented sherpa calls; FeatureConfig 16 kHz / 80 dim, OnlineTransducerModelConfig for zipformer2, EndpointConfig
rules 1=2.4s, 2=0.8s after non-silence, 3=20s, greedy_search)),
`asr/TranscriberFactory.kt` (`fun interface TranscriberFactory { fun create(language: String): StreamingTranscriber }`),
`engine/sherpa/SherpaTranscriberFactory.kt` (`class(store, settings)` returning
`EndpointSegmenter(SherpaRecognizerBackend(store.bundleDir(bundle), bundle.asr!!, settings.settings.value.numThreads))`,
throwing `IllegalStateException("Model not installed: <id>")` when `!store.isInstalled(bundle)`).
Wired `AppContainer.transcriberFactory = SherpaTranscriberFactory(modelStore, settingsRepository)`.
Overloaded `audio.samplesToMs(samples: Long, sampleRate: Int = AudioSpec.SAMPLE_RATE): Long` so the segmenter can stamp
timestamps at the negotiated rate without duplicating the formula.

Tests under `app/src/test/java/com/convoscribe/asr/`:
* `TextNormalizerTest` (8 tests): the four explicit examples from the spec ("  HELLO   I AM HERE " -> "Hello I am here",
"bonjour à tous" -> "Bonjour à tous", "" -> "", "Already Mixed" unchanged) plus whitespace-only, single-letter "I",
mixed-case "inline items" (must not capitalise standalone `i`), and a no-letters smoke case.
* `EndpointSegmenterTest` (9 tests) drives a private `ScriptedBackend` (script = list of `(text, isEndpoint)` per
`acceptWaveform`, initial state blank/no-endpoint, holds last entry when the script runs out, counts `reset()` calls):
three 1600-sample chunks with the endpoint on the third (Final(Hello world, 0, 300) + Partial(""), resetCount == 1);
five chunks verifying the second segment starts at the first segment's `endMs` (Final("Foo", 300, 500)); blank-text
endpoint -> no Final but reset(); three identical partials -> exactly one emission; finish() emits Final at
`samplesToMs(totalSamples)`; finish() with a blank currentText emits only Partial(""); empty `accept` is a true no-op
(no wakeup, `totalSamples` unchanged, recognizer state untouched); `totalSamples` only advances for non-empty chunks;
segmenter.close() forwards to the backend.

Verify command
`source scripts/env.sh && ./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug`
exits 0 (84 unit tests, all pass; ktlint, lint, assembleDebug clean). Sherpa classes are only referenced from
`com.convoscribe.engine.sherpa.*` and no unit test instantiates them, so the JVM-side `System.loadLibrary("sherpa-onnx-jni")`
in `OnlineRecognizer`'s static initializer never runs in tests.
