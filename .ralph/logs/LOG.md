## 2026-10-15 — TASK-31: v2 speaker embedder (sherpa) and enroll-from-audio use case

Added the v2 "enroll this speaker by voice" pipeline so a fresh
recording can label matched voices with the user's real names.

`diarization/SpeakerEmbedder.kt`: engine-agnostic façade
(`SpeakerEmbedder : Closeable { fun embed(FloatArray): FloatArray }`)
plus a `fun interface SpeakerEmbedderFactory`. Kept thin so the
use-case test never touches sherpa.

`engine/sherpa/SherpaSpeakerEmbedder.kt`: wraps
`sherpa-onnx` 1.13.8's `SpeakerEmbeddingExtractor` against the
same `wespeaker_en_voxceleb_resnet34_LM.onnx` file the
diarizer uses, so an enrollment embedding is comparable to
diarizer embeddings. `embed` builds an `OnlineStream`, runs
`acceptWaveform(samples, 16_000)`, `inputFinished()`, checks
`extractor.isReady`, returns `extractor.compute`, and releases
the stream in `finally`. `SherpaSpeakerEmbedderFactory(store,
settings)` throws `IllegalStateException("Model not installed:
…")` when the embedding file is missing, mirroring the other
factory error messages. Wired `speakerEmbedderFactory:
SpeakerEmbedderFactory = SherpaSpeakerEmbedderFactory(...)`
and `enrollSpeakerUseCase = EnrollSpeakerUseCase(...)` into
`AppContainer`.

`diarization/EnrollSpeakerUseCase.kt`: `record(durationMs,
onProgress)` collects from the audio source, concatenates
chunks via `ShortArray.copyInto`, truncates the final chunk so
the returned float array is exactly `durationMs * 16_000 / 1000`
samples (the spec's exact-length acceptance), and reports the
0..1 progress. `enroll(name, samples)` requires a non-blank
name and `samples.size ≥ 48_000` (3 s at 16 kHz),
runs the embedder on `Dispatchers.Default`, closes it via
`use { }`, and saves via `EnrolledSpeakerStore.save`.

`EnrollSpeakerUseCaseTest`: 6 tests with a fake
`AudioSource` emitting 1 600-sample mute chunks (forever, or
capped with `stopAfter`), a fake `SpeakerEmbedder` that returns
`floatArrayOf(samples.size.toFloat(), 1f)`, and an in-memory
fake `EnrolledSpeakerStore`. Covers the four acceptance cases
(`record(1000)` returns 16 000 samples, progress reaches 1.0;
`enroll(" Alice ", 64 000)` saves "Alice"; 16 000 samples and
both empty + whitespace names throw) plus a sanity check on
the fake store. Sherpa classes are never referenced from tests.

Verify `source scripts/env.sh && ./gradlew ktlintCheck
testDebugUnitTest lintDebug assembleDebug` exits 0 — 285 tests,
0 failures (was 279, +6 for this task). Marked `TASK-31` as
passes.
