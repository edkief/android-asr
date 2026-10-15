# Log

<!-- The agent adds an entry after each task: date, task id, brief summary. Newest first. -->

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
