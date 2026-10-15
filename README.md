# ConvoScribe

ConvoScribe is an Android app for the Qualcomm Dragonwing Q-6690 that records conversations,
transcribes them on-device with sherpa-onnx (the NPU path uses the Nemotron Speech Streaming EN
0.6B model and the Hexagon NPU via QNN; the CPU path uses the Kroko streaming zipformer), labels
speakers with on-device diarization, and forwards the transcript to a user-configured
OpenAI-compatible endpoint for summaries and questions about the session.

## Build

```bash
bash scripts/setup-toolchain.sh   # one-time
source scripts/env.sh
./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug
```

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```