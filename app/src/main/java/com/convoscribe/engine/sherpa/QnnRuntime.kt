package com.convoscribe.engine.sherpa

import com.k2fsa.sherpa.onnx.OnlineRecognizer

/**
 * One-shot preparation of the QNN runtime before the first
 * [OnlineRecognizer] is built. sherpa-onnx loads the Hexagon
 * DSP libraries through the Android linker, which on API 29+
 * does not search the application's `nativeLibraryDir` by
 * default; the sherpa API exposes a static helper that makes
 * the app's own `.so` directory available to `dlopen` so the
 * DSP libraries can be resolved without bundling them inside
 * the AAR.
 *
 * Prepending the same path twice is harmless (the underlying
 * loader dedupes), but we still guard with a [Boolean] flag
 * under a class-level lock so we do not pay the JNI hop on
 * every recognizer build. In practice the guard prevents
 * duplicate work when [SherpaTranscriberFactory] rebuilds a
 * transcriber for a fresh recording session.
 */
object QnnRuntime {

    /** `true` after [ensurePrepared] has run on this process. */
    @Volatile
    private var prepared: Boolean = false

    /** Lock guarding [prepared] (volatile alone does not give us CAS over a flag read+write). */
    private val lock = Any()

    /**
     * Idempotently add [nativeLibraryDir] to sherpa-onnx's
     * ADSP loader search path. Safe to call from any thread;
     * the underlying call is a `dl_iterate_phdr` walk and
     * cheap enough to run on the UI thread on a cold start.
     *
     * The parameter is the absolute path the Android runtime
     * exposes as
     * `Context.getApplicationInfo().nativeLibraryDir`, e.g.
     * `/data/app/~~XYZ/convoscribe-/lib/arm64-v8a`.
     */
    fun ensurePrepared(nativeLibraryDir: String) {
        if (prepared) return
        synchronized(lock) {
            if (prepared) return
            OnlineRecognizer.prependAdspLibraryPath(nativeLibraryDir)
            prepared = true
        }
    }
}
