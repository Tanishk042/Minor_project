"""Window cache: ONE heavy pass over the dataset.

For every manifest clip we generate a fixed number of augmented 1.0 s windows
(seed pinned) and cache, per window:
  - log-mel (98, 64) float16
  - YAMNet 1024-d embedding (float32)
  - handcrafted features (6)
  - M1 selected-class scores (12)
  - class label, split (train/val/test), origin_id

All later training (M2-M7), OOF stacking (M9) and evaluation read this cache,
so every model sees exactly the same windows -> comparable, honest metrics.

Fold mapping (dataset-provided folds only, never split):
  ESC-50    fold 1-3 -> train, 4 -> val, 5 -> test
  US8K      fold 1-6 -> train, 7 -> val, 8-10 -> test
  FSD50K    dev/train -> train, dev/val -> val, eval -> test
  synthetic 80/10/10 (index-hash, deterministic)
"""
from __future__ import annotations

import logging
import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from common.config import data_dir, load_config  # noqa: E402
from ml.audio_io import load_audio  # noqa: E402
from ml.augment import BackgroundBank, augment_event  # noqa: E402
from ml.features import YamNet, log_mel, handcrafted, logmel_tf_input  # noqa: E402

log = logging.getLogger("forestear.cache")
SR = 16000


def _rowget(row, key):
    """Read a field from a dict-like or attribute-style row.
    ('class' is a reserved word: pandas itertuples renames that column, so
    all callers pass plain dicts — never itertuples rows — for 'class'.)"""
    if hasattr(row, key):
        return getattr(row, key)
    return row[key]


def split_of(row) -> str:
    source = _rowget(row, "source")
    fold = int(_rowget(row, "fold"))
    if source == "esc50":
        return {1: "train", 2: "train", 3: "train", 4: "val", 5: "test"}[fold]
    if source == "urbansound8k":
        return "train" if fold <= 6 else ("val" if fold == 7 else "test")
    if source == "fsd50k":
        return {1: "train", 6: "val", 9: "test"}[fold]
    # synthetic folds 1,6,9 -> train,val,test
    return {1: "train", 6: "val", 9: "test"}[fold]


