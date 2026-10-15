## 2026-10-15 — TASK-29: Manual on-device test guide and README completion

Wrote `docs/MANUAL_TEST.md` with 18 numbered headings (16 distinct
checks per the spec, with 2 split into 2b/2c for the NPU / CPU
engine-check paths). Each item has a Steps / Expected block and a
combined troubleshooting section covers `adb logcat -s sherpa-onnx
ConvoScribe AndroidRuntime`, where models live on disk,
`/data/data/com.convoscribe/files/models`, `uses-native-library
libcdsprpc.so`, the QNN error `14001` recovery
(delete `encoder.bin`/`decoder.bin`/`joiner.bin` to force
re-preparation), and `adb shell pm clear com.convoscribe` for a
fresh start.

Added `Log.kt` with a single shared `const val TAG = "ConvoScribe"`
and pointed the existing `Log.w` calls in `DiarizeSessionUseCase`
at it (removed the private `"DiarizeSessionUseCase"` companion).
New `Log.i(TAG, …)` lines for the events the spec calls out —
recording started (`session=` + `lang=`), recording stopped
(`session=` + `durationMs=`), diarization completed
(`session=` + `durationMs=` + `speakers=`), and model download
completed (`bytesDone=` + `bundles=`). `grep 'const val TAG'` now
finds exactly one definition; `grep 'Log\.[iw](TAG'` matches 8
lines (6 real calls + 2 kdoc snippets in `Log.kt` itself).

Expanded `README.md` with a Features list, an Architecture section
that walks every package under `app/src/main/java/com/convoscribe/`
with one line of prose each (`audio`, `models`, `asr`,
`diarization`, `engine/sherpa`, `data`, `settings`, `llm`,
`recording`, `service`, `enginecheck`, `ui/<screen>/`, `ui/theme/`),
a Models table (bundleId, engine, size, files, source per row),
an LLM endpoint subsection covering `Base URL must end with /v1`
with examples for vLLM (`http://<host>:8000/v1`), llama.cpp server
and Ollama (`http://<host>:11434/v1`), a link to
`docs/MANUAL_TEST.md`, a "Model licences" section (Nemotron —
NVIDIA Open Model License, Kroko — CC-BY-SA 4.0, pyannote MIT,
WeSpeaker Apache-2.0, sherpa-onnx Apache-2.0, QNN — Qualcomm
licence), and a security note that
`android:usesCleartextTraffic="true"` is enabled on `<application>`
for plain-HTTP local LLM servers.

Added `android:usesCleartextTraffic="true"` to `<application>` in
`AndroidManifest.xml` and an About section at the bottom of
`SettingsScreen` listing each model with its licence and source URL
(NVIDIA Open Model License, CC-BY-SA 4.0, MIT, Apache-2.0,
Apache-2.0, Qualcomm). New strings `settings_section_about` plus
six `settings_about_*_name` / `_licence` pairs.

`app/build.gradle.kts` `testOptions { unitTests.isReturnDefaultValues
= true }` so pure-JUnit tests (RecordingControllerTest,
ModelDownloaderTest) no longer throw `RuntimeException("Method i in
android.util.Log not mocked")` when the new diagnostic lines run;
Robolectric tests were already fine.

Verify command
`source scripts/env.sh && ./gradlew ktlintCheck testDebugUnitTest
lintDebug assembleDebug` exits 0 (257 tests passing, ktlint clean,
lintDebug clean, assembleDebug succeeds).

## 2026-10-15 — TASK-28: French transcription: language setting, model download and per-session language

