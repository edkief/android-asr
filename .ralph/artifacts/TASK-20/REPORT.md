# TASK-20 — Home screen: session list with status and delete

## Acceptance criteria results

| Criterion | Evidence | Result |
| --- | --- | --- |
| `sessionSubtitle(0L, 754_000, TRANSCRIBED, UTC) == "1 Jan 1970, 00:00 · 12:34"` and DIARIZING adds `" · Identifying speakers…"` | `ui/home/SessionSubtitleTest.kt`: `epoch_in_utc_renders_1Jan1970_withDuration`, `diarizing_appendsIdentifyingSpeakersSuffix` (passing) | ✅ |
| HomeViewModel emits sessions newest first; `delete(id)` removes it from the emitted list | `ui/home/HomeViewModelTest.kt`: `sessions_areEmittedNewestFirst`, `delete_removesTheRowFromTheNextEmission` (passing) | ✅ |
| Route `settings` exists in `AppNavHost` (placeholder allowed) | `ui/AppNavHost.kt`: `ROUTE_SETTINGS = "settings"` + `SettingsPlaceholder` composable | ✅ |
| `source scripts/env.sh && ./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug` exits 0 | ktlintCheck, testDebugUnitTest, lintDebug, assembleDebug all `BUILD SUCCESSFUL` | ✅ |

## Test counts

- `com.convoscribe.ui.home.HomeViewModelTest`: tests=4, failures=0, errors=0
- `com.convoscribe.ui.home.SessionSubtitleTest`: tests=7, failures=0, errors=0