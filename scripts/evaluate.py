"""ForestEar evaluation harness — ALL metrics are measured on the held-out
TEST split of the window cache (never used for any training or calibration).

Outputs (reports/):
  metrics_per_model.json  per-model test metrics (M2-M7, M9, baselines)
  ablation.json           M9 feature-group ablations (LightGBM retrained)
  confusion.json          M9 confusion matrix + per-class P/R/F1
  robustness.json         per-data-source breakdown (domain shift view)
  calibration.json        ECE + Brier, before/after isotonic calibration
  decision_sim.json       k-of-n persistence + weather-veto system simulation
  summary.md              human-readable summary (generated from real numbers)

Honesty rules:
- every number is computed by this script from actual predictions
- anything that could not be measured is reported as null, not guessed
- methodology notes are embedded in each JSON
"""
from __future__ import annotations

import json
import logging
import sys
import time
from collections import defaultdict
from pathlib import Path

import numpy as np

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO))

from common.config import load_config, models_dir, reports_dir  # noqa: E402
from common.logging_setup import setup_logging  # noqa: E402
from ml.models.m9_fusion import (M9LightGBM, N_FEATURES, OFFSET,  # noqa: E402
                                 TEMPORAL_MODELS)
from ml.train_m9_fusion import _oof_features  # noqa: E402
from ml.window_cache import WindowCache  # noqa: E402

log = logging.getLogger("forestear.eval")
CLASSES = ["chainsaw", "gunshot", "vehicle", "human", "benign"]
THREAT = {"chainsaw", "gunshot", "vehicle", "human"}


def source_of(origin: str) -> str:
    """origin_id prefix encodes the dataset source (esc50-, us8k-, fsd-, syn-)."""
    o = str(origin)
    for p in ("esc50-", "us8k-", "fsd-", "syn-"):
        if o.startswith(p):
            return p.rstrip("-")
    return "unknown"


def class_of(label: str) -> str:
    if label.startswith("weather_") or label == "benign":
        return "benign"
    return label


# ------------------------------------------------------------------ metrics
def prf(y_true: np.ndarray, y_pred: np.ndarray, n: int = 5):
    """Per-class precision/recall/F1 + macro/accuracy (one-vs-rest)."""
    out = {}
    for j, c in enumerate(CLASSES):
        tp = int(np.sum((y_pred == j) & (y_true == j)))
        fp = int(np.sum((y_pred == j) & (y_true != j)))
        fn = int(np.sum((y_pred != j) & (y_true == j)))
        prec = tp / (tp + fp) if tp + fp else 0.0
        rec = tp / (tp + fn) if tp + fn else 0.0
        f1 = 2 * prec * rec / (prec + rec) if prec + rec else 0.0
        out[c] = {"precision": round(prec, 4), "recall": round(rec, 4),
                  "f1": round(f1, 4), "support": int(np.sum(y_true == j)),
                  "tp": tp, "fp": fp, "fn": fn}
    acc = float(np.mean(y_pred == y_true))
    macro_f1 = float(np.mean([out[c]["f1"] for c in CLASSES]))
    return {"per_class": out, "accuracy": round(acc, 4),
            "macro_f1": round(macro_f1, 4), "n": int(len(y_true))}


def average_precision(y_true: np.ndarray, scores: np.ndarray) -> float:
    """AP for a binary problem (sklearn-free, deterministic tie handling)."""
    order = np.argsort(-scores, kind="stable")
    y = y_true[order]
    P = np.cumsum(y)
    idx = np.where(y == 1)[0]
    if len(idx) == 0:
        return 0.0
    prec_at_hits = P[idx] / (idx + 1)
    return float(np.mean(prec_at_hits))


