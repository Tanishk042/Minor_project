"""M9 Fusion meta-classifier: LightGBM (primary, calibrated via isometric
regression) with a Keras MLP alternative (exported to TFLite).

Trained on OUT-OF-FOLD predictions of M2-M7 (stacking, no leakage): the OOF
matrix is produced by evaluate.py/train_m9 scripts; this module only defines
the feature vector builder and the persistent wrapper class.

Feature vector (M9_FEATURE_ORDER is the contract shared by train + edge):
  0-11   M1 selected AudioSet scores (12 classes, configs/default.yaml)
  12     M2 gunshot
  13     M3 chainsaw
  14     M4 vehicle
  15-18  M5 rain, wind, thunder, quiet (softmax)
  19     M6 anomaly z-score
  20     M7 human
  21-26 handcrafted: rms_db, crest, spectral_flux, spectral_flatness,
         onset_strength, zcr
  27+5*k..: temporal context: per-model mean/max/slope over the last 5 windows
         for m2, m3, m4, m5_max, m6, m7 (6 models * 3 = 18 features)
  + hour-of-day sin/cos
"""
from __future__ import annotations

from pathlib import Path

import numpy as np

TEMPORAL_MODELS = ["m2", "m3", "m4", "m5_max", "m6", "m7"]
CONTEXT_WINDOWS = 5
N_M1_SCORES = 12
N_M5 = 4
N_HANDCRAFTED = 6

# fixed slice offsets into the M9 vector
OFFSET = {
    "m1_scores": 0,                    # 12
    "m2": N_M1_SCORES,                 # 1
    "m3": N_M1_SCORES + 1,
    "m4": N_M1_SCORES + 2,
    "m5": N_M1_SCORES + 3,             # 4
    "m6": N_M1_SCORES + 7,
    "m7": N_M1_SCORES + 8,
    "handcrafted": N_M1_SCORES + 9,    # 6
    "temporal": N_M1_SCORES + 15,      # 18
    "hour": N_M1_SCORES + 15 + 6 * 3,  # 2
}
N_FEATURES = OFFSET["hour"] + 2
FEATURE_ORDER = (
    [f"m1_{i}" for i in range(N_M1_SCORES)]
    + ["m2", "m3", "m4"]
    + ["m5_rain", "m5_wind", "m5_thunder", "m5_quiet"]
    + ["m6_z", "m7"]
    + ["rms_db", "crest", "sflux", "sflat", "onset", "zcr"]
    + [f"ctx_{m}_{s}" for m in TEMPORAL_MODELS for s in ("mean", "max", "slope")]
    + ["hour_sin", "hour_cos"]
)


def temporal_stats(history: list[dict[str, float]]) -> np.ndarray:
    """mean/max/slope of each tracked model score over the last CONTEXT_WINDOWS.

    history: list of per-window dicts {m2, m3, m4, m5_max, m6, m7}, oldest first.
    """
    out = []
    for m in TEMPORAL_MODELS:
        vals = [h.get(m, 0.0) for h in history[-CONTEXT_WINDOWS:]]
        if len(vals) < 2:
            slope = 0.0
        else:
            slope = (vals[-1] - vals[0]) / (len(vals) - 1)
        out += [float(np.mean(vals)), float(np.max(vals)), float(slope)]
    return np.asarray(out, dtype=np.float32)


class M9LightGBM:
    """Persistent wrapper: LightGBM + per-class isotonic calibration.

    Decision: exported via m2cgen to plain Python for the edge agent, and also
    kept as a native LightGBM model file for backend use. Documented in README.
    """

    def __init__(self, model=None, calibrators: dict | None = None,
                 classes: list[str] | None = None):
        import joblib  # noqa: F401  (not required; pickle used)
        self.model = model
        self.calibrators = calibrators or {}
        self.classes = classes or ["chainsaw", "gunshot", "vehicle", "human", "benign"]

    def predict_proba(self, X: np.ndarray) -> np.ndarray:
        """(n, n_features) -> calibrated (n, 5) class probabilities."""
        raw = self.model.predict(X.astype(np.float64))
        out = np.asarray(raw, dtype=np.float64)
        if out.ndim == 1:  # single estimator per class
            out = out.reshape(1, -1)
        # isotonic calibration per class column
        for j, cls in enumerate(self.classes):
            cal = self.calibrators.get(cls)
            if cal is not None:
                out[:, j] = np.clip(cal.predict(out[:, j]), 0.0, 1.0)
        # renormalise rows
        s = out.sum(axis=1, keepdims=True)
        s[s == 0] = 1.0
        return out / s

    def save(self, path: str | Path):
        import pickle
        with open(path, "wb") as f:
            pickle.dump({"model": self.model, "calibrators": self.calibrators,
                         "classes": self.classes}, f)

    @classmethod
    def load(cls, path: str | Path) -> "M9LightGBM":
        import pickle
        with open(path, "rb") as f:
            d = pickle.load(f)
        return cls(d["model"], d["calibrators"], d["classes"])


class M9MLP:
    """Keras MLP alternative (TFLite-exportable). Softmax over 5 classes."""

    @staticmethod
    def build(n_features: int = N_FEATURES, n_classes: int = 5) -> "tf.keras.Model":
        import tensorflow as tf
        from tensorflow.keras import layers, models
        inp = layers.Input(shape=(n_features,))
        x = layers.Dense(64, activation="relu")(inp)
        x = layers.Dropout(0.2)(x)
        x = layers.Dense(32, activation="relu")(x)
        out = layers.Dense(n_classes, activation="softmax")(x)
        m = models.Model(inp, out, name="m9_fusion_mlp")
        return m
