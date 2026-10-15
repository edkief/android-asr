# Parked: Parakeet v3 high-accuracy final pass

**Status:** parked on 2026-10-16. Running Parakeet TDT 0.6B v3 (int8 encoder: 652 MB) on the
CPU is not viable on the target QCS/QCM6690 devices. Revisit if an NPU/QNN build of the model, or
a smaller offline multilingual model, becomes an option.

These files sit outside `.ralph/`, so Ralph does not run them.

## Idea

After diarization, re-transcribe each speaker turn with an offline multilingual model, replacing
the live (Kroko streaming) transcript. Each line then has exactly one speaker and more accurate
text. If the pass fails, the live transcript is kept.

## Model (verified 2026-10-16)

| bundleId | files (bytes) | source |
|---|---|---|
| `asr-parakeet-tdt-0.6b-v3-int8` | `encoder.int8.onnx` 652184281, `decoder.int8.onnx` 11845275, `joiner.int8.onnx` 6355277, `tokens.txt` 93939 (total 670478772); offline, modelType `nemo_transducer` | `https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/<file>` |

Licence: CC-BY-4.0 (NVIDIA). Covers 25 European languages, including English and French.

## Task specs

`TASK-31.json`, `TASK-32.json` and `TASK-33.json` were written to run after TASK-30. They assume
TASK-1 to TASK-30 are done: model catalog, settings, `DiarizeSessionUseCase` with enrolled-speaker
naming, `SessionRepository`, and the session/settings screens.

To restore them:
1. Copy them back to `.ralph/tasks/`, renumbering if TASK-31+ ids are taken by then. Update the
   `id` field to match.
2. Add `{ "id": "TASK-NN", "passes": false }` entries to `.ralph/tasks.json`.
3. Add back to the PRD: a goal for the final pass, the model row above in the model catalog table,
   Parakeet's licence, and an acceptance line ("with High-accuracy final transcript on, lines are
   re-transcribed per speaker turn; on failure the live transcript is kept").
4. If moving to an NPU, replace `provider = "cpu"` in TASK-32 with the QNN setup
   (sherpa-onnx has `QnnConfig` in `OfflineTransducerModelConfig`).
