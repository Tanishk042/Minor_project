"""Train M9 fusion meta-classifier on OUT-OF-FOLD predictions (stacking).

Process (leakage-free):
1. Load window cache; train split only used to fit M2-M7 (already done by
   train_m2_m7.py on TRAIN folds).
2. Predict M2-M7 on the VAL split (these folds were never trained on ->
   out-of-fold predictions), build the full M9 feature vector there.
3. Fit LightGBM (multiclass, 5 classes) on OOF features; calibrate per class
   with isotonic regression using a random 25% holdout of the OOF set.
4. Persist: m9_lgbm.pkl (native), m9_python.py (m2cgen transpile, edge),
   m9_iso.pkl (isotonic calibrators).
5. Train the Keras MLP alternative on the same OOF features (for TFLite).
The TEST split is never touched here.
"""
from __future__ import annotations

import logging
import sys
import time
from pathlib import Path

import numpy as np

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO))

from common.config import load_config, models_dir, reports_dir  # noqa: E402
from common.logging_setup import setup_logging  # noqa: E402
from ml.models.m9_fusion import (M9LightGBM, M9MLP, N_FEATURES, TEMPORAL_MODELS,
                                 CONTEXT_WINDOWS)  # noqa: E402
from ml.window_cache import WindowCache  # noqa: E402

log = logging.getLogger("forestear.train_m9")
CLASSES = ["chainsaw", "gunshot", "vehicle", "human", "benign"]


def _class_of(label: str) -> str:
    """Map window label -> M9 final class."""
    if label.startswith("weather_"):
        return "benign"   # weather is handled by M5 veto, not a final class
    if label == "benign":
        return "benign"
    return label


def _oof_features(cache: WindowCache, split: str) -> tuple[np.ndarray, np.ndarray]:
    """Run M2-M7 over a split via the edge zoo logic and build M9 features."""
    import tensorflow as tf  # noqa: F401

    cfg = load_config()
    sel_names = cfg["features"]["yamnet_selected_classes"]

    # --- load models (Keras, fast enough for this offline pass)
    m2 = tf.keras.models.load_model(models_dir("m2_gunshot") / "model.keras",
                                   compile=False)
    m5 = tf.keras.models.load_model(models_dir("m5_weather") / "model.keras",
                                    compile=False)
    m6 = tf.keras.models.load_model(models_dir("m6_anomaly") / "model.keras",
                                    compile=False)
    m3 = tf.keras.models.load_model(models_dir("m3_chainsaw") / "model.keras",
                                    compile=False)
    m4 = tf.keras.models.load_model(models_dir("m4_vehicle") / "model.keras",
                                    compile=False)
    m7 = tf.keras.models.load_model(models_dir("m7_human") / "model.keras",
                                    compile=False)
    base = np.load(models_dir("m6_anomaly") / "baseline.npy")
    m6_med, m6_mad = float(base[0]), float(base[1])

    def norm(mid):
        p = models_dir(mid) / "norm.npy"
        return np.load(p) if p.exists() else None

    n3, n4, n7 = norm("m3_chainsaw"), norm("m4_vehicle"), norm("m7_human")

    idx = cache.subset(split)
    y_true = np.array([CLASSES.index(_class_of(c)) for c in cache.y[idx]])
    logmel = cache.logmel_tf(idx)
    emb = cache.X_emb[idx]
    hand = cache.X_hand[idx]
    m1sel = cache.X_m1[idx]

    # model outputs (batched)
    p2 = m2.predict(logmel, verbose=0, batch_size=64).reshape(-1)
    p5 = m5.predict(logmel, verbose=0, batch_size=64)
    rec = m6.predict(logmel, verbose=0, batch_size=64)
    err = np.mean(np.square(logmel - rec), axis=(1, 2, 3))
    z6 = (err - m6_med) / m6_mad
    x3 = (emb - n3[0]) / n3[1] if n3 is not None else emb
    p3 = m3.predict(x3, verbose=0, batch_size=64).reshape(-1)
    x4 = (emb - n4[0]) / n4[1] if n4 is not None else emb
    p4 = m4.predict(x4, verbose=0, batch_size=64).reshape(-1)
    # M7 extra features = subset of M1 selected scores present in cache
    from ml.models.m3_m4_m7_heads import M7_EXTRA_CLASSES
    extra = np.zeros((len(idx), len(M7_EXTRA_CLASSES)), np.float32)
    for j, cn in enumerate(M7_EXTRA_CLASSES):
        if cn in sel_names:
            extra[:, j] = m1sel[:, sel_names.index(cn)]
    x7 = np.hstack([emb, extra])
    if n7 is not None:
        x7 = (x7 - n7[0]) / n7[1]
    p7 = m7.predict(x7, verbose=0, batch_size=64).reshape(-1)

    # temporal context over windows with the SAME origin (order = cache order)
    origins = cache.origin[idx]
    feats = np.zeros((len(idx), N_FEATURES), np.float32)
    hist: dict[str, list[dict[str, float]]] = {}
    for i in range(len(idx)):
        o = str(origins[i])
        h = hist.setdefault(o, [])
        h.append({"m2": p2[i], "m3": p3[i], "m4": p4[i],
                  "m5_max": float(max(p5[i, 0], p5[i, 1], p5[i, 2])),
                  "m6": z6[i], "m7": p7[i]})
        from ml.models.m9_fusion import temporal_stats
        ctx = temporal_stats(h)
        hc = hand[i]
        feats[i] = np.concatenate([
            m1sel[i], [p2[i], p3[i], p4[i]], p5[i], [z6[i], p7[i]],
            hc, ctx, [np.sin(2 * np.pi * 9 / 24), np.cos(2 * np.pi * 9 / 24)],
        ]).astype(np.float32)
    assert feats.shape[1] == N_FEATURES
    return feats, y_true


