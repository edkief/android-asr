# TASK-24 — Settings screen

## Files added
* `app/src/main/java/com/convoscribe/ui/settings/SettingsViewModel.kt`
* `app/src/main/java/com/convoscribe/ui/settings/SettingsScreen.kt`
* `app/src/test/java/com/convoscribe/ui/settings/SettingsViewModelTest.kt`

## Files modified
* `app/src/main/java/com/convoscribe/ui/AppNavHost.kt` — route now points at
  `SettingsScreen`; deleted the `SettingsPlaceholder` composable and its imports
* `app/src/main/res/values/strings.xml` — 25 new `settings_*` strings, removed
  `settings_placeholder_body`
* `app/build.gradle.kts` — serialise unit tests
  (`testOptions.unitTests.all { it.maxParallelForks = 1 }`) because the
  `kotlinx-coroutines-test` `TestMainDispatcher` is a global singleton and
  JUnit-parallel `HomeViewModelTest` / `SessionViewModelTest` were racing on
  `Dispatchers.setMain` / `resetMain`.

## ViewModel surface

| Member | Purpose |
| --- | --- |
| `SettingsForm(form)` | Editable state — strings for editable text, ints / floats for sliders and dropdown |
| `SettingsForm.from(settings, apiKey)` | Build the initial form from the repo + the SecretStore |
| `SettingsUiState(form, asrEngine, testing, testResult, availableModels, saved)` | Snapshot rendered by the screen |
| `state: StateFlow<SettingsUiState>` | Always-available flow (no `WhileSubscribed`, the settings screen is short-lived and lives across config changes anyway) |
| `update(form)` | Replace the form wholesale (every keystroke calls this) |
| `save()` | Persist the form through `repo.update` + `repo.setApiKey`, parse `maxChars` via `toIntOrNull() ?: <previous persisted value>`, set `saved = true` |
| `setEngine("npu" \| "cpu")` | Write the ASR engine immediately (unknown values are no-ops) |
| `testConnection()` | Save, then call `chatClient.listModels()`; success → `"Connected – N models: a, b, c"` (first 5) + `availableModels`; failure → `"Failed: <message>"` (the `LlmException.message`) |
| `acknowledgeSaved()` | Clear the transient `saved` flag after the screen has shown it once |

## Screen layout

Scrollable `Column`, sections separated by `SectionHeader` text:

1. **Language model**
   * `OutlinedTextField` Base URL (`Uri` keyboard, placeholder
     `http://192.168.1.10:8000/v1`)
   * `OutlinedTextField` API key, `PasswordVisualTransformation` toggle (show /
     hide rendered as a `Text` label since `material-icons-core` is the only
     icons module we ship, no `Visibility` / `VisibilityOff` glyph)
   * `OutlinedTextField` Model name
   * `OutlinedTextField` Max transcript characters (number keyboard)
   * `OutlinedButton` "Test connection" (disabled while `testing`) + result text
     in `error` colour when it starts with `Failed`
   * `FlowRow` of `SuggestionChip`s when `availableModels` is non-empty — tapping
     fills the Model field
2. **Transcription**
   * `SingleChoiceSegmentedButtonRow` "NPU (Nemotron)" / "CPU (Kroko)" bound to
     `AppSettings.asrEngine` and saved immediately by the segmented button
   * Helper text "NPU uses the Qualcomm Hexagon processor; CPU is slower to
     start but works everywhere."
   * `OutlinedButton` "Engine check" → `navController.navigate("engine-check")`
   * `Slider` CPU threads 1..8 (6 steps)
   * Disabled Row "Language / English"
3. **Speakers**
   * `OutlinedButton` showing the current number of speakers or "Automatic";
     tap to open a `DropdownMenu` with Automatic, 2, 3, 4, 5, 6
   * `Slider` Clustering threshold 0.3..0.8 (value to 2 decimals) and the
     "Lower = more speakers" helper text

The top bar has a back arrow and a Save tick icon. The `BackHandler` and
`DisposableEffect.onDispose` also call `save()` so the form persists when the
user just hits the system back without tapping Save.

## Tests
10 tests in `SettingsViewModelTest`, all Robolectric (so the `SharedPreferences`
in `SettingsRepository` works) and using `InMemorySecretStore` so the Android
Keystore is never touched. `ChatClient` is a `FakeChatClient` that returns a
caller-supplied model list or throws a caller-supplied `LlmException`.
