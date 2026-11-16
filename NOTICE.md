# Third-party notices and attributions

ForestEar is a research prototype. The MIT license in `LICENSE` covers the
original source code only. The models, datasets and libraries below keep their
own licenses. **Several are non-commercial**, so do not redistribute trained
weights or model files for commercial use without checking each license.

This file is a good-faith summary, not legal advice. If a license text and this
summary disagree, the upstream license wins.

## Models and pretrained artifacts

| Component | Used for | License | Source |
|---|---|---|---|
| YAMNet (TFLite / TF Hub SavedModel) | M1 audio-event scores + 1024-d embeddings | Apache-2.0 | <https://tfhub.dev/google/yamnet/1> |
| AudioSet ontology / class names | YAMNet label mapping | CC BY 4.0 | <https://research.google.com/audioset/> |
| BirdNET (GLOBAL 6K V2.4, int8) | M8 biodiversity only, never alerts | CC BY-NC-SA 4.0 | <https://github.com/kahst/BirdNET-Analyzer> |
| BirdNET labels | M8 label list | CC BY-NC-SA 4.0 | same as above |

M2–M7 and M9 were trained by this project on the datasets below plus synthetic
augmentation. Their weights are derived from those datasets and should be
treated as carrying the same non-commercial restrictions where applicable.

## Datasets

| Dataset | Used for | License | Source |
|---|---|---|---|
| ESC-50 | environmental sound classes | CC BY-NC 3.0 | <https://github.com/karolpiczak/ESC-50> |
| UrbanSound8K | urban/vehicle/background classes | CC BY-NC 3.0 | <https://urbansounddataset.weebly.com/urbansound8k.html> |
| FSD50K | broad audio-event pretraining/negatives | CC BY 4.0 (audio and annotations) | <https://freesound.org/people/Freesound/collections/579649/> |
| Synthetic ForestEar generator | gunshots, chainsaws, vehicles, weather, ambience | original code, MIT | `ml/synth.py` |
| Recorded field audio (if you add it) | optional field evaluation | operator's responsibility | — |

The synthetic generator is not a substitute for field recordings. Every
evaluation number in `reports/` states which source it came from.

## Python libraries

| Library | License |
|---|---|
| TensorFlow / TensorFlow Lite | Apache-2.0 |
| XNNPACK (bundled with TFLite) | BSD-3-Clause |
| librosa | ISC |
| soundfile (libsndfile) | BSD-3-Clause (LGPL for libsndfile) |
| NumPy | BSD-3-Clause |
| pandas | BSD-3-Clause |
| scikit-learn | BSD-3-Clause |
| LightGBM | MIT |
| Matplotlib | PSF-based / Matplotlib license |
| FastAPI | MIT |
| Uvicorn | BSD-3-Clause |
| SQLAlchemy | MIT |
| httpx | BSD-3-Clause |
| PyYAML | MIT |
| sounddevice | MIT |
| psutil | BSD-3-Clause |
| m2cgen | MIT |
| pytest | MIT |

## Android libraries

| Library | License |
|---|---|
| Kotlin standard library / coroutines | Apache-2.0 |
| AndroidX Core, Activity, Lifecycle, WorkManager | Apache-2.0 |
| Jetpack Compose (UI, Material 3, Material Icons) | Apache-2.0 |
| OkHttp | Apache-2.0 |
| TensorFlow Lite (CPU + GPU delegate) | Apache-2.0 |
| SQLite (platform) | Public domain |
| JUnit 4 | EPL-1.0 |
| org.json (JVM test only) | JSON license / public domain |

## Services

| Service | Notes |
|---|---|
| Telegram Bot API | optional alert delivery; subject to Telegram's terms. No bot token is shipped. |
| dontkillmyapp.com | linked as an OEM reference; content belongs to its authors. |

## Generated artifacts

`models_store/` (trained checkpoints and TFLite exports), the exported M9 tree
asset, the isotonic parameters, mel filterbanks, golden vectors and `reports/`
are derived from the models and datasets above. They are included for reproducibility of this
research prototype and inherit the upstream restrictions.

If you publish or deploy this project, include this file and the upstream
license links. If your use is commercial, replace the non-commercial model and
dataset dependencies, or obtain separate permission.
