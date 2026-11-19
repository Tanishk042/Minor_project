"""Transport: HTTP POST with retry + exponential backoff, SQLite offline
queue flushed on reconnect, heartbeat sender, compact SMS-sized payload.

Payload contract (Section 5.4):
  node_id, timestamp_utc, lat, lon, class, severity, confidence,
  per_model_scores, rms_db, suppressed_by?, clip_path?, battery, cpu,
  inference_ms
"""
from __future__ import annotations

import json
import logging
import sqlite3
import time
from pathlib import Path

import numpy as np

from common.config import get_node_token, load_config
from ml.audio_io import save_wav

log = logging.getLogger("forestear.transport")


class Transport:
    def __init__(self, cfg: dict | None = None, backend_url: str | None = None,
                 node_id: str | None = None, queue_path: str | None = None,
                 lat: float | None = None, lon: float | None = None):
        self.cfg = cfg or load_config()
        self.url = (backend_url or self.cfg["node"]["backend_url"]).rstrip("/")
        self.node_id = node_id or self.cfg["node"]["id"]
        self.lat = lat if lat is not None else self.cfg["node"]["lat"]
        self.lon = lon if lon is not None else self.cfg["node"]["lon"]
        self.token = get_node_token(self.cfg)
        qp = queue_path or self.cfg["node"]["queue_path"]
        Path(qp).parent.mkdir(parents=True, exist_ok=True)
        self.queue_path = str(qp)
        # The agent may create the Transport on the main thread and use it from
        # a worker thread (simulator/run_nodes.py); SQLite defaults to
        # same-thread-only connections, so opt out and serialise writes.
        import threading
        self._db_lock = threading.Lock()
        self._db = sqlite3.connect(self.queue_path, check_same_thread=False)
        self._db.execute(
            "CREATE TABLE IF NOT EXISTS offline_queue "
            "(id INTEGER PRIMARY KEY, ts REAL, payload TEXT, tries INT DEFAULT 0)")
        self._db.commit()
        self.online = True
        self._backoff = 1.0
        self.sent = 0
        self.queued = 0

    # ---------------------------------------------------------------- HTTP
    def _post(self, path: str, payload: dict, timeout: float = 4.0) -> bool:
        import httpx
        headers = {"X-ForestEar-Token": self.token}
        try:
            with httpx.Client(timeout=timeout) as c:
                r = c.post(f"{self.url}{path}", json=payload, headers=headers)
            if r.status_code in (200, 201, 202):
                self.online = True
                self._backoff = 1.0
                return True
            log.warning("POST %s -> %s", path, r.status_code)
            return False
        except Exception as e:  # noqa: BLE001
            self.online = False
            log.debug("POST %s failed: %s", path, e)
            return False

    def send_event(self, payload: dict) -> bool:
        ok = self._post("/ingest/event", payload)
        if ok:
            self.sent += 1
            self.flush()
            return True
        self.queue(payload)
        return False

    def send_heartbeat(self, health: dict, scores: dict | None = None) -> bool:
        hb = {"node_id": self.node_id, "health": health,
              "scores": scores or {}, "online": self.online,
              "lat": self.lat, "lon": self.lon}
        return self._post("/ingest/heartbeat", hb)

    # ------------------------------------------------------------- queue
    def queue(self, payload: dict):
        with self._db_lock:
            self._db.execute("INSERT INTO offline_queue (ts, payload) VALUES (?, ?)",
                             (time.time(), json.dumps(payload)))
            self._db.commit()
        self.queued += 1

    def queue_len(self) -> int:
        with self._db_lock:
            return int(self._db.execute("SELECT COUNT(*) FROM offline_queue"
                                        ).fetchone()[0])

    def flush(self, max_items: int = 200) -> int:
        with self._db_lock:
            rows = self._db.execute(
                "SELECT id, tries, payload FROM offline_queue "
                "ORDER BY id LIMIT ?", (max_items,)).fetchall()
        sent = 0
        for rid, tries, payload in rows:
            if tries > 30:
                with self._db_lock:
                    self._db.execute("DELETE FROM offline_queue WHERE id=?", (rid,))
                    self._db.commit()
                continue
            ok = self._post("/ingest/event", json.loads(payload))
            if ok:
                with self._db_lock:
                    self._db.execute("DELETE FROM offline_queue WHERE id=?", (rid,))
                    self._db.commit()
                sent += 1
            else:
                break  # still offline; backoff handled by caller loop
        if sent:
            log.info("offline queue flushed %d events (%d remain)", sent,
                     self.queue_len())
        return sent


def compact_payload(payload: dict) -> str:
    """SMS-sized compact payload for very low bandwidth links.

    Format: FE|node|cls|sev|conf|lat|lon|utc  (~60 bytes vs ~900 for full JSON).
    """
    p = payload
    return (f"FE|{p['node_id']}|{p['class']}|{p['severity']}|"
            f"{p['confidence']:.2f}|{p['lat']:.4f}|{p['lon']:.4f}|"
            f"{p['timestamp_utc'][:19]}")


def save_alert_clip(wav: np.ndarray, clip_dir: str | Path, node_id: str,
                    cls: str, ts: str) -> str | None:
    """Persist a 5 s alert clip for non-speech classes ONLY (privacy rule).

    Returns the path or None for human/speech-related classes.
    """
    if cls in ("human",):
        return None
    d = Path(clip_dir)
    d.mkdir(parents=True, exist_ok=True)
    fname = f"{node_id}_{cls}_{ts.replace(':', '').replace('-', '')}.wav"
    save_wav(d / fname, wav)
    return str(d / fname)
