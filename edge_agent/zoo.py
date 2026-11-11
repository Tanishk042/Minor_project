"""Runtime model zoo for the edge agent: loads all 9 models once, runs them
per hop on a shared feature pass.

Loading order and fallbacks:
- M1 YAMNet: TFLite backends (classification+embedding). Falls back to hub.
- M2-M5, M6: our own TFLite exports (export_tflite.py); fall back to Keras.
- M7: Keras or TFLite with normalisation stats.
- M8 BirdNET: TFLite INT8 + labels; if missing -> disabled (biodiversity off).
- M9: LightGBM via exported pure-Python (m2cgen) or pickled LGBM; MLP via TFLite.
"""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

from common.config import load_config, models_dir
from ml.features import YamNet, log_mel, handcrafted, logmel_tf_input
from ml.models.m9_fusion import (M9LightGBM, M7_EXTRA_CLASSES, N_FEATURES,
                                 TEMPORAL_MODELS, CONTEXT_WINDOWS)

log = logging.getLogger("forestear.zoo")


@dataclass
class WindowResult:
    """Everything computed for one analysis hop."""
    m1_scores: dict[str, float] = field(default_factory=dict)
    m1_emb: np.ndarray | None = None
    m2: float = 0.0
    m3: float = 0.0
    m4: float = 0.0
    m5: dict[str, float] = field(default_factory=dict)
    m6_err: float = 0.0
    m6_z: float = 0.0
    m7: float = 0.0
    m9_probs: dict[str, float] = field(default_factory=dict)
    handcrafted: dict[str, float] = field(default_factory=dict)
    logmel: np.ndarray | None = None
    inference_ms: dict[str, float] = field(default_factory=dict)
    m8_species: list[dict] | None = None   # duty-cycled
    rms_db: float = 0.0


