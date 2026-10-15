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
