# Product requirements: ConvoScribe

An Android app for a **Qualcomm Dragonwing Q-6690** (QCS6690/QCM6690, Hexagon NPU) device — the
owner's test device is an Onyx Boox Tab (e-ink, build fingerprint `TabBoox:13`) —
that transcribes conversations **on-device, on the NPU**,
labels who spoke, and sends the transcript to a **remote OpenAI-compatible endpoint** for a summary
or for questions about the conversation.

## Goals

1. Live, on-device speech-to-text while recording. Two engines, **chosen by the user** in Settings
   (no automatic fallback): **NPU** (default) = Nemotron Speech Streaming EN 0.6B on the Hexagon NPU
   via sherpa-onnx's QNN backend, English only; **CPU** = Kroko streaming zipformer, English and French.
   An in-app Engine check verifies the NPU on the device.
2. After a recording stops, on-device speaker diarization labels each transcript line
   "Speaker 1", "Speaker 2", ...; the user can rename speakers.
3. Sessions (audio, transcript, speakers, summary, Q&A) persist across app restarts, in a list.
4. Summarise a session, or ask questions about it, through an OpenAI-compatible
   `/v1/chat/completions` endpoint, with answers streamed token by token.
5. v2 (last tasks): enroll known speakers by voice so they are named automatically.

## Scope

- Single-module Android app `:app`, Kotlin + Jetpack Compose, Material 3.
- ASR and diarization: **sherpa-onnx 1.13.8**. `libsherpa-onnx-jni.so` is **compiled from source**
  with `SHERPA_ONNX_ENABLE_QNN=ON` and diarization ON by `scripts/build-sherpa-onnx.sh` (TASK-3) into
  `app/src/main/jniLibs/arm64-v8a/`, together with Qualcomm QNN 2.40 runtime libraries
  (`libQnnHtp.so`, `libQnnSystem.so`, `libQnnHtpV68…V81 Skel/Stub`). The Kotlin API (package
  `com.k2fsa.sherpa.onnx`) comes from the official AAR's `classes.jar` → `app/libs/sherpa-onnx-classes-1.13.8.jar`.
  Both are built artifacts, never committed.
- Speaker diarization and embeddings run on the CPU (no NPU builds exist for those models).
- Models are **downloaded on first run** into `context.filesDir/models/<bundleId>/` (constructors get
  `assetManager = null` and absolute paths). The NPU model is a `.tar.bz2` extracted while downloading.
- Audio: 16 kHz, mono, PCM16 from `AudioRecord`, saved as a WAV per session in
  `context.filesDir/recordings/<sessionId>.wav`.
- Recording runs in a foreground service (type `microphone`).
- Storage: Room (sessions, segments, speakers, chat messages); settings in SharedPreferences;
  API key in EncryptedSharedPreferences.
- LLM: OkHttp + kotlinx.serialization, SSE streaming, configurable base URL / API key / model.

## Non-goals

- GPU inference; NPU for diarization/embedding models; French (or other languages) on the NPU.
- Automatic fallback between engines (the user picks the engine).
- Builds for x86_64/armeabi-v7a or the emulator: arm64-v8a only.
- Cloud ASR, translation, real-time (live) speaker labels, audio playback, editing transcript text.
- Play Store release, signing config, minification, analytics, accounts, sync.
- Instrumented (on-device) tests or an emulator in the loop: the owner tests on the device by hand.

## Constraints

- Package / applicationId / namespace: `com.convoscribe`. App name: `ConvoScribe`.
- minSdk 29, targetSdk 35, compileSdk 35. JDK 17. ABI packaged: `arm64-v8a` only.
- **NPU packaging rules** (do not remove): `packaging { jniLibs { useLegacyPackaging = true } }` (the
  DSP loads `libQnnHtpV*Skel.so` from the extracted native library dir);
  `<uses-native-library android:name="libcdsprpc.so" android:required="false"/>` in `<application>`;
  `OnlineRecognizer.prependAdspLibraryPath(applicationInfo.nativeLibraryDir)` before creating an NPU
  recognizer (only in `engine/sherpa/QnnRuntime.kt`).
- **`TranscriberFactory.create(language)` may take an optional `engine: String?`** (default
  `null` reads from `SettingsRepository.settings.value.asrEngine`). The engine-check runner
  passes the requested engine explicitly so it always tests the engine the user tapped, not
  whatever is currently selected in Settings. The recording controller keeps using the
  no-engine-parameter overload (still reads from settings) so a settings change while recording
  picks up the right value, as today.
