"""API contract tests: token auth, ingest endpoints (event / heartbeat /
scores / suppressed / biodiversity), queries, live WebSocket relay.

Runs against a TEMP database (never touches data/backend.db)."""
from __future__ import annotations

import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

import backend.models as bm
import backend.main as bmain
from backend.models import Base
from common.config import get_node_token

GOOD = {"X-ForestEar-Token": get_node_token()}
BAD = {"X-ForestEar-Token": "wrong-token"}


@pytest.fixture()
def client(tmp_path, monkeypatch):
    """TestClient with an isolated per-test SQLite DB."""
    engine = create_engine(f"sqlite:///{tmp_path / 'test.db'}",
                           connect_args={"check_same_thread": False})
    TestSession = sessionmaker(bind=engine)
    Base.metadata.create_all(engine)
    monkeypatch.setattr(bm, "engine", engine)
    monkeypatch.setattr(bm, "SessionLocal", TestSession)
    monkeypatch.setattr(bmain, "engine", engine)
    monkeypatch.setattr(bmain, "SessionLocal", TestSession)
    bmain._pending.clear()
    bmain._rate.clear()
    with TestClient(bmain.app) as c:      # runs startup handlers
        yield c
    bmain._pending.clear()


def _hb(node="node-a", **over):
    d = dict(node_id=node, health={"cpu_pct": 22.0, "ram_mb": 180.0,
                                   "battery": {"percent": 88.0}},
             online=True, lat=12.97, lon=77.59)
    d.update(over)
    return d


class TestAuth:
    def test_missing_token_401(self, client, event_payload):
        r = client.post("/ingest/event", json=event_payload)
        assert r.status_code == 401

    def test_wrong_token_401(self, client, event_payload):
        r = client.post("/ingest/event", json=event_payload, headers=BAD)
        assert r.status_code == 401

    def test_token_accepted(self, client, event_payload):
        r = client.post("/ingest/event", json=event_payload, headers=GOOD)
        assert r.status_code == 200 and r.json()["ok"] is True

    def test_queries_are_public(self, client):
        # dashboard reads are unauthenticated by design (read-only LAN)
        assert client.get("/events").status_code == 200
        assert client.get("/nodes").status_code == 200
        assert client.get("/health").status_code == 200


class TestIngest:
    def test_event_roundtrip(self, client, event_payload):
        r = client.post("/ingest/event", json=event_payload, headers=GOOD)
        eid = r.json()["id"]
        evs = client.get("/events").json()
        assert len(evs) == 1
        e = evs[0]
        assert e["id"] == eid and e["class"] == "chainsaw"
        assert e["severity"] == "WARNING"
        assert e["per_model_scores"]["m3_chainsaw"] == 0.9
        assert e["per_model_scores"]["m9_fusion"]["chainsaw"] == 0.82
        assert e["inference_ms"] == 96.0

    def test_event_creates_node(self, client, event_payload):
        client.post("/ingest/event", json=event_payload, headers=GOOD)
        nodes = client.get("/nodes").json()
        assert nodes[0]["node_id"] == "node-test"
        assert abs(nodes[0]["lat"] - 12.9716) < 1e-6

    def test_heartbeat_updates_node(self, client):
        client.post("/ingest/heartbeat", json=_hb(), headers=GOOD)
        nodes = client.get("/nodes").json()
        n = nodes[0]
        assert n["status"] == "healthy"      # last_seen just now
        assert n["cpu_pct"] == 22.0 and n["battery"] == 88.0

    def test_heartbeat_latlon_recorded(self, client):
        client.post("/ingest/heartbeat", json=_hb(lat=1.5, lon=2.5), headers=GOOD)
        assert client.get("/nodes").json()[0]["lat"] == 1.5

    def test_suppressed(self, client):
        p = {"node_id": "node-a", "timestamp_utc": "2026-01-01T00:00:00Z",
             "class": "gunshot", "confidence": 0.64,
             "suppressed_by": "weather_veto(M5 weather=0.91)", "rms_db": 48.0}
        assert client.post("/ingest/suppressed", json=p,
                           headers=GOOD).status_code == 200
        sup = client.get("/suppressed").json()
        assert len(sup) == 1 and sup[0]["reason"].startswith("weather_veto")

    def test_biodiversity(self, client):
        import datetime as dt
        now = (dt.datetime.now(dt.timezone.utc) -
               dt.timedelta(hours=1)).isoformat()
        p = {"node_id": "node-a",
             "timestamp_utc": now,
             "detections": [{"species": "Oriolus oriolus", "confidence": 0.91},
                            {"species": "Psittacula krameri", "confidence": 0.77}]}
        assert client.post("/ingest/biodiversity", json=p,
                           headers=GOOD).status_code == 200
        s = client.get("/biodiversity/summary").json()
        assert s["total"] == 2
        assert s["top_species"][0]["species"] == "Oriolus oriolus"

    def test_scores_relayed_not_stored(self, client):
        """Live telemetry: 200 OK, broadcast queued, but never persisted."""
        p = {"node_id": "node-a", "ts": "2026-01-01T00:00:00Z",
             "scores": {"m2": 0.11, "m9": {"chainsaw": 0.81}},
             "spect": [i * 4 for i in range(64)], "rms_db": 40.0}
        assert client.post("/ingest/scores", json=p,
                           headers=GOOD).status_code == 200
        assert any(m["type"] == "scores" for m in bmain._pending)
        h = client.get("/health").json()
        assert h["events"] == 0            # telemetry must not create events

    def test_bad_payload_422(self, client):
        r = client.post("/ingest/event", json={"node_id": "x"},
                        headers=GOOD)
        assert r.status_code == 422


