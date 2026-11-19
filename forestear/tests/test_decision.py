"""Decision engine tests: k-of-n persistence, high-confidence gunshot,
weather veto (+ over-veto guard), cooldown, chainsaw escalation, anomaly."""
from __future__ import annotations

from collections import deque

import pytest

from common.config import load_config
from edge_agent.decision import DecisionEngine, WindowScores, k_of_n_met


def ws(**kw) -> WindowScores:
    """WindowScores with sensible zeros for fields not under test."""
    d = dict(m2=0.0, m3=0.0, m4=0.0, m7=0.0, m6_z=0.0,
             m5_rain=0.0, m5_wind=0.0, m5_thunder=0.0, m9={}, rms_db=-100.0)
    d.update(kw)
    return WindowScores(**d)


def fired_of(decs):
    return [d for d in decs if d.fired]


class TestPersistence:
    def test_chainsaw_needs_k_of_n(self):
        eng = DecisionEngine()
        t = 0.0
        out = []
        for _ in range(2):                       # k=3 -> two hits not enough
            out += eng.update(ws(m3=0.9, m9={"chainsaw": 0.8}), t)
            t += 0.5
        assert not fired_of(out)
        out += eng.update(ws(m3=0.9, m9={"chainsaw": 0.8}), t)  # third hit
        f = fired_of(out)
        assert len(f) == 1 and f[0].cls == "chainsaw"
        assert f[0].severity == "WARNING"
        assert f[0].k_of_n == "3/5"

    def test_stale_hits_expire(self):
        """Hits older than n*hop seconds must not count towards k."""
        eng = DecisionEngine()
        eng.update(ws(m3=0.9, m9={"chainsaw": 0.8}), 0.0)
        eng.update(ws(m3=0.9, m9={"chainsaw": 0.8}), 0.5)
        # jump past the n=5 window (5 * 0.5 s + 0.1)
        decs = eng.update(ws(m3=0.9, m9={"chainsaw": 0.8}), 6.0)
        assert not fired_of(decs)                 # only 1 fresh hit

    def test_head_model_bypass_threshold(self):
        """A specialist model fires with a 0.15 headroom over the threshold."""
        eng = DecisionEngine()
        # chainsaw thr 0.5: m3=0.66 > 0.5+0.15 -> detected even with m9=0
        decs = eng.update(ws(m3=0.66), 0.0)
        assert eng.detections["chainsaw"]
        assert decs == []                         # 1 hit: no fire yet

    def test_vehicle_k_of_n(self):
        eng = DecisionEngine()
        t, out = 0.0, []
        for _ in range(3):
            out += eng.update(ws(m4=0.9, m9={"vehicle": 0.7}), t)
            t += 0.5
        f = fired_of(out)
        assert len(f) == 1 and f[0].cls == "vehicle" and f[0].severity == "WARNING"

    def test_k_of_n_met_pure(self):
        ring = deque([(0.0, 0.9), (0.5, 0.9), (1.0, 0.9), (5.0, 0.9)])
        assert k_of_n_met(ring, k=3, n=3, window_s=0.5, t=1.2)   # 3 fresh
        assert not k_of_n_met(ring, k=3, n=3, window_s=0.5, t=6.0)  # stale


class TestGunshot:
    def test_high_conf_single_window(self):
        eng = DecisionEngine()
        decs = eng.update(ws(m2=0.9), 0.0)        # conf 0.9 >= 0.85
        f = fired_of(decs)
        assert len(f) == 1 and f[0].cls == "gunshot"
        assert f[0].severity == "CRITICAL"

    def test_low_conf_two_hits(self):
        eng = DecisionEngine()
        t, out = 0.0, []
        for _ in range(2):
            out += eng.update(ws(m9={"gunshot": 0.65}), t)
            t += 0.5
        assert len(fired_of(out)) == 1

    def test_single_low_conf_hit_no_fire(self):
        eng = DecisionEngine()
        decs = eng.update(ws(m9={"gunshot": 0.65}), 0.0)
        assert not fired_of(decs)


