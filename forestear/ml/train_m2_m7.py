"""Train M2-M7 (all six custom models) from the window cache.

Usage: python ml/train_m2_m7.py [--only m2,m5] [--epochs N]

- M2 gunshot:  small CNN on log-mel, binary, focal loss (imbalance)
- M3 chainsaw: MLP head on YAMNet embedding
- M4 vehicle:  MLP head on YAMNet embedding
- M5 weather:  small CNN on log-mel, 4-class softmax (rain/wind/thunder/quiet)
- M6 anomaly:  conv autoencoder on benign TRAIN windows only
- M7 human:    MLP on YAMNet embedding + 8 selected M1 class scores
Splits strictly from the cache (train/val/test by dataset folds); the test
split is never touched here. Per-model metrics on val are written to
reports/ and model cards to docs/model_cards.md.
"""
from __future__ import annotations

import argparse
import json
import logging
import sys
import time
from pathlib import Path

import numpy as np
import pandas as pd

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO))

from common.config import load_config, models_dir, reports_dir  # noqa: E402
from common.logging_setup import setup_logging  # noqa: E402
from ml.models.m2_gunshot import build_m2, build_m5, focal_loss  # noqa: E402
from ml.models.m3_m4_m7_heads import build_m3, build_m4, build_m7, M7_EXTRA_CLASSES  # noqa: E402
from ml.models.m6_anomaly import build_m6  # noqa: E402
from ml.window_cache import WindowCache  # noqa: E402

log = logging.getLogger("forestear.train")


def _binary(cache: WindowCache, pos_class: str) -> tuple[np.ndarray, np.ndarray,
                                                          np.ndarray, np.ndarray]:
    """Indices/labels for a one-vs-rest binary task."""
    def ds(split: str):
        idx = cache.subset(split)
        y = (cache.y[idx] == pos_class).astype(np.float32)
        return idx, y
    tr_i, tr_y = ds("train")
    va_i, va_y = ds("val")
    return tr_i, tr_y, va_i, va_y


def train_binary_cnn(cache, build_fn, name: str, pos_class: str, epochs: int,
                     batch: int = 32) -> dict:
    import tensorflow as tf
    tr_i, tr_y, va_i, va_y = _binary(cache, pos_class)
    Xtr, Xva = cache.logmel_tf(tr_i), cache.logmel_tf(va_i)
    pos_w = float((len(tr_y) - tr_y.sum()) / max(tr_y.sum(), 1.0))
    model = build_fn()
    model.compile(optimizer=tf.keras.optimizers.Adam(1e-3),
                  loss=focal_loss(alpha=min(0.75, 0.25 + 0.5 / (1 + pos_w / 10))),
                  metrics=["accuracy", tf.keras.metrics.AUC(name="auc")])
    t0 = time.time()
    model.fit(Xtr, tr_y, validation_data=(Xva, va_y), epochs=epochs, batch_size=batch,
              verbose=2, class_weight={0: 1.0, 1: min(8.0, pos_w)})
    train_s = time.time() - t0
    p = model.predict(Xva, verbose=0).reshape(-1)
    from sklearn.metrics import roc_auc_score, average_precision_score
    auc = float(roc_auc_score(va_y, p)) if len(np.unique(va_y)) > 1 else float("nan")
    ap = float(average_precision_score(va_y, p)) if len(np.unique(va_y)) > 1 else float("nan")
    out = models_dir(name)
    model.save(out / "model.keras")
    return {"model": name, "auc": auc, "ap": ap, "n_train": len(tr_i), "n_val": len(va_i),
            "train_seconds": round(train_s, 1), "params": int(model.count_params())}


def train_mlp_head(cache, build_fn, name: str, pos_class: str, epochs: int,
                   extra: np.ndarray | None = None, batch: int = 64) -> dict:
    import tensorflow as tf
    tr_i, tr_y, va_i, va_y = _binary(cache, pos_class)

    def feats(idx):
        emb = cache.X_emb[idx]
        if extra is not None:
            return np.hstack([emb, extra[idx]])
        return emb
    Xtr, Xva = feats(tr_i), feats(va_i)
    mu, sd = Xtr.mean(axis=0), Xtr.std(axis=0) + 1e-6
    Xtr = (Xtr - mu) / sd
    Xva = (Xva - mu) / sd
    np.save(models_dir(name) / "norm.npy", np.stack([mu, sd]))
    pos_w = float((len(tr_y) - tr_y.sum()) / max(tr_y.sum(), 1.0))
    model = build_fn()
    model.compile(optimizer=tf.keras.optimizers.Adam(1e-3),
                  loss="binary_crossentropy",
                  metrics=["accuracy", tf.keras.metrics.AUC(name="auc")])
    t0 = time.time()
    model.fit(Xtr, tr_y, validation_data=(Xva, va_y), epochs=epochs, batch_size=batch,
              verbose=2, class_weight={0: 1.0, 1: min(8.0, pos_w)})
    train_s = time.time() - t0
    p = model.predict(Xva, verbose=0).reshape(-1)
    from sklearn.metrics import roc_auc_score, average_precision_score
    auc = float(roc_auc_score(va_y, p)) if len(np.unique(va_y)) > 1 else float("nan")
    ap = float(average_precision_score(va_y, p)) if len(np.unique(va_y)) > 1 else float("nan")
    model.save(models_dir(name) / "model.keras")
    return {"model": name, "auc": auc, "ap": ap, "n_train": len(tr_i), "n_val": len(va_i),
            "train_seconds": round(train_s, 1), "params": int(model.count_params())}


