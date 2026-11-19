"""Backend load test: N simulated nodes against a running ForestEar backend.

Every number printed here is measured from this run. Nothing is a pass/fail
threshold and nothing is extrapolated. If the backend is not running, the
script reports errors instead of inventing results.

Example (backend already listening on :8000):

    ../.venv/bin/python scripts/load_test.py \
        --url http://127.0.0.1:8000 --nodes 20 --duration 30 --rate 0.5 \
        --out reports/load_test.json

Each simulated node posts a heartbeat and, with probability --event-ratio, an
event, at --rate requests/second. Node IDs are prefixed --prefix (default
load) so they are easy to tell apart from real nodes. Point the backend at a
throwaway DB with FORESTEAR_DB=/tmp/load.db when running the load test.
"""
from __future__ import annotations

import argparse
import json
import math
import random
import statistics
import sys
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

import httpx

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO))

CLASSES = ["chainsaw", "gunshot", "vehicle", "weather"]


def _percentile(sorted_vals: list[float], p: float) -> float | None:
    if not sorted_vals:
        return None
    k = max(0, min(len(sorted_vals) - 1, math.ceil(p / 100 * len(sorted_vals)) - 1))
    return sorted_vals[k]


def _payload(node: str, kind: str, rng: random.Random) -> dict:
    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    if kind == "heartbeat":
        return {"node_id": node,
                "health": {"cpu_pct": round(rng.uniform(5, 60), 1),
                           "ram_mb": round(rng.uniform(80, 400), 1),
                           "temp_c": round(rng.uniform(28, 44), 1),
                           "battery": {"percent": round(rng.uniform(40, 100), 1)}},
                "scores": {}}
    return {"node_id": node, "timestamp_utc": now,
            "lat": 12.9716 + rng.uniform(-0.002, 0.002),
            "lon": 77.5946 + rng.uniform(-0.002, 0.002),
            "class": rng.choice(CLASSES),
            "severity": "WARNING",
            "confidence": round(rng.uniform(0.6, 0.95), 3),
            "persistence": "3/5",
            "rms_db": round(rng.uniform(-45, -15), 1),
            "per_model_scores": {"m2": 0.1, "m3": 0.7, "m9": 0.8},
            "inference_ms": {"total": round(rng.uniform(40, 90), 1)}}


def _worker(idx: int, args, stop: threading.Event, results: list[dict]):
    rng = random.Random(1000 + idx)
    node = f"{args.prefix}-{idx:03d}"
    headers = {"X-ForestEar-Token": args.token,
               "Content-Type": "application/json"}
    interval = 1.0 / args.rate
    with httpx.Client(timeout=args.timeout) as client:
        while not stop.is_set():
            kind = "event" if rng.random() < args.event_ratio else "heartbeat"
            path = "/ingest/event" if kind == "event" else "/ingest/heartbeat"
            t0 = time.perf_counter()
            try:
                r = client.post(args.url.rstrip("/") + path,
                                json=_payload(node, kind, rng), headers=headers)
                ms = (time.perf_counter() - t0) * 1e3
                results.append({"node": node, "kind": kind,
                                "status": r.status_code, "ms": ms,
                                "error": None if r.status_code < 400 else r.text[:200]})
            except Exception as e:  # noqa: BLE001
                results.append({"node": node, "kind": kind, "status": None,
                                "ms": (time.perf_counter() - t0) * 1e3,
                                "error": str(e)[:200]})
            # pace each node; time spent waiting does not count as latency
            wait = interval - (time.perf_counter() - t0)
            if wait > 0:
                stop.wait(wait)


def run(args) -> dict:
    stop = threading.Event()
    results: list[dict] = []
    threads = [threading.Thread(target=_worker, args=(i, args, stop, results),
                                daemon=True)
               for i in range(args.nodes)]
    started = time.time()
    for t in threads:
        t.start()
    try:
        stop.wait(args.duration)
    except KeyboardInterrupt:
        pass
    stop.set()
    for t in threads:
        t.join(timeout=10)
    elapsed = time.time() - started

    lat = sorted(r["ms"] for r in results if r["status"] is not None)
    ok = [r for r in results if r["status"] is not None and r["status"] < 400]
    ok_ids = {id(r) for r in ok}
    bad = [r for r in results if id(r) not in ok_ids]
    codes: dict[str, int] = {}
    for r in results:
        key = str(r["status"]) if r["status"] is not None else "transport_error"
        codes[key] = codes.get(key, 0) + 1

    summary = {
        "measured_at_utc": datetime.now(timezone.utc).isoformat(),
        "target_url": args.url,
        "config": {"nodes": args.nodes, "duration_s": args.duration,
                   "rate_per_node_per_s": args.rate,
                   "event_ratio": args.event_ratio,
                   "timeout_s": args.timeout},
        "requests_total": len(results),
        "requests_ok": len(ok),
        "requests_failed": len(bad),
        "status_codes": codes,
        "throughput_req_per_s": round(len(results) / elapsed, 2) if elapsed else None,
        "latency_ms": {
            "mean": round(statistics.fmean(lat), 2) if lat else None,
            "p50": _percentile(lat, 50),
            "p95": _percentile(lat, 95),
            "p99": _percentile(lat, 99),
            "max": round(max(lat), 2) if lat else None,
        },
        "elapsed_s": round(elapsed, 2),
        "errors_sample": [r["error"] for r in bad if r["error"]][:5],
        "notes": [
            "Measured on this machine against this backend process only.",
            "The backend is single-process SQLite: throughput is not a",
            "multi-node production capacity claim.",
        ],
    }
    return summary


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--url", default="http://127.0.0.1:8000")
    ap.add_argument("--nodes", type=int, default=10)
    ap.add_argument("--duration", type=float, default=30.0, help="seconds")
    ap.add_argument("--rate", type=float, default=0.5,
                    help="requests/second per node (backend cap is 2/s/node)")
    ap.add_argument("--event-ratio", type=float, default=0.2,
                    help="fraction of requests that are events")
    ap.add_argument("--token", default=None)
    ap.add_argument("--prefix", default="load")
    ap.add_argument("--timeout", type=float, default=10.0)
    ap.add_argument("--out", default="reports/load_test.json")
    args = ap.parse_args(argv)

    if args.token is None:
        from common.config import get_node_token, load_config
        args.token = get_node_token(load_config())

    summary = run(args)
    out = Path(args.out)
    if not out.is_absolute():
        out = REPO / out
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))
    print(f"\nwrote {out}")
    return 0 if summary["requests_ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