class ModelZoo:
    """All models, one __call__ per hop."""

    def __init__(self, tflite_first: bool = True):
        import tensorflow as tf  # noqa: F401

        cfg = load_config()
        self.cfg = cfg
        self.sel_names = cfg["features"]["yamnet_selected_classes"]
        self.m1 = YamNet(backend="tflite", model_dir=models_dir("m1_yamnet"))
        self._classes = None
        self._load_m2_m7(tflite_first)
        self._load_m8()
        self._load_m9(tflite_first)
        # M6 rolling baseline (median/MAD over recent windows)
        base = np.load(models_dir("m6_anomaly") / "baseline.npy")
        self.m6_med, self.m6_mad = float(base[0]), float(base[1])
        self.m6_roll: list[float] = []
        self.roll_n = int(cfg["anomaly"]["zscore_median_window"])
        self.m8_counter = 0
        self.m8_duty = int(cfg["biodiversity"]["duty_cycle"])
        self.m8_topk = int(cfg["biodiversity"]["top_k"])
        self.m9_history: list[dict[str, float]] = []

    # ------------------------------------------------------------------ load
    def _load_m2_m7(self, tflite_first: bool):
        import tensorflow as tf

        self._k = {}
        self._i = {}
        self._norm = {}
        for mid, in_shape, out in (
            ("m2_gunshot", "logmel", 1), ("m5_weather", "logmel", 4),
            ("m6_anomaly", "logmel", None),  # AE, separate handling
            ("m3_chainsaw", "emb", 1), ("m4_vehicle", "emb", 1),
            ("m7_human", "emb+extra", 1),
        ):
            tfl = models_dir(mid) / "model.tflite"
            keras = models_dir(mid) / "model.keras"
            if tflite_first and tfl.exists():
                it = tf.lite.Interpreter(model_path=str(tfl), num_threads=2)
                it.allocate_tensors()
                self._i[mid] = it
            elif keras.exists():
                self._k[mid] = tf.keras.models.load_model(keras, compile=False)
            else:
                raise FileNotFoundError(f"model {mid} missing (run make train/export)")
            np_path = models_dir(mid) / "norm.npy"
            if np_path.exists():
                self._norm[mid] = np.load(np_path)
        self.m5_classes = ["rain", "wind", "thunder", "quiet"]
        self.m7_extra_names = M7_EXTRA_CLASSES

    def _load_m8(self):
        import tensorflow as tf

        self.m8 = None
        tfl = models_dir("m8_birdnet") / "BirdNET_GLOBAL_6K_V2.4_Model_INT8.tflite"
        lab = models_dir("m8_birdnet") / "BirdNET_GLOBAL_6K_V2.4_Labels.txt"
        if tfl.exists() and lab.exists():
            self.m8 = tf.lite.Interpreter(model_path=str(tfl), num_threads=2)
            self.m8.allocate_tensors()
            self.m8_labels = [l.strip() for l in open(lab, encoding="utf-8").read()
                              .splitlines() if l.strip()]
            log.info("M8 BirdNET loaded: %d labels", len(self.m8_labels))
        else:
            log.warning("M8 BirdNET not found -> biodiversity logging disabled")

    def _load_m9(self, tflite_first: bool):
        import tensorflow as tf

        self.m9_kind = None       # "lgbm" | "export" | "mlp"
        self.m9_lgbm = None
        self.m9_mlp = None
        self.m9_classes = ["chainsaw", "gunshot", "vehicle", "human", "benign"]
        pick = models_dir("m9_fusion") / "m9_lgbm.pkl"
        py = models_dir("m9_fusion") / "m9_python.py"
        iso = models_dir("m9_fusion") / "m9_iso.pkl"
        tfl = models_dir("m9_fusion") / "m9_mlp.tflite"
        if pick.exists():
            try:
                self.m9_lgbm = M9LightGBM.load(pick)
                self.m9_classes = self.m9_lgbm.classes
                self.m9_kind = "lgbm"
                log.info("M9 LightGBM loaded (native)")
            except Exception as e:  # noqa: BLE001
                log.warning("M9 pickle failed: %s", e)
        if self.m9_kind is None and py.exists():
            import importlib.util
            spec = importlib.util.spec_from_file_location("m9_export", py)
            mod = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(mod)
            self.m9_export = mod
            self.m9_kind = "export"
            if iso.exists():
                import pickle
                with open(iso, "rb") as f:
                    self.m9_iso = pickle.load(f)
            log.info("M9 LightGBM loaded via m2cgen export")
        if tfl.exists():
            self.m9_mlp = tf.lite.Interpreter(model_path=str(tfl), num_threads=2)
            self.m9_mlp.allocate_tensors()
            if self.m9_kind is None:
                self.m9_kind = "mlp"
            log.info("M9 MLP TFLite loaded")

    def _run_m9(self, feats: np.ndarray) -> dict[str, float]:
        if self.m9_kind == "lgbm":
            probs = self.m9_lgbm.predict_proba(feats)[0]
        elif self.m9_kind == "export":
            raw = np.array(self.m9_export.score(feats[0].astype(np.float64)),
                           dtype=np.float64).reshape(-1)
            iso = getattr(self, "m9_iso", None)
            if iso is not None:
                for j in range(len(raw)):
                    raw[j] = float(np.clip(iso[j].predict([raw[j]])[0], 0.0, 1.0))
            s = raw.sum() or 1.0
            probs = raw / s
        elif self.m9_kind == "mlp":
            it = self.m9_mlp
            inp = it.get_input_details()[0]
            out = it.get_output_details()[0]
            it.set_tensor(inp["index"], feats.astype(inp["dtype"]))
            it.invoke()
            probs = np.asarray(it.get_tensor(out["index"])).reshape(-1)
        else:
            raise RuntimeError("no M9 loaded")
        return {c: float(p) for c, p in zip(self.m9_classes, probs)}

    # ------------------------------------------------------------------ run
    def _predict(self, mid: str, x: np.ndarray) -> np.ndarray:
        if mid in self._i:
            it = self._i[mid]
            inp = it.get_input_details()[0]
            out = it.get_output_details()[0]
            it.set_tensor(inp["index"], np.asarray(x, dtype=inp["dtype"]))
            it.invoke()
            return np.asarray(it.get_tensor(out["index"]))
        return self._k[mid].predict(x, verbose=0)

    def __call__(self, wav: np.ndarray, hour: float | None = None) -> WindowResult:
        """Full pipeline for one hop. wav: 1.0 s float32 @16k."""
        r = WindowResult()
        t_all = time.perf_counter()

        # shared feature pass
        t0 = time.perf_counter()
        lm = log_mel(wav)
        hc = handcrafted(wav)
        r.logmel = lm
        r.handcrafted = hc.__dict__
        r.rms_db = hc.rms_db
        r.inference_ms["features"] = (time.perf_counter() - t0) * 1e3

        # M1
        t0 = time.perf_counter()
        scores, emb = self.m1(wav)
        r.m1_emb = emb
        r.m1_scores = self.m1.selected_scores(wav, self.sel_names)
        r.inference_ms["m1"] = (time.perf_counter() - t0) * 1e3

        lm4 = logmel_tf_input(lm)[None]  # (1, 98, 64, 1)

        # M2, M5
        for mid in ("m2_gunshot", "m5_weather"):
            t0 = time.perf_counter()
            out = self._predict(mid, lm4)
            r.inference_ms[mid.split("_")[0]] = (time.perf_counter() - t0) * 1e3
            if mid == "m2_gunshot":
                r.m2 = float(np.asarray(out).reshape(-1)[0])
            else:
                p = np.asarray(out).reshape(-1)[:4]
                r.m5 = {c: float(p[i]) for i, c in enumerate(self.m5_classes)}

        # M3, M4 (embedding heads, with normalisation)
        for mid in ("m3_chainsaw", "m4_vehicle"):
            t0 = time.perf_counter()
            x = emb[None]
            if mid in self._norm:
                mu, sd = self._norm[mid]
                x = (x - mu) / sd
            out = self._predict(mid, x)
            r.inference_ms[mid.split("_")[0]] = (time.perf_counter() - t0) * 1e3
            setattr(r, mid.split("_")[0], float(np.asarray(out).reshape(-1)[0]))

        # M6 (AE) + rolling z-score
        t0 = time.perf_counter()
        rec = self._predict("m6_anomaly", lm4)
        r.m6_err = float(np.mean(np.square(lm4 - rec)))
        self.m6_roll.append(r.m6_err)
        self.m6_roll = self.m6_roll[-self.roll_n:]
        arr = np.array(self.m6_roll)
        med = float(np.median(arr)) if len(arr) >= 10 else self.m6_med
        mad = float(np.median(np.abs(arr - med)) + 1e-9) if len(arr) >= 10 else self.m6_mad
        r.m6_z = (r.m6_err - med) / mad
        r.inference_ms["m6"] = (time.perf_counter() - t0) * 1e3

        # M7 (embedding + selected M1 scores)
        t0 = time.perf_counter()
        extra = np.array([r.m1_scores.get(n, 0.0) for n in self.m7_extra_names],
                         dtype=np.float32)
        x = np.concatenate([emb, extra])[None]
        if "m7_human" in self._norm:
            mu, sd = self._norm["m7_human"]
            x = (x - mu) / sd
        out = self._predict("m7_human", x)
        r.m7 = float(np.asarray(out).reshape(-1)[0])
        r.inference_ms["m7"] = (time.perf_counter() - t0) * 1e3

        # M8 duty-cycled
        self.m8_counter += 1
        if self.m8 is not None and (self.m8_counter % self.m8_duty) == 1:
            t0 = time.perf_counter()
            r.m8_species = self._run_m8(wav)
            r.inference_ms["m8"] = (time.perf_counter() - t0) * 1e3

        # M9 fusion
        t0 = time.perf_counter()
        self.m9_history.append({
            "m2": r.m2, "m3": r.m3, "m4": r.m4,
            "m5_max": max(r.m5.get("rain", 0), r.m5.get("wind", 0),
                          r.m5.get("thunder", 0)),
            "m6": r.m6_z, "m7": r.m7,
        })
        feats = self.build_m9_features(r, hour=hour)
        r.m9_probs = self._run_m9(feats)
        r.inference_ms["m9"] = (time.perf_counter() - t0) * 1e3
        r.inference_ms["total"] = (time.perf_counter() - t_all) * 1e3
        return r

    def build_m9_features(self, r: WindowResult, hour: float | None = None) -> np.ndarray:
        """Assemble the M9 feature vector (contract in ml/models/m9_fusion.py)."""
        from ml.models.m9_fusion import temporal_stats
        m1v = np.array([r.m1_scores.get(n, 0.0) for n in self.sel_names],
                       dtype=np.float32)
        m5v = np.array([r.m5.get(c, 0.0) for c in self.m5_classes], dtype=np.float32)
        hc = r.handcrafted
        hand = np.array([hc.get(k, 0.0) for k in
                         ("rms_db", "crest", "spectral_flux", "spectral_flatness",
                          "onset_strength", "zcr")], dtype=np.float32)
        ctx = temporal_stats(self.m9_history)
        import datetime as dt
        if hour is None:
            now = dt.datetime.now(dt.timezone.utc)
            hour = now.hour + now.minute / 60.0
        hsin = np.sin(2 * np.pi * hour / 24.0)
        hcos = np.cos(2 * np.pi * hour / 24.0)
        v = np.concatenate([m1v, [r.m2, r.m3, r.m4], m5v, [r.m6_z, r.m7],
                            hand, ctx, [hsin, hcos]]).astype(np.float32)
        assert v.shape == (N_FEATURES,), f"M9 feature mismatch {v.shape}"
        return v[None]

    def _run_m8(self, wav: np.ndarray) -> list[dict]:
        """BirdNET on the hop window (padded/looped to 3 s)."""
        it = self.m8
        inp = it.get_input_details()[0]
        out = it.get_output_details()[0]
        need = int(inp["shape"][-1]) if len(inp["shape"]) >= 1 else 144000
        seg = np.asarray(wav, dtype=np.float32)
        reps = int(np.ceil(need / max(len(seg), 1)))
        seg = np.tile(seg, reps)[:need]
        x = seg.reshape([1] + ([int(v) for v in inp["shape"][:-1]] or []) + [-1])
        x = np.asarray(x, dtype=inp["dtype"])
        if inp["dtype"] == np.int16 or "quant" in str(inp["dtype"]):
            x = (np.clip(x, -1, 1) * 32767).astype(np.int16)
        it.set_tensor(inp["index"], x)
        it.invoke()
        p = np.asarray(it.get_tensor(out["index"])).reshape(-1)
        top = np.argsort(p)[::-1][: self.m8_topk]
        return [{"species": self.m8_labels[i], "confidence": float(p[i])}
                for i in top if p[i] > 0.03]
