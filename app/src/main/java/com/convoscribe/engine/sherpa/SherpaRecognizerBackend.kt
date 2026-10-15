package com.convoscribe.engine.sherpa

import com.convoscribe.asr.RecognizerBackend
import com.convoscribe.audio.AudioSpec
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream

/**
 * [RecognizerBackend] backed by sherpa-onnx's streaming transducer
 * (`OnlineRecognizer` + a single `OnlineStream`). The recognizer
 * is engine-agnostic at this layer: callers supply an
 * [OnlineRecognizerConfig] (CPU or NPU) built by
 * [SherpaAsrConfigs] and we wire it up to the native library.
 *
 * The recognizer holds onto native memory for the encoder, decoder,
 * joiner and tokens table; [close] is therefore mandatory — without
 * it, every chunk of audio recorded by the user would leak the
 * native model into the heap. The matching [OnlineStream] is
 * created once and `reset` clears its state in place.
 */
class SherpaRecognizerBackend(
    config: OnlineRecognizerConfig
) : RecognizerBackend {

    private val recognizer: OnlineRecognizer
    private val stream: OnlineStream

    init {
        recognizer = OnlineRecognizer(assetManager = null, config = config)
        stream = recognizer.createStream()
    }

    override fun acceptWaveform(samples: FloatArray) {
        stream.acceptWaveform(samples, AudioSpec.SAMPLE_RATE)
    }

    override fun decodeAvailable() {
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
        }
    }

    override fun currentText(): String = recognizer.getResult(stream).text

    override fun isEndpoint(): Boolean = recognizer.isEndpoint(stream)

    override fun reset() {
        recognizer.reset(stream)
    }

    override fun inputFinished() {
        stream.inputFinished()
    }

    override fun close() {
        // sherpa-onnx's Kotlin API exposes both `stream.release()`
        // and `recognizer.release()`; calling both keeps the
        // shutdown ordering explicit and symmetric with the
        // documentation. Each release() only forwards to finalize()
        // in this binding, so even a double call is harmless.
        stream.release()
        recognizer.release()
    }
}
