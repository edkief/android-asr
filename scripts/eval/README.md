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
| `threshold` | clustering threshold (Settings → Speakers; default 0.5) |
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
