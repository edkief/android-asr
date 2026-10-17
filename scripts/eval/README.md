# Speaker-attribution evaluation

`eval_speakers.py` runs ConvoScribe's transcription and speaker identification on a desktop, with
the same models and settings as the app, and scores how many words are given to the right
speaker. Use it to tune the clustering threshold and to check changes to speaker attribution.
On a desktop a run takes seconds; the same experiment on the phone takes minutes.

## Setup

```sh
python3 -m venv .venv-eval
.venv-eval/bin/pip install -r scripts/eval/requirements.txt
```

The first run downloads the models (about 100 MB) into `~/.cache/convoscribe-eval`. Set
`CONVOSCRIBE_EVAL_CACHE` to use another folder.

## Label a recording

1. Copy a session's audio off the phone (debug build), for example session 12:
   `adb exec-out run-as com.convoscribe cat files/recordings/12.wav > meeting.wav`
2. Open it in [Audacity](https://www.audacityteam.org/) and add a label (Ctrl+B) over each stretch
   of speech, named after the speaker (`Alice`, `Bob`, …). The labels don't need to be exact: a
   tenth of a second either way makes little difference.
3. *File → Export → Export Labels…* to `meeting.labels.txt`.

A CSV with `start,end,speaker` columns in seconds also works.

## Run

```sh
.venv-eval/bin/python scripts/eval/eval_speakers.py meeting.wav meeting.labels.txt
```

Options:
- `--lang fr` for French recordings;
- `--thresholds 0.4 0.5 0.6` to choose which thresholds to try;
- `--num-speakers 3` to compare a fixed count with auto. By default it compares auto with the
  number of labelled speakers.

Output, one row per setting:

| column | meaning |
|---|---|
| `threshold` | clustering threshold (Settings → Speakers; default 0.75) |
| `speakers` | `auto`, or the fixed speaker count |
| `found` | how many speakers the diarizer detected |
| `line acc` | word accuracy when each transcript line gets one speaker (the behaviour before #1) |
| `split acc` | word accuracy when lines are split at speaker changes (the app's behaviour) |
| `mean conf` | mean diarizer confidence; negative means none was computed |

"Word accuracy" is the share of recognised words whose speaker matches the label at that moment,
after the best one-to-one mapping between detected and labelled speakers. Words outside every
label (more than 0.5 s away) are not counted.

## Try it without a recording

```sh
.venv-eval/bin/python scripts/eval/eval_speakers.py --make-demo /tmp/demo
.venv-eval/bin/python scripts/eval/eval_speakers.py /tmp/demo/demo.wav /tmp/demo/demo.labels.txt
```

The demo alternates two LibriSpeech speakers with 0.4 s pauses. The pauses are too short to end
a transcript line, so whole lines contain both speakers. Expect about 72% `line acc` and 100%
`split acc`.

## Benchmark: choosing the clustering threshold

```sh
.venv-eval/bin/python scripts/eval/eval_speakers.py --make-bench bench
.venv-eval/bin/python scripts/eval/eval_speakers.py --bench bench --threads 2 -v
```

`--make-bench` downloads LibriSpeech dev-clean (340 MB) and four AMI meetings (180 MB) into
`bench/`:

- **AMI**: one meeting from each AMI test-set series (ES2004a, IS1009a, TS3003a, EN2002a). Each has
  four people and lasts 15–35 min. The audio is from `Array1-01`, a single microphone on the
  table, like a tablet lying there. Labels are the
  [pyannote AMI setup](https://github.com/pyannote/AMI-diarization-setup) word-level RTTMs.
- **libri-Nspk**: 12 synthetic conversations of about 4 min, four each with 2, 3 and 4
  LibriSpeech speakers (fixed seed). A third of the turns are short interjections and pauses are
  0.15–1.2 s, so many speaker changes fall inside a transcript line.

`--bench DIR` scores every WAV in a folder that has a `.rttm`, `.labels.txt` or `.csv` next to it,
so your own labelled recordings can go in the same folder. Transcripts and diarizer output are
cached, so re-running is quick. Columns:

| column | meaning |
|---|---|
| `F1` | purity/coverage F1: each detected speaker should be one person, and each person one detected speaker. Too many and too few speakers both lower it. **Tune on this.** |
| `time acc` | share of single-speaker time given to the right person. It rewards lumping a quiet speaker in with a dominant one, so don't use it alone. |
| `split acc` | word accuracy with the app's line splitting (needs ASR; `--no-asr` skips it) |
| `count ok` / `count err` | share of recordings with the right speaker count / mean found − labelled |
| `fixed n` | the speaker count set to the labelled count, as with Re-identify speakers |

Result with sherpa-onnx 1.13.8, all 16 recordings, automatic speaker count:

| threshold | F1 | split acc | count err |
|---|---|---|---|
| 0.50 (old default) | 61.1% | 54.8% | +11.25 |
| 0.60 | 65.7% | 60.9% | +5.25 |
| 0.70 | 70.9% | 68.1% | +2.12 |
| **0.75 (default)** | **70.7%** | **67.6%** | **+1.00** |
| 0.80 | 70.0% | 65.3% | +0.06 |
| 0.90 | 67.3% | 61.1% | −1.19 |
| fixed n | 68.9% | 64.7% | −0.56 |

0.70 and 0.75 tie on F1. 0.75 was chosen because it over-counts by half as much, and it does
better on AMI, the closest set to real use (F1 62.3% against 59.8%). Per set:

- **AMI:** 0.5 found 8–16 speakers in 4-person meetings. F1 keeps rising up to 0.9, but past 0.8
  speakers are merged.
- **2 and 3 speakers:** best around 0.70–0.80.
- **4 speakers:** best around 0.5–0.6, and 0.75 is within 2.5 points of that.

Fixing the count is no better than automatic at 0.75. Re-identify speakers helps when automatic
gets the count visibly wrong, not as a general improvement.

Far-field meetings are much harder than the clean synthetic conversations: around 60% F1 against
around 75%. Add a few labelled recordings from your own phone to `bench/` to check the choice
holds for your rooms.

## After upgrading sherpa-onnx

```sh
.venv-eval/bin/python scripts/eval/eval_speakers.py --check-sherpa
```

This re-checks two behaviours the app relies on:
- token timestamps restart at 0 after each endpoint reset;
- diarizer confidences are filled in for multi-speaker audio.

## Keeping it in sync

The script contains Python copies of `SpeakerAssigner` and `SegmentSplitter` (in
`app/src/main/java/com/convoscribe/diarization/`) and of the endpoint, chunk and diarizer
settings. Change both together, or the scores stop describing the app.
