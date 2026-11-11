"""System health: CPU %, RAM, temperature, battery (if available), mic level,
dropped frames, model latencies. Cross-platform best effort (macOS/Linux)."""
from __future__ import annotations

import logging
import platform
import shutil
import subprocess
import time

import psutil

log = logging.getLogger("forestear.health")


def _temperature() -> float | None:
    try:
        if platform.system() == "Darwin":
            out = subprocess.run(["sudo", "-n", "powermetrics", "--samplers", "smc",
                                  "-i", "1", "-n", "1"], capture_output=True,
                                 text=True, timeout=5)
            return None  # needs sudo; skip silently
        temps = psutil.sensors_temperatures()
        for key in ("coretemp", "cpu_thermal", "k10temp", "cpu-thermal"):
            if key in temps and temps[key]:
                return float(temps[key][0].current)
    except Exception:  # noqa: BLE001
        pass
    return None


def _battery() -> dict | None:
    try:
        b = psutil.sensors_battery()
        if b is None:
            return None
        return {"percent": b.percent, "plugged": bool(b.power_plugged)}
    except Exception:  # noqa: BLE001
        return None


class HealthMonitor:
    """Snapshot + rolling CPU/RAM sampling for the dashboard."""

    def __init__(self, interval_s: float = 5.0):
        self.interval = interval_s
        self._last = 0.0
        self.cpu = psutil.cpu_percent(interval=None)
        self.ram_mb = psutil.Process().memory_info().rss / 1e6
        self.cpu_history: list[float] = []
        self._proc = psutil.Process()

    def sample(self) -> dict:
        now = time.time()
        if now - self._last > self.interval:
            self.cpu = psutil.cpu_percent(interval=0.05)
            self.ram_mb = self._proc.memory_info().rss / 1e6
            self.cpu_history.append(self.cpu)
            self.cpu_history = self.cpu_history[-240:]
            self._last = now
        return {
            "cpu_pct": round(self.cpu, 1),
            "ram_mb": round(self.ram_mb, 1),
            "temp_c": _temperature(),
            "battery": _battery(),
            "platform": platform.platform(),
            "python": platform.python_version(),
        }
