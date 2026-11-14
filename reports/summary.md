# ForestEar evaluation summary

Generated 2026-11-12 11:19:24 — all numbers measured by `scripts/evaluate.py` on the held-out **test** split (6612 windows; {'chainsaw': 152, 'gunshot': 472, 'vehicle': 1692, 'human': 1608, 'benign': 2688}).

## M9 ensemble vs baselines (macro F1, test)

| variant | macro F1 | accuracy |
|---|---|---|
| M9 fusion (calibrated, deployed) | 0.8186 | 0.8200 |
| M9 MLP alternative | 0.7889 | 0.7840 |
| YAMNet-only baseline | 0.3308 | 0.4109 |
| handcrafted LR baseline | 0.3077 | 0.3365 |
| majority class | 0.1156 | 0.4065 |

## Ablations (macro F1)

| ablation | macro F1 |
|---|---|
| full_ensemble | 0.8186 |
| no_m1_scores | 0.8142 |
| no_temporal_context | 0.6657 |
| no_handcrafted | 0.8121 |
| no_calibration | 0.8189 |

## Per-model detection (AP / AUROC)

- M2 gunshot: AP 0.7694, AUROC 0.9379
- M3 chainsaw: AP 0.6701, AUROC 0.9557
- M4 vehicle: AP 0.7051, AUROC 0.8221
- M7 human: AP 0.6561, AUROC 0.7994
- M6 anomaly z: AUROC 0.482
- M5 weather macro F1: 0.5761

## Calibration

- ECE (calibrated) 0.0506 vs (uncalibrated) 0.1196
- Brier (calibrated) 0.2717 vs (uncalibrated) 0.3003

## Robustness by data source (macro F1)

| source | n | macro F1 |
|---|---|---|
| esc50 | 1568 | 0.5147 |
| syn | 960 | 0.8393 |
| us8k | 4084 | 0.6701 |

## Decision layer (system-level simulation)

- alerts fired: {'human': 284, 'vehicle': 418, 'gunshot': 120, 'chainsaw': 42}
- precision per class: {'human': 0.8838, 'vehicle': 0.8804, 'gunshot': 0.8917, 'chainsaw': 0.7857}
- recall per class: {'vehicle': 0.2175, 'gunshot': 0.2267, 'human': 0.1561, 'chainsaw': 0.2171}
- weather veto: 0/1 suppressions on weather windows; 0 false vetoes on true gunshot windows
- false alarms (threat classes, window-level): 105 in 0.9 h of replayed listening (2744.1 per node-day; stress stream — see decision_sim.json)

Full JSON provenance: `reports/*.json`.
