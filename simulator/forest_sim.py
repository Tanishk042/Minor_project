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
    leaking into the simulator), with synthetic fallback.

    Paths are indexed eagerly but audio is decoded lazily: the manifest has
    thousands of rows and eagerly decoding all of them delayed every simulator
    start by minutes for clips a given scenario may never use.
    """

    def __init__(self, split: str = "test"):
        self.paths: dict[str, list[str]] = {}
        self._cache: dict[str, np.ndarray | None] = {}
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
                self.paths[cls] = [str(p) for p in rows["path"]]
            log.info("clip pool index: %s",
                     {k: len(v) for k, v in self.paths.items()})
        else:
            log.warning("no dataset manifest -> synthetic clips only")

    def _clip(self, path: str) -> np.ndarray | None:
        if path in self._cache:
            return self._cache[path]
        try:
            w = load_audio(path, sr=SR)
        except Exception:  # noqa: BLE001
            w = None
        if w is not None and len(w) < SR // 2:
            w = None
        self._cache[path] = w
        return w

    @staticmethod
    def _tile(w: np.ndarray, n: int) -> np.ndarray:
        reps = int(math.ceil(n / len(w)))
        return np.tile(w, reps)[:n].astype(np.float32)

    @staticmethod
    def _floor_rms_db(w: np.ndarray) -> float:
        """RMS of the quietest 0.5 s window (dBFS) — a continuity score."""
        hop = SR // 2
        if len(w) < hop:
            return -120.0
        n = len(w) // hop
        frames = w[:n * hop].reshape(n, hop)
        rms = np.sqrt(np.mean(frames.astype(np.float64) ** 2, axis=1))
        return float(20.0 * np.log10(max(float(rms.min()), 1e-12)))

    def sample(self, cls: str, rng: np.random.Generator, n: int,
               sustained: bool = False) -> np.ndarray:
        """n samples of class audio (loop a random clip).

        ``sustained=True`` picks, among a few random candidates, the clip with
        the loudest *quietest* 0.5 s window. A single random clip can be a
        pass-by recording with long silences: looped, it drops the source to
        near-ambience at an arbitrary point in the scenario, which made
        `vehicle_track` depend on loop phase. Sustained selection is opt-in
        per source (`clip_select: sustained` in the scenario YAML).
        """
        ds_cls = CLASS_TO_DATASET_CLASS.get(cls, cls)
        paths = self.paths.get(ds_cls, [])
        if paths:
            if sustained:
                best: tuple[float, np.ndarray] | None = None
                for _ in range(min(8, len(paths))):
                    w = self._clip(paths[int(rng.integers(0, len(paths)))])
                    if w is None:
                        continue
                    score = self._floor_rms_db(w)
                    if best is None or score > best[0]:
                        best = (score, w)
                if best is not None:
                    return self._tile(best[1], n)
            # try a few random clips in case a file is unreadable
            for _ in range(min(8, len(paths))):
                w = self._clip(paths[int(rng.integers(0, len(paths)))])
                if w is not None:
                    return self._tile(w, n)
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


# Digital level calibration for the simulator.
#
# Scenario `level_db` values are physical SPLs: a source level is its SPL at
# 1 m, `ambience.level_db` is the ambient SPL. We map the *reference* ambient
# level (33 dB SPL) to AMB_PEAK full-scale so that (a) nothing clips, (b) quiet
# forest ambience stays well above the agent's -60 dBFS silence gate, and
# (c) every source sits at its correct level relative to the ambience
# (spherical spreading, 6 dB per doubling of distance). This is a demo
# rendering model, not a calibrated acoustic simulation.
AMB_REF_DB = 33.0
AMB_PEAK = 0.02


# ---------------------------------------------------------------------------
class VirtualNode:
    def __init__(self, node_id: str, lat: float, lon: float,
                 clock_offset_s: float):
        self.node_id = node_id
        self.lat = lat
        self.lon = lon
        self.clock_offset_s = clock_offset_s
        self.amb_kind = "birds"
        self.amb_level = AMB_REF_DB
        self.amb_state = np.zeros(0, dtype=np.float32)
        self.amb_rng = np.random.default_rng(abs(hash(node_id)) % (2**31))
        # Per-source causal lowpass state. A per-hop FFT brick-wall filter
        # pre-rings, so a truncated impulse near a hop boundary leaked energy
        # before its true onset and shifted TDOA by tens of milliseconds.
        self._lp_state: dict[str, float] = {}

    @property
    def amb_amp(self) -> float:
        """Digital amplitude that renders this scenario's ambient SPL."""
        return AMB_PEAK * 10 ** ((self.amb_level - AMB_REF_DB) / 20.0)

    def mix(self, t: float, sources: list, pool: ClipPool,
            rng: np.random.Generator, n: int) -> np.ndarray:
        """Render n samples (one hop) of this node's microphone.

        Returns (audio, arrival_delay_s) where arrival_delay_s is the air
        propagation delay of the dominant source at time t — used to give each
        node a physically meaningful capture timestamp for TDOA.
        """
        out = np.zeros(n, dtype=np.float32)
        best_atten = 0.0
        best_delay = 0.0
        for src in sources:
            level = src.level_at(t)
            if level <= 0:
                continue
            lat, lon = src.position_at(t)
            d = haversine_m(self.lat, self.lon, lat, lon)
            # Spherical spreading: SPL at this node = level - 20*log10(d).
            # Render it at the same digital scale as the ambience, so the SNR
            # at the microphone is physically meaningful.
            spl_here = level - 20.0 * math.log10(max(d, 1.0))
            atten = self.amb_amp * 10 ** ((spl_here - self.amb_level) / 20.0)
            # source audio with propagation delay d/c seconds
            delay_s = d / C_SOUND
            if atten > best_atten:
                best_atten, best_delay = atten, delay_s
            w = src.audio_for(n, t - delay_s, pool, rng)
            # distance-dependent atmospheric HF roll-off (gentler than the old
            # model: a hard 400 Hz ceiling erased the impulsive gunshot onset
            # that TDOA depends on)
            cutoff = max(1200.0, 9000.0 - 9.0 * d)
            if cutoff < 7900:
                w = self._lowpass(w, cutoff, src.id)
            out += (w * atten).astype(np.float32)
        # ambience
        out += self.ambience(n)
        # wind gust noise occasionally
        if rng.uniform() < 0.02:
            g = synth.wind(seed=int(rng.integers(1 << 30)),
                           dur=n / SR, sr=SR) * rng.uniform(0.05, 0.2)
            out += g
        return np.clip(out, -1.0, 1.0), best_delay

    def _lowpass(self, x: np.ndarray, cutoff: float, key: str) -> np.ndarray:
        """Causal one-pole lowpass with state carried across hops.

        A zero-phase FFT filter applied to each 0.5 s hop rings backwards in
        time; for an impulse near a hop boundary that pre-ringing arrives
        before the real onset and corrupts TDOA. A causal filter has no such
        artefact and is the physically sensible model of air absorption.
        """
        from scipy.signal import lfilter
        alpha = math.exp(-2.0 * math.pi * cutoff / SR)
        zi = alpha * self._lp_state.get(key, 0.0)
        y, _ = lfilter([1.0 - alpha], [1.0, -alpha], x, zi=[zi])
        self._lp_state[key] = float(y[-1]) if len(y) else 0.0
        return y.astype(np.float32)

    def ambience(self, n: int) -> np.ndarray:
        """Continuous background for this scenario's ambience kind + level."""
        if len(self.amb_state) < n:
            gen = {"rain": synth.rain, "wind": synth.wind,
                   "birds": synth.forest_ambience,
                   "ambience": synth.forest_ambience}.get(
                       self.amb_kind, synth.forest_ambience)
            self.amb_state = gen(seed=int(self.amb_rng.integers(1 << 30)),
                                 dur=30.0, sr=SR)
        seg = self.amb_state[:n]
        self.amb_state = self.amb_state[n:]
        return (seg * self.amb_amp).astype(np.float32)


