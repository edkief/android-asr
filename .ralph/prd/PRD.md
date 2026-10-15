# Product requirements: ConvoScribe

An Android app for a Qualcomm QCS/QCM6690 device that transcribes conversations **on-device**,
labels who spoke, and sends the transcript to a **remote OpenAI-compatible endpoint** for a summary
or for questions about the conversation.

## Goals

1. Live, on-device speech-to-text while recording (English first; French added later).
2. After a recording stops, on-device speaker diarization labels each transcript line
   "Speaker 1", "Speaker 2", ...; the user can rename speakers.
3. Sessions (audio, transcript, speakers, summary, Q&A) persist across app restarts, in a list.
4. Summarise a session, or ask questions about it, through an OpenAI-compatible
   `/v1/chat/completions` endpoint, with answers streamed token by token.
5. v2: enroll known speakers by voice so they are named automatically.
6. v2 (last tasks): optional "high-accuracy final transcript": after diarization, re-transcribe each
   speaker turn with the offline multilingual Parakeet TDT 0.6B v3 model (670 MB, opt-in).

## Scope

- Single-module Android app `:app`, Kotlin + Jetpack Compose, Material 3.
- ASR and diarization: **sherpa-onnx 1.13.8** Android AAR (CPU, onnxruntime), Kotlin API in
  package `com.k2fsa.sherpa.onnx`. Models are **downloaded on first run** into
  `context.filesDir/models/<bundleId>/` (constructors get `assetManager = null` and absolute paths).
- Audio: 16 kHz, mono, PCM16 from `AudioRecord`, saved as a WAV per session in
  `context.filesDir/recordings/<sessionId>.wav`.
- Recording runs in a foreground service (type `microphone`).
- Storage: Room (sessions, segments, speakers, chat messages); settings in SharedPreferences;
  API key in EncryptedSharedPreferences.
- LLM: OkHttp + kotlinx.serialization, SSE streaming, configurable base URL / API key / model.

## Non-goals

- NPU/DSP (QNN/Hexagon) acceleration, GPU inference.
- Cloud ASR, translation, real-time (live) speaker labels, audio playback, editing transcript text.
- Play Store release, signing config, minification, analytics, accounts, sync.
- Instrumented (on-device) tests or an emulator in the loop: the owner tests on the device by hand.

## Constraints

- Package / applicationId / namespace: `com.convoscribe`. App name: `ConvoScribe`.
- minSdk 29, targetSdk 35, compileSdk 35. JDK 17. ABIs packaged: `arm64-v8a`, `x86_64` only.
- Exact versions are pinned in `gradle/libs.versions.toml` (created by TASK-2). Do not upgrade
  them, and do not add libraries a task does not name.
- **Dependency injection is manual**: `com.convoscribe.AppContainer`, created in
  `com.convoscribe.ConvoApp : Application`. No Hilt/Koin.
- **Every `com.k2fsa.sherpa.onnx` class is used only inside package `com.convoscribe.engine.sherpa`**,
  behind interfaces. Unit tests must never instantiate those classes (their native library cannot
  load on the JVM). Test logic with fakes.
- Package layout under `app/src/main/java/com/convoscribe/`: `audio/`, `models/`, `asr/`,
  `diarization/`, `engine/sherpa/`, `data/` (Room), `settings/`, `llm/`, `recording/`, `service/`,
  `ui/<screen>/` (one package per screen: Screen composable + ViewModel), `ui/theme/`.
- ViewModels take their dependencies as constructor parameters and are created with a
  `viewModelFactory { initializer { ... } }` that reads from `AppContainer`.
- Tests: JUnit 4 in `app/src/test/`; Robolectric only where Android APIs are needed (Room,
  SharedPreferences); `kotlinx-coroutines-test` + Turbine for flows; OkHttp `MockWebServer` for HTTP.
  ViewModel tests: `Dispatchers.setMain(...)` in `@Before`, `resetMain()` in `@After`; a StateFlow built
  with `stateIn(WhileSubscribed)` only updates while collected, so read it with Turbine
  (`vm.state.test { … }`) or `awaitItem`/`first { condition }`, never `.value` without a collector.
