"""Smoke-ingest: push synthetic but realistic traffic into a running backend
to exercise the dashboard, correlator and alert routing WITHOUT trained models.

Usage:  python scripts/smoke_ingest.py [--backend http://127.0.0.1:8000]

What it does (all fake data, clearly distinguishable from real sensor data):
- registers 4 nodes around the default GPS sector
- streams live scores + spectrogram columns (harmonics/noise/impulses)
- raises chainsaw / vehicle / human events and a 3-node gunshot (TDOA fires)
- one weather-vetoed gunshot (suppressed feed + veto banner)
- a handful of BirdNET-style biodiversity detections

This is a development/demo utility only; it never writes to data/processed.
"""
from __future__ import annotations

import argparse
import math
import random
import sys
import time
import datetime as dt
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO))

import httpx  # noqa: E402

from common.config import get_node_token, load_config  # noqa: E402

rng = random.Random(7)

# nodes: ~300 m grid around the demo sector centre
CENTER = (12.9716, 77.5946)
NODES = [
    ("node-01", CENTER[0] + 0.0018, CENTER[1] - 0.0020),   # ~N-W
    ("node-02", CENTER[0] - 0.0016, CENTER[1] - 0.0012),   # ~S-W
    ("node-03", CENTER[0] - 0.0009, CENTER[1] + 0.0021),   # ~S-E
    ("node-04", CENTER[0] + 0.0021, CENTER[1] + 0.0016),   # ~N-E
]


def utcnow() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def spect_column(kind: str = "forest", t: float = 0.0) -> list[int]:
    """Synthetic 64-bin mel column, uint8 (same encoding the edge agent uses)."""
    col = []
    for b in range(64):
        f = b / 63.0
        v = 18 + 22 * (1 - f)                       # noise floor, LF-heavy
        if kind == "chainsaw":
            for h in (0.18, 0.26, 0.34):            # saw harmonics
                v += 120 * math.exp(-((f - h) ** 2) / 0.0012)
            v += 20 * math.sin(t * 9.0)
        elif kind == "gunshot":
            v += rng.randint(90, 200) if f > 0.35 else 60
        elif kind == "thunder":
            v += 160 * math.exp(-((f - 0.06) ** 2) / 0.01)
        elif kind == "bird":
            v += rng.randint(60, 170) if 0.55 < f < 0.8 else 0
        col.append(max(0, min(255, int(v + rng.randint(-10, 10)))))
    return col


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--backend", default=load_config()["node"]["backend_url"])
    args = ap.parse_args()
    cfg = load_config()
    headers = {"X-ForestEar-Token": get_node_token(cfg)}
    cl = httpx.Client(timeout=5.0)

    def post(path: str, payload: dict):
        r = cl.post(f"{args.backend}{path}", json=payload, headers=headers)
        if r.status_code >= 300:
            print(f"  !! {path} -> {r.status_code} {r.text[:120]}")
        return r

    print("== ForestEar smoke ingest ==")

    # 1) heartbeats (nodes appear on the map + health grid)
    for nid, lat, lon in NODES:
        post("/ingest/heartbeat", {
            "node_id": nid, "online": True, "lat": lat, "lon": lon,
            "health": {"cpu_pct": rng.uniform(18, 40), "ram_mb": rng.uniform(210, 320),
                       "temp_c": rng.uniform(38, 47),
                       "battery": {"percent": rng.uniform(72, 96)}},
        })
    print("heartbeats: 4 nodes")

    # 2) live scores + spectrogram stream (chainsaw ramping on node-01)
    t0 = time.time()
    n_cols = 36
    for i in range(n_cols):
        t = time.time() - t0
        for nid, lat, lon in NODES:
            kind = "forest"
            m3 = 0.03
            if nid == "node-01":
                ramp = min(1.0, i / n_cols * 1.4)
                kind = "chainsaw" if ramp > 0.35 else "forest"
                m3 = 0.05 + 0.9 * ramp
            post("/ingest/scores", {
                "node_id": nid, "ts": utcnow(),
                "scores": {
                    "m2": 0.02, "m3": round(m3, 3), "m4": 0.05, "m7": 0.04,
                    "m6_z": round(rng.uniform(0.3, 1.1), 2),
                    "m5_rain": 0.02, "m5_wind": 0.06, "m5_thunder": 0.01,
                    "m9": {"chainsaw": round(m3, 3), "gunshot": 0.01,
                           "vehicle": 0.02, "human": 0.01, "benign": round(1 - m3, 3)},
                    "rms_db": rng.uniform(-38, -18),
                },
                "spect": spect_column(kind, t),
                "rms_db": rng.uniform(-38, -18),
            })
        time.sleep(0.12)
    print(f"scores stream: {n_cols} hops x 4 nodes")

    def event(nid, cls, sev, conf, lat, lon, pms, rms=-24.0):
        return post("/ingest/event", {
            "node_id": nid, "timestamp_utc": utcnow(), "lat": lat, "lon": lon,
            "class": cls, "severity": sev, "confidence": conf,
            "per_model_scores": pms, "rms_db": rms, "persistence": "3/5",
            "escalated": False, "battery": 88, "cpu": 27,
            "inference_ms": {"total": 141.0},
        })

    # 3) chainsaw event (node-01, escalated)
    event("node-01", "chainsaw", "WARNING", 0.91, NODES[0][1], NODES[0][2], {
        "m1_selected": {"Chainsaw": 0.42}, "m3_chainsaw": 0.93, "m9_fusion":
        {"chainsaw": 0.91, "benign": 0.06}}, rms=-21)

    # 4) vehicle + human
    event("node-03", "vehicle", "WARNING", 0.74, NODES[2][1], NODES[2][2], {
        "m4_vehicle": 0.77, "m9_fusion": {"vehicle": 0.74}}, rms=-30)
    event("node-02", "human", "INFO", 0.66, NODES[1][1], NODES[1][2], {
        "m7_human": 0.68}, rms=-36)

    # 5) 3-node gunshot -> TDOA correlation (delays from a source ~250 m N)
    #    source at (x=+40, y=+300) ENU; nodes at their positions; c=343 m/s
    src = (40.0, 300.0)
    base = time.time()
    for nid, lat, lon in NODES[:3]:
        x = (lon - CENTER[1]) * 111320 * math.cos(math.radians(CENTER[0]))
        y = (lat - CENTER[0]) * 110574
        d = math.hypot(x - src[0], y - src[1])
        arrival = base + d / 343.0
        while time.time() < arrival:
            time.sleep(0.01)
        event(nid, "gunshot", "CRITICAL", rng.uniform(0.86, 0.95), lat, lon,
              {"m2_gunshot": 0.94, "m9_fusion": {"gunshot": 0.93}}, rms=-8)
    print("gunshot: 3 nodes (TDOA should fire)")

    # 6) weather-vetoed gunshot (suppressed feed)
    post("/ingest/suppressed", {
        "node_id": "node-02", "timestamp_utc": utcnow(), "class": "gunshot",
        "confidence": 0.62, "suppressed_by": "thunder_veto", "rms_db": -19})
    print("suppressed: 1 (thunder veto)")

    # 7) biodiversity (BirdNET-style)
    species = [("Oriental Magpie-Robin", 0.82), ("Red-whiskered Bulbul", 0.77),
               ("Common Iora", 0.64), ("Jungle Babbler", 0.58)]
    post("/ingest/biodiversity", {
        "node_id": "node-04", "timestamp_utc": utcnow(),
        "detections": [{"species": s, "confidence": c} for s, c in species]})
    print("biodiversity: 4 detections")

    print("== done ==")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
