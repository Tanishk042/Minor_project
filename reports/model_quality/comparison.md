# M6 model-quality comparison — measured, not adopted

## Question

M6, the anomaly detector, scored **AUROC 0.482** on the held-out test split:
below random chance. The question tested here was narrow and practical: *if M6
is removed from M9's inputs, does the final classifier become equal or better
overall?*

**Answer: no.** The no-M6 variant was trained and evaluated, but the deployed
M9 model and the Android assets were left unchanged. Saving the variant is
evidence for a decision; it is not a promotion.

## What changed, and what did not

- Baseline: the deployed M9 LightGBM, using all 47 features.
- No-M6 variant: M9 retrained with
  `../.venv/bin/python ml/train_m9_fusion.py --ignore-m6 --suffix _no_m6`.
- M6 enters M9 at four fixed slots: direct z-score **19**, and temporal
  mean/max/slope **39, 40, 41**. Those four slots were set to zero during both
  training and inference. The vector still had **47 features**, so the phone
  and Python contracts did not change.
- The evaluation used the same held-out **6,612 windows** and the existing
  config/protocol. M6 scores were still available to the INFO-only anomaly
  decision path; only M9's use of them was masked.
- Commands recorded: `make eval` for the baseline, then
  `../.venv/bin/python scripts/evaluate.py --m9-variant _no_m6 --ignore-m6-inputs --report-suffix _no_m6`
  for the variant.

Training-stage numbers, measured on the out-of-fold validation set (4,320
windows): baseline macro-F1 **0.9665**, ECE **0.0278**; no-M6 macro-F1
**0.9646**, ECE **0.0277**. These are not test results and are not used to
claim field performance.

## Held-out test comparison

| Measure | Deployed M9 | No-M6 variant | Direction |
|---|---:|---:|---|
| LightGBM macro-F1 | 0.8186 | 0.8173 | slightly worse |
| LightGBM accuracy | 0.8200 | 0.8162 | slightly worse |
| MLP alternative macro-F1 | 0.7889 | 0.7987 | better |
| MLP alternative accuracy | 0.7840 | 0.8016 | better |
| Calibrated ECE | 0.0506 | 0.0442 | better |
| Calibrated Brier score | 0.2717 | 0.2729 | slightly worse |
| Robustness: ESC-50 macro-F1 | 0.5147 | 0.5141 | slightly worse |
| Robustness: synthetic macro-F1 | 0.8393 | 0.8274 | worse |
| Robustness: UrbanSound8K macro-F1 | 0.6701 | 0.6690 | slightly worse |
| Threat false alerts in decision simulation | 105 | 111 | worse |
| False alarms per node-day (simulation) | 2,744.10 | 2,900.91 | worse |
| False weather vetoes on true gunshot windows | 0 | 1 | worse |

The `no_m6` row in `reports/ablation.json` is an independent retraining with
the same four slots masked; it measured macro-F1 **0.8173** and accuracy
**0.8162**, matching the separately retrained variant on the headline metrics.

M6 itself did not improve in this test: its threat-vs-benign AUROC stayed
**0.482**. M5 also stayed unchanged at macro-F1 **0.5761**.

## Decision

**Do not adopt the no-M6 model.** It improves confidence calibration and the
smaller MLP alternative, but the primary deployed model is slightly less
accurate, the simulation raises more false alerts, and a false weather veto
appears on a real gunshot window. Those costs outweigh the gains on this
split.

This result does **not** prove that M6 helps: the headline differences are
small. It only shows that simply deleting M6 from M9 was not an overall
improvement. M6 remains INFO-only and below chance; a future fix needs better
normal-data training and a fresh, pre-registered evaluation, not a threshold
changed against this test split.

The deployed artifacts remain `models_store/m9_fusion/m9_lgbm.pkl`,
`m9_python.py`, `m9_iso.pkl`, `m9_mlp.keras` and the exported Android assets.
The suffixed `_no_m6` artifacts are local experiment outputs only: no TFLite
export, no phone asset replacement and no Android rerun were performed.

## Evidence files

| Baseline | No-M6 variant |
|---|---|
| `baseline_metrics_per_model.json` | `no_m6_metrics_per_model.json` |
| `baseline_calibration.json` | `no_m6_calibration.json` |
| `baseline_robustness.json` | `no_m6_robustness.json` |
| `baseline_decision_sim.json` | `no_m6_decision_sim.json` |
| `baseline_ablation.json` | `no_m6_ablation.json` |
| `baseline_confusion.json` | `no_m6_confusion.json` |
| `baseline_summary.md` | `no_m6_summary.md` |
| `baseline_train_m9.json` | `no_m6_train_m9.json` |

All files are in `reports/model_quality/`. Numbers above were read directly
from those generated files; nothing was estimated by hand.

---

# M5 probability calibration — measured, not adopted

A separate investigation asked whether the weak M5 weather classifier could be
improved without an expensive full CNN retrain. A fixed log-probability bias of
`[-1.5, -2.0, -2.0, 0.0]` (rain/wind/thunder/quiet) was selected on the
**validation split only** by coordinate descent, then applied **once** to the
held-out test split.

| Measure | Baseline | Calibrated |
|---|---:|---:|
| M5 macro-F1 (validation) | 0.6055 | 0.7061 |
| M5 macro-F1 (held-out test) | 0.5761 | 0.6728 |

The M5 gain is real and confirmed out of sample. It did **not** translate into
a better deployed system:

| Measure | Deployed M9 | Note |
|---|---:|---|
| Macro-F1 on raw M5 features | 0.8186 | deployed baseline |
| Macro-F1 on calibrated M5 features | 0.8079 | same trained model; input distribution changed |
| Accuracy on raw / calibrated | 0.8200 / 0.8091 | same trained model |
| Macro-F1 after retraining M9 on calibrated features | 0.8199 | +0.0013 vs baseline |
| Accuracy after retraining M9 on calibrated features | 0.8138 | -0.0062 vs baseline |

M9 was trained on the raw M5 softmax values, so feeding it biased values
creates a train/serve mismatch that costs ~1.1 points of macro-F1. Retraining M9
to remove that mismatch is a wash at best (macro-F1 +0.0013, accuracy -0.0062).
Applying the bias only to M5's reported/veto probability while leaving M9's
inputs raw would require a matched dual path in both the Python agent and the
Android app for a marginal result.

**Decision: not adopted.** The deployed M5 model, M9 artifacts and Android
assets are unchanged; M5 remains **0.5761** and M9 remains **0.8186** with
**0 false weather vetoes on true gunshot windows**. The dense-M5 alternative was
also rejected: its best validation macro-F1 was 0.4933 versus 0.6055 for the
deployed CNN. A full M5 CNN retrain was not viable here: the original training
run took 48,713.6 s (≈13.5 h).

Evidence files: `m5_bias_calibration_val.json`, `m5_bias_test_confirmation.json`,
`m5_dense_tuning_val.json`, `m5_calibration_m9_tradeoff.json` (all in this
directory). They record the experiment; none changed a deployed artifact.
