# TASK-29 artifact: manual test checklist + completion record

Fill this in as the manual on-device checks in
[`docs/MANUAL_TEST.md`](../../docs/MANUAL_TEST.md) are executed.
Each row corresponds to one numbered check; the **Report** column
is what the on-device run produced (so the engine-check numbers
are not lost when filing a follow-up issue).

## Checklist (date, signature, notes per check)

| #  | Check                                                  | Pass? | Notes |
|----|--------------------------------------------------------|-------|-------|
| 1  | Install                                                |       |       |
| 2  | First-launch model download                            |       |       |
| 2b | Engine check — NPU                                     |       |       |
| 2c | Engine check — CPU                                     |       |       |
| 3  | Microphone permission                                  |       |       |
| 4  | Live transcription                                     |       |       |
| 5  | Screen-off during recording                            |       |       |
| 6  | Post-stop diarization                                  |       |       |
| 7  | Rename speakers and session, restart                  |       |       |
| 8  | Share transcript                                       |       |       |
| 9  | LLM endpoint                                           |       |       |
| 10 | Summarise                                              |       |       |
| 11 | Ask questions (context)                                |       |       |
| 12 | French                                                 |       |       |
| 13 | Delete session                                         |       |       |
| 14 | Force-stop during recording                            |       |       |
| 15 | Performance (CPU%)                                     |       |       |
| 16 | Engine switching NPU → CPU → NPU                       |       |       |

## On-device values to record

Filled by the engine-check screens (`docs/MANUAL_TEST.md` §2b/§2c):

| quantity | NPU | CPU |
|---|---|---|
| Manufacturer |  |  |
| Model |  |  |
| SoC |  |  |
| Android |  |  |
| Free storage (MiB) |  |  |
| First-run prepare (ms) |  |  |
| Init (ms) |  |  |
| Audio (ms) |  |  |
| Processing (ms) |  |  |
| RTF |  |  |
| Recognised text |  |  |

Recording-screen values (`docs/MANUAL_TEST.md` §15):

| run | engine | CPU% recognizer | total CPU% |
|---|---|---|---|
| 1 | NPU |  |  |
| 2 | CPU |  |  |
