"""Edge agent main loop: source -> pipeline (all 9 models) -> decision ->
transport. Modes: mic | file | sim.

Usage:
  python edge_agent/agent.py --mode mic
  python edge_agent/agent.py --mode file --path data/dataset/xxx.wav
  python edge_agent/agent.py --mode file --path dir/ --loop
  python edge_agent/agent.py --mode sim  (fed by simulator/forest_sim.py)
"""
from __future__ import annotations

import argparse
import logging
import signal
import sys
import time
from pathlib import Path

import numpy as np

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO))

from common.config import load_config, data_dir  # noqa: E402
from common.logging_setup import setup_logging  # noqa: E402
from edge_agent.decision import DecisionEngine, WindowScores  # noqa: E402
from edge_agent.health import HealthMonitor  # noqa: E402
from edge_agent.sources import MicSource, FileSource, SimSource  # noqa: E402
from edge_agent.transport import Transport, save_alert_clip, compact_payload  # noqa: E402
from edge_agent.zoo import ModelZoo  # noqa: E402

log = logging.getLogger("forestear.agent")


class EdgeAgent:
    def __init__(self, source, node_id: str | None = None,
                 backend_url: str | None = None, lat: float | None = None,
                 lon: float | None = None, queue_path: str | None = None):
        self.cfg = load_config()
        self.source = source
        self.node_id = node_id or self.cfg["node"]["id"]
        self.lat = lat if lat is not None else self.cfg["node"]["lat"]
        self.lon = lon if lon is not None else self.cfg["node"]["lon"]
        self.zoo = ModelZoo()
        self.decision = DecisionEngine(self.cfg)
        self.transport = Transport(self.cfg, backend_url, self.node_id,
                                  queue_path or
                                  f"data/edge_queue_{self.node_id}.db",
                                  lat=self.lat, lon=self.lon)
        self.health = HealthMonitor()
        self.heartbeat_s = int(self.cfg["node"]["heartbeat_s"])
        self._last_hb = 0.0
        self._last_scores_push = 0.0
        self._scores_min_hz = 4.0
        self._running = True
        self.ring = np.zeros(16000, dtype=np.float32)  # last 1 s
        self._clips_dir = data_dir("clips")
        self._signal_handlers()

    def _signal_handlers(self):
        for sig in (signal.SIGINT, signal.SIGTERM):
            signal.signal(sig, self._stop)

    def _stop(self, *_):
        log.info("shutting down")
        self._running = False

    # ------------------------------------------------------------------
    def process_window(self, ts: str, wav: np.ndarray) -> dict | None:
        """One hop through all models + decision. Returns fired event or None."""
        result = self.zoo(wav)
        s = WindowScores(
            m2=result.m2, m3=result.m3, m4=result.m4, m7=result.m7,
            m6_z=result.m6_z,
            m5_rain=result.m5.get("rain", 0.0),
            m5_wind=result.m5.get("wind", 0.0),
            m5_thunder=result.m5.get("thunder", 0.0),
            m9=result.m9_probs, rms_db=result.rms_db,
        )
        decisions = self.decision.update(s)
        fired = None
        for d in decisions:
            if d.fired:
                fired = self._emit_event(ts, wav, d, result)
            elif d.suppressed_by:
                self._emit_suppressed(ts, d, result)
        # biodiversity (duty-cycled M8)
        if result.m8_species:
            self.transport._post("/ingest/biodiversity", {
                "node_id": self.node_id, "timestamp_utc": ts,
                "detections": result.m8_species})
        self._push_scores(ts, result)
        return fired

    def _push_scores(self, ts: str, result) -> None:
        """Best-effort live telemetry for the dashboard (WS relay, no queue)."""
        now = time.time()
        if now - self._last_scores_push < 1.0 / self._scores_min_hz:
            return
        self._last_scores_push = now
        spect: list[int] = []
        if result.logmel is not None:
            col = np.mean(result.logmel, axis=1)          # 64 mel bins
            lo, hi = float(np.min(col)), float(np.max(col))
            rng = max(hi - lo, 1e-6)
            spect = [int(round(255 * (v - lo) / rng)) for v in col]
        m9 = {k: round(v, 3) for k, v in result.m9_probs.items()}
        self.transport._post("/ingest/scores", {
            "node_id": self.node_id, "ts": ts,
            "scores": {
                "m2": round(result.m2, 3), "m3": round(result.m3, 3),
                "m4": round(result.m4, 3), "m7": round(result.m7, 3),
                "m6_z": round(result.m6_z, 2),
                "m5_rain": round(result.m5.get("rain", 0.0), 3),
                "m5_wind": round(result.m5.get("wind", 0.0), 3),
                "m5_thunder": round(result.m5.get("thunder", 0.0), 3),
                "m9": m9, "rms_db": round(result.rms_db, 1),
            },
            "spect": spect, "rms_db": round(result.rms_db, 1),
            "inference_ms": {k: round(v, 1)
                             for k, v in result.inference_ms.items()},
        }, timeout=2.0)

    def _emit_event(self, ts, wav, d, result) -> dict:
        payload = {
            "node_id": self.node_id,
            "timestamp_utc": ts,
            "lat": self.lat, "lon": self.lon,
            "class": d.cls, "severity": d.severity,
            "confidence": round(float(d.conf), 3),
            "per_model_scores": {
                "m1_selected": {k: round(v, 3) for k, v in result.m1_scores.items()},
                "m2_gunshot": round(result.m2, 3),
                "m3_chainsaw": round(result.m3, 3),
                "m4_vehicle": round(result.m4, 3),
                "m5_weather": {k: round(v, 3) for k, v in result.m5.items()},
                "m6_anomaly_z": round(result.m6_z, 2),
                "m7_human": round(result.m7, 3),
                "m9_fusion": {k: round(v, 3) for k, v in result.m9_probs.items()},
            },
            "rms_db": round(result.rms_db, 1),
            "persistence": d.k_of_n,
            "escalated": d.escalated,
            "battery": None, "cpu": None, "inference_ms": {
                k: round(v, 1) for k, v in result.inference_ms.items()},
            "suppressed_by": None,
            "clip_path": None,
        }
        h = self.health.sample()
        payload["cpu"] = h["cpu_pct"]
        payload["battery"] = h["battery"]["percent"] if h["battery"] else None
        if self.cfg["alerts"]["clip_enable"]:
            payload["clip_path"] = save_alert_clip(
                self.ring, self._clips_dir, self.node_id, d.cls, ts)
        # compact payload for very-low-bandwidth mode (documented byte size)
        payload["compact_bytes"] = len(compact_payload(payload).encode())
        ok = self.transport.send_event(payload)
        log.info("EVENT %s/%s conf=%.2f %s%s%s",
                 d.cls, d.severity, d.conf, d.k_of_n,
                 " [ESCALATED]" if d.escalated else "",
                 "" if ok else " [QUEUED OFFLINE]")
        return payload

    def _emit_suppressed(self, ts, d, result):
        payload = {
            "node_id": self.node_id, "timestamp_utc": ts,
            "class": d.cls, "confidence": round(float(d.conf), 3),
            "suppressed_by": d.suppressed_by,
            "rms_db": round(result.rms_db, 1),
        }
        self.transport._post("/ingest/suppressed", payload)
        log.info("SUPPRESSED %s conf=%.2f by %s", d.cls, d.conf, d.suppressed_by)

    # ------------------------------------------------------------------
    def run(self, max_windows: int | None = None):
        log.info("edge agent %s starting (source=%s)",
                 self.node_id, type(self.source).__name__)
        n = 0
        while self._running:
            for ts, wav, silent, clipping in self.source.windows():
                if not self._running:
                    break
                self.ring = np.roll(self.ring, -8000)
                self.ring[8000:] = wav[:8000]
                if clipping:
                    log.warning("input clipping detected")
                if silent:
                    # keep the pipeline warm but skip heavy models on silence
                    n += 1
                    continue
                try:
                    self.process_window(ts, wav)
                except Exception as e:  # noqa: BLE001
                    log.exception("pipeline error: %s", e)
                n += 1
                # heartbeat
                if time.time() - self._last_hb > self.heartbeat_s:
                    h = self.health.sample()
                    lat_ms = self.zoo and getattr(self, "_last_inference_ms", None)
                    self.transport.send_heartbeat(h)
                    self._last_hb = time.time()
                if getattr(self.source, "done", False):
                    log.info("source finished")
                    break
                if max_windows and n >= max_windows:
                    log.info("reached max_windows=%d", max_windows)
                    self._running = False
                    break
        # final flush
        self.transport.flush()
        log.info("agent stopped after %d windows; sent=%d queued=%d queue_len=%d",
                 n, self.transport.sent, self.transport.queued,
                 self.transport.queue_len())


