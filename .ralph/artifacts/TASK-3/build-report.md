# TASK-3 — sherpa-onnx 1.13.8 (QNN + diarization) build report

Built on a Linux x86_64 host (Ubuntu, 16 vCPU, ~82 GB free disk, no
`wget` available) using `bash scripts/build-sherpa-onnx.sh` and
verified with the project's standard verify command.

## Environment

- JDK 17.0.20.1 (Temurin), Gradle 8.11.1, Android command-line tools
  `11076708`, NDK `27.2.12479018`, CMake 3.27.9 — all installed by
  `scripts/setup-toolchain.sh` (TASK-1).
- sherpa-onnx v1.13.8 cloned from `https://github.com/k2-fsa/sherpa-onnx`
  (shallow, tag `v1.13.8`).
- QNN 2.40.0.251030 C headers + runtime libraries from the
  `asr-models-qnn` GitHub release.
- Prebuilt onnxruntime 1.28.2 from
  `https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.28.2/onnxruntime-android-1.28.2.zip`.

## Build settings

`build-android-arm64-v8a.sh` was invoked with:

| flag | value |
|---|---|
| `QNN_SDK_ROOT` | `$WORK/qnn-include-2.40.0.251030` |
| `ANDROID_NDK` | `~/Android/Sdk/ndk/27.2.12479018` |
| `SHERPA_ONNX_ENABLE_QNN` | `ON` |
| `SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION` | `ON` |
| `SHERPA_ONNX_ENABLE_TTS` | `OFF` |
| `SHERPA_ONNX_ENABLE_BINARY` | `OFF` |
| `SHERPA_ONNX_ENABLE_C_API` | `OFF` |
| `SHERPA_ONNX_ENABLE_JNI` | `ON` |
| `BUILD_SHARED_LIBS` | `ON` |
| `SHERPA_ONNX_ONNXRUNTIME_VERSION` | `1.28.2` |

`sherpa-onnx-core` finishes at 92% (third-party deps + ~220 cc files),
`sherpa-onnx-jni` at 100% and links `libsherpa-onnx-jni.so`.

## Native artefacts shipped to the APK

```
app/src/main/jniLibs/arm64-v8a/
  libQnnHtp.so                 2.5 MB
  libQnnHtpPrepare.so         74.2 MB
  libQnnHtpV{68,69,73,75,79,81}Skel.so   ~9-10 MB each
  libQnnHtpV{68,69,73,75,79,81}Stub.so   ~570-630 KB each
  libQnnSystem.so              2.6 MB
  libonnxruntime.so           22.2 MB
  libsherpa-onnx-jni.so        3.9 MB   (QNN + diarization, exports
                                          Java_…_OfflineSpeakerDiarization_*
                                          and Java_…_*Recognizer_prependAdspLibraryPath)
```

```
app/libs/sherpa-onnx-classes-1.13.8.jar  239 KB
  ↳ classes.jar extracted from sherpa-onnx-1.13.8.aar
  ↳ sha256 = 633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96 ✓
```

## Idempotency

A second invocation of `bash scripts/build-sherpa-onnx.sh` prints
`sherpa-onnx native libs OK` and exits 0 without rebuilding (the early
guard at the top of the script short-circuits when the jni .so, a QNN
.so and the classes jar are all present).

## Verify command

```
$ source scripts/env.sh && ./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug
…
BUILD SUCCESSFUL in 17s
```

`app/build/outputs/apk/debug/app-debug.apk` contains
`lib/arm64-v8a/libQnnHtpV73Skel.so` and no entries under
`lib/x86_64/` or `lib/armeabi-v7a/`.
