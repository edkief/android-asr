# Log

<!-- The agent adds an entry after each task: date, task id, brief summary. Newest first. -->

## 2026-10-15 — TASK-20 — Home screen: session list with status and delete

Added `ui/home/HomeViewModel.kt`: `data class SessionRow(id, title, subtitle, status)` and `class HomeViewModel(repository: SessionRepository)` exposing `val sessions: StateFlow<List<SessionRow>> = repository.observeAllSessions().map { it.toRow() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())` and `fun delete(id)` that launches `repository.deleteSession(id)`. Pure top-level `fun sessionSubtitle(createdAt: Long, durationMs: Long, status: SessionStatus, zone: ZoneId = ZoneId.systemDefault()): String` formats `LocalDateTime.ofInstant(Instant.ofEpochMilli(createdAt), zone)` with `DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.UK)`, appends `" · mm:ss"` (or `" · h:mm:ss"` past an hour; nothing when `durationMs <= 0`), then `" · Recording…"` / `" · Identifying speakers…"` / `" · Speaker detection failed"` for `RECORDING` / `DIARIZING` / `DIARIZATION_FAILED` (nothing for `TRANSCRIBED` / `DIARIZED`).

Rewrote `ui/home/HomeScreen.kt`: kept the ConvoScribe top bar and FAB, added an `Icons.Filled.Settings` button navigating to the new `settings` route (alongside the existing overflow `Engine check` item), and turned the body into an empty-state (`R.string.home_empty_with_hint` = "No sessions yet. Tap New recording to start.") or a `LazyColumn` of `ListItem`s whose `combinedClickable(onClick = navigate("session/{id}"), onLongClick = { pendingDeleteId = it })` opens an `AlertDialog` "Delete this session?" with `vm.delete(id)` on confirm.

`ui/AppNavHost.kt` gained `ROUTE_SETTINGS = "settings"` and a `composable(ROUTE_SETTINGS) { SettingsPlaceholder(navController) }` branch (placeholder screen with back button; real wiring lands in TASK-21). `strings.xml` gained `home_empty_with_hint`, `home_settings`, `home_delete_title`, `home_delete_message`, `home_delete_confirm`, `home_delete_cancel`, `settings_title`, `settings_back`, `settings_placeholder_body`.

Tests: `ui/home/SessionSubtitleTest.kt` (pure JUnit, 7 cases using `ZoneOffset.UTC`) — `epoch_in_utc_renders_1Jan1970_withDuration` (`754_000 ms` → `"1 Jan 1970, 00:00 · 12:34"`), `diarizing_appendsIdentifyingSpeakersSuffix`, `recording_appendsRecordingSuffix`, `diarizationFailed_appendsSpeakerDetectionFailedSuffix`, `diarized_hasNoStatusSuffix`, `zeroDuration_skipsTheDurationSlot`, `hourOrMoreDuration_rendersHoursMinutesSeconds`. `ui/home/HomeViewModelTest.kt` (Robolectric, in-memory Room, `Dispatchers.setMain`, `StandardTestDispatcher`) — 4 cases: `sessions_areEmittedNewestFirst` (three rows inserted out of order, ViewModel emits newest→middle→older), `sessions_inFlightHaveRecordingSuffix`, `delete_removesTheRowFromTheNextEmission` (wait for Room snapshot via `flow.first { it.size == n }` then issue delete), `delete_isIdempotent_forUnknownId`. Verify command `source scripts/env.sh && ./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug` exits 0.

## 2026-10-15 — TASK-19 — Diarize sessions after recording (use case + service action + crash recovery)

Added `diarization/DiarizeSessionUseCase.kt`: `class DiarizeSessionUseCase(repository, diarizerFactory, settings, readSamples = WavReader::readMono16k)` whose `suspend fun run(sessionId, onProgress = {})` flips the session to `DIARIZING`, exits straight to `DIARIZED` (via `applyDiarization` with empty map / 0 speakers) when there are zero segments without ever invoking the diarizer, otherwise reads the WAV on `Dispatchers.Default`, runs `diarizerFactory.create().use { it.diarize(samples, s.numSpeakers, s.clusterThreshold, onProgress) }`, maps `SegmentEntity` rows to `SegmentSpan`, calls `SpeakerAssigner.assign`, and writes the assignment through `repository.applyDiarization`. Any throwable except `CancellationException` is logged with `Log.w` and the session is moved to `DIARIZATION_FAILED`; `CancellationException` is re-thrown so structured concurrency stays intact.

