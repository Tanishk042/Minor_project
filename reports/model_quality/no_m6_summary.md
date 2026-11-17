# ForestEar evaluation summary

Generated 2026-11-17 21:10:40 — all numbers measured by `scripts/evaluate.py` on the held-out **test** split (6612 windows; {'chainsaw': 152, 'gunshot': 472, 'vehicle': 1692, 'human': 1608, 'benign': 2688}).

Model variant: **_no_m6**; M6 inputs ignored by M9: **True**.

## M9 ensemble vs baselines (macro F1, test)

| variant | macro F1 | accuracy |
|---|---|---|
| M9 fusion (calibrated, _no_m6) | 0.8173 | 0.8162 |
| M9 MLP alternative | 0.7987 | 0.8016 |
| YAMNet-only baseline | 0.3308 | 0.4109 |
| handcrafted LR baseline | 0.3077 | 0.3365 |
| majority class | 0.1156 | 0.4065 |

## Ablations (macro F1)

| ablation | macro F1 |
|---|---|
| full_ensemble | 0.8173 |
| no_m1_scores | 0.8142 |
| no_temporal_context | 0.6657 |
| no_handcrafted | 0.8121 |
| no_m6 | 0.8173 |
| no_calibration | 0.8181 |

## Per-model detection (AP / AUROC)

- M2 gunshot: AP 0.7694, AUROC 0.9379
- M3 chainsaw: AP 0.6701, AUROC 0.9557
- M4 vehicle: AP 0.7051, AUROC 0.8221
- M7 human: AP 0.6561, AUROC 0.7994
- M6 anomaly z: AUROC 0.482
- M5 weather macro F1: 0.5761

## Calibration

- ECE (calibrated) 0.0442 vs (uncalibrated) 0.1221
- Brier (calibrated) 0.2729 vs (uncalibrated) 0.3012

## Robustness by data source (macro F1)

| source | n | macro F1 |
|---|---|---|
| esc50 | 1568 | 0.5141 |
| syn | 960 | 0.8274 |
| us8k | 4084 | 0.6690 |

## Decision layer (system-level simulation)

- alerts fired: {'human': 280, 'vehicle': 422, 'gunshot': 122, 'chainsaw': 41}
- precision per class: {'human': 0.875, 'vehicle': 0.872, 'gunshot': 0.8852, 'chainsaw': 0.8049}
- recall per class: {'human': 0.1524, 'vehicle': 0.2175, 'chainsaw': 0.2171, 'gunshot': 0.2288}
- weather veto: 0/1 suppressions on weather windows; 1 false vetoes on true gunshot windows
- false alarms (threat classes, window-level): 111 in 0.9 h of replayed listening (2900.91 per node-day; stress stream — see decision_sim.json)

Full JSON provenance: `reports/*.json`.