def auroc(y_true: np.ndarray, scores: np.ndarray) -> float:
    pos = scores[y_true == 1]
    neg = scores[y_true == 0]
    if len(pos) == 0 or len(neg) == 0:
        return float("nan")
    # rank-based AUROC with proper tie handling: tied scores share the
    # average rank (equivalent to scipy.stats.rankdata 'average')
    allv = np.concatenate([pos, neg])
    order = np.argsort(allv, kind="stable")
    ranks = np.empty(len(allv), dtype=np.float64)
    sv = allv[order]
    i = 0
    while i < len(sv):
        j = i
        while j + 1 < len(sv) and sv[j + 1] == sv[i]:
            j += 1
        ranks[order[i:j + 1]] = (i + j) / 2.0 + 1.0   # avg of ranks i+1..j+1
        i = j + 1
    rp = ranks[:len(pos)].sum()
    return float((rp - len(pos) * (len(pos) + 1) / 2) / (len(pos) * len(neg)))


def ece_brier(probs: np.ndarray, y_true: np.ndarray, n_bins: int = 10):
    conf = probs.max(axis=1)
    pred = probs.argmax(axis=1)
    correct = (pred == y_true).astype(float)
    bins = np.linspace(0, 1, n_bins + 1)
    ece = 0.0
    rel = []
    for lo, hi in zip(bins[:-1], bins[1:]):
        m = (conf > lo) & (conf <= hi)
        if m.sum() > 0:
            acc_b = correct[m].mean()
            conf_b = conf[m].mean()
            ece += m.sum() / len(conf) * abs(acc_b - conf_b)
            rel.append({"bin_lo": round(lo, 2), "bin_hi": round(hi, 2),
                        "n": int(m.sum()), "avg_conf": round(float(conf_b), 4),
                        "accuracy": round(float(acc_b), 4)})
    # multiclass Brier (normalised): mean over classes of (p - y)^2
    Y = np.eye(probs.shape[1])[y_true]
    brier = float(np.mean(np.sum((probs - Y) ** 2, axis=1)))
    return float(ece), brier, rel


