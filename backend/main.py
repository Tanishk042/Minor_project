"""FastAPI backend: ingest, query, WebSocket live stream, alert routing,
multi-node correlation, CSV export. Auth: X-ForestEar-Token shared secret."""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import io
import json
import logging
import os
import time
from collections import defaultdict
from pathlib import Path

import numpy as np
from fastapi import Depends, FastAPI, Header, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from backend.alerts import AlertRouter
from backend.correlator import correlate
from backend.models import (Base, BiodiversityDetection, Event, Heartbeat,
                            Node, SessionLocal, SourceEstimate, SuppressedEvent,
                            engine)
from common.config import REPO_ROOT, get_node_token, load_config
from common.logging_setup import setup_logging

log = logging.getLogger("forestear.backend")
cfg = load_config()
app = FastAPI(title="ForestEar", version="1.0")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"],
                   allow_headers=["*"])
router_alerts = AlertRouter(cfg)

# --- dashboard basemap settings ----------------------------------------
TILE_DEFAULTS = {
    "tile_url": "https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png",
    "tile_attribution": "&copy; OpenStreetMap &copy; CARTO",
    "tile_max_zoom": 19,
}


def _tile_raw(key: str, env: str):
    """Environment wins over configs/default.yaml -> dashboard.<key>.

    An empty environment variable counts as unset so docker-compose can pass
    `${FORESTEAR_TILE_URL:-}` without overriding the YAML with a blank value.
    """
    raw = os.environ.get(env)
    if raw is None or not raw.strip():
        raw = (cfg.get("dashboard") or {}).get(key)
    return raw


def _tile_url() -> str:
    raw = _tile_raw("tile_url", "FORESTEAR_TILE_URL")
    if raw is None:
        return TILE_DEFAULTS["tile_url"]
    value = str(raw).strip()
    if not value.startswith(("http://", "https://")):
        log.warning("dashboard tile_url is not http(s); using the default")
        return TILE_DEFAULTS["tile_url"]
    missing = [p for p in ("{z}", "{x}", "{y}") if p not in value]
    if missing:
        log.warning("dashboard tile_url is missing %s; using the default",
                    ", ".join(missing))
        return TILE_DEFAULTS["tile_url"]
    return value


def _tile_attribution() -> str:
    raw = _tile_raw("tile_attribution", "FORESTEAR_TILE_ATTRIBUTION")
    return TILE_DEFAULTS["tile_attribution"] if raw is None else str(raw)


def _tile_max_zoom() -> int:
    raw = _tile_raw("tile_max_zoom", "FORESTEAR_TILE_MAX_ZOOM")
    if raw is None:
        return int(TILE_DEFAULTS["tile_max_zoom"])
    try:
        return max(1, min(22, int(raw)))
    except (TypeError, ValueError):
        log.warning("dashboard tile_max_zoom %r is not an integer; using 19", raw)
        return int(TILE_DEFAULTS["tile_max_zoom"])

# --- simple rate limiting on ingest (per node) -------------------------
_rate: dict[str, list[float]] = defaultdict(list)
RATE_LIMIT_PER_MIN = 120


def check_token(x_forestear_token: str = Header(default="")) -> None:
    if x_forestear_token != get_node_token(cfg):
        raise HTTPException(401, "bad token")


def rate_limit(node_id: str) -> None:
    now = time.time()
    lst = [t for t in _rate[node_id] if now - t < 60]
    if len(lst) > RATE_LIMIT_PER_MIN:
        raise HTTPException(429, "rate limited")
    lst.append(now)
    _rate[node_id] = lst


# --- live WS fan-out ----------------------------------------------------
class LiveHub:
    def __init__(self):
        self.clients: list[WebSocket] = []

    async def connect(self, ws: WebSocket):
        await ws.accept()
        self.clients.append(ws)

    def disconnect(self, ws: WebSocket):
        if ws in self.clients:
            self.clients.remove(ws)

    async def broadcast(self, msg: dict):
        dead = []
        for ws in self.clients:
            try:
                await ws.send_json(msg)
            except Exception:  # noqa: BLE001
                dead.append(ws)
        for ws in dead:
            self.disconnect(ws)


hub = LiveHub()


def broadcast_sync(msg: dict):
    """Broadcast from sync context via the event loop (anyio worker thread)."""
    import asyncio
    try:
        loop = asyncio.get_event_loop()
        if loop.is_running():
            import starlette.concurrency
            starlette.concurrency.run_in_threadpool(_noop_broadcast, msg)
    except RuntimeError:
        pass


