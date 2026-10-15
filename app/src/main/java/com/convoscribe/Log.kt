package com.convoscribe

/**
 * Shared log tag every diagnostic call from the app uses, so an
 * operator can filter with one `adb logcat -s ConvoScribe` command and
 * see the whole app narrative regardless of which sub-package emitted
 * the line.
 *
 * The native engine (`libsherpa-onnx-jni.so` + QNN runtime) keeps
 * its own tag (`sherpa-onnx`), filtered separately with
 * `adb logcat -s sherpa-onnx ConvoScribe AndroidRuntime`.
 *
 * Call sites:
 *
 * ```kotlin
 * import android.util.Log
 * import com.convoscribe.TAG
 *
 * Log.w(TAG, "Diarization failed for session $sessionId", throwable)
 * Log.i(TAG, "recording started session=$sessionId")
 * ```
 *
 * The file is intentionally tiny (one top-level `const val`) so
 * the acceptance grep `grep -r 'const val TAG' app/src/main` finds
 * exactly one definition site. Tests pin this behaviour: if a
 * second `TAG` constant sneaks in, the build fails on an explicit
 * grep-driven assertion rather than at runtime with two streams.
 */
const val TAG = "ConvoScribe"
