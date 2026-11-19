"""Simulator ground-truth and clip-selection bookkeeping.

`quiet_day` has no sources at all; before this was handled, writing its ground
truth crashed with IndexError and the whole scenario exited non-zero.
"""
from __future__ import annotations

from pathlib import Path

import numpy as np

from simulator.forest_sim import ClipPool, ForestSim, SR, default_nodes

REPO = Path(__file__).resolve().parents[1]
SCEN = REPO / "simulator" / "scenarios"


class StubPool:
    def sample(self, cls, rng, n, sustained=False):
        return np.zeros(n, dtype=np.float32)


def test_quiet_day_ground_truth_empty_and_written(tmp_path):
    sim = ForestSim(SCEN / "quiet_day.yaml", default_nodes(1), pool=StubPool())
    assert sim.ground_truth() == []

    out = tmp_path / "gt.csv"
    assert sim.write_ground_truth(out) == []
    header = out.read_text().strip().split(",")
    assert header == ["source_id", "cls", "t_start", "t_end",
                      "lat", "lon", "scenario"]


def test_gunfire_ground_truth_interval(tmp_path):
    sim = ForestSim(SCEN / "gunfire_burst.yaml", default_nodes(1),
                    pool=StubPool())
    rows = sim.ground_truth()
    assert len(rows) == 1
    row = rows[0]
    assert row["cls"] == "gunshot"
    assert 19.5 <= row["t_start"] <= 20.5
    assert 79.5 <= row["t_end"] <= 80.5

    out = tmp_path / "gt.csv"
    assert sim.write_ground_truth(out) == rows
    assert "gunshot" in out.read_text()


class FakeClipPool(ClipPool):
    """ClipPool with in-memory clips: one continuous, two with long silence."""

    def __init__(self):
        self.paths = {"vehicle": ["silent-a", "continuous", "silent-b"]}
        self._cache = {}

    def _clip(self, path):
        t = np.arange(SR, dtype=np.float32) / SR
        if path == "continuous":
            return 0.3 * np.sin(2 * np.pi * 120.0 * t).astype(np.float32)
        w = np.zeros(SR, dtype=np.float32)
        w[:SR // 10] = 0.8          # loud burst, then 0.9 s of silence
        return w


def test_sustained_clip_selection_avoids_silent_gaps():
    pool = FakeClipPool()

    class CycleRng:
        """Deterministic rng that visits every candidate path."""

        def __init__(self):
            self.i = 0

        def integers(self, low, high):
            v = low + self.i % (high - low)
            self.i += 1
            return v

    buf = pool.sample("vehicle", CycleRng(), SR * 4, sustained=True)
    assert len(buf) == SR * 4
    # every 0.5 s window is audible, unlike the burst-then-silence candidates
    frames = buf[:SR * 4].reshape(8, SR // 2)
    rms = np.sqrt(np.mean(frames.astype(np.float64) ** 2, axis=1))
    assert rms.min() > 0.1