def main() -> int:
    setup_logging()
    cfg = load_config()
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["mic", "file", "sim"], default="mic")
    ap.add_argument("--path", default=None, help="file/dir for --mode file")
    ap.add_argument("--loop", action="store_true")
    ap.add_argument("--no-loop", dest="loop", action="store_false")
    ap.set_defaults(loop=True)
    ap.add_argument("--node-id", default=cfg["node"]["id"])
    ap.add_argument("--lat", type=float, default=cfg["node"]["lat"])
    ap.add_argument("--lon", type=float, default=cfg["node"]["lon"])
    ap.add_argument("--backend", default=cfg["node"]["backend_url"])
    ap.add_argument("--max-windows", type=int, default=None)
    ap.add_argument("--pace", choices=["realtime", "fast"], default="realtime")
    args = ap.parse_args()

    if args.mode == "mic":
        try:
            source = MicSource()
        except Exception as e:  # noqa: BLE001
            log.error("No microphone available: %s — use --mode file instead", e)
            return 2
    elif args.mode == "file":
        p = Path(args.path)
        files = sorted(p.glob("*.wav")) if p.is_dir() else [p]
        source = FileSource(files[0], loop=args.loop,
                            pace_realtime=(args.pace == "realtime"))
    else:
        source = SimSource(pace_realtime=(args.pace == "realtime"))

    agent = EdgeAgent(source, node_id=args.node_id, backend_url=args.backend,
                      lat=args.lat, lon=args.lon)
    agent.run(max_windows=args.max_windows)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
