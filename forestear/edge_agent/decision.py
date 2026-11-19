"""Decision logic: k-of-n persistence, cooldown, severity, weather veto,
anomaly escalation. Pure functions + a stateful DecisionEngine (per node).

All thresholds come from configs/default.yaml (single source of truth).
"""
from __future__ import annotations

import logging
import time
from collections import defaultdict, deque
from dataclasses import dataclass, field

from common.config import load_config

log = logging.getLogger("forestear.decision")


@dataclass
class Decision:
    """Outcome of one window for one class."""
    cls: str
    severity: str | None
    confidence: float
    fired: bool = False
    suppressed_by: str | None = None     # e.g. "weather_veto"
    escalated: bool = False              # sustained chainsaw -> CRITICAL
    k_of_n: str = ""                     # e.g. "4/5"
    cooldown_active: bool = False


@dataclass
class WindowScores:
    """Model scores entering the decision engine (per hop)."""
    m2: float = 0.0
    m3: float = 0.0
    m4: float = 0.0
    m7: float = 0.0
    m6_z: float = 0.0
    m5_rain: float = 0.0
    m5_wind: float = 0.0
    m5_thunder: float = 0.0
    m9: dict[str, float] = field(default_factory=dict)
    rms_db: float = -100.0


class DecisionEngine:
    """Stateful per-node decision engine."""

    def __init__(self, cfg: dict | None = None):
        self.cfg = cfg or load_config()
        d = self.cfg["decision"]
        self.thresholds = d["thresholds"]
        self.persistence = d["persistence"]
        self.cooldown = d["cooldown_s"]
        self.severity_map = d["severity"]
        self.veto = d["weather_veto"]
        self.sustain = d["chainsaw_sustain"]
        # ring of recent detections per class: (timestamp, conf)
        self.detections: dict[str, deque] = defaultdict(
            lambda: deque(maxlen=64))
        self.last_fired: dict[str, float] = {}
        self.chainsaw_times: list[float] = []   # DETECTION times (not fires)
        self.chainsaw_escalated: bool = False   # CRITICAL already emitted
        self.suppressed_log: list[dict] = []

    # ------------------------------------------------------------------
    def update(self, s: WindowScores, t: float | None = None) -> list[Decision]:
        """Process one hop; returns decisions (fired or suppressed) for audit."""
        t = time.time() if t is None else t
        out: list[Decision] = []

        weather = max(s.m5_rain, s.m5_wind, s.m5_thunder)
        veto_active = (self.veto["enable"] and weather >= self.veto["weather_prob"])

        # per-class detection test
        classes = {
            "chainsaw": (s.m9.get("chainsaw", 0.0), s.m3),
            "gunshot": (s.m9.get("gunshot", 0.0), s.m2),
            "vehicle": (s.m9.get("vehicle", 0.0), s.m4),
            "human": (s.m9.get("human", 0.0), s.m7),
        }
        for cls, (m9p, model_p) in classes.items():
            thr = self.thresholds[cls]
            detected = (m9p >= thr) or (model_p >= thr + 0.15)
            conf = max(m9p, model_p)
            if detected:
                self.detections[cls].append((t, conf))
                if cls == "chainsaw":
                    # sustain counts DETECTIONS (every hop), not fires: the
                    # 60 s cooldown throttles fires to < 8 per 30 s, which
                    # would make escalation unreachable.
                    self.chainsaw_times.append(t)
                    self.chainsaw_times = [x for x in self.chainsaw_times
                                           if t - x <= self.sustain["n"]]
                    if len(self.chainsaw_times) < self.sustain["k"]:
                        self.chainsaw_escalated = False

        # anomaly escalation (unclassified loud/unusual)
        if s.m6_z >= self.thresholds["anomaly"] and s.rms_db > 35:
            self.detections["anomaly"].append((t, float(s.m6_z)))

        for cls in ("chainsaw", "gunshot", "vehicle", "human", "anomaly"):
            dec = self._decide_class(cls, t, veto_active, s)
            if dec is not None:
                out.append(dec)
        return out

    def _decide_class(self, cls: str, t: float, veto_active: bool,
                      s: WindowScores) -> Decision | None:
        ring = self.detections[cls]
        p = self.persistence.get(cls)
        if p is None or not ring:
            return None
        k, n = int(p["k"]), int(p["n"])
        recent = [c for (tt, c) in ring if t - tt <= n * 0.5 + 0.1]
        conf = recent[-1] if recent else ring[-1][1]

        # cooldown — bypassed exactly once when sustained chainsaw first
        # reaches CRITICAL (severity upgrade is worth alerting even in cooldown)
        sustained_now = (cls == "chainsaw"
                         and len(self.chainsaw_times) >= self.sustain["k"])
        if (cls in self.last_fired and t - self.last_fired[cls] < self.cooldown[cls]
                and not (sustained_now and not self.chainsaw_escalated)):
            # still in cooldown: don't fire again, but log if persistent
            return None

        fired = False
        suppressed_by = None
        n_hit = len(recent)

        if cls == "gunshot":
            high = float(p.get("high_conf", 0.85))
            if conf >= high and n_hit >= 1:
                fired = True
            elif n_hit >= 2 and conf >= self.thresholds["gunshot"] * 0.7:
                fired = True
        else:
            fired = n_hit >= k

        # weather veto: only suppresses gunshot below the stricter threshold
        if fired and cls == "gunshot" and veto_active:
            if conf < self.veto["gunshot_conf"]:
                d = Decision(cls, None, conf, fired=False,
                             suppressed_by=f"weather_veto(M5 weather={max(s.m5_rain, s.m5_wind, s.m5_thunder):.2f})")
                self.suppressed_log.append(dict(t=t, cls=cls, conf=conf,
                                                reason=d.suppressed_by))
                return d

        if not fired:
            return None

        # severity
        sev = self.severity_map.get(cls, "INFO")
        escalated = False
        if cls == "chainsaw":
            if len(self.chainsaw_times) >= self.sustain["k"]:
                sev = "CRITICAL"
                escalated = True
                self.chainsaw_escalated = True

        self.last_fired[cls] = t
        return Decision(cls, sev, conf, fired=True, escalated=escalated,
                        k_of_n=f"{n_hit}/{n if cls != 'gunshot' else 3}")

    def suppressed_events(self) -> list[dict]:
        return list(self.suppressed_log)


def k_of_n_met(ring: deque, k: int, n: int, window_s: float, t: float) -> bool:
    """Pure helper (unit-tested): k detections within n*window_s seconds."""
    cnt = sum(1 for (tt, _) in ring if t - tt <= n * window_s)
    return cnt >= k
