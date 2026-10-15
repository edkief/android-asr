# TASK-26: Summary tab: stream, save and regenerate the summary

## Files added
- `app/src/main/java/com/convoscribe/ui/session/SummaryViewModel.kt`
- `app/src/test/java/com/convoscribe/ui/session/SummaryViewModelTest.kt`

## Files modified
- `app/src/main/java/com/convoscribe/data/SessionRepository.kt` — added `observeMessages`, `addMessage`, `clearMessages` methods
- `app/src/main/java/com/convoscribe/ui/session/SessionScreen.kt` — replaced `ComingSoonTab` for the Summary tab with `SummaryTab`; added the `SummaryViewModel` viewModel in the screen; added the new layout (configured placeholder, streaming + Stop, saved + Copy, error + Retry)
- `app/src/main/res/values/strings.xml` — added `session_summary_*` strings

## Behaviour summary
- The Summary tab shows a placeholder when the LLM endpoint is not configured (no `llmBaseUrl` or `llmModel`), with a button navigating to Settings.
- Otherwise it exposes "Summarise" (or "Regenerate" when a previous summary is saved). While running, it shows the streaming text and a "Stop" button + spinner; the saved summary is rendered through `SelectionContainer` with a `Copy` `TextButton` (no markdown, plain text).
- Errors are shown in error color with a "Retry" button. `LlmException` keeps the previous summary intact.

## Verify command
`source scripts/env.sh && ./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug` exits 0
(242 tests, including 5 new for this task; ktlint clean; lintDebug clean; assembleDebug succeeds).