def main() -> int:
    setup_logging()
    cfg = load_config()
    cache = WindowCache()

    t0 = time.time()
    Xval, yval = _oof_features(cache, "val")
    log.info("OOF(val) features %s in %.1fs", Xval.shape, time.time() - t0)

    import lightgbm as lgb
    from sklearn.isotonic import IsotonicRegression
    from sklearn.model_selection import train_test_split

    seed = cfg["training"]["seed"]
    Xtr, Xcal, ytr, ycal = train_test_split(Xval, yval, test_size=0.25,
                                            random_state=seed, stratify=yval)
    lgbm = lgb.LGBMClassifier(
        n_estimators=300, learning_rate=0.06, num_leaves=31,
        class_weight="balanced", random_state=seed, verbose=-1,
        objective="multiclass", n_jobs=2)
    lgbm.fit(Xtr, ytr)

    # isotonic calibration per class on the held-out calibration slice
    raw_cal = lgbm.predict_proba(Xcal)
    calibrators = {}
    for j, cls in enumerate(CLASSES):
        iso = IsotonicRegression(out_of_bounds="clip", y_min=0.0, y_max=1.0)
        iso.fit(raw_cal[:, j], (ycal == j).astype(float))
        calibrators[cls] = iso
    m9 = M9LightGBM(lgbm, calibrators, CLASSES)
    m9.save(models_dir("m9_fusion") / "m9_lgbm.pkl")

    # m2cgen export (plain Python for edge / no LightGBM runtime needed)
    import m2cgen as m2c
    py_code = m2c.export_to_python(lgbm)
    (models_dir("m9_fusion") / "m9_python.py").write_text(
        "# AUTO-GENERATED by m2cgen (LightGBM -> pure Python)\n" + py_code)
    import pickle
    with open(models_dir("m9_fusion") / "m9_iso.pkl", "wb") as f:
        pickle.dump([calibrators[c] for c in CLASSES], f)

    # Keras MLP alternative on the same OOF data
    import tensorflow as tf
    mlp = M9MLP.build()
    mlp.compile(optimizer=tf.keras.optimizers.Adam(1e-3),
                loss="sparse_categorical_crossentropy", metrics=["accuracy"])
    mlp.fit(Xtr, ytr, epochs=30, batch_size=64, verbose=2,
            validation_split=0.1)
    mlp.save(models_dir("m9_fusion") / "m9_mlp.keras")

    # quick val metrics (NOT test: test is reserved for evaluate.py)
    from sklearn.metrics import classification_report, f1_score
    proba = m9.predict_proba(Xval)
    pred = proba.argmax(axis=1)
    rep = classification_report(yval, pred, target_names=CLASSES, output_dict=True)
    f1m = f1_score(yval, pred, average="macro")
    # ECE on val
    conf = proba.max(axis=1)
    correct = (pred == yval).astype(float)
    bins = np.linspace(0, 1, 11)
    ece = 0.0
    for lo, hi in zip(bins[:-1], bins[1:]):
        m = (conf > lo) & (conf <= hi)
        if m.sum() > 0:
            ece += m.sum() / len(conf) * abs(correct[m].mean() - conf[m].mean())
    import json
    (reports_dir() / "train_m9.json").write_text(json.dumps({
        "n_val_windows": int(len(yval)), "macro_f1_val": float(f1m),
        "ece_val": float(ece), "train_seconds": round(time.time() - t0, 1),
        "report": rep}, indent=2))
    log.info("M9 val macro-F1 %.3f ECE %.3f", f1m, ece)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
