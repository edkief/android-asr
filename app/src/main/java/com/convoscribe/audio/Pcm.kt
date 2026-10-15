package com.convoscribe.audio

/**
 * Conversions between raw PCM16 sample storage (16-bit signed `Short`
 * arrays) and the `FloatArray` shape the ASR and diarization
 * pipelines expect.
 *
 * PCM16 covers `[-32768, 32767]`, so dividing by 32 768f maps the
 * range to approximately `[-1.0, 1.0]`. The 0.5 dB asymmetry
 * (`-32768 / 32768 == -1.0` but `32767 / 32768 ≈ 0.99997`) is what
 * every other ASR tool does, so we match the convention rather than
 * exact mathematical symmetry.
 */

/**
 * Convert a buffer of signed 16-bit PCM samples to floats in
 * `[-1.0, 1.0]` (each value divided by 32 768f). Returns a new
 * array; the receiver is not modified.
 */
fun ShortArray.toFloatSamples(): FloatArray {
    val out = FloatArray(size)
    for (i in indices) {
        out[i] = this[i] / 32_768f
    }
    return out
}

/**
 * Convert a sample count to milliseconds at [AudioSpec.SAMPLE_RATE].
 * Used to translate streaming ASR timestamps and
 * `AudioRecord` chunk counts into the millisecond range the UI and
 * the diarization segments share.
 */
fun samplesToMs(samples: Long): Long = samples * 1000L / AudioSpec.SAMPLE_RATE
