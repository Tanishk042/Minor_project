"""Forest simulator: N virtual nodes over a real area, scenario engine in YAML,
per-node audio synthesis = ambience + distance-attenuated, delayed sources,
per-node clock offsets, ground-truth CSV log for event-level evaluation.

Audio sources: REAL dataset clips when available (much more realistic for the
models) - drawn from data/dataset/manifest.csv by class - with the synthetic
generator as fallback. Nodes push mixed hops to SimSource queues consumed by
edge agents (edge_agent/agent.py --mode sim), or the simulator can run the
pipeline itself in-process for evaluation (--offline-eval).

Scenario YAML schema (simulator/scenarios/*.yaml):
  duration_s: 180
  clock_offset_s: {node-01: 0.012, ...}     # optional, random if omitted
  sources:
    - id: saw1
      class: chainsaw        # chainsaw|gunshot|vehicle|human|thunder|rain|wind
      trajectory: [[t, lat, lon, level_db], ...]   # piecewise linear
      clip_pool: esc50       # esc50|synthetic (auto: dataset if available)
  ambience:
    level_db: 35
    kind: birds|rain|wind|quiet
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
import logging
import math
import sys
import time
from pathlib import Path

import numpy as np

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO))

from common.config import data_dir, load_config  # noqa: E402
from common.logging_setup import setup_logging  # noqa: E402
from ml import synth  # noqa: E402
from ml.audio_io import load_audio  # noqa: E402

log = logging.getLogger("forestear.sim")
SR = 16000
HOP = SR // 2
C_SOUND = 343.0

CLASS_TO_DATASET_CLASS = {
    "chainsaw": "chainsaw", "gunshot": "gunshot", "vehicle": "vehicle",
    "human": "human", "thunder": "weather_thunder", "rain": "weather_rain",
    "wind": "weather_wind",
}


# ---------------------------------------------------------------------------
class ClipPool:
    """Real dataset clips per class (eval-split only, to avoid train data
    leaking into the simulator), with synthetic fallback."""

    def __init__(self, split: str = "test"):
        self.clips: dict[str, list[np.ndarray]] = {}
        man = data_dir("dataset") / "manifest.csv"
        import pandas as pd
        if man.exists():
            df = pd.read_csv(man)
            for cls in CLASS_TO_DATASET_CLASS.values():
                rows = df[(df["class"] == cls) & (df["source"] != "synthetic")]
                # use any fold: the simulator is a DEMO, not an eval set;
                # for evaluation use --clip-split test
                if split == "test":
                    # prefer test fold clips for evaluation runs
                    sub = rows[rows["fold"] >= 5]
                    if len(sub):
                        rows = sub
                for r in rows.itertuples(index=False):
                    try:
                        w = load_audio(r.path, sr=SR)
                        if len(w) >= SR // 2:
                            self.clips.setdefault(cls, []).append(w)
                    except Exception:  # noqa: BLE001
                        pass
            log.info("clip pool: %s",
                     {k: len(v) for k, v in self.clips.items()})
        else:
            log.warning("no dataset manifest -> synthetic clips only")

    def sample(self, cls: str, rng: np.random.Generator, n: int) -> np.ndarray:
        """n samples of class audio (loop a random clip)."""
        ds_cls = CLASS_TO_DATASET_CLASS.get(cls, cls)
        bank = self.clips.get(ds_cls)
        if bank:
            w = bank[int(rng.integers(0, len(bank)))]
            reps = int(math.ceil(n / len(w)))
            return np.tile(w, reps)[:n].astype(np.float32)
        gen = {"chainsaw": synth.chainsaw, "gunshot": synth.gunshot,
               "vehicle": synth.vehicle, "human": synth.voice_like,
               "thunder": synth.thunder, "rain": synth.rain,
               "wind": synth.wind}.get(cls, synth.forest_ambience)
        return gen(seed=int(rng.integers(1 << 30)), dur=n / SR, sr=SR)


def haversine_m(lat1, lon1, lat2, lon2) -> float:
    R = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * R * math.asin(math.sqrt(a))


def offset_ll(lat, lon, d_north_m, d_east_m):
    R = 6371000.0
    return (lat + math.degrees(d_north_m / R),
            lon + math.degrees(d_east_m / (R * math.cos(math.radians(lat)))))


# ---------------------------------------------------------------------------
class VirtualNode:
    def __init__(self, node_id: str, lat: float, lon: float,
                 clock_offset_s: float):
        self.node_id = node_id
        self.lat = lat
        self.lon = lon
        self.clock_offset_s = clock_offset_s
        self.amb_state = np.zeros(0, dtype=np.float32)
        self.amb_rng = np.random.default_rng(abs(hash(node_id)) % (2**31))

    def mix(self, t: float, sources: list, pool: ClipPool,
            rng: np.random.Generator, n: int) -> np.ndarray:
        """Render n samples (one hop) of this node's microphone."""
        out = np.zeros(n, dtype=np.float32)
        for src in sources:
            level = src.level_at(t)
            if level <= 0:
                continue
            lat, lon = src.position_at(t)
            d = haversine_m(self.lat, self.lon, lat, lon)
            # inverse-square + air absorption roll-off
            atten = (1.0 / max(d, 1.0) ** 1.0) * 10 ** (-level / 20.0)
            # source audio with propagation delay d/c seconds
            delay_s = d / C_SOUND
            w = src.audio_for(n, t - delay_s, pool, rng)
            # distance HF roll-off
            cutoff = max(400.0, 8000.0 - 30.0 * d)
            if cutoff < 7900:
                w = synth._fast_lowpass(w, SR, cutoff)
            out += (w * atten).astype(np.float32)
        # ambience
        out += self.ambience(n)
        # wind gust noise occasionally
        if rng.uniform() < 0.02:
            g = synth.wind(seed=int(rng.integers(1 << 30)),
                           dur=n / SR, sr=SR) * rng.uniform(0.05, 0.2)
            out += g
        return np.clip(out, -1.0, 1.0)

    def ambience(self, n: int) -> np.ndarray:
        """Continuous benign background (birds + insects + soft wind)."""
        if len(self.amb_state) < n:
            self.amb_state = synth.forest_ambience(
                seed=int(self.amb_rng.integers(1 << 30)), dur=30.0, sr=SR)
        seg = self.amb_state[:n]
        self.amb_state = self.amb_state[n:]
        return (seg * 0.30).astype(np.float32)


