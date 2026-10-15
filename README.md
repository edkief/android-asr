# ConvoScribe

ConvoScribe is an Android app for the Qualcomm Dragonwing Q-6690 that records conversations,
transcribes them on-device with sherpa-onnx (the NPU path uses the Nemotron Speech Streaming EN
0.6B model and the Hexagon NPU via QNN; the CPU path uses the Kroko streaming zipformer), labels
speakers with on-device diarization, and forwards the transcript to a user-configured
OpenAI-compatible endpoint for summaries and questions about the session.

## Build

```bash
bash scripts/setup-toolchain.sh   # one-time, installs JDK, SDK, NDK and builds the sherpa-onnx native libs
source scripts/env.sh
./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug
```

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Native engine (sherpa-onnx + Qualcomm QNN)

The on-device ASR and diarization engine is
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) pinned to
**v1.13.8**. The published Android AAR does not include the Qualcomm QNN
backend, and the prebuilt QNN demo APKs ship without speaker
diarization, so we compile the native library from source with both
features enabled.

`scripts/build-sherpa-onnx.sh` (called automatically at the end of
`scripts/setup-toolchain.sh`):

1. Shallow-clones the `v1.13.8` tag of `k2-fsa/sherpa-onnx` into
   `~/.local/share/convoscribe-toolchain/sherpa-onnx-build/`.
2. Downloads the **Qualcomm QNN 2.40.0.251030** C headers and runtime
   shared libraries from the
   [sherpa-onnx QNN assets release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models-qnn).
3. Runs the upstream `build-android-arm64-v8a.sh` with
   `SHERPA_ONNX_ENABLE_QNN=ON`, `SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=ON`,
   `SHERPA_ONNX_ENABLE_TTS=OFF`, `SHERPA_ONNX_ENABLE_BINARY=OFF`,
   `SHERPA_ONNX_ENABLE_C_API=OFF`, `SHERPA_ONNX_ENABLE_JNI=ON` and
   `BUILD_SHARED_LIBS=ON`. This fetches a prebuilt onnxruntime
   (`csukuangfj/onnxruntime-libs`) and links it as a shared library.
4. Copies `libsherpa-onnx-jni.so` + `libonnxruntime.so` from
   `build-android-arm64-v8a/install/lib/` and the QNN runtime
   (`libQnnHtp.so`, `libQnnHtpPrepare.so`, `libQnnSystem.so`,
   `libQnnHtpV{68,69,73,75,79,81}{Skel,Stub}.so`) into
   `app/src/main/jniLibs/arm64-v8a/`.
5. Verifies the resulting `libsherpa-onnx-jni.so` exports both
   `Java_com_k2fsa_sherpa_onnx_OfflineSpeakerDiarization` and the
   `prependAdspLibraryPath` symbol (QNN path).
6. Downloads `sherpa-onnx-1.13.8.aar`, checks its sha256 against
   `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`,
   and extracts the AAR's `classes.jar` into
   `app/libs/sherpa-onnx-classes-1.13.8.jar` (the AAR itself is **not**
   used as a dependency because its bundled native libs have no NPU).

The build takes **10-30 minutes** on a fresh checkout. Re-runs are
idempotent: a second `bash scripts/build-sherpa-onnx.sh` prints
`sherpa-onnx native libs OK` and exits 0. Pass `--force` to wipe
`$WORK` and rebuild from scratch.

The `libQnnHtp*.so`, `libQnnSystem.so` and `libQnnHtpV*` shared
libraries are **Qualcomm AI Engine Direct runtime files** redistributed
by sherpa-onnx; we ship them unmodified so the NPU backend (Hexagon
DSP via `libcdsprpc.so`) can be reached at runtime. All of
`app/src/main/jniLibs/` and `app/libs/*.jar` are git-ignored build
artifacts.