def train_m5(cache, epochs: int, batch: int = 32) -> dict:
    import tensorflow as tf
    weather = ["weather_rain", "weather_wind", "weather_thunder"]
    cls_to_int = {c: i for i, c in enumerate(weather)}
    quiet = 3

    def ds(split: str):
        idx = cache.subset(split)
        y = np.array([cls_to_int.get(c, quiet) for c in cache.y[idx]], dtype=np.int32)
        return idx, y
    tr_i, tr_y = ds("train")
    va_i, va_y = ds("val")
    Xtr, Xva = cache.logmel_tf(tr_i), cache.logmel_tf(va_i)
    # class weights (weather classes are the minority)
    counts = np.bincount(tr_y, minlength=4)
    w = counts.sum() / np.maximum(counts, 1) / 2
    model = build_m5()
    model.compile(optimizer=tf.keras.optimizers.Adam(1e-3),
                  loss="sparse_categorical_crossentropy",
                  metrics=["accuracy"])
    t0 = time.time()
    model.fit(Xtr, tr_y, validation_data=(Xva, va_y), epochs=epochs, batch_size=batch,
              verbose=2, class_weight=dict(enumerate(w)))
    train_s = time.time() - t0
    p = model.predict(Xva, verbose=0)
    from sklearn.metrics import f1_score
    pred = p.argmax(axis=1)
    f1m = float(f1_score(va_y, pred, average="macro"))
    out = models_dir("m5_weather")
    model.save(out / "model.keras")
    return {"model": "m5_weather", "macro_f1": f1m, "n_train": len(tr_i), "n_val": len(va_i),
            "train_seconds": round(train_s, 1), "params": int(model.count_params())}


def train_m6(cache, epochs: int, batch: int = 32) -> dict:
    """AE trained ONLY on benign train windows (never weather/threat)."""
    import tensorflow as tf
    tr_i = cache.subset("train", classes=["benign"])
    va_i = cache.subset("val", classes=["benign"])
    Xtr = cache.logmel_tf(tr_i)
    Xva = cache.logmel_tf(va_i)
    model = build_m6()
    t0 = time.time()
    model.fit(Xtr, Xtr, validation_data=(Xva, Xva), epochs=epochs, batch_size=batch,
              verbose=2)
    train_s = time.time() - t0
    # val reconstruction error stats (baseline z-score parameters)
    rec = model.predict(Xva, verbose=0)
    err = np.mean(np.square(Xva - rec), axis=(1, 2, 3))
    med = float(np.median(err))
    mad = float(np.median(np.abs(err - med)) + 1e-9)
    np.save(models_dir("m6_anomaly") / "baseline.npy", np.array([med, mad]))
    model.save(models_dir("m6_anomaly") / "model.keras")
    # quick sanity: unseen threats should reconstruct worse
    th_i = cache.subset("val", classes=["gunshot", "chainsaw", "vehicle"])
    if len(th_i):
        Xt = cache.logmel_tf(th_i)
        rt = model.predict(Xt, verbose=0)
        err_t = np.mean(np.square(Xt - rt), axis=(1, 2, 3))
        z_t = (np.median(err_t) - med) / mad
    else:
        z_t = float("nan")
    return {"model": "m6_anomaly", "benign_med_err": med, "benign_mad": mad,
            "val_threat_zscore": float(z_t), "n_train": len(tr_i),
            "train_seconds": round(train_s, 1), "params": int(model.count_params())}


def main() -> int:
    setup_logging()
    cfg = load_config()
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="m2,m3,m4,m5,m6,m7")
    ap.add_argument("--epochs", type=int, default=None)
    args = ap.parse_args()
    epochs = args.epochs or cfg["training"]["epochs_small_cnn"]
    cache = WindowCache()
    which = set(args.only.split(","))

    results = []
    if "m2" in which:
        results.append(train_binary_cnn(cache, build_m2, "m2_gunshot",
                                        "gunshot", epochs))
    if "m3" in which:
        results.append(train_mlp_head(cache, build_m3, "m3_chainsaw",
                                       "chainsaw", cfg["training"]["epochs_mlp"]))
    if "m4" in which:
        results.append(train_mlp_head(cache, build_m4, "m4_vehicle",
                                       "vehicle", cfg["training"]["epochs_mlp"]))
    if "m5" in which:
        results.append(train_m5(cache, epochs))
    if "m6" in which:
        results.append(train_m6(cache, epochs))
    if "m7" in which:
        # extra input: 8 selected M1 class scores
        # NOTE: M7_EXTRA_CLASSES must exist among cached M1 selected scores; the
        # cache stores 12 configured classes; we intersect and pad missing with 0.
        sel_names = cfg["features"]["yamnet_selected_classes"]
        extra = np.zeros((len(cache.y), len(M7_EXTRA_CLASSES)), dtype=np.float32)
        for j, cn in enumerate(M7_EXTRA_CLASSES):
            if cn in sel_names:
                extra[:, j] = cache.X_m1[:, sel_names.index(cn)]
        results.append(train_mlp_head(cache, build_m7, "m7_human", "human",
                                       cfg["training"]["epochs_mlp"], extra=extra))

    for r in results:
        log.info("%s", r)
    path = reports_dir() / "train_m2_m7.json"
    old = []
    if path.exists():
        try:
            old = json.loads(path.read_text())
        except Exception:  # noqa: BLE001
            old = []
    merged = {d["model"]: d for d in old + results}
    path.write_text(json.dumps(list(merged.values()), indent=2))
    log.info("wrote %s", path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