Added French as a second ASR language: `TextNormalizer` now pins its
case-mapping through `Locale.ROOT` so French accented letters round-trip
predictably; `SettingsScreen` exposes an "English" / "Français"
`SingleChoiceSegmentedButtonRow` bound to `AppSettings.asrLanguage` and
saved immediately, with the "French uses the CPU engine." hint under
the engine selector when Français is selected; `ModelSetupViewModel`'s
initial `totalBytes` now comes from `store.missing(bundles).sumOf { it.totalBytes }`
(so the on-screen "About N MB" matches what lands on disk) and the
factory already pulled `bundles = ModelCatalog.requiredFor(language,
engine)`, so the "French missing + everything else installed" path
ends up downloading the single French bundle only; the home and
`MainActivity` already read `settings.asrLanguage`, so a French session
that arrives before its bundle is downloaded routes to `setup`; the
settings screen shows "Model installed" or "Model not downloaded
(N MB) – it will be downloaded before your next recording" under the
language selector plus a "Delete French model" text button that
invokes `modelStore.delete(ModelCatalog.ASR_FR)`; the per-session
subtitle in `HomeViewModel` gains a `language` parameter that appends
`" · FR"` only when the language is not `"en"`; the obsolete
`settings_transcription_language_value` string and the
`settings_transcription_language_label` row's disabled look were both
dropped in favour of the active segmented control; six new strings
(`settings_transcription_language_en`, `…_fr`,
`…_french_uses_cpu`, `…_language_model_installed`,
`…_language_model_missing`, `…_language_delete_fr`).

`RecordingController` already threads the language into the factory
and persists it on the session; `toRow()` now passes `language` to the
formatter, the formatter carries it through, and French sessions end
with `" · FR"` while English sessions get no language suffix.

`TextNormalizer.normalize("L'ÉTÉ EST CHAUD") == "L'été est chaud"`
(uppercase É round-trips to É through `Locale.ROOT`).

`SettingsViewModel` gained `setLanguage`, `deleteFrenchModel` and a
settings-flow collector that mirrors changes onto the new
`frenchModelInstalled`, `selectedLanguageModelInstalled` and
`selectedLanguageModelMissingBytes` fields. `setLanguage` / `setEngine`
refresh those fields synchronously so the test (or a recomposition)
that reads `state.value` immediately after a click already sees the
post-update model status, without waiting for the collector to fire.

Tests (10 new, 257 total — was 247):

- `TextNormalizerTest` adds `normalize_frenchAccentedUppercaseCapitalisesFirstChar`
  for `"L'ÉTÉ EST CHAUD"`.
- `SessionSubtitleTest` adds `frenchSession_appendsUppercaseLanguageSuffix`,
  `englishSession_hasNoLanguageSuffix`,
  `frenchSessionWithRecordingStatus_appendsFrAfterStatus` and
  `emptyLanguage_doesNotAddLanguageSuffix`; existing tests
  rewritten with named arguments so they still pin the old format.
- `ModelSetupViewModelTest` adds
  `start_mixedLanguageAndEngineOnlyDownloadsMissingFrenchBundle`:
  the segmentation + embedding bundles are pre-installed via
  `RandomAccessFile.setLength` to avoid OOM on the NPU bundle's
  ≈592 MB `libencoder.so`, then with `requiredFor("fr", "npu")` we
  get `bundles[0].id == "asr-fr-kroko-2025-08-06"`,
  `store.missing(bundles).map { it.id } == ["asr-fr-kroko-2025-08-06"]`,
  `vm.state.value.totalBytes == 71_052_319L` and the downloader is
  called exactly once.
- `SettingsViewModelTest` adds `setLanguage_persistsImmediatelyAndIgnoresUnknownValues`,
  `setLanguage_toFrench_marksModelAsNotInstalled` (French bundle
  is not on disk so `selectedLanguageModelInstalled == false` and
  `selectedLanguageModelMissingBytes == 71_052_319L`),
  `deleteFrenchModel_removesBundleFromDisk_andUpdatesState`, and
  `deletingFrenchModel_whileEnglishIsActive_keepsEnglishInstalled`
  which installs `ASR_EN_NPU` (the NPU bundle, since the default
  engine is "npu") + `ASR_FR`, deletes the French bundle, and
  asserts English is still installed while French is gone.

