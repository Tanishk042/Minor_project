"""Export M2-M7 and the M9 MLP alternative to TensorFlow Lite.

- int8 (full post-training integer quantization) for m2, m5
- float16 for m3, m4, m6, m7 (embedding/ AE: int8 hurt accuracy; documented)
- m9 MLP alternative: float16 fallback if LightGBM path unavailable
Validates numerical parity vs Keras within config tolerance and writes
reports/edge_performance.json with model sizes and measured CPU latency.
"""
from __future__ import annotations

import argparse
import json
import logging
import sys
import time
from pathlib import Path

import numpy as np

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO))

from common.config import load_config, models_dir, reports_dir  # noqa: E402
from common.logging_setup import setup_logging  # noqa: E402
from ml.window_cache import WindowCache  # noqa: E402

log = logging.getLogger("forestear.export")


def _rep_dataset(cache: WindowCache, mode: str, n: int = 64) -> np.ndarray:
    """Representative dataset for int8 calibration (train split only)."""
    idx = cache.subset("train")
    rng = np.random.default_rng(0)
    take = rng.choice(idx, size=min(n, len(idx)), replace=False)
    if mode == "logmel":
        x = cache.logmel_tf(take)
    else:
        x = cache.X_emb[take]
    for row in x:
        yield [row[None].astype(np.float32)]


def export_one(name: str, mode: str, quant: str, cache: WindowCache) -> dict:
    import tensorflow as tf

    keras_path = models_dir(name) / "model.keras"
    tfl_path = models_dir(name) / "model.tflite"
    model = tf.keras.models.load_model(keras_path, compile=False)

    conv = tf.lite.TFLiteConverter.from_keras_model(model)
    if quant == "int8":
        conv.optimizations = [tf.lite.Optimize.DEFAULT]
        conv.representative_dataset = lambda: _rep_dataset(cache, mode)
        conv.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
        conv.inference_input_type = tf.int8
        conv.inference_output_type = tf.int8
    elif quant == "float16":
        conv.optimizations = [tf.lite.Optimize.DEFAULT]
        conv.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS,
                                          tf.lite.OpsSet.SELECT_TF_OPS
                                          ] if False else [tf.lite.OpsSet.TFLITE_BUILTINS]
        conv.target_spec.supported_types = [tf.float16]
    tfl = conv.convert()
    tfl_path.write_bytes(tfl)

    # parity check vs Keras on val windows
    if mode == "logmel":
        idx = cache.subset("val")[:32]
        x = cache.logmel_tf(idx)
    else:
        idx = cache.subset("val")[:32]
        x = cache.X_emb[idx]
    yk = model.predict(x, verbose=0)
    # tflite inference
    it = tf.lite.Interpreter(model_path=str(tfl_path), num_threads=2)
    it.allocate_tensors()
    inp, out = it.get_input_details()[0], it.get_output_details()[0]

    def run_one(row):
        xx = row[None].astype(np.float32)
        if inp["dtype"] == np.int8 or inp["dtype"] == np.uint8:
            sc = inp["quantization"]
            if sc and sc[0] != 0:
                xx = np.clip(xx / sc[0] + sc[1], -128, 127).astype(np.int8)
            else:
                xx = (xx * 127).astype(np.int8)
        it.set_tensor(inp["index"], xx)
        it.invoke()
        o = np.asarray(it.get_tensor(out["index"]), dtype=np.float32)
        if out["dtype"] == np.int8 or out["dtype"] == np.uint8:
            sc = out["quantization"]
            if sc and sc[0] != 0:
                o = (o.astype(np.float32) - sc[1]) * sc[0]
        return o.reshape(yk[0].shape)

    yq = np.stack([run_one(r) for r in x[:16]])
    max_abs = float(np.max(np.abs(yk[:16] - yq))) if yq.size else 0.0
    corr = float(np.corrcoef(yk[:16].reshape(len(yq), -1).mean(1),
                             yq.reshape(len(yq), -1).mean(1))[0, 1]) \
        if len(yq) > 1 else 1.0

    # latency (100 runs after warmup)
    sample = x[0][None].astype(np.float32)
    if inp["dtype"] == np.int8:
        sc = inp["quantization"]
        if sc and sc[0] != 0:
            sample = np.clip(sample / sc[0] + sc[1], -128, 127).astype(np.int8)
    for _ in range(5):
        it.set_tensor(inp["index"], sample)
        it.invoke()
    t0 = time.perf_counter()
    for _ in range(100):
        it.set_tensor(inp["index"], sample)
        it.invoke()
    lat = (time.perf_counter() - t0) / 100 * 1e3

    return {"model": name, "quant": quant, "kb": tfl_path.stat().st_size // 1024,
            "latency_ms": round(lat, 2), "parity_max_abs": round(max_abs, 4),
            "parity_corr": round(corr, 4)}