`SessionRepository` gained `setStatus`, `setDuration`, `sessionsWithStatus` (and `SessionDao` got `updateDuration`). `AppContainer` now wires `diarizeSessionUseCase = DiarizeSessionUseCase(sessionRepository, diarizerFactory, settingsRepository)`.

`RecordingService.handleStop` now (in order) calls `controller.stop()`, promotes the service back to the foreground via `ServiceCompat.startForeground` with a new "Identifying speakers…" notification built by `buildDiarizingNotification` and `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC` (the microphone is no longer in use), runs `diarizerUseCase().run(completedId)`, releases the wake lock, stops foreground, and `stopSelf()`. New companion members: `ACTION_DIARIZE = "com.convoscribe.DIARIZE"`, `EXTRA_SESSION_ID = "com.convoscribe.EXTRA_SESSION_ID"`, and `diarizeIntent(context, sessionId)` factory. `handleDiarize(sessionId)` is the manual retry path: starts foreground (`DATA_SYNC` type), runs the use case, stops. `AndroidManifest.xml` gained `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />` and `android:foregroundServiceType="microphone|dataSync"` on the service. Two new strings: `notification_diarizing_title` ("Identifying speakers"), `notification_diarizing_text` ("Identifying speakers…").

`recording/SessionRecovery.kt`: `class SessionRecovery(private val repository: SessionRepository)` with `suspend fun recover()`. For each `RECORDING` session: if the WAV exists call `WavWriter.repairHeader(file)` and stamp `durationMs = (file.length() - 44) / 32` (coerced ≥ 0); flip to `TRANSCRIBED`. For each `DIARIZING` session: flip to `TRANSCRIBED`. Wired in `ConvoApp.onCreate` via `container.applicationScope.launch { runCatching { recovery.recover() } }`, skipping when `container.recordingController.state.value.phase != IDLE`.

Tests: `diarization/DiarizeSessionUseCaseTest.kt` (Robolectric + in-memory Room, `ScriptedDiarizerFactory` + `ThrowingDiarizer`, `readSamples = { FloatArray(16000) }`) — 3 tests: `run_withTwoSegmentsAndTwoSpeakers_resultsInDiarized` (status DIARIZED, speaker indices 0/1, "Speaker 1"/"Speaker 2", factory created once, diarizer received `numSpeakers`/`clusterThreshold` from settings and a 16 000-sample buffer), `run_withThrowingDiarizer_leavesSessionInDiarizationFailed` (status DIARIZATION_FAILED, segment indices still `null`, no speakers written, factory invoked once), `run_withZeroSegments_skipsDiarizer_andGoesStraightToDiarized` (factory created zero times). `recording/SessionRecoveryTest.kt` (Robolectric + in-memory Room) — 4 tests: `recover_recordingSessionWithCrashedWav_repairsHeader_andSetsDuration` (44+3 200-byte WAV left by a never-closed `WavWriter`, after `recover()` the header has RIFF size `36+3 200` and data size `3 200`, session is `TRANSCRIBED` with `durationMs == 100`), `recover_diarizingSession_withClosedWav_becomesTranscribed` (closed WAV is left untouched, status flipped), `recover_isIdempotent`, `recover_recordingSession_withMissingWav_becomesTranscribed_withZeroDuration`. Verify command `source scripts/env.sh && ./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug` exits 0 (154 unit tests, 0 failures, 0 errors; ktlint, lintDebug, assembleDebug all clean).

## 2026-10-15 — TASK-18 — Diarization engine and speaker-to-segment assignment

