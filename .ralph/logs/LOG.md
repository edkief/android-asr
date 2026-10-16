## 2026-10-16 — TASK-33: v2 name enrolled speakers automatically after diarization

Wired the post-diarization speaker-name pass so a fresh
recognition can replace `"Speaker 1 / 2 / 3"` with the user's
enrolled name when the cosine match clears the threshold.

`diarization/SpeakerSampleCollector.kt`: new pure-Kotlin object
that, per renumbered speaker index, concatenates the audio of
its diarized turns (16 samples per ms, clamped to the
recording's bounds), skipping turns shorter than 500 ms and
capping each speaker at 30 000 ms. Speakers whose concatenated
audio is shorter than `MIN_COLLECTED_MS = 2_000` ms are
dropped from the embedding map (too little speech to be
useful). Returns a `Map<Int, FloatArray>` so the embedder can
handle each speaker without re-walking the turn list.

`diarization/SpeakerAssigner.kt`: `Assignment` now also
exposes `rawToIndex: Map<Int, Int>` so the post-diarization
pass can resolve raw engine ids to renumbered indices when
slicing per-speaker audio.

`data/SessionRepository.kt`: `applyDiarization` accepts an
optional `labels: Map<Int, String>`; any index present keeps
its name instead of the default `"Speaker N"`. Unknown
indices are ignored.

`diarization/DiarizeSessionUseCase.kt`: takes the new
`embedderFactory: SpeakerEmbedderFactory` and
`enrolled: EnrolledSpeakerStore` constructor parameters. After
the speaker assignment, when the user has at least one
enrollment, the use case builds the per-speaker windows,
embeds them through one shared `SpeakerEmbedder` (in `use
{ }`), matches the embeddings against the enrolled names via
`SpeakerMatcher.assignNames`, and forwards the resulting
`speakerIndex → name` map to the repository. Embedding
failures are caught and logged with `Log.w` so a broken
speaker model never fails the diarization — the session
still lands on `DIARIZED` with default labels.

`ui/settings/SettingsScreen.kt`: Speakers section gains a
"Voice match threshold" slider (0.3..0.8) bound to
`AppSettings.speakerMatchThreshold`, mirroring the existing
clustering threshold. Strings added to `strings.xml`.

`AppContainer`: wires the sherpa `speakerEmbedderFactory`
and `enrolledSpeakers` store into `diarizeSessionUseCase`.

Tests: `SpeakerSampleCollectorTest` (8 spec tests: cap at
30 s, skip short turns, drop sparse speakers, clamp to
recording bounds, etc.); three new acceptance tests in
`DiarizeSessionUseCaseTest` — enrolled "Alice" matches
speaker 0, no enrolled speakers short-circuits the embedder
factory, throwing embedder still ends in `DIARIZED` with
default labels.

Verified with
`source scripts/env.sh && ./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug`
(exit 0).

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

## 2026-10-16 — TASK-32: v2 known speakers screen (enroll, list, delete)

Added the user-facing "Known speakers" screen that drives the
v2 enrollment pipeline from TASK-31 so a recording can label
matched voices with the speaker's real name.

`ui/speakers/SpeakersViewModel.kt`: `class
SpeakersViewModel(repo: EnrolledSpeakerStore, enroll:
EnrollSpeakerUseCase)` with `SpeakersUiState(speakers,
enrollName, phase: EnrollmentPhase { IDLE, RECORDING,
PROCESSING }, progress, embeddingModelInstalled, error)`.
`startEnrollment()` short-circuits on a blank name with the
`"Speaker name must not be blank"` error, otherwise launches
`viewModelScope.launch { enroll.record(15_000) { p -> ... };
enroll.enroll(name, samples) }`, surfaces engine
exception messages verbatim on the `error` field, and clears
the name + error on success. `cancel()` kills the in-flight
job. `delete(id)` deletes via the repository so the
`observeAll` collector re-emits. Open-ed the
`EnrollSpeakerUseCase.record` / `enroll` overrides so a
JVM-friendly fake use case can drive the state machine.

`ui/speakers/SpeakersScreen.kt` (route `speakers`): 15-second
explanation text, name field, "Record sample" button that
runs the `RECORD_AUDIO` permission flow (mirrors the record
screen's launcher), `LinearProgressIndicator` + "X s
remaining" caption while `RECORDING`, "Processing…" caption
while `PROCESSING`, and an enrolled-names `LazyColumn` with
a trash icon per row that opens an `AlertDialog`
("Remove this speaker? ConvoScribe will no longer recognise
%1$s …"). When the embedding `.onnx` is missing the screen
hides the Record button and shows "Open setup" that pushes
`setup`. Delete state is owned locally as `pendingDelete:
EnrolledSpeakerEntity?`; `vm.delete(id)` is called on
confirm.

`AppNavHost.kt`: added `composable(ROUTE_SPEAKERS) {
SpeakersScreen(...) }` so the route is reachable from the
global graph.

`SettingsScreen.kt`: added a "Known speakers" row inside the
existing Speakers section (`SpeakersSection` now takes an
`onOpenKnownSpeakers` callback that pushes `speakers`),
keeping the v2 enrollment entry discoverable next to the
related diarization controls.

`strings.xml`: 19 new strings (`speakers_*`,
`settings_speakers_known_row`, `settings_speakers_known_button`)
plus a `settings_speakers_known_button = "Manage"` so the
button label and row label read distinctively.

`ui/speakers/SpeakersViewModelTest.kt`: 5 Robolectric +
`StandardTestDispatcher`-flavoured tests with a fake
`EnrolledSpeakerStore` (StateFlow-backed for
`observeAll()`) and a `FakeEnrollUseCase` that overrides
`record` / `enroll`. Covers the three acceptance bullets
(successful enrollment clears the name and the list
contains it; blank/empty name sets error and never touches
the use case; `delete(id)` removes the row and the flow
re-emits without it) plus a sanity round-trip on the
fake store. Sherpa classes are never referenced.

Verify `source scripts/env.sh && ./gradlew ktlintCheck
testDebugUnitTest lintDebug assembleDebug` exits 0 — 290
tests, 0 failures (was 285, +5 for this task). Marked
`TASK-32` as passes.