class MovingSource:
    """A sound source with a piecewise-linear trajectory and level schedule."""

    def __init__(self, spec: dict, rng: np.random.Generator):
        self.id = spec.get("id", "src")
        self.cls = spec["class"]
        self.traj = [(float(p[0]), float(p[1]), float(p[2]), float(p[3]))
                     for p in spec["trajectory"]]
        self.clip_pool_name = spec.get("clip_pool", "auto")
        self._buf = np.zeros(0, dtype=np.float32)
        self._pos = 0
        self.rng = rng

    def position_at(self, t: float) -> tuple[float, float]:
        return self._interp(t, 1, 2)

    def level_at(self, t: float) -> float:
        return self._interp(t, 3, 3)

    def _interp(self, t: float, vi: int, vj: int) -> float:
        pts = self.traj
        if t <= pts[0][0]:
            return pts[0][vi] if vi == 3 else (pts[0][1], pts[0][2])[vj - 1]
        for (t0, la0, lo0, lv0), (t1, la1, lo1, lv1) in zip(pts, pts[1:]):
            if t0 <= t <= t1:
                f = (t - t0) / max(t1 - t0, 1e-6)
                if vi == 3:
                    return lv0 + (lv1 - lv0) * f
                return (la0 + (la1 - la0) * f, lo0 + (lo1 - lo0) * f)[vj - 1]
        last = pts[-1]
        return last[3] if vi == 3 else (last[1], last[2])[vj - 1]

    def audio_for(self, n: int, t: float, pool: ClipPool,
                  rng: np.random.Generator) -> np.ndarray:
        """Source waveform at (delayed) time t, n samples."""
        if len(self._buf) < self._pos + n:
            self._buf = pool.sample(self.cls, rng, SR * 30)  # 30 s chunks
        seg = self._buf[self._pos:self._pos + n]
        self._pos += n
        if len(seg) < n:
            seg = np.pad(seg, (0, n - len(seg)))
        return seg.astype(np.float32)