Verify command
`source scripts/env.sh && ./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug`
exits 0 (257 tests, ktlint clean, lintDebug clean — 62 warnings
pre-existing, 0 errors — assembleDebug succeeds).

## 2026-10-15 — TASK-27: Ask tab: question/answer chat about the transcript

Added `ui/session/AskViewModel.kt` and the matching unit test
`ui/session/AskViewModelTest.kt`. The ViewModel exposes a single
`AskUiState(messages, streaming, running, error, configured)` and
mirrors the persisted QA history through
`SessionRepository.observeMessages(sessionId, QA)`. `ask(question)`
trims blank input, ignores re-entry while running, persists the
USER row first (so a crash mid-stream keeps the question visible),
and streams the response through `chatClient.stream(
PromptBuilder.question(transcript, maxChars, history, question))`
where `history` is the persisted USER/ASSISTANT rows mapped to
`LlmMessage`. On completion the streamed text is appended as an
ASSISTANT QA row. `retryLast()` resends the last USER message
without saving a second USER row, and `clearHistory()` deletes
every QA row for the session. `LlmException` is surfaced verbatim
through `error`; the USER row stays in the DB so retry can
re-send the question.

The Ask tab replaces the previous "Coming soon" placeholder with
`AskTab` in `ui/session/SessionScreen.kt`: same not-configured
state as Summary, then a `LazyColumn` of chat bubbles (USER right-
aligned with `primaryContainer`, ASSISTANT left-aligned with
`surfaceVariant`, text selectable via `SelectionContainer`), the
in-flight streamed answer as a live bubble, an inline error with
a "Retry" button, and a bottom row with an `OutlinedTextField`
("Ask about this conversation…", `ImeAction.Send`) and a send
`IconButton` that swaps to a Stop icon while a request is
running. Auto-scroll runs through `LaunchedEffect(messages.size,
streaming)` so the list follows the tail when bubbles are added
and when the streaming bubble expands. The overflow menu now has
"Clear questions" (gated on `messages.isNotEmpty()`) which opens
a confirmation dialog before invoking `clearHistory()`.

5 new tests in `AskViewModelTest` (Robolectric + in-memory Room
with synchronous executors + fake `ChatClient` +
`Dispatchers.setMain`): streaming `"Bob"` ends with a USER row
followed by an ASSISTANT row in order, both in `state.messages`
and in the DB; the second question's prompt contains the first Q
and A as history (compared against `PromptBuilder.question`
exactly); `LlmException("HTTP 500: x")` sets `error` and keeps
the persisted USER row, then `retryLast()` succeeds and produces
exactly one USER + one ASSISTANT row (no duplicate USER); blank
and whitespace-only questions do nothing — no `stream()` call, no
DB row; `clearHistory()` empties both `state.messages` and the
DB. 12 new strings (`session_ask_*`).

Verify command
`source scripts/env.sh && ./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug`
exits 0 (247 tests including 5 new, ktlint clean, lintDebug clean,
assembleDebug succeeds).

## 2026-10-15 — TASK-26: Summary tab: stream, save and regenerate the summary

Added `ui/session/SummaryViewModel.kt` and the matching unit test
`ui/session/SummaryViewModelTest.kt`. Extended
`data/SessionRepository.kt` with three new methods —
`observeMessages(sessionId, kind)` (alias for the existing `observeChat`),
`addMessage(sessionId, kind, role, content)` (stamps `createdAt = now` and
inserts), and `clearMessages(sessionId, kind)` (alias for `deleteChatKind`).
The Summary tab replaces the previous "Coming soon" placeholder with
`SummaryTab` in `ui/session/SessionScreen.kt`: a "Set up a language model
endpoint in Settings" line + Open Settings button when the endpoint is not
configured; a "Summarise" / "Regenerate" button, the streaming text or
saved summary rendered through `SelectionContainer` with a `Copy`
`TextButton` (using `LocalClipboardManager`), an inline error in error
color with a "Retry" button, and a "Stop" button + spinner while the
streaming call is in flight.