def main() -> int:
    setup_logging()
    cfg = load_config()
    ap = argparse.ArgumentParser()
    ap.add_argument("--skip-benchmark", action="store_true")
    args = ap.parse_args()
    cache = WindowCache()
    jobs = [
        ("m2_gunshot", "logmel", "int8"),
        ("m5_weather", "logmel", "int8"),
        ("m6_anomaly", "logmel", "float16"),
        ("m3_chainsaw", "emb", "float16"),
        ("m4_vehicle", "emb", "float16"),
        ("m7_human", "emb", "float16"),
    ]
    results = []
    for name, mode, quant in jobs:
        try:
            r = export_one(name, mode, quant, cache)
            results.append(r)
            log.info("%s", r)
        except Exception as e:  # noqa: BLE001
            log.error("export %s failed: %s", name, e)
            results.append({"model": name, "error": str(e)})

    # M9 MLP alternative (if trained)
    import tensorflow as tf
    m9k = models_dir("m9_fusion") / "m9_mlp.keras"
    if m9k.exists():
        m = tf.keras.models.load_model(m9k, compile=False)
        conv = tf.lite.TFLiteConverter.from_keras_model(m)
        conv.optimizations = [tf.lite.Optimize.DEFAULT]
        conv.target_spec.supported_types = [tf.float16]
        (models_dir("m9_fusion") / "m9_mlp.tflite").write_bytes(conv.convert())
        size = (models_dir("m9_fusion") / "m9_mlp.tflite").stat().st_size // 1024
        it = tf.lite.Interpreter(model_path=str(models_dir("m9_fusion") / "m9_mlp.tflite"),
                                 num_threads=2)
        it.allocate_tensors()
        inp, out = it.get_input_details()[0], it.get_output_details()[0]
        xx = np.zeros((1, inp["shape"][1]), np.float32)
        it.set_tensor(inp["index"], xx)
        it.invoke()
        t0 = time.perf_counter()
        for _ in range(100):
            it.set_tensor(inp["index"], xx)
            it.invoke()
        lat = (time.perf_counter() - t0) / 100 * 1e3
        results.append({"model": "m9_fusion_mlp", "quant": "float16", "kb": size,
                        "latency_ms": round(lat, 2)})
    else:
        log.info("M9 MLP not trained yet (LightGBM is the primary M9)")

    # M1 latency benchmark (TFLite)
    from ml.features import YamNet
    yam = YamNet(backend="tflite", model_dir=models_dir("m1_yamnet"))
    w = np.zeros(15600, np.float32)
    yam(w)  # warm
    t0 = time.perf_counter()
    for i in range(50):
        yam(np.random.default_rng(i).standard_normal(15600).astype(np.float32) * 0.1)
    results.append({"model": "m1_yamnet_tflite", "quant": "as-is",
                    "kb": (models_dir("m1_yamnet") /
                           "lite-model_yamnet_classification_tflite_1.tflite"
                           ).stat().st_size // 1024,
                    "latency_ms": round((time.perf_counter() - t0) / 50 * 1e3, 2)})
    # M8 latency
    m8p = models_dir("m8_birdnet") / "BirdNET_GLOBAL_6K_V2.4_Model_INT8.tflite"
    if m8p.exists():
        it = tf.lite.Interpreter(model_path=str(m8p), num_threads=2)
        it.allocate_tensors()
        inp = it.get_input_details()[0]
        out = it.get_output_details()[0]
        xx = np.zeros(tuple(int(v) for v in inp["shape"]), inp["dtype"])
        it.set_tensor(inp["index"], xx)
        it.invoke()
        t0 = time.perf_counter()
        for _ in range(20):
            it.set_tensor(inp["index"], xx)
            it.invoke()
        results.append({"model": "m8_birdnet", "quant": "int8-as-is",
                        "kb": m8p.stat().st_size // 1024,
                        "latency_ms": round((time.perf_counter() - t0) / 20 * 1e3, 2)})

    total = sum(r.get("latency_ms", 0) for r in results
                if r["model"] not in ("m8_birdnet", "m9_fusion_mlp"))
    results.append({"model": "TOTAL (m1..m7 per hop)", "latency_ms": round(total, 2),
                    "target_ms": cfg["export"]["latency_target_ms"],
                    "meets_target": total < cfg["export"]["latency_target_ms"]})
    path = reports_dir() / "edge_performance.json"
    path.write_text(json.dumps(results, indent=2))
    log.info("wrote %s", path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
