"""Transport tests: offline queue flush, compact payload, privacy clip rule."""
from __future__ import annotations

from pathlib import Path

import numpy as np
import pytest

from edge_agent.transport import Transport, compact_payload, save_alert_clip


@pytest.fixture()
def transport(tmp_path, monkeypatch) -> Transport:
    monkeypatch.delenv("FORESTEAR_TOKEN", raising=False)
    t = Transport(backend_url="http://127.0.0.1:9", node_id="node-t",
                  queue_path=str(tmp_path / "queue.db"), lat=1.0, lon=2.0)
    return t


class TestOfflineQueue:
    def test_failed_send_is_queued(self, transport, monkeypatch, event_payload):
        monkeypatch.setattr(transport, "_post", lambda *a, **k: False)
        assert transport.send_event(event_payload) is False
        assert transport.queue_len() == 1
        assert transport.queued == 1

    def test_queue_survives_restart(self, transport, monkeypatch, event_payload):
        monkeypatch.setattr(transport, "_post", lambda *a, **k: False)
        transport.send_event(event_payload)
        transport.send_event(dict(event_payload, timestamp_utc="2026-01-02T00:00:00Z"))
        # a NEW transport instance over the same queue file sees both rows
        t2 = Transport(node_id="node-t",
                       queue_path=transport.queue_path, lat=1.0, lon=2.0)
        assert t2.queue_len() == 2

    def test_flush_drains_when_back_online(self, transport, monkeypatch,
                                           event_payload):
        monkeypatch.setattr(transport, "_post", lambda *a, **k: False)
        transport.send_event(event_payload)
        transport.send_event(dict(event_payload, timestamp_utc="2026-01-02T00:00:00Z"))
        assert transport.queue_len() == 2
        monkeypatch.setattr(transport, "_post", lambda *a, **k: True)
        assert transport.flush() == 2
        assert transport.queue_len() == 0

    def test_successful_send_tries_flush(self, transport, monkeypatch,
                                         event_payload):
        sent = []
        monkeypatch.setattr(transport, "_post",
                            lambda p, j, timeout=4.0: sent.append(p) or True)
        assert transport.send_event(event_payload) is True
        assert transport.sent == 1
        assert sent == ["/ingest/event"]

    def test_heartbeat_payload_shape(self, transport, monkeypatch):
        posts = []

        def fake_post(path, payload, timeout=4.0):
            posts.append((path, payload))
            return True

        monkeypatch.setattr(transport, "_post", fake_post)
        transport.send_heartbeat({"cpu_pct": 10.0}, scores={"m9": {}})
        path, hb = posts[0]
        assert path == "/ingest/heartbeat"
        assert hb["node_id"] == "node-t"
        assert hb["lat"] == 1.0 and hb["lon"] == 2.0
        assert hb["health"] == {"cpu_pct": 10.0}


class TestCompactPayload:
    def test_format_and_size(self, event_payload):
        s = compact_payload(event_payload)
        parts = s.split("|")
        assert parts[0] == "FE" and parts[1] == "node-test"
        assert parts[2] == "chainsaw" and parts[3] == "WARNING"
        assert parts[4] == "0.82"
        assert len(s) < 120, "compact payload must stay SMS-sized"


class TestPrivacyClips:
    def test_human_never_recorded(self, tmp_path):
        wav = np.zeros(16000, dtype=np.float32)
        assert save_alert_clip(wav, tmp_path, "node-1", "human",
                               "2026-01-01T00:00:00") is None
        assert list(Path(tmp_path).glob("*.wav")) == []

    def test_other_classes_saved(self, tmp_path):
        wav = np.zeros(16000, dtype=np.float32)
        p = save_alert_clip(wav, tmp_path, "node-1", "chainsaw",
                             "2026-01-01T00:00:00")
        assert p is not None and Path(p).exists()
        assert "human" not in Path(p).name

    def test_clip_length_is_five_seconds(self, tmp_path):
        from ml.audio_io import load_audio
        wav = np.zeros(16000 * 5, dtype=np.float32)
        p = save_alert_clip(wav, tmp_path, "node-1", "gunshot",
                             "2026-01-01T00:00:00")
        assert len(load_audio(p)) == 16000 * 5
