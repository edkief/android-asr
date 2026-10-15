# Log

<!-- The agent adds an entry after each task: date, task id, brief summary. Newest first. -->

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
