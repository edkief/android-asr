# Log

<!-- The agent adds an entry after each task: date, task id, brief summary. Newest first. -->

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
