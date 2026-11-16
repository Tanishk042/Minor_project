"""Field-audio evaluation harness.

Point this at a directory of real recordings plus a label manifest and it runs
the same ModelZoo pipeline the edge agent uses, then writes measured metrics.
It never invents numbers: if a model or a label is missing, the corresponding
metric is null and the report says why.

Manifest CSV (header required, comma-separated):

    file,label
    clips/forest_001.wav,benign
    clips/saw_014.wav,chainsaw

`file` is relative to --dir (absolute paths also work). `label` must be one of
the M9 classes in --classes. Optional `start_s` restricts analysis to a slice
of the file (useful when a clip contains one labelled event).

If --manifest is omitted, the label is taken from the file's parent directory
name (e.g. clips/chainsaw/saw_014.wav -> chainsaw).

Output: reports/field_eval_<utc>.json plus a short console table.

Example:

    ../.venv/bin/python scripts/field_eval.py \
        --dir data/field --manifest data/field/labels.csv \
        --out reports/field_eval.json
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO))

DEFAULT_CLASSES = ["chainsaw", "gunshot", "vehicle", "human", "benign"]

# binary models -> the single class they are supposed to detect
BINARY_MODELS = {"m2": "gunshot", "m3": "chainsaw", "m4": "vehicle", "m7": "human"}

AUDIO_EXTS = {".wav", ".flac", ".ogg", ".mp3", ".m4a", ".aac"}


# ------------------------------------------------------------------ manifest
def read_manifest(path: Path, root: Path, classes: list[str]) -> list[dict]:
    """Parse and validate a manifest. Raises ValueError on bad rows."""
    rows: list[dict] = []
    with path.open(newline="") as fh:
        reader = csv.DictReader(fh)
        missing = {"file", "label"} - set(reader.fieldnames or [])
        if missing:
            raise ValueError(f"manifest missing columns: {sorted(missing)}")
        for i, raw in enumerate(reader, start=2):
            label = (raw.get("label") or "").strip().lower()
            if label not in classes:
                raise ValueError(f"line {i}: label {label!r} not in {classes}")
            f = Path((raw.get("file") or "").strip())
            if not f.is_absolute():
                f = root / f
            if not f.exists():
                raise ValueError(f"line {i}: file not found: {f}")
            start = raw.get("start_s")
            rows.append({"file": str(f), "label": label,
                         "start_s": float(start) if start not in (None, "") else None})
    return rows


def discover(root: Path, classes: list[str]) -> list[dict]:
    """Fallback: label = parent directory name, skipping unknown dirs."""
    rows = []
    for f in sorted(root.rglob("*")):
        if not f.is_file() or f.suffix.lower() not in AUDIO_EXTS:
            continue
        label = f.parent.name.lower()
        if label in classes:
            rows.append({"file": str(f), "label": label, "start_s": None})
    return rows


# ------------------------------------------------------------------ metrics
def confusion(y_true: list[int], y_pred: list[int], n: int) -> list[list[int]]:
    cm = [[0] * n for _ in range(n)]
    for t, p in zip(y_true, y_pred):
        cm[t][p] += 1
    return cm


def prf(y_true: np.ndarray, y_pred: np.ndarray, classes: list[str]) -> dict:
    """One-vs-rest P/R/F1 per class + macro F1 + accuracy (sklearn-free)."""
    per = {}
    for j, c in enumerate(classes):
        tp = int(np.sum((y_pred == j) & (y_true == j)))
        fp = int(np.sum((y_pred == j) & (y_true != j)))
        fn = int(np.sum((y_pred != j) & (y_true == j)))
        prec = tp / (tp + fp) if tp + fp else 0.0
        rec = tp / (tp + fn) if tp + fn else 0.0
        f1 = 2 * prec * rec / (prec + rec) if prec + rec else 0.0
        per[c] = {"precision": round(prec, 4), "recall": round(rec, 4),
                  "f1": round(f1, 4), "support": int(np.sum(y_true == j)),
                  "tp": tp, "fp": fp, "fn": fn}
    acc = float(np.mean(y_pred == y_true)) if len(y_true) else None
    macro = (float(np.mean([per[c]["f1"] for c in classes]))
             if len(y_true) else None)
    return {"per_class": per,
            "accuracy": round(acc, 4) if acc is not None else None,
            "macro_f1": round(macro, 4) if macro is not None else None,
            "n": int(len(y_true))}


def auroc(y_true: np.ndarray, scores: np.ndarray) -> float | None:
    pos, neg = scores[y_true == 1], scores[y_true == 0]
    if len(pos) == 0 or len(neg) == 0:
        return None
    allv = np.concatenate([pos, neg])
    order = np.argsort(allv, kind="stable")
    ranks = np.empty(len(allv), dtype=np.float64)
    sv = allv[order]
    i = 0
    while i < len(sv):
        j = i
        while j + 1 < len(sv) and sv[j + 1] == sv[i]:
            j += 1
        ranks[order[i:j + 1]] = (i + j) / 2.0 + 1.0
        i = j + 1
    rp = ranks[:len(pos)].sum()
    return float((rp - len(pos) * (len(pos) + 1) / 2.0) / (len(pos) * len(neg)))


def average_precision(y_true: np.ndarray, scores: np.ndarray) -> float | None:
    order = np.argsort(-scores, kind="stable")
    y = y_true[order]
    P = np.cumsum(y)
    idx = np.where(y == 1)[0]
    if len(idx) == 0:
        return None
    return float(np.mean(P[idx] / (idx + 1)))


def ece(probs: np.ndarray, y_true: np.ndarray, n_bins: int = 10) -> float | None:
    if len(y_true) == 0:
        return None
    conf = probs.max(axis=1)
    pred = probs.argmax(axis=1)
    correct = (pred == y_true).astype(float)
    bins = np.linspace(0, 1, n_bins + 1)
    total = 0.0
    for lo, hi in zip(bins[:-1], bins[1:]):
        m = (conf > lo) & (conf <= hi)
        if m.sum():
            total += m.sum() / len(conf) * abs(correct[m].mean() - conf[m].mean())
    return float(total)


# ------------------------------------------------------------------ pipeline
def analyse(rows: list[dict], classes: list[str], hop_s: float,
            limit_windows: int | None, quiet: bool = False) -> dict:
    """Run ModelZoo over every row. Imports TF lazily so helpers stay testable."""
    from edge_agent.zoo import ModelZoo
    from ml.audio_io import frame_windows, load_audio, rms_db

    zoo = ModelZoo()
    y_true: list[int] = []
    y_pred: list[int] = []
    probs: list[np.ndarray] = []
    bin_scores: dict[str, list[float]] = defaultdict(list)
    bin_true: dict[str, list[int]] = defaultdict(list)
    per_file: list[dict] = []
    windows_done = 0

    for row in rows:
        wav = load_audio(row["file"])
        if row["start_s"] is not None:
            a = int(row["start_s"] * 16000)
            wav = wav[a:a + 16000 * 10]
        n_before = windows_done
        for _, win in frame_windows(wav, 1.0, hop_s):
            if limit_windows is not None and windows_done >= limit_windows:
                break
            level = rms_db(win)
            if level < -60.0:          # same silence rule as the edge agent
                continue
            r = zoo(win)
            p = np.array([r.m9_probs.get(c, 0.0) for c in classes],
                         dtype=np.float64)
            s = p.sum()
            if s > 0:
                p = p / s
            idx = int(p.argmax())
            y_true.append(classes.index(row["label"]))
            y_pred.append(idx)
            probs.append(p)
            for mid, cls in BINARY_MODELS.items():
                bin_scores[mid].append(float(getattr(r, mid)))
                bin_true[mid].append(1 if row["label"] == cls else 0)
            windows_done += 1
        if not quiet:
            print(f"  {Path(row['file']).name}: label={row['label']} "
                  f"windows={windows_done - n_before}")
        per_file.append({"file": row["file"], "label": row["label"],
                         "windows_used": windows_done - n_before})

    yt = np.asarray(y_true, dtype=int)
    yp = np.asarray(y_pred, dtype=int)
    P = np.vstack(probs) if probs else np.zeros((0, len(classes)))

    binary = {}
    for mid, cls in BINARY_MODELS.items():
        t = np.asarray(bin_true[mid], dtype=int)
        s = np.asarray(bin_scores[mid], dtype=np.float64)
        binary[mid] = {"detects": cls,
                       "n_pos": int(t.sum()) if len(t) else 0,
                       "n_neg": int((1 - t).sum()) if len(t) else 0,
                       "auroc": auroc(t, s) if len(t) else None,
                       "average_precision": average_precision(t, s) if len(t) else None}

    return {
        "windows": int(len(yt)),
        "files": per_file,
        "m9": prf(yt, yp, classes) if len(yt) else None,
        "m9_confusion": confusion(y_true, y_pred, len(classes)) if len(yt) else None,
        "m9_ece": ece(P, yt) if len(yt) else None,
        "binary": binary,
    }


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dir", required=True, help="root directory of field audio")
    ap.add_argument("--manifest", default=None, help="CSV with file,label[,start_s]")
    ap.add_argument("--out", default=None, help="default reports/field_eval_<utc>.json")
    ap.add_argument("--classes", default=",".join(DEFAULT_CLASSES))
    ap.add_argument("--hop-s", type=float, default=0.5)
    ap.add_argument("--limit-windows", type=int, default=None,
                    help="cap analysed windows (smoke runs)")
    ap.add_argument("--dry-run", action="store_true",
                    help="parse the manifest and report counts, run no models")
    args = ap.parse_args(argv)

    root = Path(args.dir).expanduser().resolve()
    if not root.is_dir():
        print(f"error: --dir is not a directory: {root}", file=sys.stderr)
        return 2
    classes = [c.strip().lower() for c in args.classes.split(",") if c.strip()]
    if len(classes) < 2:
        print("error: need at least two classes", file=sys.stderr)
        return 2

    try:
        rows = (read_manifest(Path(args.manifest), root, classes)
                if args.manifest else discover(root, classes))
    except ValueError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    if not rows:
        print("error: no labelled audio found; provide --manifest or label "
              "subdirectories", file=sys.stderr)
        return 2

    counts = Counter(r["label"] for r in rows)
    print(f"files={len(rows)} classes={dict(counts)}")
    if args.dry_run:
        print("dry run: no models loaded, no metrics computed")
        return 0

    started = datetime.now(timezone.utc)
    try:
        result = analyse(rows, classes, args.hop_s, args.limit_windows)
    except Exception as e:  # noqa: BLE001
        print(f"error: model pipeline failed: {e}", file=sys.stderr)
        print("Field metrics are NOT available; do not report numbers.", file=sys.stderr)
        return 3

    report = {
        "measured_at_utc": started.isoformat(),
        "harness": "scripts/field_eval.py",
        "audio_root": str(root),
        "manifest": str(Path(args.manifest).resolve()) if args.manifest else None,
        "classes": classes,
        "hop_s": args.hop_s,
        "label_counts": dict(counts),
        "result": result,
        "provenance": {
            "audio": "operator-provided field recordings (not shipped)",
            "models": "same assets used by the edge agent",
            "note": "Field audio is out-of-distribution relative to the "
                    "public training sets; treat these numbers separately "
                    "from the held-out test-split metrics.",
        },
    }
    out = Path(args.out) if args.out else REPO / "reports" / (
        "field_eval_" + started.strftime("%Y%m%dT%H%M%SZ") + ".json")
    if not out.is_absolute():
        out = REPO / out
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, indent=2) + "\n")

    if result["m9"]:
        print(f"\nwindows={result['windows']} "
              f"macro_f1={result['m9']['macro_f1']} "
              f"accuracy={result['m9']['accuracy']} "
              f"ece={result['m9_ece']}")
        for c, m in result["m9"]["per_class"].items():
            print(f"  {c:10s} P={m['precision']:.3f} R={m['recall']:.3f} "
                  f"F1={m['f1']:.3f} n={m['support']}")
    else:
        print("\nno windows analysed (all silent or --limit-windows=0)")
    print(f"\nwrote {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
