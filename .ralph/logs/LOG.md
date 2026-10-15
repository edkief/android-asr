## 2026-10-15 — TASK-30: v2 enrolled speakers table and embedding math

Started the v2 speaker-enrollment feature: added the
`enrolled_speakers` Room table (with a unique index on `name`),
bumped `AppDatabase` to `version = 2` with an auto-migration
from 1, and wrote the small but hot embedding-codec layer that
goes between `FloatArray` (the model output) and `ByteArray`
(the Room column). Cosine matching lives in
`diarization/SpeakerMatcher` as a pure-Kotlin `object` so the
greedy name-assignment algorithm can be unit-tested without
Robolectric; the repository (`EnrolledSpeakerRepository`)
sits behind an `EnrolledSpeakerStore` interface so the
later-task enrollment UI never touches Room.

Wired `enrolledSpeakers: EnrolledSpeakerStore =
EnrolledSpeakerRepository(database.enrolledSpeakerDao())` into
`AppContainer` and committed `2.json` (the new schema) under
`app/schemas/com.convoscribe.data.AppDatabase/`. New tests:
`EmbeddingCodecTest` (round-trip, little-endian, 4 bytes/float,
zero + signed-zero preservation), `SpeakerMatcherTest` (cosine
identity/orthogonal/zero/mismatched-sizes, `assignNames` at
threshold 0.5 → both names, threshold 0.7 → only the 0.9
match), and `EnrolledSpeakerRepositoryTest` (Robolectric, in-
memory DB: REPLACE strategy keeps one row per name and `Flow`
re-emits on insert).

`source scripts/env.sh && ./gradlew ktlintCheck
testDebugUnitTest lintDebug assembleDebug` exits 0 — 279 tests,
0 failures.

## 2026-10-15 — TASK-29: Manual on-device test guide and README completion

Wrote `docs/MANUAL_TEST.md` with 18 numbered headings (16 distinct
checks per the spec, with 2 split into 2b/2c for the NPU / CPU
engine-check paths). Each item has a Steps / Expected block and a
combined troubleshooting section covers `adb logcat -s sherpa-onnx
ConvoScribe AndroidRuntime`, where models live on disk,
`/data/data/com.convoscribe/files/models`, `uses-native-library
libcdsprpc.so`, the QNN error `14001` recovery
(delete `encoder.bin`/`decoder.bin`/`joiner.bin` to force
re-preparation), and `adb shell pm clear com.convoscribe` for a
fresh start.

Added `Log.kt` with a single shared `const val TAG = "ConvoScribe"`
and pointed the existing `Log.w` calls in `DiarizeSessionUseCase`
at it (removed the private `"DiarizeSessionUseCase"` companion).
New `Log.i(TAG, …)` lines for the events the spec calls out —
recording started (`session=` + `lang=`), recording stopped
(`session=` + `durationMs=`), diarization completed
(`session=` + `durationMs=` + `speakers=`), and model download
completed (`bytesDone=` + `bundles=`). `grep 'const val TAG'` now
finds exactly one definition; `grep 'Log\.[iw](TAG'` matches 8
lines (6 real calls + 2 kdoc snippets in `Log.kt` itself).

Expanded `README.md` with a Features list, an Architecture section
that walks every package under `app/src/main/java/com/convoscribe/`
with one line of prose each (`audio`, `models`, `asr`,
`diarization`, `engine/sherpa`, `data`, `settings`, `llm`,
`recording`, `service`, `enginecheck`, `ui/<screen>/`, `ui/theme/`),
a Models table (bundleId, engine, size, files, source per row),