Added `diarization/SpeakerTurn.kt` (`data class SpeakerTurn(startMs, endMs, speaker)`), `diarization/Diarizer.kt` (`interface Diarizer : Closeable { fun diarize(samples, numSpeakers, threshold, onProgress): List<SpeakerTurn> }` + `fun interface DiarizerFactory { fun create(): Diarizer }`), `engine/sherpa/SherpaDiarizer.kt` (wraps `OfflineSpeakerDiarization` with `OfflineSpeakerSegmentationModelConfig(pyannote = ...)` + `SpeakerEmbeddingExtractorConfig` + `FastClusteringConfig(numClusters = -1, threshold = 0.5)`, `minDurationOn = 0.3f`, `minDurationOff = 0.5f`; per-call `sd.setConfig(sd.config.copy(clustering = FastClusteringConfig(numClusters, threshold)))` then `sd.processWithCallback(samples, callback = { done, total, _ -> onProgress(done.toFloat() / total); 0 })`; map `OfflineSpeakerDiarizationSegment(start*1000, end*1000, speaker)` → `SpeakerTurn`; `close()` calls `sd.release()`), `engine/sherpa/SherpaDiarizerFactory.kt` (`class SherpaDiarizerFactory(store, settings) : DiarizerFactory` using `ModelCatalog.SEGMENTATION.files.single().fileName = "model.int8.onnx"` and `ModelCatalog.EMBEDDING.files.single().fileName = "wespeaker_en_voxceleb_resnet34_LM.onnx"` via `store.fileFor`; throws `IllegalStateException("Model not installed: <id>")` if missing; `numThreads` from `settings.settings.value.numThreads`). `AppContainer` gained `val diarizerFactory: DiarizerFactory = SherpaDiarizerFactory(modelStore, settingsRepository)`.

`diarization/SpeakerAssigner.kt`: pure-Kotlin `object SpeakerAssigner.assign(segments: List<SegmentSpan>, turns: List<SpeakerTurn>): Assignment` with `data class SegmentSpan(id, startMs, endMs)` and `data class Assignment(speakerBySegment: Map<Long, Int?>, speakerCount: Int)`. Rules: (1) for each segment sum overlap ms per speaker across all turns, ties → speaker whose earliest overlapping turn starts first; (2) no overlap → speaker of the nearest turn if gap ≤ 1000 ms (gap measured as distance between non-overlapping half-open intervals), else `null`; (3) renumber in first-appearance order over segments sorted by `startMs` (`turns not overlapping any segment` excluded). 

Tests: `diarization/SpeakerAssignerTest.kt` (12 cases): all five acceptance-criteria tests plus `emptySegmentsYieldsEmptyAssignment`, `nearestTurnGapBoundary` (1000 ms accepted, 1001 ms rejected), `overlapIsSummedAcrossMultipleTurnsOfTheSameSpeaker`, `touchingTurnIsTreatedAsZeroGapAndAssigned`, `firstAppearanceFollowsStartMsNotInputOrder`, `turnsNotOverlappingAnySegmentDoNotInfluenceRenumbering`. `source scripts/env.sh && ./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug` exits 0.

## 2026-10-15 — TASK-17 — Engine check screen (device info, NPU/CPU self-test, real-time factor)

Added `enginecheck/EngineCheckRunner.kt` with `data class EngineCheckResult(engine, initMs, firstRun, audioMs, processMs, text, error)` (realTimeFactor getter) and `class EngineCheckRunner(factory, loadSamples, clock)` that feeds samples in 1600-sample chunks, times `factory.create("en")` and the processing loop, joins the Final texts and always closes the transcriber — exceptions surface as `error` and verdict `FAILED`.

Added `enginecheck/DeviceInfo.kt` with `data class DeviceInfo(...)` and `DeviceInfo.Companion.read(context)` (Build.SOC_* on API 31+, libcdsprpc.so presence, libQnnHtpV*Skel.so enumeration, StatFs availableBytes) plus `fun formatReport(info, results)`, `fun verdict(r)` and `fun storageWarning(freeBytes, npuPrepared)` as pure functions.

Added `ui/enginecheck/EngineCheckViewModel.kt` + `EngineCheckScreen.kt` (route `engine-check`): device-info card, two `Check NPU` / `Check CPU` buttons, per-engine result card with verdict prominent, `Copy report` / `Share report` row. Gated on `recordingController.state.phase != IDLE` ("Stop the recording first"); shows "Download the `<engine>` model first" when the bundle is missing with a button to `setup?engine=<engine>`.

`HomeScreen` gained a top-bar overflow menu (`Icons.Filled.MoreVert`) with item `Engine check` → `engine-check`. `AppNavHost` registers the route and adds `setup?engine={engine}` (optional, defaults to null). `ModelSetupScreen` accepts an `initialEngine: String? = null` so existing callers keep working.

