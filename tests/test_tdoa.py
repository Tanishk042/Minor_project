"""TDOA / geometry tests: correlator solver accuracy, simulator geometry,
correlate() end-to-end with lat/lon events."""
from __future__ import annotations

import datetime as dt
import math

import numpy as np
import pytest

from backend.correlator import (C_SOUND, bearing_band, correlate, enu, latlon,
                               single_node_radius, tdoa_solve)
from simulator.forest_sim import haversine_m, offset_ll

AREA = (12.9716, 77.5946)   # demo forest area centre


class TestGeometry:
    def test_haversine_known_distance(self):
        # 1 degree of latitude ~ 111.2 km
        d = haversine_m(12.0, 77.0, 13.0, 77.0)
        assert abs(d - 111_195) < 200

    def test_offset_roundtrip(self):
        lat, lon = offset_ll(*AREA, 300, 400)
        # back through haversine components
        d = haversine_m(AREA[0], AREA[1], lat, lon)
        assert abs(d - math.hypot(300, 400)) < 0.5   # 500 m +- rounding

    def test_enu_latlon_roundtrip(self):
        x, y = 350.0, -220.0
        lat, lon = latlon(x, y, *AREA)
        x2, y2 = enu(lat, lon, *AREA)
        assert abs(x2 - x) < 0.01 and abs(y2 - y) < 0.01


class TestTDOASolve:
    def test_recovers_synthetic_source(self):
        # 4 nodes on a square, source inside
        nodes = [(-200, -200, 0.0), (200, -200, 0.0),
                 (200, 200, 0.0), (-200, 200, 0.0)]
        src = np.array([137.0, 91.0])
        P = np.array([(x, y) for x, y, _ in nodes])
        d = np.linalg.norm(P - src, axis=1)
        arrivals = [(x, y, d[i] / C_SOUND) for i, (x, y, _) in enumerate(nodes)]
        x, y, r = tdoa_solve(arrivals)
        assert math.hypot(x - src[0], y - src[1]) < 2.0    # metres
        assert r < 60.0                                    # radius sane

    def test_outside_source_three_nodes_ambiguous_covered(self):
        """With exactly 3 nodes the hyperbolas can intersect twice; the
        solver must either pick the right one or inflate the radius to
        honestly cover the truth."""
        nodes = [(0, 0, 0.0), (500, 0, 0.0), (0, 500, 0.0)]
        src = np.array([650.0, -120.0])
        P = np.array([(x, y) for x, y, _ in nodes])
        d = np.linalg.norm(P - src, axis=1)
        arrivals = [(x, y, d[i] / C_SOUND) for i, (x, y, _) in enumerate(nodes)]
        x, y, r = tdoa_solve(arrivals)
        err = math.hypot(x - src[0], y - src[1])
        assert err < 25.0 or err <= r, \
            f"TDOA wrong by {err:.0f} m with radius {r:.0f} m (uncovered)"

    def test_outside_source_four_nodes_unique(self):
        nodes = [(0, 0, 0.0), (500, 0, 0.0), (0, 500, 0.0), (500, 500, 0.0)]
        src = np.array([650.0, -120.0])
        P = np.array([(x, y) for x, y, _ in nodes])
        d = np.linalg.norm(P - src, axis=1)
        arrivals = [(x, y, d[i] / C_SOUND) for i, (x, y, _) in enumerate(nodes)]
        x, y, _ = tdoa_solve(arrivals)
        assert math.hypot(x - src[0], y - src[1]) < 25.0

    def test_needs_three_nodes(self):
        with pytest.raises(AssertionError):
            tdoa_solve([(0, 0, 0.0), (100, 0, 0.1)])

    def test_bearing_band_physical(self):
        # source closer to node 1 -> node 1 hears first
        n1, n2 = (0.0, 0.0, 0.30), (600.0, 0.0, 0.60)
        bb = bearing_band([n1, n2])
        assert abs(bb["distance_diff_m"]) <= 600.0
        assert 0.0 <= bb["frac"] <= 0.99

    def test_single_node_radius_bounds(self):
        assert single_node_radius(0.0) <= 3000.0
        assert single_node_radius(60.0) >= 20.0


class TestCorrelate:
    def make_events(self, n_nodes=3, src_xy=(150.0, 100.0)):
        """Events as the backend receives them, with exact physics delays."""
        lat0, lon0 = AREA
        src = np.array(src_xy)
        P = []
        for i in range(n_nodes):
            ang = 2 * math.pi * i / n_nodes
            P.append((400 * math.cos(ang), 400 * math.sin(ang)))
        P = np.array(P)
        d = np.linalg.norm(P - src, axis=1)
        base = dt.datetime(2026, 1, 1, 12, 0, 0)
        evs = []
        for i, (x, y) in enumerate(P):
            lat, lon = latlon(x, y, lat0, lon0)
            ts = base + dt.timedelta(seconds=float(d[i] / C_SOUND))
            evs.append(dict(node_id=f"node-{i+1:02d}", lat=lat, lon=lon,
                            ts=ts, cls="gunshot", confidence=0.9,
                            rms_db=55.0))
        return evs, latlon(src[0], src[1], lat0, lon0)

    def test_three_nodes_tdoa_localises(self):
        evs, true_ll = self.make_events(n_nodes=3)
        ests = correlate(evs)
        tdoa = [e for e in ests if e["method"] == "tdoa"]
        assert len(tdoa) == 1
        err = haversine_m(tdoa[0]["lat"], tdoa[0]["lon"], *true_ll)
        assert err < 50.0, f"TDOA error {err:.0f} m"
        assert sorted(tdoa[0]["nodes_used"]) == [
            "node-01", "node-02", "node-03"]

    def test_two_nodes_bearing_only(self):
        evs, _ = self.make_events(n_nodes=2)
        ests = correlate(evs)
        assert not any(e["method"] == "tdoa" for e in ests)
        assert any(e["method"] == "bearing" for e in ests)

    def test_one_node_single_radius(self):
        evs, _ = self.make_events(n_nodes=1)
        ests = correlate(evs)
        singles = [e for e in ests if e["method"] == "single"]
        assert len(singles) == 1 and singles[0]["radius_m"] > 0

    def test_non_gunshot_never_localised(self):
        evs, _ = self.make_events(n_nodes=3)
        for e in evs:
            e["cls"] = "chainsaw"
        assert correlate(evs) == []

    def test_window_groups_same_event(self):
        evs, true_ll = self.make_events(n_nodes=3)
        # a 4th node far in time (outside the 6 s window) must not join
        lat, lon = latlon(-800.0, -800.0, *AREA)
        evs.append(dict(node_id="node-99", lat=lat, lon=lon,
                        ts=evs[0]["ts"] + dt.timedelta(seconds=30),
                        cls="gunshot", confidence=0.9, rms_db=40.0))
        ests = correlate(evs)
        tdoa = [e for e in ests if e["method"] == "tdoa"]
        assert len(tdoa) == 1 and "node-99" not in tdoa[0]["nodes_used"]

    def test_same_node_duplicate_ignored(self):
        evs, true_ll = self.make_events(n_nodes=3)
        evs.append(dict(evs[0]))       # duplicate from node-01
        ests = correlate(evs)
        assert len([e for e in ests if e["method"] == "tdoa"]) == 1