def _noop_broadcast(msg):
    # WS broadcast from sync endpoints is done through a background task queue
    _pending.append(msg)


_pending: list[dict] = []


# --- models --------------------------------------------------------------
class EventIn(BaseModel):
    node_id: str
    timestamp_utc: str
    lat: float
    lon: float
    class_: str = Field(alias="class")
    severity: str
    confidence: float
    per_model_scores: dict = {}
    rms_db: float = 0.0
    persistence: str = ""
    escalated: bool = False
    battery: float | None = None
    cpu: float | None = None
    inference_ms: dict | float = {}
    suppressed_by: str | None = None
    clip_path: str | None = None
    compact_bytes: int | None = None
    model_config = {"populate_by_name": True}


class HeartbeatIn(BaseModel):
    node_id: str
    health: dict = {}
    scores: dict = {}
    online: bool = True
    lat: float | None = None
    lon: float | None = None


class ScoresIn(BaseModel):
    """Live per-window telemetry (WS fan-out only, never persisted)."""
    node_id: str
    ts: str = ""
    scores: dict = {}
    spect: list = []          # 64 mel bins, uint8-quantized
    rms_db: float = 0.0
    inference_ms: dict | float = {}


class SuppressedIn(BaseModel):
    node_id: str
    timestamp_utc: str
    class_: str = Field(alias="class")
    confidence: float
    suppressed_by: str
    rms_db: float = 0.0
    model_config = {"populate_by_name": True}


class BiodivIn(BaseModel):
    node_id: str
    timestamp_utc: str
    detections: list[dict]


def parse_ts(s: str) -> dt.datetime:
    try:
        return dt.datetime.fromisoformat(s.replace("Z", "+00:00"))
    except Exception:  # noqa: BLE001
        return dt.datetime.utcnow()


# --- ingest ---------------------------------------------------------------
@app.post("/ingest/event", dependencies=[Depends(check_token)])
def ingest_event(e: EventIn):
    rate_limit(e.node_id)
    db = SessionLocal()
    try:
        ts = parse_ts(e.timestamp_utc)
        node = db.query(Node).filter_by(node_id=e.node_id).first()
        if not node:
            node = Node(node_id=e.node_id, lat=e.lat, lon=e.lon)
            db.add(node)
        node.last_seen = dt.datetime.utcnow()
        node.lat, node.lon = e.lat, e.lon
        ev = Event(node_id=e.node_id, ts=ts, lat=e.lat, lon=e.lon,
                   cls=e.class_, severity=e.severity, confidence=e.confidence,
                   persistence=e.persistence, escalated=e.escalated,
                   rms_db=e.rms_db,
                   per_model_scores=json.dumps(e.per_model_scores),
                   inference_ms=(e.inference_ms.get("total")
                                 if isinstance(e.inference_ms, dict) else e.inference_ms),
                   clip_path=e.clip_path, compact_bytes=e.compact_bytes)
        db.add(ev)
        db.commit()
        _pending.append({"type": "event", "id": ev.id, "node_id": e.node_id,
                         "ts": e.timestamp_utc, "class": e.class_,
                         "severity": e.severity, "confidence": e.confidence,
                         "lat": e.lat, "lon": e.lon, "rms_db": e.rms_db,
                         "persistence": e.persistence,
                         "per_model_scores": e.per_model_scores})
        # alert routing + correlation
        alert_event = {**e.model_dump(by_alias=True), "id": ev.id}
        router_alerts.send(alert_event)
        _maybe_correlate(db, e, ts)
        return {"ok": True, "id": ev.id}
    finally:
        db.close()


