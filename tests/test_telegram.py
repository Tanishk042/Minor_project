"""Telegram delivery integration tests against a local fake HTTP server.

No network access and no real bot token: a threaded http.server stands in for
api.telegram.org, records every request, and returns a canned Bot API reply.
This exercises the real httpx code path in AlertRouter and TelegramBot.
"""
from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from backend.alerts import AlertRouter
from backend.telegram_bot import TelegramBot


class _FakeTelegram:
    def __init__(self):
        self.requests: list[dict] = []
        self.reply_status = 200
        self.reply_body: dict = {"ok": True, "result": {"message_id": 1}}
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):  # noqa: N802
                length = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(length).decode("utf-8") if length else ""
                try:
                    body = json.loads(raw) if raw else {}
                except json.JSONDecodeError:
                    body = {"_raw": raw}
                fake.requests.append({"method": "POST", "path": self.path,
                                      "body": body})
                payload = json.dumps(fake.reply_body).encode()
                self.send_response(fake.reply_status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def do_GET(self):  # noqa: N802
                fake.requests.append({"method": "GET", "path": self.path,
                                      "body": {}})
                payload = json.dumps({"ok": True, "result": []}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *args):  # silence test output
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever,
                                       daemon=True)
        self.thread.start()

    @property
    def base_url(self) -> str:
        host, port = self.server.server_address[:2]
        return f"http://{host}:{port}"

    def close(self):
        self.server.shutdown()
        self.server.server_close()


@pytest.fixture()
def fake_telegram():
    f = _FakeTelegram()
    try:
        yield f
    finally:
        f.close()


def _cfg(base_url: str, *, enable=True, dry_run=False) -> dict:
    return {
        "alerts": {
            "telegram": {"enable": enable, "dry_run": dry_run,
                         "api_base": base_url},
            "dedup_window_s": 45,
            "clip_enable": False,
        }
    }


def _event(**over) -> dict:
    e = {
        "node_id": "node-test",
        "timestamp_utc": "2026-01-01T00:00:00Z",
        "lat": 12.9716,
        "lon": 77.5946,
        "class": "chainsaw",
        "severity": "WARNING",
        "confidence": 0.91,
        "persistence": "3/5",
        "suppressed_by": None,
    }
    e.update(over)
    return e


class TestAlertRouterDelivery:
    def test_sends_expected_bot_api_request(self, fake_telegram, monkeypatch):
        monkeypatch.setenv("TELEGRAM_BOT_TOKEN", "123:TESTTOKEN")
        monkeypatch.setenv("TELEGRAM_CHAT_ID", "42")
        router = AlertRouter(cfg=_cfg(fake_telegram.base_url))

        assert router.enabled is True
        assert router.send(_event()) is True

        posts = [r for r in fake_telegram.requests if r["method"] == "POST"]
        assert len(posts) == 1
        assert posts[0]["path"] == "/bot123:TESTTOKEN/sendMessage"
        body = posts[0]["body"]
        assert body["chat_id"] == "42"
        assert "chainsaw" in body["text"].lower()
        assert "12.97160,77.59460" in body["text"]
        assert body["parse_mode"] == "HTML"

    def test_dedup_suppresses_second_alert(self, fake_telegram, monkeypatch):
        monkeypatch.setenv("TELEGRAM_BOT_TOKEN", "t")
        monkeypatch.setenv("TELEGRAM_CHAT_ID", "c")
        router = AlertRouter(cfg=_cfg(fake_telegram.base_url))

        assert router.send(_event()) is True
        assert router.send(_event()) is False
        assert len([r for r in fake_telegram.requests
                    if r["method"] == "POST"]) == 1

    def test_different_classes_both_send(self, fake_telegram, monkeypatch):
        monkeypatch.setenv("TELEGRAM_BOT_TOKEN", "t")
        monkeypatch.setenv("TELEGRAM_CHAT_ID", "c")
        router = AlertRouter(cfg=_cfg(fake_telegram.base_url))

        assert router.send(_event(**{"class": "chainsaw"})) is True
        assert router.send(_event(**{"class": "gunshot"})) is True
        assert len([r for r in fake_telegram.requests
                    if r["method"] == "POST"]) == 2

    def test_non_200_reports_failure(self, fake_telegram, monkeypatch):
        monkeypatch.setenv("TELEGRAM_BOT_TOKEN", "t")
        monkeypatch.setenv("TELEGRAM_CHAT_ID", "c")
        fake_telegram.reply_status = 429
        fake_telegram.reply_body = {"ok": False, "description": "Too Many Requests"}
        router = AlertRouter(cfg=_cfg(fake_telegram.base_url))

        assert router.send(_event()) is False

    def test_dry_run_never_touches_network(self, fake_telegram, monkeypatch):
        monkeypatch.setenv("TELEGRAM_BOT_TOKEN", "t")
        monkeypatch.setenv("TELEGRAM_CHAT_ID", "c")
        router = AlertRouter(cfg=_cfg(fake_telegram.base_url, enable=True,
                                      dry_run=True))

        assert router.enabled is False
        assert router.send(_event()) is True
        assert fake_telegram.requests == []

    def test_missing_credentials_falls_back_to_dry_run(self, fake_telegram,
                                                       monkeypatch):
        monkeypatch.delenv("TELEGRAM_BOT_TOKEN", raising=False)
        monkeypatch.delenv("TELEGRAM_CHAT_ID", raising=False)
        router = AlertRouter(cfg=_cfg(fake_telegram.base_url))

        assert router.enabled is False
        assert router.send(_event()) is True
        assert fake_telegram.requests == []

    def test_env_api_base_used_when_config_omits_it(self, fake_telegram,
                                                    monkeypatch):
        monkeypatch.setenv("TELEGRAM_BOT_TOKEN", "t")
        monkeypatch.setenv("TELEGRAM_CHAT_ID", "c")
        monkeypatch.setenv("TELEGRAM_API_BASE", fake_telegram.base_url)
        cfg = _cfg(fake_telegram.base_url)
        del cfg["alerts"]["telegram"]["api_base"]

        router = AlertRouter(cfg=cfg)
        assert router.api_base == fake_telegram.base_url
        assert router.send(_event()) is True
        assert len(fake_telegram.requests) == 1


class TestTelegramBotPolling:
    def test_poll_reads_updates_and_advances_offset(self, fake_telegram):
        bot = TelegramBot(token="tok", api_base=fake_telegram.base_url)

        assert bot.poll_once() == []
        get = [r for r in fake_telegram.requests if r["method"] == "GET"][0]
        assert get["path"].startswith("/bottok/getUpdates")
        assert "offset=0" in get["path"]

    def test_no_token_makes_no_request(self, fake_telegram, monkeypatch):
        monkeypatch.delenv("TELEGRAM_BOT_TOKEN", raising=False)
        bot = TelegramBot(api_base=fake_telegram.base_url)

        assert bot.poll_once() == []
        assert fake_telegram.requests == []

    def test_send_uses_injected_base(self, fake_telegram):
        bot = TelegramBot(token="tok", api_base=fake_telegram.base_url)
        bot.send("77", "hello")

        posts = [r for r in fake_telegram.requests if r["method"] == "POST"]
        assert len(posts) == 1
        assert posts[0]["path"] == "/bottok/sendMessage"
        assert posts[0]["body"] == {"chat_id": "77", "text": "hello"}

    def test_default_base_is_real_telegram(self):
        bot = TelegramBot(token="tok")
        assert bot._url("sendMessage") == (
            "https://api.telegram.org/bottok/sendMessage")