`SummaryViewModel` exposes `SummaryUiState(saved, streaming, running,
error, configured)`. `summarise()` ignores re-entry while running, builds
the transcript through `TranscriptFormatter`, sets the
`The transcript is empty.` error on a blank transcript (without calling
the client), otherwise collects `chatClient.stream(PromptBuilder.summary(...))`
appending every token to `streaming`. On completion it wipes the previous
SUMMARY row, inserts the new ASSISTANT SUMMARY row, and resets the
flags. `LlmException` surfaces the message verbatim in `error` and keeps
the previous `saved` summary. `cancel()` aborts the in-flight job. The
saved value is mirrored from the Room `chat_messages` observer so the
screen reflects the most recent persisted SUMMARY even if the VM was
recreated. 10 new strings (`session_summary_*`).

5 new tests in `SummaryViewModelTest` (Robolectric + in-memory Room with
synchronous query/transaction executors + fake `ChatClient` +
`Dispatchers.setMain`): streaming `["A","B","C"]` ends with `saved ==
"ABC"` and exactly one SUMMARY row in the DB; regenerate replaces the
previous summary (still one row); `LlmException("HTTP 500: x")` sets
`error` and leaves the previous saved summary intact in both state and
DB; an empty transcript sets `error` to `The transcript is empty.` and
records zero `stream()` calls; the messages handed to the fake equal
`PromptBuilder.summary(transcript, maxTranscriptChars)`.

Verify command
`source scripts/env.sh && ./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug`
exits 0 (242 tests including 5 new, ktlint clean, lintDebug clean,
assembleDebug succeeds).

## 2026-10-15 — TASK-25: Prompt builder and transcript truncation

Added `llm/TranscriptTruncator.kt` and `llm/PromptBuilder.kt`, with tests under
`app/src/test/java/com/convoscribe/llm/`.

`TranscriptTruncator.fit(transcript, maxChars)` returns the input unchanged when
it fits the budget; otherwise it slices the first `maxChars / 2` characters
(trimmed back to the last newline) and the last `maxChars / 2 - 80` characters
(cut forward from the first newline), joins them with a single
`\n[… N characters omitted …]\n` marker, and trims one trailing newline so the
output ends with the content of the last line.

`PromptBuilder` exposes the exact `SUMMARY_SYSTEM` and `QA_SYSTEM_PREFIX`
strings the spec calls for, plus `summary(...)` (system + user messages, the
user message starts with `Transcript:`) and `question(...)` (system message
wrapping the fitted transcript + last six user/assistant history turns +
trimmed question). The truncation budget is passed in by the caller so the
ViewModel picks it from the chat model's context window.

Tests pin every acceptance criterion: a short transcript returns unchanged;
the 10 000-char / 100-line transcript fitted to 2 000 stays in budget,
contains `characters omitted`, starts with the first line and ends with the
last, and no line is cut mid-way; `summary` returns 2 messages; `question`
keeps only the last six history turns and trims the final question; the
constants match the spec verbatim; truncation never exceeds the budget across
many budgets.

Verify command `source scripts/env.sh && ./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug`
exits 0 (ktlint clean, 237 tests including 16 new, lintDebug clean, assembleDebug
succeeds).

## 2026-10-15 — TASK-24: Settings screen (LLM endpoint, transcription, speakers)