class TestQueries:
    def test_filters(self, client, event_payload):
        for cls in ("chainsaw", "gunshot"):
            p = dict(event_payload, **{"class": cls})
            client.post("/ingest/event", json=p, headers=GOOD)
        assert len(client.get("/events", params={"cls": "gunshot"}).json()) == 1
        assert len(client.get("/events", params={"severity": "CRITICAL"}
                              ).json()) == 0

    def test_ack(self, client, event_payload):
        eid = client.post("/ingest/event", json=event_payload,
                          headers=GOOD).json()["id"]
        assert client.post(f"/events/{eid}/ack").status_code == 200
        assert client.get("/events").json()[0]["acknowledged"] is True
        assert client.post("/events/99999/ack").status_code == 404

    def test_export_csv(self, client, event_payload):
        client.post("/ingest/event", json=event_payload, headers=GOOD)
        r = client.get("/export/events.csv")
        assert r.status_code == 200 and r.headers["content-type"].startswith("text/csv")
        assert "chainsaw" in r.text

    def test_health(self, client, event_payload):
        client.post("/ingest/event", json=event_payload, headers=GOOD)
        h = client.get("/health").json()
        assert h["status"] == "ok" and h["events"] == 1 and h["nodes"] == 1

    def test_reports_endpoint(self, client, tmp_path, monkeypatch):
        # serve a JSON report from the real reports/ dir via monkeypatched path
        reports = tmp_path / "reports"
        reports.mkdir()
        (reports / "probe.json").write_text(json.dumps({"accuracy": 0.5}))
        monkeypatch.setattr(bmain, "REPO_ROOT", tmp_path)
        r = client.get("/reports/probe.json")
        assert r.status_code == 200 and r.json() == {"accuracy": 0.5}
        assert client.get("/reports/missing.json").status_code == 404

    def test_clip_404(self, client):
        assert client.get("/clips/nope.wav").status_code == 404


class TestGunshotCorrelation:
    def test_tdoa_estimate_stored(self, client):
        import datetime as dt
        import math
        from backend.correlator import latlon
        base = dt.datetime(2026, 1, 1, 12, 0, 0)
        pts = [(400, 0), (-400, 0), (0, 400)]
        for i, (x, y) in enumerate(pts):
            lat, lon = latlon(x, y, 12.9716, 77.5946)
            d = math.hypot(x - 150, y - 100)
            client.post("/ingest/event", json={
                "node_id": f"node-{i+1:02d}",
                "timestamp_utc": (base + dt.timedelta(seconds=d / 343.0)
                                 ).isoformat() + "Z",
                "lat": lat, "lon": lon, "class": "gunshot",
                "severity": "CRITICAL", "confidence": 0.9,
            }, headers=GOOD)
        ests = client.get("/estimates").json()
        assert ests and ests[0]["method"] == "tdoa"
        assert len(ests[0]["nodes_used"]) == 3


class TestWebSocket:
    def test_live_relay(self, client, event_payload):
        with client.websocket_connect("/live") as ws:
            ws.send_text("hello")            # keepalive ignored
            client.post("/ingest/event", json=event_payload, headers=GOOD)
            msg = None
            for _ in range(5):
                ws.send_text("tick")         # each receive_text drains _pending
                msg = ws.receive_json()
                if msg.get("type") == "event":
                    break
            assert msg and msg["type"] == "event"
            assert msg["class"] == "chainsaw"


