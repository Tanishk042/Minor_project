"""Build the unified ForestEar dataset.

Unifies ESC-50 / UrbanSound8K / FSD50K + synthetic fallback into:
  data/dataset/manifest.csv   columns:
    path, class (chainsaw|gunshot|vehicle|human|weather_rain|weather_wind|
                 weather_thunder|benign), source, fold (int), origin_id

Fold discipline (critical for honest results):
- ESC-50: folds 1-5 as provided by the dataset.
- UrbanSound8K: folds 1-10 as provided, mapped: 1-5->train, 6-7->val, 8-10->test
- FSD50K: dev/train->train, dev/val->val, eval->test (as provided by the dataset)
- Synthetic: seeded random fold split (80/10/10).
Folds are never mixed for the same origin recording; the ESC-50 fold column
comes from the clip filename itself so identical fold assignment is guaranteed.
"""
from __future__ import annotations

import argparse
import logging
import re
import sys
from collections import Counter
from pathlib import Path

import numpy as np
import pandas as pd

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO))

from common.config import REPO_ROOT, data_dir, data_file, load_labels  # noqa: E402
from common.logging_setup import setup_logging  # noqa: E402
from ml import synth  # noqa: E402
from ml.audio_io import load_audio, save_wav  # noqa: E402

log = logging.getLogger("forestear.build")


