#!/usr/bin/env python3
"""Export ForestEar Python artefacts for the ForestEar Node Android app.

Run from anywhere:
    python android/tools/export_android_assets.py

What it writes
--------------
app/src/main/assets/
  models/                  TFLite models + BirdNET labels
  features/                mel filterbanks + analysis windows (float32 LE)
  config/forestear.json    thresholds, feature contract, model metadata
  config/norms.json        M3/M4/M7 embedding normalisation stats
  config/m9_trees.bin      deployed LightGBM trees, compact little-endian binary
  config/m9_meta.json      M9 class order + isotonic calibration curves
  config/yamnet_classes.json
  golden/                  input windows + expected Python outputs
app/src/test/resources/golden/  same golden data for JVM unit tests

The script validates the exported tree interpreter against the native
LightGBM model before writing anything, so the Kotlin port has a trustworthy
reference. It only exports measured/model-derived values; no invented metrics.
"""
from __future__ import annotations

import argparse
import csv
import json
import pickle
import shutil
import struct
import sys
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import yaml

# ---------------------------------------------------------------------------
ROOT = Path(__file__).resolve().parents[2]          # .../MInor Project
FORESTEAR = ROOT / "forestear"
APP = ROOT / "android" / "ForestEarNode" / "app"
ASSETS = APP / "src" / "main" / "assets"
TEST_RES = APP / "src" / "test" / "resources"

sys.path.insert(0, str(FORESTEAR))

from ml.features import (  # noqa: E402
    SR, N_FFT, WIN_LEN, HOP_LEN, FMIN, FMAX, N_MELS, LOG_OFFSET,
    log_mel, handcrafted, WINDOW_S, HOP_S, FRAME_COUNT,
)
from ml.models.m9_fusion import M9LightGBM, N_FEATURES, TEMPORAL_MODELS  # noqa: E402
from ml.models.m3_m4_m7_heads import M7_EXTRA_CLASSES  # noqa: E402
from edge_agent.decision import DecisionEngine, WindowScores  # noqa: E402
from edge_agent.zoo import ModelZoo  # noqa: E402
import ml.synth as synth  # noqa: E402

MODELS = FORESTEAR / "models_store"


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------
def write_json(path: Path, obj) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, separators=(",", ":"), allow_nan=False),
                    encoding="utf-8")


def write_f32(path: Path, arr: np.ndarray) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    np.asarray(arr, dtype="<f4").tofile(path)


def copy(src: Path, dst: Path) -> None:
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dst)


def yamnet_names() -> list[str]:
    p = FORESTEAR / "ml" / "yamnet_class_map.csv"
    with open(p, encoding="utf-8") as f:
        return [row["display_name"] for row in csv.DictReader(f)]


def find_index(names: list[str], name: str) -> int:
    try:
        return names.index(name)
    except ValueError:
        return -1


def pad_to(w: np.ndarray, n: int = SR) -> np.ndarray:
    w = np.asarray(w, dtype=np.float32).reshape(-1)
    if len(w) < n:
        w = np.pad(w, (0, n - len(w)))
    return w[:n]


# ---------------------------------------------------------------------------
# LightGBM -> compact arrays
# ---------------------------------------------------------------------------
def flatten_tree(node: dict, features: list[int], thresholds: list[float],
                 left: list[int], right: list[int], values: list[float]) -> int:
    idx = len(features)
    features.append(0)
    thresholds.append(0.0)
    left.append(-1)
    right.append(-1)
    values.append(0.0)
    if "leaf_value" in node:
        values[idx] = float(node["leaf_value"])
        return idx
    features[idx] = int(node["split_feature"])
    thresholds[idx] = float(node["threshold"])
    left[idx] = flatten_tree(node["left_child"], features, thresholds, left,
                             right, values)
    right[idx] = flatten_tree(node["right_child"], features, thresholds, left,
                              right, values)
    return idx


