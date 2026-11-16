"""Telegram bot wrapper (long-polling) for acknowledged commands.

Optional component: lets a ranger ACK an alert or query status from Telegram.
Credentials from env: TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID.

The Bot API base URL is injectable (constructor arg, else TELEGRAM_API_BASE,
else https://api.telegram.org) so the integration tests can point the client
at a local fake HTTP server instead of the real Telegram service.
"""
from __future__ import annotations

import logging
import os

import httpx

log = logging.getLogger("forestear.telegram_bot")

DEFAULT_API_BASE = "https://api.telegram.org"


class TelegramBot:
    """Minimal long-polling bot: /status, /ack <id>, /events."""

    def __init__(self, token: str | None = None,
                 backend_url: str = "http://127.0.0.1:8000",
                 api_base: str | None = None):
        self.token = token or os.environ.get("TELEGRAM_BOT_TOKEN", "")
        self.backend = backend_url.rstrip("/")
        self.api_base = (api_base or os.environ.get("TELEGRAM_API_BASE")
                         or DEFAULT_API_BASE).rstrip("/")
        self.offset = 0

    def _url(self, method: str) -> str:
        return f"{self.api_base}/bot{self.token}/{method}"

    def poll_once(self) -> list[dict]:
        if not self.token:
            return []
        try:
            with httpx.Client(timeout=15) as c:
                r = c.get(self._url("getUpdates"),
                          params={"timeout": 10, "offset": self.offset})
                data = r.json()
            ups = data.get("result", [])
            if ups:
                self.offset = ups[-1]["update_id"] + 1
            return ups
        except Exception as e:  # noqa: BLE001
            log.debug("poll failed: %s", e)
            return []

    def send(self, chat_id: str, text: str) -> None:
        with httpx.Client(timeout=10) as c:
            c.post(self._url("sendMessage"),
                   json={"chat_id": chat_id, "text": text})

    def handle_update(self, up: dict) -> None:
        msg = up.get("message") or up.get("edited_message") or {}
        text = (msg.get("text") or "").strip()
        chat = msg.get("chat", {}).get("id")
        if not text or not chat:
            return
        try:
            with httpx.Client(timeout=5) as c:
                if text.startswith("/status"):
                    nodes = c.get(f"{self.backend}/nodes").json()
                    lines = [f"Node {n['node_id']}: {n['status']} "
                             f"(last seen {n['last_seen'][-8:]}, batt {n.get('battery')})"
                             for n in nodes]
                    self.send(chat, "\n".join(lines) or "no nodes")
                elif text.startswith("/ack"):
                    eid = text.split()[-1]
                    c.post(f"{self.backend}/events/{eid}/ack")
                    self.send(chat, f"Event {eid} acknowledged.")
                elif text.startswith("/events"):
                    ev = c.get(f"{self.backend}/events", params={"limit": 5}).json()
                    lines = [f"[{e['severity']}] {e['class']} @ {e['node_id']} "
                             f"conf={e['confidence']}" for e in ev]
                    self.send(chat, "\n".join(lines) or "no events")
                else:
                    self.send(chat, "Commands: /status /ack <id> /events")
        except Exception as e:  # noqa: BLE001
            log.warning("command failed: %s", e)

    def run_forever(self):
        log.info("telegram bot polling")
        while True:
            for up in self.poll_once():
                self.handle_update(up)