def _maybe_correlate(db, e: EventIn, ts):
    """Correlate recent same-class events across nodes (gunshot only)."""
    try:
        if e.class_ != "gunshot":
            return
        since = ts - dt.timedelta(seconds=6)
        evs = db.query(Event).filter(
            Event.cls == "gunshot", Event.ts >= since).all()
        data = [dict(node_id=v.node_id, lat=v.lat, lon=v.lon, ts=v.ts,
                     cls=v.cls, confidence=v.confidence, rms_db=v.rms_db)
                for v in evs]
        ests = correlate(data)
        for est in ests:
            if est["method"] == "tdoa":
                # The correlator is re-run every time another node reports the
                # same shot, so the same source can be estimated two or three
                # times (3-node subset, then 4-node). Keep one row per shot and
                # replace it when a later estimate uses more nodes.
                est_ts = est["ts"]
                if isinstance(est_ts, str):
                    est_ts = parse_ts(est_ts)
                near = (db.query(SourceEstimate)
                        .filter(SourceEstimate.cls == est["cls"],
                                SourceEstimate.method == "tdoa",
                                SourceEstimate.ts >= est_ts - dt.timedelta(seconds=6),
                                SourceEstimate.ts <= est_ts + dt.timedelta(seconds=6))
                        .all())
                n_new = len(est["nodes_used"])
                better = [p for p in near
                          if len((p.nodes_used or "").split(",")) < n_new]
                for p in better:
                    db.delete(p)
                if better or not near:
                    se = SourceEstimate(ts=est_ts, cls=est["cls"],
                                        method=est["method"],
                                        lat=est["lat"], lon=est["lon"],
                                        radius_m=est["radius_m"],
                                        nodes_used=",".join(est["nodes_used"]))
                    db.add(se)
                db.commit()
                _pending.append({"type": "source_estimate", **{
                    k: (v.isoformat() if isinstance(v, dt.datetime) else v)
                    for k, v in est.items()}})
                # enrich telegram alert with estimated position
                router_alerts.send({"node_id": e.node_id,
                                    "class": "gunshot", "severity": "CRITICAL",
                                    "confidence": e.confidence,
                                    "timestamp_utc": e.timestamp_utc,
                                    "lat": est["lat"], "lon": est["lon"],
                                    "estimated_position": est})
    except Exception as ex:  # noqa: BLE001
        log.warning("correlate failed: %s", ex)


@app.post("/ingest/suppressed", dependencies=[Depends(check_token)])
def ingest_suppressed(s: SuppressedIn):
    db = SessionLocal()
    try:
        db.add(SuppressedEvent(node_id=s.node_id, ts=parse_ts(s.timestamp_utc),
                               cls=s.class_, confidence=s.confidence,
                               reason=s.suppressed_by, rms_db=s.rms_db))
        db.commit()
        _pending.append({"type": "suppressed", **s.model_dump(by_alias=True)})
        return {"ok": True}
    finally:
        db.close()


@app.post("/ingest/biodiversity", dependencies=[Depends(check_token)])
def ingest_biodiv(b: BiodivIn):
    db = SessionLocal()
    try:
        for d in b.detections:
            db.add(BiodiversityDetection(node_id=b.node_id,
                                         ts=parse_ts(b.timestamp_utc),
                                         species=d.get("species", "?"),
                                         confidence=float(d.get("confidence", 0))))
        db.commit()
        _pending.append({"type": "biodiversity", "node_id": b.node_id,
                         "detections": b.detections})
        return {"ok": True}
    finally:
        db.close()


@app.post("/ingest/heartbeat", dependencies=[Depends(check_token)])
def ingest_hb(h: HeartbeatIn):
    rate_limit(h.node_id)
    db = SessionLocal()
    try:
        node = db.query(Node).filter_by(node_id=h.node_id).first()
        if not node:
            node = Node(node_id=h.node_id)
            db.add(node)
        node.last_seen = dt.datetime.utcnow()
        if h.lat is not None and h.lon is not None:
            node.lat, node.lon = h.lat, h.lon
        health = h.health or {}
        db.add(Heartbeat(node_id=h.node_id, cpu_pct=health.get("cpu_pct"),
                         ram_mb=health.get("ram_mb"), temp_c=health.get("temp_c"),
                         battery=(health.get("battery") or {}).get("percent")
                         if health.get("battery") else None,
                         online=h.online))
        db.commit()
        _pending.append({"type": "heartbeat", "node_id": h.node_id,
                         "health": health, "scores": h.scores,
                         "ts": dt.datetime.utcnow().isoformat()})
        return {"ok": True}
    finally:
        db.close()


@app.post("/ingest/scores", dependencies=[Depends(check_token)])
def ingest_scores(s: ScoresIn):
    """Live telemetry relay: broadcast to dashboard, never stored."""
    rate_limit(s.node_id)
    _pending.append({"type": "scores", "node_id": s.node_id, "ts": s.ts,
                     "scores": s.scores, "spect": s.spect, "rms_db": s.rms_db})
    if len(_pending) > 600:   # bound replay buffer under telemetry floods
        _pending[:] = [m for m in _pending if m.get("type") != "scores"][-300:]
    return {"ok": True}