def write_m9_binary(dst: Path, trees: list[dict], n_features: int,
                    classes: list[str]) -> None:
    """Little-endian layout read by FusionTrees.kt.

    magic "FEM9" | int32 version | int32 n_features | int32 n_classes |
    int32 n_trees | per tree: int32 class, int32 n_nodes,
    per node: int32 feature, int32 left, int32 right, float64 threshold,
    float64 value.
    """
    buf = bytearray()
    buf += b"FEM9"
    buf += struct.pack("<iiii", 1, n_features, len(classes), len(trees))
    for t in trees:
        n = len(t["f"])
        buf += struct.pack("<ii", int(t["c"]), n)
        for i in range(n):
            buf += struct.pack("<iiidd", int(t["f"][i]), int(t["l"][i]),
                               int(t["r"][i]), float(t["t"][i]),
                               float(t["v"][i]))
    dst.write_bytes(bytes(buf))


def export_m9_tree(pkl: Path, dst_bin: Path, dst_meta: Path) -> dict:
    with open(pkl, "rb") as f:
        d = pickle.load(f)
    model = d["model"]
    calibrators = d["calibrators"]
    classes = list(d["classes"])
    dm = model.booster_.dump_model()
    n_classes = int(dm["num_class"])
    n_trees_per_class = int(dm["num_tree_per_iteration"])
    assert n_classes == len(classes)
    assert dm["objective"].startswith("multiclass")

    trees = []
    for ti, info in enumerate(dm["tree_info"]):
        f, t, l, r, v = [], [], [], [], []
        flatten_tree(info["tree_structure"], f, t, l, r, v)
        trees.append({"f": f, "t": t, "l": l, "r": r, "v": v,
                      "c": ti % n_trees_per_class})

    iso = {}
    for cls in classes:
        cal = calibrators.get(cls)
        if cal is None:
            continue
        iso[cls] = {"x": [float(x) for x in cal.X_thresholds_],
                    "y": [float(y) for y in cal.y_thresholds_]}

    meta = {
        "format": "forestear-m9-meta-v1",
        "n_features": N_FEATURES,
        "n_classes": n_classes,
        "classes": classes,
        "iso": iso,
    }

    # ---- validate the interpreter against the native model ----------------
    wrapper = M9LightGBM.load(pkl)
    rng = np.random.default_rng(42)
    X = rng.normal(0.0, 1.0, size=(64, N_FEATURES)).astype(np.float64)
    X[:8] = 0.0
    X[8:16] = 1.0
    expected = wrapper.predict_proba(X)

    def tree_eval(tree, x):
        i = 0
        while tree["l"][i] >= 0:
            i = (tree["l"][i] if x[tree["f"][i]] <= tree["t"][i]
                 else tree["r"][i])
        return tree["v"][i]

    def predict(x):
        raw = np.zeros(n_classes, dtype=np.float64)
        for tree in trees:
            raw[tree["c"]] += tree_eval(tree, x)
        raw -= raw.max()
        e = np.exp(raw)
        p = e / e.sum()
        for j, cls in enumerate(classes):
            curve = iso.get(cls)
            if curve is not None:
                p[j] = float(np.interp(p[j], curve["x"], curve["y"]))
        p = np.clip(p, 0.0, 1.0)
        s = p.sum()
        return p / s if s else p

    got = np.stack([predict(x) for x in X])
    max_diff = float(np.max(np.abs(got - expected)))
    if max_diff > 1e-6:
        raise SystemExit(f"M9 tree interpreter mismatch: max|diff|={max_diff}")
    print(f"M9 tree export OK: {len(trees)} trees, "
          f"max|diff| vs native={max_diff:.2e}")

    dst_bin.parent.mkdir(parents=True, exist_ok=True)
    write_m9_binary(dst_bin, trees, N_FEATURES, classes)
    write_json(dst_meta, meta)
    print(f"  {dst_bin} ({dst_bin.stat().st_size/1e6:.2f} MB) + "
          f"{dst_meta.name} ({dst_meta.stat().st_size/1e3:.1f} kB)")
    return meta


