package com.convoscribe.engine.sherpa

/**
 * Compile-time pin to the sherpa-onnx version this app is built against.
 *
 * The native library ([libsherpa-onnx-jni.so]) is built from
 * https://github.com/k2-fsa/sherpa-onnx at tag v[VERSION] by
 * `scripts/build-sherpa-onnx.sh` with `SHERPA_ONNX_ENABLE_QNN=ON` and
 * `SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=ON`. The Kotlin API classes
 * (`com.k2fsa.sherpa.onnx.*`) come from the matching AAR.
 *
 * This object is intentionally tiny and contains no engine instances, so
 * the JVM unit tests can reach it without loading the native library.
 */
object SherpaInfo {
    const val VERSION: String = "1.13.8"

    /**
     * Default 16 kHz / 80-dim log-mel features used by every sherpa-onnx
     * streaming model in this project (Nemotron NPU + Kroko CPU).
     */
    fun defaultFeatureConfig(): com.k2fsa.sherpa.onnx.FeatureConfig =
        com.k2fsa.sherpa.onnx.FeatureConfig(
            sampleRate = 16_000,
            featureDim = 80,
        )
}