# --- queries ----------------------------------------------------------------
@app.get("/nodes")
def nodes():
    db = SessionLocal()
    try:
        out = []
        now = dt.datetime.utcnow()
        for n in db.query(Node).all():
            hb = (db.query(Heartbeat).filter_by(node_id=n.node_id)
                  .order_by(Heartbeat.ts.desc()).first())
            last_seen = (hb.ts if hb else n.last_seen)
            age = (now - last_seen).total_seconds() if last_seen else 1e9
            status = "healthy" if age < 90 else ("degraded" if age < 300 else "offline")
            out.append({
                "node_id": n.node_id, "lat": n.lat, "lon": n.lon,
                "label": n.label, "status": status,
                "last_seen": last_seen.isoformat() if last_seen else None,
                "age_s": age,
                "cpu_pct": hb.cpu_pct if hb else None,
                "ram_mb": hb.ram_mb if hb else None,
                "battery": hb.battery if hb else None,
                "temp_c": hb.temp_c if hb else None,
            })
        return out
    finally:
        db.close()


@app.get("/events")
def events(since: str | None = None, cls: str | None = None,
           node: str | None = None, severity: str | None = None,
           limit: int = 100):
    db = SessionLocal()
    try:
        q = db.query(Event)
        if since:
            q = q.filter(Event.ts >= parse_ts(since))
        if cls:
            q = q.filter(Event.cls == cls)
        if node:
            q = q.filter(Event.node_id == node)
        if severity:
            q = q.filter(Event.severity == severity)
        q = q.order_by(Event.ts.desc()).limit(min(limit, 1000))
        out = []
        for e in q.all():
            out.append({
                "id": e.id, "node_id": e.node_id,
                "ts": e.ts.isoformat(), "lat": e.lat, "lon": e.lon,
                "class": e.cls, "severity": e.severity,
                "confidence": e.confidence, "persistence": e.persistence,
                "escalated": e.escalated, "rms_db": e.rms_db,
                "clip_path": e.clip_path,
                "per_model_scores": json.loads(e.per_model_scores or "{}"),
                "inference_ms": e.inference_ms, "acknowledged": e.acknowledged,
            })
        return out
    finally:
        db.close()


@app.get("/events/{eid}/ack")
@app.post("/events/{eid}/ack")
def ack_event(eid: int):
    db = SessionLocal()
    try:
        e = db.query(Event).filter_by(id=eid).first()
        if not e:
            raise HTTPException(404)
        e.acknowledged = True
        db.commit()
        return {"ok": True}
    finally:
        db.close()


@app.get("/biodiversity/summary")
def biodiversity_summary(hours: int = 24):
    db = SessionLocal()
    try:
        since = dt.datetime.utcnow() - dt.timedelta(hours=hours)
        rows = (db.query(BiodiversityDetection)
                .filter(BiodiversityDetection.ts >= since).all())
        per_species: dict[str, dict] = defaultdict(lambda: {"count": 0, "conf_sum": 0.0})
        per_hour: dict[int, int] = defaultdict(int)
        for r in rows:
            per_species[r.species]["count"] += 1
            per_species[r.species]["conf_sum"] += r.confidence
            per_hour[r.ts.hour] += 1
        top = sorted(per_species.items(), key=lambda kv: -kv[1]["count"])[:12]
        return {
            "total": len(rows),
            "top_species": [
                {"species": s, "count": v["count"],
                 "mean_conf": round(v["conf_sum"] / max(v["count"], 1), 3)}
                for s, v in top],
            "by_hour": {str(h): c for h, c in sorted(per_hour.items())},
        }
    finally:
        db.close()


@app.get("/suppressed")
def suppressed(limit: int = 100):
    db = SessionLocal()
    try:
        rows = db.query(SuppressedEvent).order_by(SuppressedEvent.ts.desc()
                                                 ).limit(limit).all()
        return [{"node_id": r.node_id, "ts": r.ts.isoformat(), "class": r.cls,
                 "confidence": r.confidence, "reason": r.reason}
                for r in rows]
    finally:
        db.close()


@app.get("/estimates")
def estimates(limit: int = 50):
    db = SessionLocal()
    try:
        rows = db.query(SourceEstimate).order_by(SourceEstimate.ts.desc()
                                                ).limit(limit).all()
        return [{"ts": r.ts.isoformat(), "class": r.cls, "method": r.method,
                 "lat": r.lat, "lon": r.lon, "radius_m": r.radius_m,
                 "nodes_used": r.nodes_used.split(","),
                 "ground_truth_error_m": r.ground_truth_error_m}
                for r in rows]
    finally:
        db.close()


@app.get("/dashboard-config")
def dashboard_config():
    """Basemap settings for the dashboard map.

    Public on purpose: the dashboard itself is unauthenticated and needs this
    before it can draw a map. Do not put a secret in a tile URL; a key placed
    there is visible to every dashboard visitor.
    """
    return {
        "tile_url": _tile_url(),
        "tile_attribution": _tile_attribution(),
        "tile_max_zoom": _tile_max_zoom(),
    }