class TestWeatherVeto:
    def test_thunder_suppresses_low_conf_gunshot(self):
        eng = DecisionEngine()
        t, out = 0.0, []
        for _ in range(2):
            out += eng.update(ws(m9={"gunshot": 0.65}, m5_thunder=0.9), t)
            t += 0.5
        sup = [d for d in out if d.suppressed_by and "weather_veto" in d.suppressed_by]
        assert len(sup) == 1 and sup[0].fired is False
        assert eng.suppressed_events()            # audit trail populated

    def test_high_conf_gunshot_survives_weather(self):
        """Over-veto guard: a confident gunshot must alert during a storm."""
        eng = DecisionEngine()
        decs = eng.update(ws(m2=0.92, m5_thunder=0.9), 0.0)
        f = fired_of(decs)
        assert len(f) == 1 and f[0].cls == "gunshot"

    def test_veto_never_blocks_other_classes(self):
        eng = DecisionEngine()
        t, out = 0.0, []
        for _ in range(3):
            out += eng.update(ws(m3=0.9, m9={"chainsaw": 0.8},
                                 m5_rain=0.95), t)
            t += 0.5
        f = fired_of(out)
        assert len(f) == 1 and f[0].cls == "chainsaw"   # rain cannot hide a saw

    def test_veto_disabled_by_config(self):
        cfg = load_config()
        cfg["decision"]["weather_veto"]["enable"] = False
        eng = DecisionEngine(cfg=cfg)
        t, out = 0.0, []
        for _ in range(2):
            out += eng.update(ws(m9={"gunshot": 0.65}, m5_thunder=0.9), t)
            t += 0.5
        assert len(fired_of(out)) == 1


class TestCooldown:
    def test_no_refire_within_cooldown(self):
        eng = DecisionEngine()
        eng.update(ws(m2=0.9), 0.0)
        for t in (0.5, 5.0, 15.0):                # gunshot cooldown 20 s
            decs = eng.update(ws(m2=0.9), t)
            assert not fired_of(decs)

    def test_refire_after_cooldown(self):
        eng = DecisionEngine()
        eng.update(ws(m2=0.9), 0.0)
        decs = eng.update(ws(m2=0.9), 21.0)
        assert len(fired_of(decs)) == 1


class TestChainsawEscalation:
    def test_sustained_saw_escalates_to_critical(self):
        eng = DecisionEngine()
        t, out = 0.0, []
        for _ in range(20):                        # 10 s of continuous saw
            out += eng.update(ws(m3=0.9, m9={"chainsaw": 0.8}), t)
            t += 0.5
        f = fired_of(out)
        assert f[0].severity == "WARNING"
        crit = [d for d in f if d.severity == "CRITICAL"]
        assert crit and crit[0].escalated
        # escalation bypasses cooldown exactly once
        assert len([d for d in f if d.severity == "CRITICAL"]) == 1

    def test_escalation_resets_after_silence(self):
        eng = DecisionEngine()
        t, out = 0.0, []
        for _ in range(20):
            out += eng.update(ws(m3=0.9, m9={"chainsaw": 0.8}), t)
            t += 0.5
        f = fired_of(out)
        assert any(d.severity == "CRITICAL" for d in f)
        t += 60.0                                  # silence lapses the sustain
        for _ in range(3):                          # k-of-n must re-arm
            out += eng.update(ws(m3=0.9, m9={"chainsaw": 0.8}), t)
            t += 0.5
        f = fired_of(out)
        assert f[-1].severity == "WARNING"          # back to plain WARNING
        assert f[-1].escalated is False


class TestAnomaly:
    def test_anomaly_needs_loud_zscore_and_persistence(self):
        eng = DecisionEngine()
        # z-score alone (quiet) -> nothing
        assert eng.update(ws(m6_z=0.9, rms_db=20.0), 0.0) == []
        # loud but not anomalous -> nothing
        assert eng.update(ws(m6_z=0.1, rms_db=50.0), 0.5) == []
        # both, first window -> detected but k-of-n not yet met
        assert eng.update(ws(m6_z=0.9, rms_db=50.0), 1.0) == []
        # second window -> fires INFO
        decs = eng.update(ws(m6_z=0.9, rms_db=50.0), 1.5)
        f = fired_of(decs)
        assert f and f[0].cls == "anomaly" and f[0].severity == "INFO"