Tests: `enginecheck/EngineCheckRunnerTest.kt` covers the 32 000-sample / 400-ms clock ⇒ audioMs 2000 / RTF 0.20 / verdict OK, the throwing-factory + throwing-accept paths (error set, verdict FAILED, transcriber closed), verdict boundaries 0.49 / 0.5 / 1.0, zero-audio RTF safety and multi-chunk feeding. `enginecheck/ReportTest.kt` covers `formatReport` containing `socModel: QCS6690`, `RTF: 0.20`, `freeStorage: 2.5 GB` for 2 500 000 000 free bytes and `storageWarning` at 1 GB / 1.5 GB / 2 GB. `source scripts/env.sh && ./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug` exits 0.

## 2026-10-15 — TASK-16 — QNN (NPU) recognizer backend and engine-aware transcriber factory

Added `engine/sherpa/SherpaAsrConfigs.kt` with `endpoint: EndpointConfig`
(rule1=2.4s trailing silence, rule2=0.8s after non-silence, rule3=20s
hard cap) and two builders:

* `cpu(modelDir, asr, numThreads): OnlineRecognizerConfig` — moved
  the body that used to live in `SherpaRecognizerBackend`'s `init`
  block here so both engines share the same FeatureConfig and
  endpoint rules. Provider `"cpu"`, modelType from `asr.modelType`
  (typically `"zipformer2"`), numThreads forwarded.
* `npu(modelDir, asr): OnlineRecognizerConfig` — provider `"qnn"`,
  numThreads `1` (QNN does its own dispatch on Hexagon cores),
  `modelType = asr.modelType` (`"nemo_transducer"` for the Nemotron
  bundle), `qnnConfig = QnnConfig(backendLib = "libQnnHtp.so",
  systemLib = "libQnnSystem.so", contextBinary = contextBinaries(modelDir).joinToString(","))`.
* `contextBinaries(modelDir): List<File> = listOf(encoder.bin,
  decoder.bin, joiner.bin)`. QNN writes these on first run and
  reuses them on subsequent starts.

`SherpaRecognizerBackend` now takes a single `config:
OnlineRecognizerConfig` instead of building one in its init block
— the engine-specific bits moved into the two configs above.

`engine/sherpa/QnnRuntime.kt` exposes `ensurePrepared(nativeLibraryDir)`:
a `@Volatile prepared: Boolean` flag behind a class-level `synchronized`
lock guards a single `OnlineRecognizer.prependAdspLibraryPath(nativeLibraryDir)`
call per process. `SherpaTranscriberFactory`'s constructor gained
`nativeLibraryDir: String`; `AppContainer` passes
`context.applicationInfo.nativeLibraryDir` (with a `?: ""` fallback
so Robolectric's null `ApplicationInfo` does not break the existing
`SettingsRepositoryTest` suite).

`asr/TranscriberFactory.kt` got a default method `fun
needsPreparation(language: String): Boolean = false`. The sherpa
implementation overrides it: returns `true` when the effective
engine is `npu` and any of
`SherpaAsrConfigs.contextBinaries(bundleDir)` is missing on disk
(the on-device compile of `.so` → `.bin` is still to be done).

`SherpaTranscriberFactory.create` now: builds the bundle via
`ModelCatalog.asrFor(language, engine)`, refuses to run if the
bundle isn't installed, then branches on the effective engine.
NPU: `QnnRuntime.ensurePrepared(nativeLibraryDir)` and try-catch
around `SherpaRecognizerBackend(config = SherpaAsrConfigs.npu(...))`;
any throwable from the recognizer construction is rewrapped as
`IllegalStateException("The NPU engine failed to start
(${e.message}). You can switch to the CPU engine in Settings.", e)`.
The CPU path builds `SherpaAsrConfigs.cpu(...)` with the user's
`numThreads` and otherwise unchanged. The temporary
`"NPU engine not available yet"` error is gone.