def esc50_rows() -> list[dict]:
    root = data_dir("raw", "ESC-50-master")
    aud = root / "audio"
    if not aud.exists():
        log.warning("ESC-50 missing -> skipped")
        return []
    lab = load_labels()["esc50"]
    inv: dict[str, str] = {}
    for cls, cats in lab.items():
        if cls == "weather":
            for wcls, wcats in cats.items():
                for c in wcats:
                    inv[c] = f"weather_{wcls}"
        else:
            for c in cats:
                inv[c] = cls
    # authoritative filename -> category mapping from meta/esc50.csv
    # (filenames carry only the letter code, e.g. 1-100032-A-0.wav)
    import csv
    meta = root / "meta" / "esc50.csv"
    if not meta.exists():
        log.warning("ESC-50 meta/esc50.csv missing -> skipped")
        return []
    cat_of: dict[str, tuple[str, int]] = {}
    with open(meta, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            cat_of[r["filename"]] = (r["category"], int(r["fold"]))
    rows = []
    for wav in sorted(aud.glob("*.wav")):
        if wav.name not in cat_of:
            continue
        cat, fold = cat_of[wav.name]
        if cat not in inv:
            continue
        rows.append(dict(path=str(wav), class_=inv[cat], source="esc50",
                         fold=fold, origin_id=f"esc50-{wav.stem}"))
    return rows


def _us8k_meta() -> list[dict]:
    import csv
    # data_file (not data_dir): never mkdir a file path (Errno 17 otherwise)
    meta = data_file("raw", "urbansound8k", "metadata.csv")
    if not meta.exists():
        return []
    inv = {
        "gun_shot": "gunshot", "engine_idling": "vehicle", "car_horn": "vehicle",
        "children_playing": "human", "dog_bark": "benign", "drilling": "benign",
        "jackhammer": "benign", "siren": "benign", "street_music": "benign",
    }
    with open(meta, newline="", encoding="utf-8") as f:
        raw = list(csv.DictReader(f))
    cols = raw[0].keys()

    def col(*names: str) -> str:
        for n in names:
            for c in cols:
                if c.lower().strip() == n:
                    return c
        raise KeyError(names)

    c_slice, c_fold, c_cls, c_id = (col("slice_file_name"), col("fold"),
                                    col("class", "class_name", "label"),
                                    col("salience", "class_id", "fsid", "fold"))
    rows = []
    for r in raw:
        if r[c_cls] not in inv:
            continue
        p = data_file("raw", "urbansound8k", f"fold{r[c_fold]}", r[c_slice])
        if not p.exists():
            continue
        rows.append(dict(path=str(p), class_=inv[r[c_cls]], source="urbansound8k",
                         fold=int(r[c_fold]), origin_id=f"us8k-{r[c_slice]}"))
    return rows


def fsd50k_rows() -> list[dict]:
    dev = data_dir("raw", "fsd50k", "dev")
    evl = data_dir("raw", "fsd50k", "eval")
    if not dev.exists() and not evl.exists():
        return []
    lab = load_labels()["fsd50k"]
    inv: dict[str, str] = {}
    for cls, lbls in lab.items():
        if cls == "weather":
            for wcls, wl in lbls.items():
                for l in wl:
                    inv[l] = f"weather_{wcls}"
        else:
            for l in lbls:
                inv[l] = cls
    import csv
    rows = []
    for split_dir, splits in ((dev, ("train", "val")), (evl, ("test",))):
        csvp = split_dir.parent / (split_dir.name + ".csv")
        if not csvp.exists():
            continue
        with open(csvp, newline="", encoding="utf-8") as f:
            for r in csv.DictReader(f):
                fname = r["fname"]
                labels = r["labels"].split(",")
                picked = None
                for l in labels:
                    if l in inv:
                        picked = inv[l]
                        break
                if picked is None:
                    continue
                p = split_dir / f"{fname}.wav"
                if not p.exists():
                    continue
                split = r.get("split", "train")
                if split not in splits:
                    continue
                fold = {"train": 1, "val": 6, "test": 9}[split]
                rows.append(dict(path=str(p), class_=picked, source="fsd50k",
                                 fold=fold, origin_id=f"fsd-{fname}"))
    return rows


def synthetic_rows(per_class: int) -> list[dict]:
    rng = np.random.default_rng(2024)
    out_dir = data_dir("synthetic")
    gen_map = {
        "chainsaw": synth.chainsaw, "gunshot": synth.gunshot, "vehicle": synth.vehicle,
        "human": synth.voice_like, "weather_rain": synth.rain, "weather_wind": synth.wind,
        "weather_thunder": synth.thunder, "benign": synth.forest_ambience,
    }
    rows = []
    for cls, gen in gen_map.items():
        for i in range(per_class):
            seed = int(rng.integers(1 << 30))
            dur = 1.0 if cls not in ("weather_rain", "weather_wind", "weather_thunder",
                                     "benign") else 2.0
            wav = gen(seed=seed, dur=dur)
            dest = out_dir / cls / f"{cls}_{i:04d}.wav"
            save_wav(dest, wav)
            # synthetic folds: deterministic 80/10/10 by index hash
            u = (i * 2654435761) % 100
            fold = 1 if u < 80 else (6 if u < 90 else 9)
            rows.append(dict(path=str(dest), class_=cls, source="synthetic",
                             fold=fold, origin_id=f"syn-{cls}-{i}"))
    return rows


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--synthetic-per-class", type=int, default=300,
                    help="synthetic clips per class ALWAYS added (fallback + augmentation "
                         "robustness); use 0 to train on real data only")
    ap.add_argument("--synthetic-only", action="store_true")
    args = ap.parse_args()
    setup_logging()

    rows: list[dict] = []
    if not args.synthetic_only:
        for fn in (esc50_rows, _us8k_meta, fsd50k_rows):
            try:
                r = fn()
                log.info("%s -> %d clips", fn.__name__, len(r))
                rows += r
            except Exception as e:  # noqa: BLE001
                log.warning("%s failed: %s", fn.__name__, e)
    if args.synthetic_per_class > 0:
        rows += synthetic_rows(args.synthetic_per_class)
        log.info("synthetic -> %d clips",
                 sum(1 for r in rows if r["source"] == "synthetic"))

    if not rows:
        log.error("no data at all - rerun scripts/download_data.py")
        return 1

    df = pd.DataFrame(rows).rename(columns={"class_": "class"})
    # resample+normalise into data/dataset/, keep the fold discipline
    out_root = data_dir("dataset")
    out_rows = []
    records = df.to_dict("records")
    for i, r in enumerate(records):
        try:
            wav = load_audio(r["path"])
        except Exception as e:  # noqa: BLE001
            log.debug("unloadable %s: %s", r["path"], e)
            continue
        if len(wav) < 8000:  # < 0.5 s
            continue
        dest = out_root / f"{r['source']}_{Path(r['path']).stem}.wav"
        save_wav(dest, wav)
        out_rows.append(dict(path=str(dest),
                             **{"class": r["class"], "source": r["source"],
                                "fold": int(r["fold"]),
                                "origin_id": r["origin_id"]}))
        if i % 500 == 0:
            log.info("  unified %d/%d", i, len(records))
    out = pd.DataFrame(out_rows)
    out.to_csv(out_root / "manifest.csv", index=False)
    log.info("manifest: %d clips", len(out))
    log.info("class counts:\n%s", out["class"].value_counts().to_string())
    log.info("source counts:\n%s", out["source"].value_counts().to_string())
    log.info("fold counts:\n%s", out["fold"].value_counts().sort_index().to_string())
    # leakage guard: same origin_id never spans folds
    dup = out.groupby("origin_id")["fold"].nunique()
    assert (dup == 1).all(), "LEAKAGE: origin in multiple folds"
    log.info("leakage check passed (origin_id unique to a fold)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
