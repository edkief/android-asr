@file:Suppress("ktlint:standard:filename")

package com.convoscribe.audio

/**
 * Audio format shared by the recorder, the WAV writer, the ASR
 * frontend and the diarization pipeline. Everything in this app
 * operates on 16 kHz mono PCM16, so the values are pinned here
 * instead of being passed through every constructor.
 *
 * [CHUNK_SAMPLES] is the size of one read from [android.media.AudioRecord]:
 * 1 600 samples = 100 ms of audio at 16 kHz, which is the unit the
 * streaming ASR (Nemotron / Kroko) consumes and the cadence at which
 * we hand samples to the recognizer.
 */
object AudioSpec {
    /** Sample rate in Hz, 16 kHz mono PCM16. */
    const val SAMPLE_RATE: Int = 16_000

    /**
     * 1 600 samples = 100 ms at 16 kHz. One chunk per flow emission
     * keeps the recognizer fed and gives the UI a useful tick rate.
     */
    const val CHUNK_SAMPLES: Int = 1_600
}