class TestDashboard:
    """The dashboard map is a shipped feature, so its wiring is regression-tested.

    These checks assert structure and cache headers, not visual rendering; a
    headless browser was used separately to confirm tiles and markers appear.
    """

    def test_page_contains_map_panel(self, client):
        page = client.get("/")
        assert page.status_code == 200
        assert 'id="map"' in page.text
        assert "leaflet" in page.text.lower()

    def test_map_container_has_explicit_height(self, client):
        css = client.get("/style.css").text
        # Without a sized container Leaflet renders into a zero-height box.
        assert "#map {" in css
        assert "height: 100%" in css

    def test_assets_are_revalidated(self, client):
        for path in ("/style.css", "/app.js"):
            r = client.get(path)
            assert r.status_code == 200, path
            assert "no-cache" in r.headers.get("cache-control", ""), path

    def test_map_libs_are_vendored_not_cdn(self, client):
        """A blocked CDN must not be able to take the dashboard down.

        Leaflet/Chart.js are served from the app itself, and app.js guards the
        map/chart init so a missing library only degrades those panels.
        """
        page = client.get("/").text
        assert "vendor/leaflet/leaflet.js" in page
        assert "vendor/leaflet/leaflet.css" in page
        assert "vendor/chart.umd.min.js" in page
        assert "unpkg.com" not in page
        assert "cdn.jsdelivr.net" not in page

        for path in ("/vendor/leaflet/leaflet.js", "/vendor/leaflet/leaflet.css",
                     "/vendor/chart.umd.min.js"):
            assert client.get(path).status_code == 200, path

        js = client.get("/app.js").text
        assert "MAP_AVAILABLE" in js            # map init is guarded
        assert 'typeof Chart === "undefined"' in js  # chart init is guarded

    def test_dashboard_config_defaults(self, client):
        c = client.get("/dashboard-config").json()
        assert c["tile_url"].startswith("https://")
        for ph in ("{z}", "{x}", "{y}"):
            assert ph in c["tile_url"]
        assert "CARTO" in c["tile_attribution"]
        assert c["tile_max_zoom"] == 19

    def test_dashboard_config_reads_yaml(self, client, monkeypatch):
        monkeypatch.setitem(bmain.cfg, "dashboard", {
            "tile_url": "https://tiles.example.org/{z}/{x}/{y}.png",
            "tile_attribution": "&copy; Example",
            "tile_max_zoom": 17,
        })
        c = client.get("/dashboard-config").json()
        assert c == {"tile_url": "https://tiles.example.org/{z}/{x}/{y}.png",
                     "tile_attribution": "&copy; Example",
                     "tile_max_zoom": 17}

    def test_dashboard_config_env_wins(self, client, monkeypatch):
        monkeypatch.setitem(bmain.cfg, "dashboard", {
            "tile_url": "https://config.example/{z}/{x}/{y}.png"})
        monkeypatch.setenv("FORESTEAR_TILE_URL",
                           "https://env.example/{z}/{x}/{y}.png")
        assert client.get("/dashboard-config").json()["tile_url"] == \
            "https://env.example/{z}/{x}/{y}.png"

    def test_dashboard_config_rejects_bad_url(self, client, monkeypatch):
        monkeypatch.setitem(bmain.cfg, "dashboard",
                            {"tile_url": "javascript:alert(1)"})
        c = client.get("/dashboard-config").json()
        assert c["tile_url"].startswith("https://{s}.basemaps.cartocdn.com/")

    def test_dashboard_config_clamps_max_zoom(self, client, monkeypatch):
        monkeypatch.setitem(bmain.cfg, "dashboard", {"tile_max_zoom": 99})
        assert client.get("/dashboard-config").json()["tile_max_zoom"] == 22

    def test_dashboard_config_empty_env_falls_back_to_yaml(self, client, monkeypatch):
        monkeypatch.setitem(bmain.cfg, "dashboard", {
            "tile_url": "https://config.example/{z}/{x}/{y}.png"})
        monkeypatch.setenv("FORESTEAR_TILE_URL", "")   # compose passes :-}
        assert client.get("/dashboard-config").json()["tile_url"] == \
            "https://config.example/{z}/{x}/{y}.png"