def build_cache(windows_per_clip: int = 4, max_per_class_train: int = 2600,
                balanced: bool = True, yamnet_backend: str = "hub") -> Path:
    """Build data/cache/windows.npz + windows_index.csv."""
    import tensorflow as tf  # noqa: F401  (register kernels)

    cfg = load_config()
    man = pd.read_csv(data_dir("dataset") / "manifest.csv")
    bank = BackgroundBank.from_dir(data_dir("background"), max_clips=400)
    yam = YamNet(backend=yamnet_backend,
                 model_dir=Path("models_store/m1_yamnet"))
    sel_names = cfg["features"]["yamnet_selected_classes"]

    rng = np.random.default_rng(cfg["training"]["seed"])

    # balance per (class, split): cap train windows per class
    counts: dict[tuple[str, str], int] = {}
    clip_list = []
    for r in man.to_dict("records"):
        sp = split_of(r)
        key = (r["class"], sp)
        if balanced and sp == "train" and counts.get(key, 0) >= max_per_class_train:
            continue
        clip_list.append(r)
        counts[key] = counts.get(key, 0) + windows_per_clip
    log.info("clips to window: %d (train caps %s)", len(clip_list), counts)

    X_logmel: list[np.ndarray] = []
    X_emb: list[np.ndarray] = []
    X_hand: list[np.ndarray] = []
    X_m1: list[np.ndarray] = []
    y: list[str] = []
    splits: list[str] = []
    origins: list[str] = []

    for i, r in enumerate(clip_list):
        try:
            wav = load_audio(r["path"], sr=SR)
        except Exception as e:  # noqa: BLE001
            log.warning("unloadable %s: %s", r["path"], e)
            continue
        n_win = windows_per_clip
        for w in range(n_win):
            # deterministic per-window seed (stable hash; PYTHONHASHSEED-independent)
            import hashlib
            h = int(hashlib.sha256(f"{r['origin_id']}|{w}".encode()).hexdigest()[:8], 16)
            seed = (h & 0x7FFFFFFF) ^ int(cfg["training"]["seed"])
            wrng = np.random.default_rng(seed)
            # first window: clean (no augmentation) so real data dominates
            if w == 0 and r["source"] != "synthetic":
                win = wav
                if len(win) > SR:  # random 1 s crop
                    s = int(wrng.integers(0, len(win) - SR))
                    win = win[s:s + SR]
                if len(win) < SR:
                    win = np.pad(win, (0, SR - len(win)))
                win = win.astype(np.float32)
                dist = None
            else:
                # crop a random 1 s event slice, then augment
                if len(wav) > SR:
                    s = int(wrng.integers(0, len(wav) - SR))
                    ev = wav[s:s + SR]
                else:
                    ev = np.pad(wav, (0, SR - len(wav)))
                dist = None
                # distance simulation on 1/3 of augmented windows
                if wrng.uniform() < 0.33:
                    dist = float(wrng.uniform(20, 400))
                win = augment_event(ev, bank, wrng, sr=SR,
                                    distance_m=dist,
                                    snr_range_db=tuple(cfg["training"]["snr_range_db"]))
            lm = log_mel(win)
            emb, _ = None, None
            scores_v = None
            try:
                scores, emb = yam(win)
                sel = yam.selected_scores(win, sel_names)
                scores_v = np.array([sel.get(n, 0.0) for n in sel_names],
                                    dtype=np.float32)
            except Exception as e:  # noqa: BLE001
                log.warning("yamnet failed on %s w%d: %s", r["origin_id"], w, e)
                emb = np.zeros(1024, dtype=np.float32)
                scores_v = np.zeros(len(sel_names), dtype=np.float32)
            hc = handcrafted(win)
            X_logmel.append(lm.astype(np.float16))
            X_emb.append(np.asarray(emb, dtype=np.float32))
            X_hand.append(hc.as_vector())
            X_m1.append(scores_v)
            y.append(r["class"])
            splits.append(split_of(r))
            origins.append(r["origin_id"])
        if i % 100 == 0:
            log.info("  windowed %d/%d clips (%d windows)", i, len(clip_list), len(y))

    out = data_dir("cache")
    np.savez_compressed(
        out / "windows.npz",
        X_logmel=np.stack(X_logmel) if X_logmel else np.zeros((0, 64, 98), np.float16),
        X_emb=np.stack(X_emb) if X_emb else np.zeros((0, 1024), np.float32),
        X_hand=np.stack(X_hand) if X_hand else np.zeros((0, 6), np.float32),
        X_m1=np.stack(X_m1) if X_m1 else np.zeros((0, 12), np.float32),
        y=np.array(y), split=np.array(splits), origin=np.array(origins),
    )
    idx = pd.DataFrame({"y": y, "split": splits, "origin": origins})
    idx.to_csv(out / "windows_index.csv", index=False)
    log.info("cache built: %d windows -> %s", len(y), out / "windows.npz")
    log.info("per split/class:\n%s",
             idx.groupby(["split", "y"]).size().to_string())
    return out / "windows.npz"


class WindowCache:
    """Reader for the cached windows (memory-mapped, converts on access)."""

    def __init__(self, path: str | Path | None = None):
        p = Path(path) if path else data_dir("cache") / "windows.npz"
        if not p.exists():
            raise FileNotFoundError(f"window cache missing: {p} (run scripts/build_cache.py)")
        self.z = np.load(p, allow_pickle=False)
        self.y = self.z["y"]
        self.split = self.z["split"]
        self.origin = self.z["origin"]
        self.X_hand = self.z["X_hand"]
        self.X_m1 = self.z["X_m1"]
        self.X_emb = self.z["X_emb"]
        self.X_logmel = self.z["X_logmel"]

    def logmel_tf(self, idx: np.ndarray) -> np.ndarray:
        """(n, 98, 64, 1) float32 batch for Keras conv models."""
        return np.transpose(self.X_logmel[idx], (0, 2, 1))[..., None].astype(np.float32)

    def subset(self, split: str, classes: list[str] | None = None) -> np.ndarray:
        m = self.split == split
        if classes is not None:
            m &= np.isin(self.y, classes)
        return np.where(m)[0]


if __name__ == "__main__":
    import argparse

    from common.logging_setup import setup_logging

    setup_logging()
    ap = argparse.ArgumentParser()
    ap.add_argument("--windows-per-clip", type=int, default=4)
    ap.add_argument("--max-per-class-train", type=int, default=2600)
    ap.add_argument("--tflite-yamnet", action="store_true",
                    help="use the YAMNet TFLite backend instead of hub (slower, no TF dep)")
    args = ap.parse_args()
    build_cache(args.windows_per_clip, args.max_per_class_train,
                yamnet_backend="tflite" if args.tflite_yamnet else "hub")
