"""Build the benign-forest background bank for augmentation and M6 training.

Collects benign ambience (forest, birds, rain, wind, quiet) from the unified
dataset + always some synthetic ambience, writes 5 s clips to
data/background/ and a manifest. M6 (anomaly AE) trains ONLY on these.
"""
from __future__ import annotations

import logging
import sys
from pathlib import Path

import numpy as np
import pandas as pd

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO))

from common.config import data_dir  # noqa: E402
from common.logging_setup import setup_logging  # noqa: E402
from ml import synth  # noqa: E402
from ml.audio_io import load_audio, save_wav  # noqa: E402

log = logging.getLogger("forestear.background")
SR = 16000
CLIP_S = 5.0


def main() -> int:
    setup_logging()
    out_dir = data_dir("background")
    bank: list[np.ndarray] = []
    man = data_dir("dataset") / "manifest.csv"
    if man.exists():
        df = pd.read_csv(man)
        benign = df[df["class"].str.startswith(("benign", "weather"))]
        log.info("benign/weather clips for background: %d", len(benign))
        rng = np.random.default_rng(5)
        for r in benign.itertuples(index=False):
            try:
                wav = load_audio(r.path)
            except Exception:  # noqa: BLE001
                continue
            if len(wav) < SR:
                continue
            # cut up to 2 slices of CLIP_S from each clip
            for _ in range(2):
                if len(wav) <= SR * CLIP_S:
                    seg = wav
                else:
                    s = int(rng.integers(0, len(wav) - int(SR * CLIP_S)))
                    seg = wav[s:s + int(SR * CLIP_S)]
                if len(seg) < SR:
                    continue
                bank.append(seg)
    else:
        log.warning("no dataset manifest - synthetic background only")
    # always add synthetic ambience (diversity for M6 open-set behaviour)
    g = np.random.default_rng(99)
    for i in range(60):
        bank.append(synth.forest_ambience(seed=int(g.integers(1 << 30)),
                                          dur=CLIP_S, sr=SR))
    log.info("background bank: %d slices", len(bank))
    files = []
    for i, seg in enumerate(bank):
        p = out_dir / f"bg_{i:05d}.wav"
        save_wav(p, seg)
        files.append(str(p))
    pd.DataFrame({"path": files}).to_csv(out_dir / "background_manifest.csv", index=False)
    log.info("wrote %d background slices to %s", len(files), out_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