# ---------------------------------------------------------------------------
# decision golden vectors
# ---------------------------------------------------------------------------
def decision_vectors(cfg: dict) -> list[dict]:
    scenarios = []

    def run(name: str, steps: list[dict]) -> None:
        eng = DecisionEngine(cfg)
        rows = []
        for i, s in enumerate(steps):
            t = i * 0.5
            sc = WindowScores(
                m2=float(s.get("m2", 0.0)), m3=float(s.get("m3", 0.0)),
                m4=float(s.get("m4", 0.0)), m7=float(s.get("m7", 0.0)),
                m6_z=float(s.get("m6_z", 0.0)),
                m5_rain=float(s.get("m5_rain", 0.0)),
                m5_wind=float(s.get("m5_wind", 0.0)),
                m5_thunder=float(s.get("m5_thunder", 0.0)),
                m9=dict(s.get("m9", {})), rms_db=float(s.get("rms_db", -40.0)))
            out = eng.update(sc, t=t)
            rows.append({
                "t": t,
                "in": s,
                "out": [{
                    "cls": d.cls, "severity": d.severity,
                    "confidence": round(float(d.confidence), 6),
                    "fired": d.fired, "suppressed_by": d.suppressed_by,
                    "escalated": d.escalated, "k_of_n": d.k_of_n,
                } for d in out],
            })
        scenarios.append({"name": name, "steps": rows})

    # 1. one high-confidence gunshot -> fires immediately
    run("gunshot_single_high", [{"m9": {"gunshot": 0.95}, "m2": 0.95}])
    # 2. two medium gunshot detections -> fires on the second
    run("gunshot_two_medium", [
        {"m9": {"gunshot": 0.62}, "m2": 0.62},
        {"m9": {"gunshot": 0.64}, "m2": 0.64},
    ])
    # 3. weather veto suppresses a medium gunshot
    run("gunshot_weather_veto", [
        {"m9": {"gunshot": 0.70}, "m2": 0.70,
         "m5_rain": 0.9, "m5_wind": 0.4, "m5_thunder": 0.2},
        {"m9": {"gunshot": 0.72}, "m2": 0.72,
         "m5_rain": 0.9, "m5_wind": 0.4, "m5_thunder": 0.2},
    ])
    # 4. sustained chainsaw -> CRITICAL escalation despite the 60 s cooldown
    run("chainsaw_sustain", [
        {"m9": {"chainsaw": 0.9}, "m3": 0.9} for _ in range(16)
    ])
    # 5. gunshot cooldown: second shot inside 20 s is silent, later shot fires
    run("gunshot_cooldown", [
        {"m9": {"gunshot": 0.95}, "m2": 0.95},     # t=0 fires
        {"m9": {"gunshot": 0.95}, "m2": 0.95},     # t=0.5 cooldown
        {"m9": {"gunshot": 0.95}, "m2": 0.95},     # t=1.0 cooldown
        {"m9": {"gunshot": 0.95}, "m2": 0.95},     # ... filler
    ] + [{"m9": {}, "m2": 0.0}] * 38 + [
        {"m9": {"gunshot": 0.95}, "m2": 0.95},     # t=21.0 fires
    ])
    # 6. anomaly persistence 2-of-3 with loud window
    run("anomaly_persistence", [
        {"m6_z": 1.2, "rms_db": 45.0},
        {"m6_z": 1.4, "rms_db": 46.0},
    ])
    # 7. vehicle 3-of-5
    run("vehicle_persistence", [
        {"m9": {"vehicle": 0.7}, "m4": 0.7},
        {"m9": {"vehicle": 0.7}, "m4": 0.7},
        {"m9": {"vehicle": 0.7}, "m4": 0.7},
    ])
    return scenarios


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--skip-models", action="store_true",
                    help="only export feature/config assets (no TF inference)")
    args = ap.parse_args()

    if ASSETS.exists():
        shutil.rmtree(ASSETS)
    ASSETS.mkdir(parents=True)
    (ASSETS / "models").mkdir()
    (ASSETS / "features").mkdir()
    (ASSETS / "config").mkdir()
    (ASSETS / "golden").mkdir()

    cfg = yaml.safe_load((FORESTEAR / "configs" / "default.yaml").read_text())
    names = yamnet_names()
    sel = cfg["features"]["yamnet_selected_classes"]
    sel_idx = [find_index(names, n) for n in sel]
    m7_idx = [find_index(names, n) for n in M7_EXTRA_CLASSES]
    missing = [n for n, i in zip(sel + list(M7_EXTRA_CLASSES), sel_idx + m7_idx)
               if i < 0]
    if missing:
        print(f"WARNING: class names not in YAMNet map (score 0): {missing}")

    # ---- models ----------------------------------------------------------
    model_files = {
        "m1_yamnet.tflite": MODELS / "m1_yamnet" / "lite-model_yamnet_tflite_1.tflite",
        "m2_gunshot.tflite": MODELS / "m2_gunshot" / "model.tflite",
        "m3_chainsaw.tflite": MODELS / "m3_chainsaw" / "model.tflite",
        "m4_vehicle.tflite": MODELS / "m4_vehicle" / "model.tflite",
        "m5_weather.tflite": MODELS / "m5_weather" / "model.tflite",
        "m6_anomaly.tflite": MODELS / "m6_anomaly" / "model.tflite",
        "m7_human.tflite": MODELS / "m7_human" / "model.tflite",
        "m8_birdnet.tflite": MODELS / "m8_birdnet" / "BirdNET_GLOBAL_6K_V2.4_Model_INT8.tflite",
        "m9_mlp.tflite": MODELS / "m9_fusion" / "m9_mlp.tflite",
    }
    for name, src in model_files.items():
        copy(src, ASSETS / "models" / name)
    copy(MODELS / "m8_birdnet" / "BirdNET_GLOBAL_6K_V2.4_Labels.txt",
         ASSETS / "models" / "birdnet_labels.txt")

    # ---- normalisation stats --------------------------------------------
    norms = {}
    for mid in ("m3_chainsaw", "m4_vehicle", "m7_human"):
        arr = np.load(MODELS / mid / "norm.npy")
        norms[mid] = {"mu": arr[0].astype(float).tolist(),
                      "sd": arr[1].astype(float).tolist()}
    write_json(ASSETS / "config" / "norms.json", norms)

    baseline = np.load(MODELS / "m6_anomaly" / "baseline.npy")
    m6_med, m6_mad = float(baseline[0]), float(baseline[1])

    # ---- feature matrices ------------------------------------------------
    import librosa
    from scipy.signal import get_window
    mel64 = librosa.filters.mel(sr=SR, n_fft=N_FFT, n_mels=N_MELS,
                                fmin=FMIN, fmax=FMAX).astype(np.float32)
    mel128 = librosa.filters.mel(sr=SR, n_fft=N_FFT, n_mels=128,
                                 fmin=0.0, fmax=8000.0).astype(np.float32)
    win400 = np.zeros(N_FFT, dtype=np.float32)
    left = (N_FFT - WIN_LEN) // 2
    win400[left:left + WIN_LEN] = get_window("hann", WIN_LEN, fftbins=True)
    win512 = get_window("hann", N_FFT, fftbins=True).astype(np.float32)
    write_f32(ASSETS / "features" / "mel64.f32", mel64)
    write_f32(ASSETS / "features" / "mel128_onset.f32", mel128)
    write_f32(ASSETS / "features" / "window400.f32", win400)
    write_f32(ASSETS / "features" / "window512.f32", win512)

    # ---- config ----------------------------------------------------------
    config = {
        "schema": 1,
        "generated_utc": datetime.now(timezone.utc).isoformat(),
        "audio": {
            "sample_rate": SR, "window_samples": int(SR * WINDOW_S),
            "hop_samples": int(SR * HOP_S), "window_s": WINDOW_S,
            "hop_s": HOP_S,
            "silence_rms_db": cfg["audio"]["silence_rms_db"],
            "clip_ratio": cfg["audio"]["clip_ratio"],
        },
        "features": {
            "n_mels": N_MELS, "n_fft": N_FFT, "win_length": WIN_LEN,
            "hop_length": HOP_LEN, "fmin": FMIN, "fmax": FMAX,
            "frames": FRAME_COUNT, "log_offset": LOG_OFFSET,
            "per_window_norm": True,
            "yamnet_selected_classes": sel,
            "yamnet_selected_indices": sel_idx,
            "m7_extra_classes": list(M7_EXTRA_CLASSES),
            "m7_extra_indices": m7_idx,
            "m5_classes": ["rain", "wind", "thunder", "quiet"],
            "m9_feature_order": [
                *[f"m1_{i}" for i in range(len(sel))],
                "m2", "m3", "m4",
                "m5_rain", "m5_wind", "m5_thunder", "m5_quiet",
                "m6_z", "m7",
                "rms_db", "crest", "sflux", "sflat", "onset", "zcr",
                *[f"ctx_{m}_{s}" for m in TEMPORAL_MODELS
                  for s in ("mean", "max", "slope")],
                "hour_sin", "hour_cos",
            ],
        },
        "models": {
            "m1": {"file": "models/m1_yamnet.tflite", "kind": "yamnet",
                   "input_samples": 15600},
            "m2": {"file": "models/m2_gunshot.tflite", "input": "logmel",
                   "quant": "int8"},
            "m3": {"file": "models/m3_chainsaw.tflite", "input": "emb"},
            "m4": {"file": "models/m4_vehicle.tflite", "input": "emb"},
            "m5": {"file": "models/m5_weather.tflite", "input": "logmel"},
            "m6": {"file": "models/m6_anomaly.tflite", "input": "logmel"},
            "m7": {"file": "models/m7_human.tflite", "input": "emb+extra"},
            "m8": {"file": "models/m8_birdnet.tflite",
                   "labels": "models/birdnet_labels.txt",
                   "input_samples": 144000},
            "m9": {"file": "models/m9_mlp.tflite",
                   "trees": "config/m9_trees.bin",
                   "meta": "config/m9_meta.json",
                   "n_features": N_FEATURES},
        },
        "m6": {"baseline_median": m6_med, "baseline_mad": m6_mad,
               "rolling_window": int(cfg["anomaly"]["zscore_median_window"])},
        "decision": cfg["decision"],
        "node": {"heartbeat_s": int(cfg["node"]["heartbeat_s"])},
        "biodiversity": cfg["biodiversity"],
        "anomaly": cfg["anomaly"],
        "alerts": {"dedup_window_s": cfg["alerts"]["dedup_window_s"],
                   "clip_enable": False},
        "android": {
            "analysis_budget_ms": 400,
            "analysis_hop_levels_s": [0.5, 1.0, 2.0],
            "thermal": {
                "warm_c": 40.0, "hot_c": 45.0, "critical_c": 50.0,
                "hard_pause_c": 55.0, "hysteresis_c": 3.0,
                "warm_stride": 2, "hot_stride": 4,
                "warm_m8_duty": 20, "hot_m8_duty": 0,
                "warm_m6_every": 2, "hot_m6_every": 0,
            },
            "low_battery": {"enable_pct": 20, "critical_pct": 10,
                            "enabled_stride": 2, "critical_stride": 4},
            "sms": {"min_interval_s": 300, "max_per_hour": 6},
            "collection": {"chunk_s": 60, "max_bytes": 2_000_000_000},
            "streaming": {"default_url": "ws://192.168.1.10:8765/stream"},
            "watchdog": {"work_minutes": 15, "alarm_minutes": 5},
            "benchmark": {"runs": 20, "warmup": 3},
        },
    }
    write_json(ASSETS / "config" / "forestear.json", config)
    write_json(ASSETS / "config" / "yamnet_classes.json", names)

    # ---- M9 tree ---------------------------------------------------------
    export_m9_tree(MODELS / "m9_fusion" / "m9_lgbm.pkl",
                   ASSETS / "config" / "m9_trees.bin",
                   ASSETS / "config" / "m9_meta.json")

    # ---- golden inputs + pipeline ---------------------------------------
    golden_inputs = [
        ("ambience", pad_to(synth.forest_ambience(seed=11, dur=1.0))),
        ("gunshot", pad_to(synth.gunshot(seed=41, dur=1.0))),
        ("chainsaw", pad_to(synth.chainsaw(seed=7, dur=1.0))),
        ("noise", pad_to(np.random.default_rng(3).standard_normal(SR) * 0.05)),
        ("vehicle", pad_to(synth.vehicle(seed=5, dur=1.0))),
    ]
    manifest = FORESTEAR / "data" / "dataset" / "manifest.csv"
    if manifest.exists():
        try:
            with open(manifest, encoding="utf-8") as f:
                rows = [r for r in csv.DictReader(f)
                        if r.get("class") == "chainsaw" and r.get("source") != "synthetic"]
            if rows:
                from ml.audio_io import load_audio
                golden_inputs.append(("real_chainsaw",
                                      pad_to(load_audio(rows[0]["path"], sr=SR))))
        except Exception as e:  # noqa: BLE001
            print(f"real clip skipped: {e}")

    for name, w in golden_inputs:
        write_f32(ASSETS / "golden" / f"input_{name}.f32", w)

    # Fixed hour-of-day so the golden M9 vector is reproducible in JVM tests.
    GOLDEN_HOUR = 12.0
    pipeline = {"windows": [], "hour": GOLDEN_HOUR,
                "note": "expected values from the Python edge path"}
    if not args.skip_models:
        zoo = ModelZoo()
        for name, w in golden_inputs:
            r = zoo(w, hour=GOLDEN_HOUR)
            lm = log_mel(w)
            write_f32(ASSETS / "golden" / f"logmel_{name}.f32", lm)
            row = {
                "name": name,
                "logmel_file": f"golden/logmel_{name}.f32",
                "handcrafted": {k: float(v) for k, v in r.handcrafted.items()},
                "rms_db": float(r.rms_db),
                "m2": float(r.m2), "m3": float(r.m3), "m4": float(r.m4),
                "m5": {k: float(v) for k, v in r.m5.items()},
                "m6_err": float(r.m6_err), "m6_z": float(r.m6_z),
                "m7": float(r.m7),
                "m1_selected": {k: float(v) for k, v in r.m1_scores.items()},
                "m9_features": [float(v) for v in
                                zoo.build_m9_features(r, hour=GOLDEN_HOUR)[0].tolist()],
                "m9_probs": {k: float(v) for k, v in r.m9_probs.items()},
                "m8_species": r.m8_species,
            }
            # full YAMNet outputs for parity (scores 521 + embedding 1024)
            scores, emb = zoo.m1(w)
            write_f32(ASSETS / "golden" / f"yamnet_scores_{name}.f32", scores)
            write_f32(ASSETS / "golden" / f"yamnet_emb_{name}.f32", emb)
            pipeline["windows"].append(row)
            print(f"golden {name}: m2={r.m2:.3f} m9={r.m9_probs}")
        write_json(ASSETS / "golden" / "pipeline.json", pipeline)
    else:
        print("--skip-models: golden model outputs not regenerated")

    # ---- M9 random vectors ----------------------------------------------
    wrapper = M9LightGBM.load(MODELS / "m9_fusion" / "m9_lgbm.pkl")
    rng = np.random.default_rng(2024)
    X = rng.normal(0.0, 1.0, size=(64, N_FEATURES)).astype(np.float32)
    X[:4] = 0.0
    X[4:8] = 1.0
    P = wrapper.predict_proba(X)
    m9_vectors = {
        "n_features": N_FEATURES,
        "classes": wrapper.classes,
        "vectors": [{"x": [float(v) for v in row],
                     "p": [float(v) for v in p]}
                    for row, p in zip(X, P)],
    }
    write_json(ASSETS / "golden" / "m9_vectors.json", m9_vectors)

    # ---- decision vectors ------------------------------------------------
    write_json(ASSETS / "golden" / "decision_vectors.json",
               decision_vectors(cfg))

    # ---- test resources --------------------------------------------------
    TEST_RES.mkdir(parents=True, exist_ok=True)
    gdst = TEST_RES / "golden"
    if gdst.exists():
        shutil.rmtree(gdst)
    shutil.copytree(ASSETS / "golden", gdst)
    # feature/config files used by JVM tests
    for rel in ("features/mel64.f32", "features/mel128_onset.f32",
                "features/window400.f32", "features/window512.f32",
                "config/forestear.json", "config/norms.json",
                "config/m9_trees.bin", "config/m9_meta.json",
                "config/yamnet_classes.json"):
        src = ASSETS / rel
        dst = TEST_RES / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)

    total = sum(p.stat().st_size for p in ASSETS.rglob("*") if p.is_file())
    print(f"assets written: {total/1e6:.1f} MB -> {ASSETS}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
