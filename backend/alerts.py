"""Alert router: deduplication across nodes + Telegram delivery.

Telegram credentials via env (never in repo): TELEGRAM_BOT_TOKEN,
TELEGRAM_CHAT_ID. dry_run mode logs the message to console (default true in
configs/default.yaml so the demo works without any secrets).
"""
from __future__ import annotations

import logging
import os
from pathlib import Path

import httpx

from common.config import load_config

log = logging.getLogger("forestear.alerts")


class AlertRouter:
    def __init__(self, cfg: dict | None = None):
        self.cfg = cfg or load_config()
        tg = self.cfg["alerts"]["telegram"]
        self.dry_run = bool(tg.get("dry_run", True))
        self.enabled = bool(tg.get("enable", False)) and not self.dry_run
        self.token = os.environ.get("TELEGRAM_BOT_TOKEN", "")
        self.chat_id = os.environ.get("TELEGRAM_CHAT_ID", "")
        if self.enabled and (not self.token or not self.chat_id):
            log.warning("Telegram enabled but TELEGRAM_BOT_TOKEN/CHAT_ID missing;"
                        " falling back to dry-run")
            self.enabled = False
        self.dedup_window = float(self.cfg["alerts"]["dedup_window_s"])
        self._recent: list[tuple[str, float]] = []

    def _dedup(self, cls: str, t: float) -> bool:
        """True if a similar alert was sent within the dedup window."""
        self._recent = [(c, tt) for c, tt in self._recent if t - tt < self.dedup_window]
        if any(c == cls for c, _ in self._recent):
            return True
        self._recent.append((cls, t))
        return False

    def format_message(self, event: dict) -> str:
        gmaps = f"https://maps.google.com/?q={event['lat']:.5f},{event['lon']:.5f}"
        sev = event.get("severity", "?")
        emoji = {"CRITICAL": "🚨", "WARNING": "⚠️", "INFO": "ℹ️"}.get(sev, "•")
        veto = event.get("suppressed_by") or "none"
        lines = [
            f"{emoji} ForestEar {sev} — {event['class'].upper()}",
            f"Confidence {event.get('confidence', 0):.2f} "
            f"| persisted {event.get('persistence', '-')} | veto: {veto}",
            f"Node {event['node_id']} @ {event['timestamp_utc']}",
            f"📍 {gmaps}",
        ]
        if event.get("estimated_position"):
            ep = event["estimated_position"]
            lines.append(f"🎯 Estimated source: {ep['lat']:.5f}, {ep['lon']:.5f} "
                         f"±{ep['radius_m']:.0f} m ({ep['method']}, "
                         f"{len(ep.get('nodes_used', []))} nodes)")
        return "\n".join(lines)

    def send(self, event: dict) -> bool:
        import time as _t
        if self._dedup(event["class"], _t.time()):
            log.debug("dedup: %s within %.0fs", event["class"], self.dedup_window)
            return False
        msg = self.format_message(event)
        if not self.enabled:
            log.info("[TELEGRAM DRY-RUN]\n%s", msg)
            return True
        try:
            with httpx.Client(timeout=10) as c:
                r = c.post(
                    f"https://api.telegram.org/bot{self.token}/sendMessage",
                    json={"chat_id": self.chat_id, "text": msg,
                          "parse_mode": "HTML"})
            ok = r.status_code == 200
            if not ok:
                log.warning("telegram %s: %s", r.status_code, r.text[:200])
            return ok
        except Exception as e:  # noqa: BLE001
            log.error("telegram send failed: %s", e)
            return False