`RecordingState` gained `val preparingNpu: Boolean = false`
(doc-commented for the UI). `RecordingController.start` samples
`transcriberFactory.needsPreparation(language())` synchronously
before launching the session coroutine and puts both flags in the
LOADING state together; the transition to RECORDING also clears
`preparingNpu` so a stray flag never leaks past the LOADING
window. `RecordBody` takes a new `preparingNpu: Boolean`
parameter and swaps the LOADING label between
`R.string.record_phase_loading` ("Loading speech model…") and
`R.string.record_phase_preparing_npu` ("Preparing the NPU model
(first run only, this can take a few minutes)…"). New string
resource added to `values/strings.xml`.

Tests: new `engine/sherpa/SherpaAsrConfigsTest.kt` (TemporaryFolder
fixture) — `cpu_providerAndThreadCountAndModelType`,
`npu_providerModelTypeAndQnnConfig`,
`contextBinaries_returnsAllThreeAbsoluteBinFiles`,
`npu_forcesOneThread`. `RecordingControllerTest` gained a
`needsPreparation` knob on the fake factory and two tests:
`start_factoryNeedsPreparation_setsPreparingNpuInLoadingState`
(LOADING + `preparingNpu == true`, then cleared on RECORDING)
and `start_factoryDoesNotNeedPreparation_leavesPreparingNpuFalse`
(steady-state CPU path stays `false`).

`grep -rn prependAdspLibraryPath app/src/main` shows exactly one
hit (the call site in `QnnRuntime.kt`). `source scripts/env.sh &&
./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug`
exits 0 (120 tests, 0 failures, lintDebug clean).

## 2026-10-15 — TASK-15 — NPU model bundle (Nemotron EN, QNN) and user-selectable speech engine setting

Added `ASR_EN_NPU` to `models/ModelCatalog.kt`:
`id = "asr-en-nemotron-0.6b-560ms-qnn"`, displayName `"English speech
recognition (NPU)"`, kind ASR, language `"en"`, files
(`libencoder.so` 591_964_328, `libdecoder.so` 14_888_744,
`libjoiner.so` 2_107_784, `tokens.txt` 8_952,
`test_wavs/0.wav` 212_044 — note: every file's URL is `""` because the
archive path provides the bytes), `asr = AsrFiles("libencoder.so",
"libdecoder.so", "libjoiner.so", "tokens.txt", modelType =
"nemo_transducer")`, and `archive = ModelArchive(url =
"https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models-qnn-2/sherpa-onnx-qnn-nemotron-speech-streaming-en-0.6b-560s-android-aarch64.tar.bz2",
sizeBytes = 421_938_876, rootDir =
"sherpa-onnx-qnn-nemotron-speech-streaming-en-0.6b-560s-android-aarch64")`.
Added to `all` (now `ASR_EN, ASR_EN_NPU, ASR_FR, SEGMENTATION,
EMBEDDING`). Because `downloadBytes` already returns
`archive.sizeBytes` (TASK-14) `downloadBytes == 421_938_876`;
`totalBytes` sums the file sizes = `609_181_852`.

`ModelCatalog.effectiveEngine(language, engine) = "npu" iff language ==
"en" && engine == "npu" else "cpu"` (any other combination — French +
npu, unknown engine, unknown language — falls back to CPU).
`asrFor(language, engine)` returns `ASR_EN_NPU` when the effective
engine is `npu`, otherwise the CPU zipformer for the language (still
throws `IllegalArgumentException` for unsupported languages).
`requiredFor(language, engine) = listOf(asrFor(...), SEGMENTATION,
EMBEDDING)`.

`AppSettings` gained `val asrEngine: String = "npu"`.
`SettingsRepository.load()` reads it with default `"npu"` and falls back
to `"npu"` if the persisted value is not `"npu"` or `"cpu"`.
`SettingsRepository.update` validates `asrEngine` exactly like
`asrLanguage`: anything that isn't `"npu"` or `"cpu"` keeps the
previous value (no exception, no overwrite). `KEY_ASR_ENGINE =
"asrEngine"` is persisted alongside the other keys.

Updated every caller to pass `settings.asrEngine`:
`MainActivity` start destination, `HomeScreen` FAB click handler, and
`ModelSetupScreen` ViewModel factory now call
`ModelCatalog.requiredFor(settings.asrLanguage, settings.asrEngine)`.
`SherpaTranscriberFactory.create(language)` reads
`settings.settings.value.asrEngine`, calls
`ModelCatalog.asrFor(language, engine)`, and when
`effectiveEngine(language, engine) == "npu"` throws
`IllegalStateException("NPU engine not available yet")` so a missing
QNN implementation (TASK-16/17) cannot silently fall back to the CPU
zipformer.

Tests:
* `ModelCatalogTest` — added `requiredForEnglishAndCpu_…`,
  `requiredForEnglishAndNpu_listsNpuAsrSegAndEmbedding`,
  `requiredForFrenchAndNpu_startsWithFrenchCpuAsr`,
  `asrEnNpu_downloadBytesAndTotalBytes` (covers both acceptance
  criteria), `asrForEnglishAndNpu_returnsNpuBundle`,
  `effectiveEngine_returnsNpuOnlyForEnglishAndNpu`,
  `asrEnNpuFiles_referenceSharedLibraries`,
  `asrEnNpuHasArchiveRootDirMatchingReleaseName`. Updated
  `everyUrlIsHttps` to skip files with empty URLs (the NPU bundle's
  archive entries), `asrForUnknownLanguage_throws` to pass `"cpu"`,
  `all_isConsistent` to assert the NPU bundle is in `all`.
* `SettingsRepositoryTest` — `defaults_areReturnedOnEmptyPrefs`
  asserts `s.asrEngine == "npu"`; `update_valuesArePersisted…` adds
  `asrEngine = "cpu"` to the round-trip;
  `update_unsupportedAsrEngineKeepsPreviousValue` tries `"gpu"`
  against both the default and a previous `"cpu"` and asserts the
  previous value survives; `update_asrEngineRoundTripsSupportedValues`
  round-trips `"cpu"` and `"npu"` through fresh repository instances.
* `ModelSetupViewModelTest` — no direct signature changes were
  required (the test passes its own custom `ModelBundle` and does not
  call `requiredFor`/`asrFor`), but it re-ran green as part of the
  verify command.

Verify command
`source scripts/env.sh && ./gradlew ktlintCheck testDebugUnitTest
lintDebug assembleDebug` exits 0; all 114 unit tests pass.

## 2026-10-15 — TASK-14 — Archive (tar.bz2) model downloads with streaming extraction

Added Apache Commons Compress 1.27.1 to `gradle/libs.versions.toml`
(`commonsCompress = "1.27.1"`, library entry `commons-compress`) and
`implementation(libs.commons.compress)` in `app/build.gradle.kts`.

Extended `models/ModelCatalog.kt`: new `data class ModelArchive(val url,
val sizeBytes, val rootDir)` and a `val archive: ModelArchive? = null`
parameter on `ModelBundle`. Added `val downloadBytes: Long` to
`ModelBundle` so the progress denominator is the upstream archive size
for archive bundles (and `totalBytes` otherwise).

Rewrote the archive path of `ModelDownloader.download`:
`bytesTotal = sum of bundle.downloadBytes` (archive bundles contribute
their `sizeBytes`, plain bundles the sum of their `files` sizes).
Already-installed archive bundles are skipped without HTTP.
For not-installed archive bundles: GET the archive URL, surface
`HTTP <code> for <rootDir>` on non-2xx, wrap the body in a small
counting `FilterInputStream` (compressed-bytes reporter for the
progress bar) and decode via
`TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(counting, 65536)))`.
For each entry, strip a leading `./` then the archive's `rootDir + "/"`;
if the rest equals a listed `ModelFile.fileName`, write the entry to
`<target>.part`, verify `entryBytes == listed.sizeBytes` (`Size mismatch
for <fileName>: expected X, got Y`) and rename; any other entry is
skipped. After the loop, throw `ModelDownloadException("Archive is
missing <fileName>")` for any listed file that wasn't written. The
final progress event's `bytesDone` equals `bytesTotal` because the
outer loop updates `bytesDone` by `archive.sizeBytes` after
`downloadArchive` returns and emits a trailing Progress with the new
running total.

`ModelSetupViewModel` now totals bytes via
`store.missing(bundles).sumOf { it.downloadBytes }` so the UI's
progress denominator matches the wire bytes for the NPU path too.

Added a `buildTarBz2(rootDir, entries, payloads)` helper to
`models/ModelDownloaderTest.kt` and four new tests:
- `archiveBundle_withFilesAndExtra_extractsListedFiles_andSkipsExtra`:
  bundle files `a.bin` (1000) + `sub/b.bin` (500) inside an archive
  that also carries `extra.txt`; both listed files land at exact
  sizes, `extra.txt` is not written, no `.part` files survive, last
  event is `Completed`, server saw exactly one request.
- `archiveBundle_missingListedFile_throwsArchiveIsMissing`: bundle
  lists `sub/b.bin` but the archive only has `a.bin` and `extra.txt`;
  assertion is the exact message `Archive is missing sub/b.bin`.
- `archiveBundle_entrySizeMismatch_throwsSizeMismatch`: bundle says
  `a.bin` is 999 bytes but the archive entry is 1000 bytes;
  exception message starts with `Size mismatch`.
- `archiveOnlyDownload_lastProgressHasBytesDoneEqualsBytesTotal`: the
  last Progress event (one before `Completed`) has `bytesDone ==
  bytesTotal == archive.size.toLong()`.

The pre-existing downloader tests (`twoFiles...`,
`http404_throwsWithHttpCodeInMessage`,
`bodyShorterThanSize_throwsSizeMismatch_andDeletesPart`,
`alreadyInstalledFile_isNotRequested`) still pass. Full verify
`source scripts/env.sh && ./gradlew ktlintCheck testDebugUnitTest
lintDebug assembleDebug` exits 0.

## 2026-10-15 — TASK-13 — Recording screen with permission flow and live transcript

Added `ui/record/RecordViewModel.kt`: thin ViewModel taking the singleton
`RecordingController` and exposing `state` + `onNavigatedToSession()` (which calls
`controller.consumeCompleted()`). The top-level `formatElapsed(ms: Long)` formatter
sits in the same file: `mm:ss` (always two digits) under one hour, `h:mm:ss` once the
elapsed time crosses an hour, negative input clamped to 0, locale pinned to `Locale.US`.
Unit-tested by `ui/record/FormatElapsedTest.kt` with the three exact-spec assertions
(0, 61_000, 3_600_000); no Android framework touched in the test.

Added `ui/record/RecordScreen.kt` (route `record`): top-app-bar `"New recording"` with
`Icons.AutoMirrored.Filled.ArrowBack` icon button. The runtime permission flow uses
`rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions())`
asking `RECORD_AUDIO` (and `POST_NOTIFICATIONS` on API 33+); a mic denial yields the
string `Microphone permission is required to record.`, while a notification-only
denial is silently ignored and recording still starts. Start button re-checks the
permission at click time and calls
`ContextCompat.startForegroundService(context, RecordingService.startIntent(context))`;
Stop button calls `context.startService(RecordingService.stopIntent(context))`.
Body: a `displayMedium` elapsed time, phase label (`LOADING` = "Loading speech
model…", `STOPPING` = "Finishing…"), `LazyColumn` of `finalLines` with an
`animateScrollToItem(itemCount - 1)` `LaunchedEffect` to keep the latest line in
view, the partial text as the last row in italics on `onSurfaceVariant`, an error
line in `colorScheme.error`, and a 96-dp round `Button` (mic icon
`painterResource(R.drawable.ic_mic)` for IDLE, stop icon
`painterResource(R.drawable.ic_stop)` for RECORDING, disabled otherwise).

When `state.lastCompletedSessionId` becomes non-null the screen calls
`navController.navigate("session/$id") { popUpTo("record") { inclusive = true } }`
and then `vm.onNavigatedToSession()`. A `session/{id}` placeholder composable is
registered in `AppNavHost.kt` showing `"Session $id"` (replaced by the real screen
in TASK-14).

`ui/AppNavHost.kt` now has the `record` and `session/{id}` routes; the placeholder
and its route travel together in the same file. `ui/home/HomeScreen.kt` takes a
`NavHostController` parameter and renders an `ExtendedFloatingActionButton` labelled
`New recording` with a mic icon whose `onClick` reads the configured language off
`settingsRepository.settings.value.asrLanguage` and computes
`modelStore.missing(ModelCatalog.requiredFor(language))`; the FAB pushes `record` if
the list is empty, otherwise `setup`.

Added `res/drawable/ic_mic.xml` (24dp, viewport 24, mic `pathData`,
`fillColor=#FF000000`) and `res/drawable/ic_stop.xml` (24dp, viewport 24,
`pathData="M6,6h12v12H6z"`, `fillColor=#FF000000`); both used via
`painterResource(...)`. Both back arrows use `Icons.AutoMirrored.Filled.ArrowBack`.

Verify command exited 0: `./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug`.

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