- **Foreground recording may request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`** (one-time
  prompt in the recording screen, ignored if the user declines). The denial path falls back to
  the existing partial-wake-lock + foreground-microphone service behaviour and continues to work
  on every device we tested on.
- Exact versions are pinned in `gradle/libs.versions.toml` (created by TASK-2). Do not upgrade
  them, and do not add libraries a task does not name.
- **Dependency injection is manual**: `com.convoscribe.AppContainer`, created in
  `com.convoscribe.ConvoApp : Application`. No Hilt/Koin.
- **Every `com.k2fsa.sherpa.onnx` class is used only inside package `com.convoscribe.engine.sherpa`**,
  behind interfaces. Unit tests must never instantiate the engine classes (`OnlineRecognizer`,
  `OnlineStream`, `OfflineSpeakerDiarization`, `SpeakerEmbeddingExtractor`, …: their native library
  cannot load on the JVM); plain `*Config` data classes are fine. Test logic with fakes.
- Package layout under `app/src/main/java/com/convoscribe/`: `audio/`, `models/`, `asr/`,
  `diarization/`, `engine/sherpa/`, `data/` (Room), `settings/`, `llm/`, `recording/`, `service/`,
  `enginecheck/`, `ui/<screen>/` (one package per screen: Screen composable + ViewModel), `ui/theme/`.
- ViewModels take their dependencies as constructor parameters and are created with a
  `viewModelFactory { initializer { ... } }` that reads from `AppContainer`.
- Tests: JUnit 4 in `app/src/test/`; Robolectric only where Android APIs are needed (Room,
  SharedPreferences); `kotlinx-coroutines-test` + Turbine for flows; OkHttp `MockWebServer` for HTTP.
  ViewModel tests: `Dispatchers.setMain(...)` in `@Before`, `resetMain()` in `@After`; a StateFlow built
  with `stateIn(WhileSubscribed)` only updates while collected, so read it with Turbine
  (`vm.state.test { … }`) or `awaitItem`/`first { condition }`, never `.value` without a collector.
- **Toolchain**: run `source scripts/env.sh` in the shell before any Gradle command (sets
  `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_NDK`, `PATH`). JDK, SDK, NDK and Gradle are installed by
  `scripts/setup-toolchain.sh` (TASK-1); the native libraries by `scripts/build-sherpa-onnx.sh`
  (TASK-3). If `app/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so` or
  `app/libs/sherpa-onnx-classes-1.13.8.jar` is missing, run `bash scripts/build-sherpa-onnx.sh`
  (10–30 min, run in the foreground).
- **Verify command** (must pass at the end of every task):
  `source scripts/env.sh && ./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug`.
  Fix ktlint issues with `./gradlew ktlintFormat`. Run `./gradlew --stop` before ending a session.
- UI strings live in `app/src/main/res/values/strings.xml`.

## Prerequisites

- Linux x86_64 host with `cmake` ≥ 3.22, `make`, `git`, `curl`, `tar`, `bzip2`, `unzip` (present on
  this machine), about 12 GB free disk, outbound HTTPS to: `api.adoptium.net`,
  `github.com` + `objects.githubusercontent.com`, `dl.google.com`, `services.gradle.org`,
  `repo.maven.apache.org`, `maven.google.com`, `plugins.gradle.org`, `huggingface.co`.
- No credentials are needed by the loop. LLM calls are tested against `MockWebServer`.
- The owner (not the loop) needs, for manual testing: the Q-6690 device with USB debugging, and an
  OpenAI-compatible endpoint (base URL, optional API key, model name), entered in the app's Settings.
  The NPU path can only be verified on the device (Engine check screen, TASK-17).

## Model catalog (download sources, verified)

| bundleId | file(s) | source |
|---|---|---|
| `asr-en-nemotron-0.6b-560ms-qnn` (NPU, default) | archive `…-560s-android-aarch64.tar.bz2` (421 938 876 bytes) → `libencoder.so`, `libdecoder.so`, `libjoiner.so`, `tokens.txt`, `test_wavs/0.wav`; provider `qnn`, modelType `nemo_transducer`; `encoder.bin`/`decoder.bin`/`joiner.bin` QNN context files are generated on first run | `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models-qnn-2/sherpa-onnx-qnn-nemotron-speech-streaming-en-0.6b-560s-android-aarch64.tar.bz2` |
| `asr-en-kroko-2025-08-06` (CPU) | `encoder.onnx`, `decoder.onnx`, `joiner.onnx`, `tokens.txt`, `test_wavs/0.wav`; streaming, modelType `zipformer2` | `https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06/resolve/main/<file>` |
| `asr-fr-kroko-2025-08-06` (CPU) | same file names without the test wav; streaming, modelType `zipformer2` | `https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-fr-kroko-2025-08-06/resolve/main/<file>` |
| `seg-pyannote-3-0-int8` | `model.int8.onnx` | `https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.int8.onnx` |
| `emb-wespeaker-resnet34-lm` | `wespeaker_en_voxceleb_resnet34_LM.onnx` | `https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/wespeaker_en_voxceleb_resnet34_LM.onnx` (the tag really is spelled `recongition`) |

Exact byte sizes are in TASK-4 and TASK-15. Licences: Nemotron — NVIDIA Open Model License; Kroko community models
CC-BY-SA 4.0 (attribution shown in README and Settings → About), pyannote MIT, WeSpeaker Apache-2.0,
sherpa-onnx Apache-2.0; QNN runtime libraries — Qualcomm (redistributed by sherpa-onnx).

## Acceptance criteria (project)

- The verify command passes on a clean checkout after `bash scripts/setup-toolchain.sh`.
- `app/build/outputs/apk/debug/app-debug.apk` installs on the device; `bash scripts/build-apk.sh`
  produces a byte-identical versioned copy at `dist/convoscribe-<versionName>-debug.apk` (TASK-35)
  with its sha256 printed. First launch downloads the models for the selected engine with a progress
  bar (NPU ≈ 450 MB, CPU ≈ 99 MB). The setup screen shows progress > 0 within the first second
  even on a slow link (no "stuck at 0 / N MB" window).
- On the device, the Engine check reports **both engines** (NPU and CPU) working with a real-time
  factor < 1.0 regardless of which engine the user currently has selected in Settings; the first
  NPU start shows a "preparing" message and later starts are fast. The CPU button never crashes
  the app.
- Recording shows live English text with either engine (switchable in Settings); after Stop, lines get "Speaker N" labels within a few minutes
  for a 10-minute recording; speakers can be renamed; the session appears in the list after restart.
- A recording started with the screen on continues capturing audio with the screen off (the
  foreground notification remains visible, no chunks are dropped); stopping via the notification's
  Stop action lands on `TRANSCRIBED` and triggers diarization. The Onyx Boox e-ink BSP is the
  hardest target — doze / aggressive battery savers must not abort the recording.
- Diarization failures (corrupted WAV, native OOM, hang > 30 min, anything else) never crash the
  app: the session is moved to `DIARIZATION_FAILED` and the user can retry from the session list.
- With an endpoint configured, "Summarise" streams a summary and "Ask" answers questions; both
  are saved with the session.
- French can be selected in Settings and transcribes French speech (CPU engine).
- (v2) An enrolled speaker is labelled by name in a new recording.
- `docs/MANUAL_TEST.md` lists the on-device checks.

## Assumptions

- The device runs Android 13 or newer on arm64 (fingerprint says 13; minSdk 29 covers it).
- The Q-6690's Hexagon NPU is a recent generation covered by the packaged QNN 2.40 libraries
  (v68–v81) and is reachable by apps: the owner has decided to assume it works; the Engine check
  confirms it on the device. sherpa's demo app on this device resolves `libcdsprpc.so` and sets
  `ADSP_LIBRARY_PATH` without errors.
- About 1.5 GB of free storage is needed for the first NPU start (model files + QNN context
  binaries); the Engine check warns when less is available.
- The device has enough free RAM for the 592 MB Nemotron NPU encoder plus diarization models.
- sherpa-onnx's generic `android-aarch64` NPU model compiles on the device on first run (no
  precompiled context binaries exist for the Q-6690); this can take minutes and is cached.
- Speaker labels are per transcript segment (an ASR endpoint split, roughly one utterance) by
  maximum time overlap with diarization turns; a segment spanning two speakers gets one label.
- Diarization loads the whole recording in memory (`android:largeHeap="true"`); recordings up to
  ~60 minutes are supported.
- Model output casing varies; the app sentence-cases all-upper-case output.
- Speaker embedding model `wespeaker_en_voxceleb_resnet34_lm` (VoxCeleb, multilingual speakers)
  is used for both diarization and v2 enrollment.
- Model files are verified by exact byte size, not checksum.
- Background recording relies on the standard Android foreground microphone service plus a
  partial wake lock refreshed periodically; we ask the user to grant the
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` exemption (via a one-time prompt in the recording
  screen) so doze on the Onyx Boox e-ink BSP does not abort the session. The user can deny the
  prompt; the app keeps working as it does today in that case.
- Diarization "crash" symptoms today are either a native SIGSEGV (out of Kotlin's reach) or a
  hang/timeout that the user perceives as a crash. The fix layers three defences: a 30-minute
  per-session timeout, a defensive try-catch on `Throwable` that flips the status to
  `DIARIZATION_FAILED` on any recoverable error, and a watchdog that tears down the foreground
  notification even if the use case never returns.
