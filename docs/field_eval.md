# Field-audio evaluation

The harness in `scripts/field_eval.py` runs the exact edge pipeline over real
recordings you supply, then reports measured metrics. Nothing is committed for
you and no field audio is shipped with this repository.

## 1. Arrange the audio

Either label subdirectories:

```
data/field/
  chainsaw/saw_001.wav
  gunshot/shot_003.wav
  benign/forest_ambience.wav
```

or provide a manifest (recommended when a file contains one labelled event):

```csv
file,label,start_s
clips/forest_001.wav,benign,
clips/saw_014.wav,chainsaw,2.5
```

Labels must be M9 classes: `chainsaw, gunshot, vehicle, human, benign`
(override with `--classes`). Files may be WAV/FLAC/OGG/MP3/M4A/AAC; everything
is resampled to 16 kHz mono, and silence below -60 dBFS is skipped exactly as
the edge agent does.

## 2. Smoke test without loading models

```bash
cd forestear
../.venv/bin/python scripts/field_eval.py --dir data/field --dry-run
```

This validates paths, labels and counts only. It prints no metrics.

## 3. Real run

```bash
../.venv/bin/python scripts/field_eval.py \
  --dir data/field --manifest data/field/labels.csv \
  --out reports/field_eval.json
```

Output JSON contains: label counts, analysed windows per file, M9 per-class
precision/recall/F1, macro-F1, accuracy, confusion matrix, ECE, and per-binary
model AUROC/average precision for M2/M3/M4/M7. Console prints a short table.

Use `--limit-windows N` for a quick look; the report then reflects only those
windows.

## 4. What the numbers mean

- These are **field** numbers, not the held-out test-split numbers in
  `reports/metrics_per_model.json`. Keep them separate in any write-up.
- Field audio is out-of-distribution relative to ESC-50/UrbanSound8K/FSD50K.
  A low score here is a real finding, not a bug to hide.
- Binary models are scored one-vs-rest: for M3 the positive class is
  `chainsaw`; every other labelled window counts as a negative.
- AUROC/AP are null when a label has no positive (or no negative) windows.
  That is reported as null, never as 0.

## 5. Verified behaviour (this repo, synthetic smoke audio)

Run on 2026-11-16 with two 3 s synthetic WAVs (a 120 Hz amplitude-modulated
tone labelled `chainsaw`, white noise labelled `benign`) to prove the harness
executes end to end:

- 2 files, 10 non-silent windows analysed, all models loaded from local assets.
- M9 macro-F1 0.133, accuracy 0.50, ECE 0.48 — the tone was classified
  `benign`, which is expected: the synthetic tone is not a chainsaw recording.
- The report was written and the JSON schema matched the fields above.

This is a **harness smoke test, not a field result**. Do not quote these
numbers as model performance.

## 6. Honest limitations

- No real forest audio has been evaluated yet.
- The harness measures per-window classification, not multi-window
  persistence or the weather veto; those are covered by `reports/decision_sim.json`.
- It does not measure phone-side latency or the Android TFLite path; use the
  on-device benchmark for that.
- Label quality is the operator's responsibility. Mislabeled windows will
  produce misleading metrics.
