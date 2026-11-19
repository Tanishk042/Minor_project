# Third-party notices and attributions — ForestEar Node

The MIT license in `LICENSE` covers the original Kotlin/source code only. The
bundled model assets and libraries keep their own licenses. **Several model
assets are non-commercial**, so do not redistribute this APK or its model files
for commercial use without checking each license.

This file is a good-faith summary, not legal advice. If a license text and this
summary disagree, the upstream license wins.

## Bundled model assets (`app/src/main/assets/`)

| Asset | Used for | License | Source |
|---|---|---|---|
| `models/m1_yamnet.tflite` | M1 YAMNet scores + embeddings | Apache-2.0 | <https://tfhub.dev/google/yamnet/1> |
| `config/yamnet_classes.json` | YAMNet label mapping | CC BY 4.0 (AudioSet ontology) | <https://research.google.com/audioset/> |
| `models/m8_birdnet.tflite`, `models/birdnet_labels.txt` | M8 biodiversity only | CC BY-NC-SA 4.0 | <https://github.com/kahst/BirdNET-Analyzer> |
| `models/m2_gunshot.tflite` … `m9_mlp.tflite` | M2–M7 + M9 MLP | derived from ESC-50 (CC BY-NC 3.0), UrbanSound8K (CC BY-NC 3.0), FSD50K (CC BY 4.0) and the synthetic generator | this project |
| `config/m9_trees.bin`, `config/m9_meta.json` | M9 tree ensemble + isotonic calibration | derived from the same training data | this project |
| `features/*.f32`, `golden/*` | filterbanks and parity vectors | derived from librosa (ISC) and the models above | this project |

## Datasets behind the trained weights

| Dataset | License | Source |
|---|---|---|
| ESC-50 | CC BY-NC 3.0 | <https://github.com/karolpiczak/ESC-50> |
| UrbanSound8K | CC BY-NC 3.0 | <https://urbansounddataset.weebly.com/urbansound8k.html> |
| FSD50K | CC BY 4.0 | <https://freesound.org/people/Freesound/collections/579649/> |
| Synthetic ForestEar generator | MIT (this project) | `../forestear/ml/synth.py` |

## Android / Kotlin libraries

| Library | License |
|---|---|
| Kotlin standard library, kotlinx.coroutines | Apache-2.0 |
| AndroidX Core, Activity, Lifecycle, WorkManager | Apache-2.0 |
| Jetpack Compose (UI, Material 3, Material Icons) | Apache-2.0 |
| OkHttp | Apache-2.0 |
| TensorFlow Lite (CPU + GPU delegate) | Apache-2.0 |
| XNNPACK (bundled with TFLite) | BSD-3-Clause |
| SQLite (platform) | Public domain |
| JUnit 4 | EPL-1.0 |
| org.json (JVM test only) | JSON license / public domain |

## Services

| Service | Notes |
|---|---|
| Telegram Bot API | optional alert delivery; subject to Telegram's terms. No token is shipped. |
| dontkillmyapp.com | linked as an OEM reference; content belongs to its authors. |

## Redistribution

An APK contains the model assets. If you distribute a build, include this file
and the upstream license links. For commercial use, replace the
non-commercial models and weights or obtain separate permission.