Added the settings screen at `ui/settings/SettingsScreen.kt` and the supporting
`ui/settings/SettingsViewModel.kt`. The ViewModel holds an editable `SettingsForm`
(`baseUrl`, `apiKey`, `model`, `maxChars` as text, plus `numThreads`,
`numSpeakers`, `clusterThreshold`) initialised from `SettingsRepository.settings` plus
`apiKey()`. `save()` writes through `repo.update` (the repo's validator clamps the
numbers and trims the URL) and `repo.setApiKey`, with `maxChars` parsed as
`toIntOrNull() ?: <current persisted value>` so an empty/non-numeric field keeps the
previous value. `setEngine("npu" | "cpu")` writes the ASR engine immediately through
the same validator; unknown values are silently ignored. `testConnection()` first
saves, then calls `chatClient.listModels()` and emits either
`"Connected – N models: a, b, c"` (first five ids) or `"Failed: <message>"`; the
successful model ids are also exposed as `availableModels` so the screen can render
them as `SuggestionChip`s that fill the Model field when tapped.

The screen replaces the TASK-21 placeholder route: scrollable Column with three
sections (`Language model`, `Transcription`, `Speakers`). Language model has the
Base URL `OutlinedTextField` (`Uri` keyboard, documented `192.168.1.10:8000/v1`
placeholder), the API key field with a show/hide `PasswordVisualTransformation`
toggle, Model name, Max transcript characters (number keyboard), a "Test connection"
`OutlinedButton` that disables while running plus its result line, and the model-id
chips. Transcription is a `SingleChoiceSegmentedButtonRow` (NPU/CPU), the "Engine
check" `OutlinedButton` that navigates to `engine-check`, a CPU threads `Slider`
(1..8, 6 steps), and a disabled Language row showing "English". Speakers is a
`DropdownMenu` driven button (Automatic, 2..6) and a clustering-threshold `Slider`
(0.3..0.8, value shown to two decimals, "Lower = more speakers" helper text). A "Save"
action sits in the top bar alongside the back button; the screen also calls `save()`
on `BackHandler` and `DisposableEffect.onDispose` so the form persists on system
back / navigation away.

`ui/settings/SettingsViewModelTest` (10 new tests, Robolectric + in-memory prefs,
fake `ChatClient`):
* `save_persistsFormAndApiKeyAndReloadReadsThemBack` — second repo on same prefs
  reads the persisted fields but not the API key (proves the API key only lives in
  the SecretStore);
* `save_persistsEveryFieldIncludingApiKey` — every text and numeric field round-trips;
* `save_invalidMaxCharsKeepsPreviousValue` — empty and non-numeric strings fall back
  to the previously persisted `maxTranscriptChars`;
* `testConnection_withFakeModelsListsPopulatesTestResultAndAvailableModels` — fake
  returning `["m1","m2"]` produces `"Connected – 2 models: m1, m2"` and the same
  list in `availableModels`;
* `testConnection_withThrowingFakeSetsFailureMessage` —
  `LlmException("HTTP 401: x", httpCode = 401)` produces
  `"Failed: HTTP 401: x"` and an empty `availableModels`;
* `testConnection_stripsTrailingSlashWhenSavingBeforeTest` — the repo strips the
  trailing slash before the request goes out;
* `setEngine_persistsImmediatelyAndIgnoresUnknownValues` — `"npu"`/`"cpu"`
  are written, anything else is a no-op (both in the repo and in the state);
* `update_replacesForm`, `acknowledgeSaved_resetsSavedFlag`, and
  `initialState_seedsFromRepository` cover the rest of the surface.

Also changed `app/build.gradle.kts` `testOptions.unitTests.all { it.maxParallelForks
= 1 }` to serialise the `kotlinx-coroutines-test` `TestMainDispatcher` — without it,
`HomeViewModelTest` and `SessionViewModelTest` could race on `Dispatchers.setMain` /
`resetMain` and throw `IllegalStateException`. With forks = 1 the full suite runs in
~17 s in a worker pool.

Added 25 new strings (`settings_*`) to `strings.xml` and removed the old
`settings_placeholder_body`. Wired the route in `AppNavHost.kt` (deleted the
now-unused `SettingsPlaceholder` composable and its imports).

Verify command
`source scripts/env.sh && ./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug`
exits 0 (232 tests including 10 new, ktlint clean, lintDebug clean, assembleDebug succeeds).