- **Toolchain**: run `source scripts/env.sh` in the shell before any Gradle command (sets
  `JAVA_HOME`, `ANDROID_HOME`, `PATH`). The SDK, JDK and sherpa-onnx AAR are installed by
  `scripts/setup-toolchain.sh` (TASK-1/TASK-3); if `app/libs/sherpa-onnx-1.13.8.aar` is missing, run
  `bash scripts/fetch-sherpa-onnx.sh`.
- **Verify command** (must pass at the end of every task):
  `source scripts/env.sh && ./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug`.
  Fix ktlint issues with `./gradlew ktlintFormat`. Run `./gradlew --stop` before ending a session.
- UI strings live in `app/src/main/res/values/strings.xml`.

## Prerequisites

- Linux x86_64 host, about 6 GB free disk, outbound HTTPS to: `api.adoptium.net`,
  `github.com` + `objects.githubusercontent.com`, `dl.google.com`, `services.gradle.org`,
  `repo.maven.apache.org`, `maven.google.com`, `plugins.gradle.org`, `huggingface.co`.
- No credentials are needed by the loop. LLM calls are tested against `MockWebServer`.
- The owner (not the loop) needs, for manual testing: the 6690 device with USB debugging, and an
  OpenAI-compatible endpoint (base URL, optional API key, model name), entered in the app's Settings.

## Model catalog (download sources, verified)

| bundleId | file(s) | source |
|---|---|---|
| `asr-en-kroko-2025-08-06` | `encoder.onnx`, `decoder.onnx`, `joiner.onnx`, `tokens.txt`; streaming, modelType `zipformer2` | `https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06/resolve/main/<file>` |
| `asr-fr-kroko-2025-08-06` | same file names; streaming, modelType `zipformer2` | `https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-fr-kroko-2025-08-06/resolve/main/<file>` |
| `seg-pyannote-3-0-int8` | `model.int8.onnx` | `https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.int8.onnx` |
| `emb-wespeaker-resnet34-lm` | `wespeaker_en_voxceleb_resnet34_LM.onnx` | `https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/wespeaker_en_voxceleb_resnet34_LM.onnx` (the tag really is spelled `recongition`) |
| `asr-parakeet-tdt-0.6b-v3-int8` (v2, optional) | `encoder.int8.onnx`, `decoder.int8.onnx`, `joiner.int8.onnx`, `tokens.txt`; offline, modelType `nemo_transducer` | `https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/<file>` |

Exact byte sizes are in TASK-4 (and TASK-31 for Parakeet). Licences: Kroko community models
CC-BY-SA 4.0 (attribution shown in README and Settings → About), pyannote MIT, WeSpeaker Apache-2.0,
Parakeet v3 CC-BY-4.0, sherpa-onnx Apache-2.0.

## Acceptance criteria (project)

- The verify command passes on a clean checkout after `bash scripts/setup-toolchain.sh`.
- `app/build/outputs/apk/debug/app-debug.apk` installs on the device. First launch downloads
  ~99 MB of models with a progress bar.
- Recording shows live English text; after Stop, lines get "Speaker N" labels within a few minutes
  for a 10-minute recording; speakers can be renamed; the session appears in the list after restart.
- With an endpoint configured, "Summarise" streams a summary and "Ask" answers questions; both
  are saved with the session.
- French can be selected in Settings and transcribes French speech.
- (v2) An enrolled speaker is labelled by name in a new recording.
- (v2) With "High-accuracy final transcript" on, a finished session's lines are re-transcribed per
  speaker turn by Parakeet v3; if that fails the live transcript is kept.
- `docs/MANUAL_TEST.md` lists the on-device checks.

## Assumptions

- The device runs Android 10 or newer on arm64; CPU inference is fast enough with 4 threads.
- Speaker labels are per transcript segment (an ASR endpoint split, roughly one utterance) by
  maximum time overlap with diarization turns; a segment spanning two speakers gets one label.
- Diarization loads the whole recording in memory (`android:largeHeap="true"`); recordings up to
  ~60 minutes are supported.
- Model output casing varies; the app sentence-cases all-upper-case output.
- Speaker embedding model `wespeaker_en_voxceleb_resnet34_LM` (VoxCeleb, multilingual speakers)
  is used for both diarization and v2 enrollment.
- Model files are verified by exact byte size, not checksum.
