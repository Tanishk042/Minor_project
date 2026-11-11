# Model cards — ForestEar ensemble

Common conventions for all trained models:

- **Input contract**: log-mel **64×98** (98 frames × 64 mel bins), 16 kHz,
  1.0 s window, per-window normalisation — identical code path at training
  (`ml/window_cache.py`) and inference (`edge_agent/zoo.py`), unit-tested.
- **Splits**: dataset-provided folds only (see README fold table); the test
  split is used exactly once by `scripts/evaluate.py`.
- **Seeds**: `training.seed: 42`; per-window augmentation seeded by a stable
  SHA-256 hash of `origin_id|window_index`.
- **Metrics**: every number in `reports/metrics_per_model.json` and
  `reports/summary.md` is measured on the held-out test split by
  `scripts/evaluate.py` — nothing is copied from literature.

---

## M1 — YAMNet (frozen feature extractor)

- **What**: Google's AudioSet CNN. Two interchangeable backends: the hub
  SavedModel (training-time, via `tf.saved_model.load`) and the official
  TFLite build (edge). A parity unit test asserts class scores agree
  within 0.05 and embedding cosine > 0.985.
- **Outputs**: 521 class scores (12 curated ones feed M9) + 1024-d
  embedding (input to M3/M4/M7).
- **Training**: none (frozen).
- **Storage**: `models_store/m1_yamnet/` (SavedModel + 2 TFLite builds).
- **Limitations**: AudioSet domain bias; no explicit chainsaw class (the
  "Chainsaw" AudioSet label exists and is surfaced, but YAMNet's chainsaw
  reliability is unverified on real saws — hence specialist heads M3).

## M2 — gunshot CNN (`ml/models/m2_gunshot.py`)

- **Architecture**: 3 blocks of SeparableConv2D(3×3) + BN + ReLU +
  MaxPool(2×2) + Dropout(0.1) → GlobalAvgPool → Dense(48) → sigmoid.
  Chosen for impulsive-event detection with few parameters.
- **Training**: binary focal loss (γ=2, α=0.25) on gunshot vs everything
  else; class imbalance handled by the loss, not by resampling.
- **Data**: gunshots from UrbanSound8K + FSD50K (+ synthetic impulses as
  augmentation); confusers (fireworks, church bells) stay in the negative
  class on purpose.
- **Output**: P(gunshot) for the decision engine's high-confidence path.

## M3 / M4 / M7 — heads on YAMNet embeddings (`ml/models/m3_m4_m7_heads.py`)

- **Architecture**: 1024-d → Dense(64) → Dropout → Dense(32) → Dropout →
  output. Small by design: the heavy lifting is YAMNet's.
- **M3** chainsaw (binary), **M4** vehicle (binary), **M7** human (binary).
- **Data**: ESC-50 chainsaw (the only real chainsaw source) + FSD50K/US8K
  for vehicle; human from FSD50K speech/walk classes and US8K
  children_playing. Synthetic clips are augmentation, never the majority.
- **Limitations**: chainsaw recall is bounded by ~300 ESC-50 clips; the
  synthetic generator covers robustness, not realism.

## M5 — weather CNN (`ml/models/m2_gunshot.py::build_m5`)

- **Architecture**: same small-CNN family as M2, 4-way softmax:
  rain / wind / thunder / quiet.
- **Role**: false-alarm suppressor (feeds the weather veto) — NOT a
  final-class predictor.
- **Data**: ESC-50 rain/wind/thunderstorm + FSD50K Rain/Wind/Thunderstorm +
  synthetic weather.
- **Safety design**: the veto can only suppress a gunshot below 0.80
  confidence; ≥ 0.80 always alerts (tested in
  `test_decision.py::test_high_conf_gunshot_survives_weather`).

## M6 — anomaly autoencoder (`ml/models/m6_anomaly.py`)

- **Architecture**: conv encoder (Conv2D stride 2 ×3 → latent 32) →
  mirrored Conv2DTranspose decoder, reconstruction target = input log-mel.
- **Training**: benign-only windows (weather + benign classes, never
  threats) from the train split + background bank.
- **Scoring**: reconstruction MSE → rolling median/MAD z-score
  (baseline `baseline.npy`, window 200 hops) in `edge_agent/zoo.py`.
- **Role**: open-set tripwire for loud unusual sounds the supervised
  heads don't know; escalates to INFO only (2-of-3 windows, loud > 35 dB).
- **Limitations**: open-set by construction — unusual benign sounds also
  trip it; that is the point, and it is documented as such.

## M8 — BirdNET 2.4 INT8 (`models_store/m8_birdnet/`)

- **What**: Google/Cornal BirdNET INT8 TFLite, official build. Frozen.
- **Role**: biodiversity logging ONLY — never feeds threat decisions or
  M9. Duty-cycled 1-in-10 windows (config `biodiversity.duty_cycle`).
- **Output**: species label + confidence → `/ingest/biodiversity` →
  dashboard biodiversity tab (species list + hourly activity chart).
- **Limitations**: trained on non-Indian avifauna mostly; treat species
  IDs as indicative in the demo forest area (Bengaluru region).

## M9 — LightGBM fusion (`ml/models/m9_fusion.py`)

- **Features (47, order is a hard contract)**:
  12 M1-selected AudioSet scores · M2, M3, M4 · M5 (rain/wind/thunder/quiet)
  · M6 z · M7 · 6 handcrafted (rms_db, crest, spectral flux/flatness,
  onset strength, zcr) · 18 temporal context features (mean/max/slope of
  6 tracked scores over the last 5 windows) · hour-of-day sin/cos.
- **Training**: out-of-fold stacking — M2–M7 predictions on train+val are
  generated K-fold OOF, so LightGBM never sees predictions a model made on
  its own training rows. Isotonic calibration per class is fit on a val
  slice; ECE/Brier before/after is reported in `reports/calibration.json`.
- **Export**: LightGBM pickle + m2cgen pure-Python translation (edge,
  no LightGBM dependency) + Keras MLP alternative (TFLite).
- **Classes**: chainsaw / gunshot / vehicle / human / benign.
- **Baselines compared in eval**: YAMNet-selected-classes argmax, M2 alone,
  majority vote of heads, and the uncalibrated LightGBM.

## Ablations (`reports/ablation.json`)

M9 retrained with feature groups masked (embeddings-derived, weather,
anomaly, handcrafted, temporal context) to expose each group's
contribution to macro-F1. Reported as measured, not assumed.

## Decision layer (not a model, but the system output)

k-of-n persistence, weather veto, cooldowns and chainsaw escalation are
config-driven (`configs/default.yaml`) and unit-tested end-to-end
(`tests/test_decision.py`); the system-level simulation
(`reports/decision_sim.json`) reports false alarms per node-day and
per-class recall at the event level on a synthetic hop clock.
