# Log

<!-- The agent adds an entry after each task: date, task id, brief summary. Newest first. -->

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