@app.get("/health")
def health():
    db = SessionLocal()
    try:
        return {"status": "ok", "events": db.query(Event).count(),
                "nodes": db.query(Node).count(),
                "suppressed": db.query(SuppressedEvent).count(),
                "biodiversity": db.query(BiodiversityDetection).count(),
                "queue_pending": len(_pending)}
    finally:
        db.close()


@app.get("/analytics/summary")
def analytics_summary():
    db = SessionLocal()
    try:
        evs = db.query(Event).order_by(Event.ts.desc()).limit(2000).all()
        per_day: dict[str, dict[str, int]] = defaultdict(lambda: defaultdict(int))
        sev_count: dict[str, int] = defaultdict(int)
        cls_count: dict[str, int] = defaultdict(int)
        for e in evs:
            d = e.ts.date().isoformat()
            per_day[d][e.cls] += 1
            sev_count[e.severity] += 1
            cls_count[e.cls] += 1
        sup = db.query(SuppressedEvent).count()
        return {"events_per_day": {d: dict(v) for d, v in per_day.items()},
                "severity_counts": dict(sev_count),
                "class_counts": dict(cls_count),
                "suppressed_total": sup,
                "raised_total": len(evs)}
    finally:
        db.close()


@app.get("/export/events.csv")
def export_events_csv():
    db = SessionLocal()
    try:
        evs = db.query(Event).order_by(Event.ts).all()
        buf = io.StringIO()
        w = csv.writer(buf)
        w.writerow(["id", "ts_utc", "node_id", "lat", "lon", "class", "severity",
                    "confidence", "persistence", "rms_db", "acknowledged"])
        for e in evs:
            w.writerow([e.id, e.ts.isoformat(), e.node_id, e.lat, e.lon, e.cls,
                         e.severity, e.confidence, e.persistence, e.rms_db,
                         e.acknowledged])
        return StreamingResponse(iter([buf.getvalue()]), media_type="text/csv",
                                 headers={"Content-Disposition":
                                          "attachment; filename=forestear_events.csv"})
    finally:
        db.close()


@app.get("/clips/{name}")
def get_clip(name: str):
    p = REPO_ROOT / "data" / "clips" / Path(name).name
    if not p.exists():
        raise HTTPException(404)
    return FileResponse(p, media_type="audio/wav")


@app.get("/reports/{name}")
def get_report(name: str):
    """Serve evaluation JSON reports (reports/*.json) to the dashboard."""
    p = REPO_ROOT / "reports" / (Path(name).name)
    if not p.exists() or p.suffix != ".json":
        raise HTTPException(404)
    return JSONResponse(json.loads(p.read_text(encoding="utf-8")))


# --- websocket -----------------------------------------------------------
@app.websocket("/live")
async def live(ws: WebSocket):
    await hub.connect(ws)
    try:
        while True:
            # flush pending messages produced by sync endpoints
            while _pending:
                await hub.broadcast(_pending.pop(0))
            await ws.receive_text()   # keepalive; ignores client text
    except WebSocketDisconnect:
        hub.disconnect(ws)
    except Exception:  # noqa: BLE001
        hub.disconnect(ws)


@app.on_event("startup")
async def _start():
    setup_logging()
    Base.metadata.create_all(engine)
    # replay-pending flusher for broadcasts produced while no WS client existed
    import asyncio

    async def flusher():
        while True:
            await asyncio.sleep(0.25)
            # only drain when someone is listening; otherwise keep for replay
            if hub.clients and _pending:
                await hub.broadcast(_pending.pop(0))
    asyncio.create_task(flusher())


# serve dashboard (static) at /
class NoCacheStaticFiles(StaticFiles):
    """Dashboard files change between runs; make browsers revalidate them.

    StaticFiles already sends ETag/Last-Modified, but without Cache-Control a
    browser may reuse a stale copy heuristically. That is how an operator ends
    up looking at an old dashboard after an update.
    """

    async def get_response(self, path, scope):
        response = await super().get_response(path, scope)
        response.headers["Cache-Control"] = "no-cache, must-revalidate"
        return response


app.mount("/", NoCacheStaticFiles(directory=str(REPO_ROOT / "dashboard"),
                                  html=True),
          name="dashboard")


def main() -> int:
    setup_logging()
    import uvicorn
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8000)
    args = ap.parse_args()
    uvicorn.run(app, host=args.host, port=args.port, log_level="warning")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