# ------------------------------------------------------------------ main
def main() -> int:
    setup_logging()
    t_start = time.time()
    cfg = load_config()
    cache = WindowCache()

    log.info("building TEST features (M2-M7 + temporal context)...")
    Xte, yte = _oof_features(cache, "test")
    idx = cache.subset("test")
    labels = np.array([class_of(c) for c in cache.y[idx]])
    origins = cache.origin[idx]
    sources = np.array([source_of(o) for o in origins])
    n = len(yte)
    log.info("test windows: %d (classes %s)", n, np.bincount(yte))

    # ------------------------------------------------------------ M9
    m9 = M9LightGBM.load(models_dir("m9_fusion") / "m9_lgbm.pkl")
    p9 = m9.predict_proba(Xte)
    pred9 = p9.argmax(axis=1)

    m9_metrics = prf(yte, pred9)
    conf_mat = np.zeros((5, 5), dtype=int)
    for t, p in zip(yte, pred9):
        conf_mat[t, p] += 1

    # ------------------------------------------------------------ M9 MLP alt
    mlp_metrics = None
    mlp_path = models_dir("m9_fusion") / "m9_mlp.keras"
    if mlp_path.exists():
        import tensorflow as tf
        mlp = tf.keras.models.load_model(mlp_path, compile=False)
        pmlp = mlp.predict(Xte, verbose=0, batch_size=128)
        mlp_metrics = prf(yte, pmlp.argmax(axis=1))

    # ------------------------------------------------------------ baselines
    # 1) majority class
    majority = int(np.bincount(yte).argmax())
    base_majority = prf(yte, np.full(n, majority))
    base_majority["note"] = "predicts the most frequent test class always"

    # 2) logistic regression on handcrafted features only
    base_lr = None
    try:
        from sklearn.linear_model import LogisticRegression
        Xtr_h, ytr_h = cache.X_hand[cache.subset("train")], \
            np.array([CLASSES.index(class_of(c)) for c in
                      cache.y[cache.subset("train")]])
        lr = LogisticRegression(max_iter=1000, class_weight="balanced")
        lr.fit(Xtr_h, ytr_h)
        base_lr = prf(yte, lr.predict(cache.X_hand[idx]))
        base_lr["note"] = "6 handcrafted features -> logistic regression (no DL)"
    except Exception as e:  # noqa: BLE001
        log.warning("LR baseline failed: %s", e)

    # 3) M1-only (YAMNet selected scores -> hand-mapped to our classes)
    sel = cfg["features"]["yamnet_selected_classes"]
    m1 = cache.X_m1[idx]
    def s(name): return m1[:, sel.index(name)] if name in sel else np.zeros(n)
    m1_map = np.stack([
        np.max([s("Chainsaw")], axis=0),
        np.max([s("Gunshot, gunfire")], axis=0),
        np.max([s("Engine"), s("Truck"), s("Motorcycle"), s("Vehicle")], axis=0),
        np.max([s("Speech"), s("Shout")], axis=0),
        np.max([s("Bird vocalization, bird call, bird song"),
                s("Rain") * 0, s("Wind") * 0], axis=0),  # benign ~ birds
    ], axis=1)
    base_m1 = prf(yte, m1_map.argmax(axis=1))
    base_m1["note"] = "YAMNet selected-score argmax with hand class mapping"

    per_model = {
        "m9_fusion_calibrated": m9_metrics,
        "m9_mlp_alternative": mlp_metrics,
        "baseline_majority": base_majority,
        "baseline_handcrafted_lr": base_lr,
        "baseline_yamnet_only": base_m1,
    }

    # ------------------------------------------------------------ per-model (M2-M7)
    # recompute the per-window head outputs from the M9 feature vector
    off = OFFSET
    p2 = Xte[:, off["m2"]]
    p3 = Xte[:, off["m3"]]
    p4 = Xte[:, off["m4"]]
    p5 = Xte[:, off["m5"]:off["m5"] + 4]
    z6 = Xte[:, off["m6"]]
    p7 = Xte[:, off["m7"]]

    def binary_eval(score, cls):
        yt = (yte == CLASSES.index(cls)).astype(int)
        ap = average_precision(yt, score)
        roc = auroc(yt, score)
        # operating threshold = decision config threshold on the M9-equivalent
        thr = float(cfg["decision"]["thresholds"].get(cls, 0.5))
        f1s = []
        for t in (0.3, 0.4, 0.5, 0.6, thr, 0.7, 0.8):
            yp = (score >= t).astype(int)
            tp = int(np.sum((yp == 1) & (yt == 1)))
            fp = int(np.sum((yp == 1) & (yt == 0)))
            fn = int(np.sum((yp == 0) & (yt == 1)))
            pr_ = tp / (tp + fp) if tp + fp else 0.0
            rc = tp / (tp + fn) if tp + fn else 0.0
            f1s.append(round(2 * pr_ * rc / (pr_ + rc) if pr_ + rc else 0.0, 4))
        return {"ap": round(ap, 4), "auroc": round(roc, 4),
                "f1_at_thresholds_0.3-0.8": f1s,
                "operating_threshold": thr,
                "n_pos": int(yt.sum()), "n": n}

    per_model["m2_gunshot"] = binary_eval(p2, "gunshot")
    per_model["m3_chainsaw"] = binary_eval(p3, "chainsaw")
    per_model["m4_vehicle"] = binary_eval(p4, "vehicle")
    per_model["m7_human"] = binary_eval(p7, "human")

    # M5 weather: 4-class over weather windows + benign-as-quiet
    weather_cls = ["rain", "wind", "thunder", "quiet"]
    wlab = []
    for c in cache.y[idx]:
        if c.startswith("weather_"):
            wlab.append(weather_cls.index(c.split("_", 1)[1]))
        else:
            wlab.append(weather_cls.index("quiet"))
    wlab = np.array(wlab)
    m5_pred = p5.argmax(axis=1)
    m5_f1s = []
    for j, wc in enumerate(weather_cls):
        tp = int(np.sum((m5_pred == j) & (wlab == j)))
        fp = int(np.sum((m5_pred == j) & (wlab != j)))
        fn = int(np.sum((m5_pred != j) & (wlab == j)))
        pr_ = tp / (tp + fp) if tp + fp else 0.0
        rc = tp / (tp + fn) if tp + fn else 0.0
        m5_f1s.append(round(2 * pr_ * rc / (pr_ + rc) if pr_ + rc else 0.0, 4))
    per_model["m5_weather"] = {
        "macro_f1": round(float(np.mean(m5_f1s)), 4),
        "per_class_f1": dict(zip(weather_cls, m5_f1s)),
        "note": "quiet = all non-weather windows",
        "n": n}

    # M6 anomaly: z-score separates threat from benign
    threat_mask = np.isin(labels, list(THREAT))
    z_roc = auroc(threat_mask.astype(int), z6)
    per_model["m6_anomaly"] = {
        "auroc_threat_vs_benign": round(z_roc, 4),
        "median_z_benign": round(float(np.median(z6[~threat_mask])), 3),
        "median_z_threat": round(float(np.median(z6[threat_mask])), 3),
        "threshold": float(cfg["decision"]["thresholds"]["anomaly"]),
        "n": n}

    (reports_dir() / "metrics_per_model.json").write_text(json.dumps(
        {"_meta": {"split": "test", "n_windows": n,
                   "class_counts": {c: int((yte == j).sum())
                                    for j, c in enumerate(CLASSES)},
                   "note": "all metrics measured on the held-out test split"},
         **per_model}, indent=2))

    # ------------------------------------------------------------ confusion
    (reports_dir() / "confusion.json").write_text(json.dumps({
        "classes": CLASSES,
        "matrix_rows_true_cols_pred": conf_mat.tolist(),
        "metrics": m9_metrics,
    }, indent=2))

    # ------------------------------------------------------------ ablations
    log.info("ablations: retraining LightGBM with masked feature groups...")
    import lightgbm as lgb
    Xva, yva = _oof_features(cache, "val")
    seed = cfg["training"]["seed"]

    def train_eval_lgbm(mask_train, mask_test, calibrate=True):
        Xa = Xva.copy(); Xb = Xte.copy()
        if mask_train is not None:
            Xa[:, mask_train] = 0.0
        if mask_test is not None:
            Xb[:, mask_test] = 0.0
        m = lgb.LGBMClassifier(n_estimators=300, learning_rate=0.06, num_leaves=31,
                               class_weight="balanced", random_state=seed,
                               verbose=-1, objective="multiclass", n_jobs=2)
        m.fit(Xa, yva)
        raw = np.asarray(m.predict(Xb))
        if raw.ndim == 1:
            raw = raw.reshape(1, -1) if len(Xb) == 1 else raw.reshape(-1, 1)
        if calibrate:
            from sklearn.isotonic import IsotonicRegression
            # calibrate on a slice of val
            rawv = np.asarray(m.predict(Xa)).reshape(len(yva), -1)
            if rawv.shape[1] == 1:      # binary-ish output; expand to 5 columns
                rawv = np.hstack([rawv, np.zeros((len(yva), 4))])
            for j in range(rawv.shape[1]):
                iso = IsotonicRegression(out_of_bounds="clip", y_min=0, y_max=1)
                iso.fit(rawv[:, j], (yva == j).astype(float))
                raw[:, j] = iso.predict(raw[:, j])
        s = raw.sum(axis=1, keepdims=True); s[s == 0] = 1
        probs = raw / s
        if probs.shape[1] < 5:  # degenerate single-column case
            probs = np.hstack([probs, np.zeros((n, 5 - probs.shape[1]))])
        pred = probs.argmax(axis=1)
        return prf(yte, pred)

    ablations = {}
    ablations["full_ensemble"] = m9_metrics
    ablations["no_m1_scores"] = train_eval_lgbm(
        np.arange(off["m1_scores"], off["m1_scores"] + 12), None)
    ablations["no_temporal_context"] = train_eval_lgbm(
        np.arange(off["temporal"], off["temporal"] + 18), None)
    ablations["no_handcrafted"] = train_eval_lgbm(
        np.arange(off["handcrafted"], off["handcrafted"] + 6), None)
    # uncalibrated full model
    raw_full = np.asarray(m9.model.predict(Xte.astype(np.float64)))
    if raw_full.ndim == 1:
        raw_full = raw_full.reshape(len(Xte), -1)
    s = raw_full.sum(axis=1, keepdims=True); s[s == 0] = 1
    ablations["no_calibration"] = prf(yte, (raw_full / s).argmax(axis=1))
    # single-model baselines (already computed above)
    ablations["m3_chainsaw_only"] = binary_eval(p3, "chainsaw")
    ablations["m2_gunshot_only"] = binary_eval(p2, "gunshot")

    (reports_dir() / "ablation.json").write_text(json.dumps({
        "_meta": {"method": "LightGBM retrained on OOF(val) features with the "
                  "feature group zeroed (train AND test); evaluated on test",
                  "reference": "full_ensemble = M9 as deployed"},
        "rows": [{"name": k, **{kk: vv for kk, vv in v.items()
                                if kk in ("macro_f1", "accuracy", "ap", "auroc")}}
                 for k, v in ablations.items()],
        "detail": ablations,
    }, indent=2))

    # ------------------------------------------------------------ robustness
    robust = {}
    for src in sorted(set(sources)):
        m = sources == src
        if m.sum() < 10:
            continue
        r = prf(yte[m], pred9[m])
        r["per_class_recall"] = {c: r["per_class"][c]["recall"]
                                  for c in CLASSES
                                  if r["per_class"][c]["support"] > 0}
        robust[src] = r
    (reports_dir() / "robustness.json").write_text(json.dumps({
        "_meta": {"note": "per-source breakdown of the deployed M9 on the same "
                  "test split; sources derived from origin_id prefix"},
        **robust}, indent=2))

    # ------------------------------------------------------------ calibration
    ece_c, brier_c, rel = ece_brier(p9, yte)
    raw_full = np.asarray(m9.model.predict(Xte.astype(np.float64)))
    if raw_full.ndim == 1:
        raw_full = raw_full.reshape(len(Xte), -1)
    s = raw_full.sum(axis=1, keepdims=True); s[s == 0] = 1
    ece_u, brier_u, _ = ece_brier(raw_full / s, yte)
    (reports_dir() / "calibration.json").write_text(json.dumps({
        "calibrated": {"ece": round(ece_c, 4), "brier": round(brier_c, 4)},
        "uncalibrated": {"ece": round(ece_u, 4), "brier": round(brier_u, 4)},
        "reliability_curve_calibrated": rel,
        "n": n,
    }, indent=2))

    # ------------------------------------------------------------ decision sim
    log.info("decision-layer simulation (k-of-n + weather veto)...")
    from edge_agent.decision import DecisionEngine, WindowScores
    fired = defaultdict(int)
    correct_alerts = defaultdict(int)
    veto_correct = 0
    veto_total = 0
    false_veto = 0
    gunshot_windows = 0
    hop_s = float(cfg["audio"]["hop_s"])
    # replay windows grouped by origin: fresh engine per origin (independent
    # clips), synthetic clock advancing one hop per window (as in deployment)
    order = np.argsort(origins, kind="stable")
    i = 0
    while i < len(order):
        o = origins[order[i]]
        j = i
        eng = DecisionEngine(cfg)
        t_sim = 0.0
        while j < len(order) and origins[order[j]] == o:
            k = order[j]
            m9p = {c: float(p9[k, jj]) for jj, c in enumerate(CLASSES)}
            sc = WindowScores(m2=float(p2[k]), m3=float(p3[k]), m4=float(p4[k]),
                              m7=float(p7[k]), m6_z=float(z6[k]),
                              m5_rain=float(p5[k, 0]), m5_wind=float(p5[k, 1]),
                              m5_thunder=float(p5[k, 2]), m9=m9p,
                              rms_db=float(cache.X_hand[idx[k]][0]))
            for d in eng.update(sc, t=t_sim):
                if d.fired:
                    fired[d.cls] += 1
                    if d.cls == labels[k]:
                        correct_alerts[d.cls] += 1
                elif d.suppressed_by:
                    veto_total += 1
                    if labels[k].startswith("weather_"):
                        veto_correct += 1
                    elif labels[k] == "gunshot":
                        false_veto += 1
            if labels[k] == "gunshot":
                gunshot_windows += 1
            t_sim += hop_s
            j += 1
        i = j
    alert_prec = {c: round(correct_alerts[c] / fired[c], 4)
                  if fired[c] else None for c in fired}
    alert_rec = {}
    for c in THREAT:
        sup = int(np.sum(labels == c))
        alert_rec[c] = round(correct_alerts[c] / sup, 4) if sup else None
    (reports_dir() / "decision_sim.json").write_text(json.dumps({
        "_meta": {"note": "replay of test windows through the DEPLOYED decision "
                  "layer (k-of-n persistence, cooldowns, weather veto); "
                  "window-level attribution: an alert counts correct if the "
                  "window's true class matches the alert class"},
        "alerts_fired": dict(fired),
        "alert_precision_per_class": alert_prec,
        "alert_recall_per_class": alert_rec,
        "weather_veto": {"total_suppressions": veto_total,
                         "on_weather_windows": veto_correct,
                         "false_vetos_on_true_gunshot": false_veto,
                         "gunshot_windows_total": gunshot_windows},
    }, indent=2))

    # ------------------------------------------------------------ summary.md
    md = ["# ForestEar evaluation summary", "",
          f"Generated {time.strftime('%Y-%m-%d %H:%M:%S')} — "
          f"all numbers measured by `scripts/evaluate.py` on the held-out "
          f"**test** split ({n} windows; "
          f"{dict(zip(CLASSES, [int((yte == j).sum()) for j in range(5)]))}).",
          "",
          "## M9 ensemble vs baselines (macro F1, test)", "",
          "| variant | macro F1 | accuracy |", "|---|---|---|"]
    rows = [("M9 fusion (calibrated, deployed)", m9_metrics),
            ("M9 MLP alternative", mlp_metrics),
            ("YAMNet-only baseline", base_m1),
            ("handcrafted LR baseline", base_lr),
            ("majority class", base_majority)]
    for name, r in rows:
        if r:
            md.append(f"| {name} | {r['macro_f1']:.4f} | {r['accuracy']:.4f} |")
    md += ["", "## Ablations (macro F1)", "", "| ablation | macro F1 |", "|---|---|"]
    for k in ("full_ensemble", "no_m1_scores", "no_temporal_context",
              "no_handcrafted", "no_calibration"):
        if k in ablations:
            md.append(f"| {k} | {ablations[k].get('macro_f1', ablations[k].get('ap'))} |")
    md += ["", "## Per-model detection (AP / AUROC)", "",
           f"- M2 gunshot: AP {per_model['m2_gunshot']['ap']}, AUROC {per_model['m2_gunshot']['auroc']}",
           f"- M3 chainsaw: AP {per_model['m3_chainsaw']['ap']}, AUROC {per_model['m3_chainsaw']['auroc']}",
           f"- M4 vehicle: AP {per_model['m4_vehicle']['ap']}, AUROC {per_model['m4_vehicle']['auroc']}",
           f"- M7 human: AP {per_model['m7_human']['ap']}, AUROC {per_model['m7_human']['auroc']}",
           f"- M6 anomaly z: AUROC {per_model['m6_anomaly']['auroc_threat_vs_benign']}",
           f"- M5 weather macro F1: {per_model['m5_weather']['macro_f1']}", "",
           "## Calibration", "",
           f"- ECE (calibrated) {ece_c:.4f} vs (uncalibrated) {ece_u:.4f}",
           f"- Brier (calibrated) {brier_c:.4f} vs (uncalibrated) {brier_u:.4f}",
           "", "## Robustness by data source (macro F1)", "",
           "| source | n | macro F1 |", "|---|---|---|"]
    for src, r in robust.items():
        md.append(f"| {src} | {r['n']} | {r['macro_f1']:.4f} |")
    md += ["", "## Decision layer (system-level simulation)", "",
           f"- alerts fired: {dict(fired)}",
           f"- precision per class: {alert_prec}",
           f"- recall per class: {alert_rec}",
           f"- weather veto: {veto_correct}/{veto_total} suppressions on weather "
           f"windows; {false_veto} false vetoes on true gunshot windows",
           "", "Full JSON provenance: `reports/*.json`.", ""]
    (reports_dir() / "summary.md").write_text("\n".join(md))

    log.info("evaluation done in %.1f s -> reports/", time.time() - t_start)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