class MovingSource:
    """A sound source with a piecewise-linear trajectory and level schedule."""

    def __init__(self, spec: dict, rng: np.random.Generator):
        self.id = spec.get("id", "src")
        self.cls = spec["class"]
        self.traj = [(float(p[0]), float(p[1]), float(p[2]), float(p[3]))
                     for p in spec["trajectory"]]
        self.clip_pool_name = spec.get("clip_pool", "auto")
        self.clip_select = spec.get("clip_select", "auto")
        self._buf: np.ndarray | None = None
        self.rng = rng

    def position_at(self, t: float) -> tuple[float, float]:
        return self._interp(t, 1, 2)

    def level_at(self, t: float) -> float:
        return self._interp(t, 3, 3)

    def _interp(self, t: float, vi: int, vj: int):
        """Linear interpolation of one column (vi == vj) or a pair of columns."""
        pts = self.traj

        def lerp(a, b, f):
            return a + (b - a) * f

        def at(p):
            return p[vi] if vi == vj else (p[vi], p[vj])

        if t < pts[0][0]:
            # A source is silent before the first trajectory point. Returning
            # the first point's level here made every scenario's source active
            # from t=0 (e.g. the 20-30 s gunfire burst fired at 1 s, 11 s...).
            if vi == vj == 3:
                return 0.0
            return at(pts[0])
        for p0, p1 in zip(pts, pts[1:]):
            if p0[0] <= t <= p1[0]:
                f = (t - p0[0]) / max(p1[0] - p0[0], 1e-6)
                if vi == vj:
                    return lerp(p0[vi], p1[vi], f)
                return (lerp(p0[vi], p1[vi], f), lerp(p0[vj], p1[vj], f))
        return at(pts[-1])

    def _synthetic_buffer(self) -> np.ndarray:
        """Deterministic synthetic source stream for timing-sensitive demos.

        Impulsive classes repeat on a fixed grid (2 s) with a single sharp
        onset, so cross-node TDOA is unambiguous instead of depending on where
        a real recording happens to start.
        """
        dur = 240.0
        buf = np.zeros(int(SR * dur), dtype=np.float32)
        if self.cls == "gunshot":
            # sparse impulses: period must exceed the largest inter-node
            # propagation difference (few seconds) or different shots alias
            # into the same TDOA group.
            period, length = 10.0, 0.9
            t = 1.0
            while t < dur - length:
                w = synth.gunshot(seed=int(t * 1000), dur=length, sr=SR)
                i0 = int(t * SR)
                buf[i0:i0 + len(w)] += w
                t += period
        else:
            gen = {"chainsaw": synth.chainsaw, "vehicle": synth.vehicle,
                   "human": synth.voice_like, "thunder": synth.thunder,
                   "rain": synth.rain, "wind": synth.wind}.get(
                       self.cls, synth.forest_ambience)
            buf[:] = gen(seed=1234, dur=dur, sr=SR)[:len(buf)]
        return buf

    def audio_for(self, n: int, t: float, pool: ClipPool,
                  rng: np.random.Generator) -> np.ndarray:
        """Source waveform at (delayed) time t, n samples.

        The buffer is indexed by ABSOLUTE time so every virtual node hears the
        same source signal at the same instant (only its propagation delay
        differs). A shared cursor would make each node hear a different slice,
        which would make TDOA physically meaningless.
        """
        if self._buf is None:
            if self.clip_pool_name == "synthetic":
                self._buf = self._synthetic_buffer()
            else:
                self._buf = pool.sample(
                    self.cls, rng, SR * 240,
                    sustained=self.clip_select == "sustained")  # > longest scenario
        i0 = int(max(t, 0.0) * SR)
        seg = self._buf[i0:i0 + n]
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
        for nd in nodes:
            nd.amb_kind = self.amb_kind
            nd.amb_level = self.amb_level

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
                w, delay_s = nd.mix(t, self.sources, self.pool, self.rng, HOP)
                if sink is not None:
                    # Node capture time = scenario time + this node's deliberate
                    # clock offset. Air propagation is already inside the audio
                    # (the source buffer is indexed at t - d/c), so the agent's
                    # onset refinement recovers the true arrival time.
                    sink.push(w, t + nd.clock_offset_s)
            if time_scale != 1.0:
                time.sleep(0.5 / time_scale * 0)  # no pacing when accelerated
            elif sinks:
                time.sleep(0.5)
        for sink in (sinks or {}).values():
            if hasattr(sink, "finish"):
                sink.finish()
        gt = self.write_ground_truth(gt_path) if gt_path else self.ground_truth()
        return gt

    def write_ground_truth(self, path: str | Path) -> list[dict]:
        """Write per-source intervals to CSV (header only when there are none)."""
        gt = self.ground_truth()
        fields = ["source_id", "cls", "t_start", "t_end",
                  "lat", "lon", "scenario"]
        with open(path, "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=fields)
            w.writeheader()
            w.writerows(gt)
        log.info("ground truth -> %s (%d events)", path, len(gt))
        return gt


def default_nodes(n: int = 4, center: tuple[float, float] | None = None,
                  radius_m: float = 250.0, seed: int = 7) -> list[VirtualNode]:
    """Place n nodes on a circle around the area centre.

    The ring radius is deliberately modest (~250 m): acoustic gunshot
    localisation needs a baseline comparable to the source distance, and a
    1 km ring puts distant nodes below the detection floor for a 105 dB
    muzzle blast. Documented as a demo deployment, not a field layout.
    """
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
