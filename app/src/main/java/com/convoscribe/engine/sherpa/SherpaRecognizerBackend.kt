package com.convoscribe.engine.sherpa

import com.convoscribe.asr.RecognizerBackend
import com.convoscribe.audio.AudioSpec
import com.convoscribe.models.AsrFiles
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * [RecognizerBackend] backed by sherpa-onnx's streaming zipformer
 * (`OnlineRecognizer` + a single `OnlineStream`). The
 * configuration matches sherpa-onnx 1.13.8: 16 kHz / 80-dim log-mel
 * features, CPU provider, greedy decoding, endpoint detection
 * tuned for natural pauses (rule 1 = "2.4 s of trailing silence",
 * rule 2 = "0.8 s after any non-silence", rule 3 = "force endpoint
 * at 20 s").
 *
 * The recognizer holds onto native memory for the encoder, decoder,
 * joiner and tokens table; [close] is therefore mandatory — without
 * it, every chunk of audio recorded by the user would leak the
 * native model into the heap. The matching [OnlineStream] is
 * created once and `reset` clears its state in place.
 */
class SherpaRecognizerBackend(
    modelDir: File,
    asr: AsrFiles,
    numThreads: Int
) : RecognizerBackend {

    private val recognizer: OnlineRecognizer
    private val stream: OnlineStream

    init {
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = AudioSpec.SAMPLE_RATE,
                featureDim = 80
            ),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(modelDir, asr.encoder).absolutePath,
                    decoder = File(modelDir, asr.decoder).absolutePath,
                    joiner = File(modelDir, asr.joiner).absolutePath
                ),
                tokens = File(modelDir, asr.tokens).absolutePath,
                numThreads = numThreads,
                provider = "cpu",
                modelType = asr.modelType
            ),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0.0f),
                rule2 = EndpointRule(true, 0.8f, 0.0f),
                rule3 = EndpointRule(false, 0.0f, 20.0f)
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search"
        )
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