# ---------------------------------------------------------------------------
class ForestSim:
    def __init__(self, scenario: str | Path, nodes: list[VirtualNode],
                 pool: ClipPool | None = None, clip_split: str = "any"):
        import yaml
        self.scenario_path = Path(scenario)
        self.cfg_sim = yaml.safe_load(self.scenario_path.read_text())
        self.duration = float(self.cfg_sim.get("duration_s", 120))
        self.nodes = nodes
        self.pool = pool or ClipPool(split=clip_split)
        self.rng = np.random.default_rng(1234)
        self.sources = [MovingSource(s, self.rng)
                        for s in self.cfg_sim.get("sources", [])]
        cof = self.cfg_sim.get("clock_offset_s", {})
        for nd in nodes:
            if nd.node_id in cof:
                nd.clock_offset_s = float(cof[nd.node_id])
        # ambience kind override
        amb = self.cfg_sim.get("ambience", {})
        self.amb_kind = amb.get("kind", "birds")
        self.amb_level = float(amb.get("level_db", 35))

    def ground_truth(self) -> list[dict]:
        """Per-source active intervals with position (for event scoring)."""
        rows = []
        for src in self.sources:
            # find active segments (level > 0)
            ts = np.arange(0, self.duration, 0.5)
            active = [src.level_at(float(t)) > 0 for t in ts]
            i = 0
            while i < len(active):
                if active[i]:
                    j = i
                    while j < len(active) and active[j]:
                        j += 1
                    t0, t1 = float(ts[i]), float(ts[j - 1]) + 0.5
                    lat, lon = src.position_at((t0 + t1) / 2)
                    rows.append(dict(source_id=src.id, cls=src.cls,
                                     t_start=t0, t_end=t1, lat=lat, lon=lon,
                                     scenario=self.scenario_path.stem))
                    i = j
                else:
                    i += 1
        return rows

    def run(self, sinks: dict[str, object] | None = None, time_scale: float = 1.0,
            gt_path: Path | None = None) -> list[dict]:
        """Generate the audio stream; push hops to per-node sinks.

        sinks: {node_id: SimSource or None}; None sinks still generate ground
        truth. time_scale > 1 = accelerated (faster than real time).
        Returns ground truth rows (also written to CSV if gt_path).
        """
        n_hops = int(self.duration / 0.5)
        log.info("scenario %s: %.0fs, %d nodes, %d sources, scale=%.1fx",
                 self.scenario_path.name, self.duration, len(self.nodes),
                 len(self.sources), time_scale)
        t = 0.0
        for hop in range(n_hops):
            t = hop * 0.5
            for nd in self.nodes:
                sink = (sinks or {}).get(nd.node_id)
                w = nd.mix(t, self.sources, self.pool, self.rng, HOP)
                if sink is not None:
                    sink.push(w, t)
            if time_scale != 1.0:
                time.sleep(0.5 / time_scale * 0)  # no pacing when accelerated
            elif sinks:
                time.sleep(0.5)
        for sink in (sinks or {}).values():
            if hasattr(sink, "finish"):
                sink.finish()
        gt = self.ground_truth()
        if gt_path:
            with open(gt_path, "w", newline="", encoding="utf-8") as f:
                w = csv.DictWriter(f, fieldnames=list(gt[0].keys()))
                w.writeheader()
                w.writerows(gt)
            log.info("ground truth -> %s (%d events)", gt_path, len(gt))
        return gt


def default_nodes(n: int = 4, center: tuple[float, float] | None = None,
                  radius_m: float = 600.0, seed: int = 7) -> list[VirtualNode]:
    """Place n nodes on a circle around the area centre."""
    cfg = load_config()["sim"]
    lat0, lon0 = center or (cfg["area_center"]["lat"], cfg["area_center"]["lon"])
    rng = np.random.default_rng(seed)
    nodes = []
    for i in range(n):
        ang = 2 * math.pi * i / n + rng.uniform(-0.2, 0.2)
        r = radius_m * (0.55 + 0.35 * rng.uniform())
        lat, lon = offset_ll(lat0, lon0, r * math.sin(ang), r * math.cos(ang))
        nodes.append(VirtualNode(f"node-{i+1:02d}", round(lat, 6), round(lon, 6),
                                 float(rng.uniform(-0.05, 0.05))))
    return nodes


def main() -> int:
    setup_logging()
    ap = argparse.ArgumentParser()
    ap.add_argument("scenario", nargs="?", default="chainsaw_approach")
    ap.add_argument("--nodes", type=int, default=4)
    ap.add_argument("--accelerate", type=float, default=1.0,
                    help="time acceleration factor (e.g. 60 = 1 min sim per s)")
    ap.add_argument("--list", action="store_true")
    args = ap.parse_args()
    if args.list:
        for p in sorted((REPO / "simulator" / "scenarios").glob("*.yaml")):
            print(p.stem)
        return 0
    nodes = default_nodes(args.nodes)
    sim = ForestSim(REPO / "simulator" / "scenarios" / f"{args.scenario}.yaml", nodes)
    gt_dir = data_dir("sim", args.scenario)
    gt = sim.run(sinks=None, time_scale=args.accelerate,
                 gt_path=gt_dir / "ground_truth.csv")
    print(json.dumps(gt, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
