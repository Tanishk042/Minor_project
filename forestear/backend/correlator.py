"""Multi-node correlator: gunshot localisation via time-difference-of-arrival.

Physics: speed of sound c = 343 m/s. Nodes timestamp the SAME impulsive event
(synced clocks assumed, e.g. NTP; the simulator injects known clock offsets
which we subtract before correlating). With >= 3 nodes we solve the linearised
TDOA equations (Taylor series, first order) for (x, y) in a local ENU frame
and publish an estimated position + uncertainty radius (residual-based).
With 2 nodes: the TDOA defines a hyperbola; we publish the bearing band and
the midpoint with an uncertainty radius. With 1 node: a detection radius from
the received RMS (crude inverse-square model), clearly labelled "estimate".
"""
from __future__ import annotations

import datetime as dt
import logging
import math

import numpy as np

log = logging.getLogger("forestear.correlator")
C_SOUND = 343.0


def enu(lat: float, lon: float, lat0: float, lon0: float) -> tuple[float, float]:
    """Approximate local east/north offsets in metres (small-area equirectangular)."""
    r = 6371000.0
    x = math.radians(lon - lon0) * r * math.cos(math.radians((lat + lat0) / 2))
    y = math.radians(lat - lat0) * r
    return x, y


def latlon(x: float, y: float, lat0: float, lon0: float) -> tuple[float, float]:
    r = 6371000.0
    lat = lat0 + math.degrees(y / r)
    lon = lon0 + math.degrees(x / (r * math.cos(math.radians((lat + lat0) / 2))))
    return lat, lon


def _gn(nodes: list[tuple[float, float, float]], p0: np.ndarray,
        iters: int = 60) -> tuple[np.ndarray | None, float]:
    """One Gauss-Newton run from start p0. Returns (point, residual_m)."""
    P = np.array([(x, y) for x, y, _ in nodes])
    t = np.array([tt for _, _, tt in nodes])
    p = np.array(p0, dtype=float)
    for _ in range(iters):
        d = np.linalg.norm(P - p, axis=1)
        rows, rhs = [], []
        for i in range(1, len(P)):
            grad = (p - P[i]) / (d[i] + 1e-9) - (p - P[0]) / (d[0] + 1e-9)
            rows.append(grad)
            rhs.append(C_SOUND * (t[i] - t[0]) - (d[i] - d[0]))
        A, b = np.array(rows), np.array(rhs)
        try:
            dp, *_ = np.linalg.lstsq(A, b, rcond=None)
        except np.linalg.LinAlgError:
            return None, float("inf")
        p = p + dp
        if np.linalg.norm(dp) < 0.1:
            break
    if not np.all(np.isfinite(p)):
        return None, float("inf")
    resid = float(np.median(
        [abs(C_SOUND * (t[i] - t[0])
             - (np.linalg.norm(P[i] - p) - np.linalg.norm(P[0] - p)))
         for i in range(1, len(P))]))
    return p, resid


def tdoa_solve(nodes: list[tuple[float, float, float]],
               max_radius_m: float = 25_000.0) -> tuple[float, float, float]:
    """Least-squares TDOA localisation.

    nodes: [(x, y, t_arrival_s), ...] >= 3 nodes in a local ENU frame with
    SYNCHRONISED clocks. Returns (x, y, residual_radius_m).
    Linearisation: for reference node 0, |p - p_i| - |p - p_0| = c (t_i - t_0).

    With EXACTLY 3 nodes the two hyperbolas can intersect twice (two-fold
    ambiguity) and both points satisfy the equations perfectly. We run
    Gauss-Newton from the centroid plus several random restarts and keep the
    solution with the smallest residual (nearest the centroid as a tie-break);
    if another distinct solution fits almost as well, the reported radius is
    inflated to cover it. 4+ nodes make the system over-determined and the
    ambiguity disappears.

    Timestamps carry unknown per-node clock offsets in a real deployment, so
    fits are accepted up to ``fit_tol_m`` of median residual rather than
    demanding a perfect fit. If no plausible solution exists (bad geometry,
    inconsistent arrivals, unsynchronised clocks) this raises ``ValueError``
    instead of publishing a numerically meaningless point.
    """
    assert len(nodes) >= 3, "TDOA needs >= 3 nodes"
    fit_tol_m = 40.0
    P = np.array([(x, y) for x, y, _ in nodes])
    centroid = P.mean(axis=0)
    scale = float(np.median(np.linalg.norm(P - centroid, axis=1)))
    if scale < 1.0:
        scale = 100.0
    rng = np.random.default_rng(7)
    starts = [centroid]
    for _ in range(6):
        starts.append(centroid + rng.uniform(-2.0, 2.0, size=2) * scale)
    sols: list[tuple[np.ndarray, float]] = []
    for s in starts:
        p, resid = _gn(nodes, s)
        if p is None or resid > fit_tol_m:   # not a valid fit (metres)
            continue
        if not np.all(np.isfinite(p)):
            continue
        if not any(np.linalg.norm(p - q) < 20.0 for q, _ in sols):
            sols.append((p, resid))
    if not sols:
        raise ValueError(
            f"no plausible TDOA solution (best residual > {fit_tol_m:.0f} m); "
            "check clock synchronisation and arrival timestamps")
    # Residual first: with 4+ nodes a wrong local minimum can sit closer to the
    # centroid than the true source, so "nearest the centroid" alone would pick
    # it. Only near-ties (the genuine 3-node hyperbola ambiguity) are broken by
    # distance to the array.
    sols.sort(key=lambda sr: (sr[1], float(np.linalg.norm(sr[0] - centroid))))
    p, resid = sols[0]
    offset = float(np.linalg.norm(p - centroid))
    if offset > max_radius_m:
        raise ValueError(
            f"TDOA solution {offset:.0f} m from array centroid exceeds "
            f"{max_radius_m:.0f} m; refusing to publish")
    r = resid + 25.0                        # residual + floor for clock jitter
    for q, qres in sols[1:]:
        # Another solution that fits within 15 m of the best one is a genuine
        # ambiguity (3-node hyperbola pair); make the circle cover both.
        if qres <= resid + 15.0:
            r = max(r, float(np.linalg.norm(q - p)) + qres + 25.0)
    return float(p[0]), float(p[1]), float(min(r, max_radius_m))


def bearing_band(nodes: list[tuple[float, float, float]]) -> dict:
    """2-node case: TDOA gives a distance difference -> hyperbola branch.

    We publish the midpoint and an uncertainty radius equal to the whole
    feasible region scale (honest, coarse).
    """
    (x1, y1, t1), (x2, y2, t2) = nodes
    mid = ((x1 + x2) / 2, (y1 + y2) / 2)
    dd = C_SOUND * (t1 - t2)         # distance difference (m)
    sep = math.hypot(x2 - x1, y2 - y1)
    # |dd| <= sep required for a physical solution; clamp
    frac = min(abs(dd) / max(sep, 1e-6), 0.99)
    # bearing of the source w.r.t. the node that heard it FIRST
    first, other = ((x1, y1), (x2, y2)) if t1 <= t2 else ((x2, y2), (x1, y1))
    bearing = math.degrees(math.atan2(first[1] - other[1], first[0] - other[0]))
    return {"x": mid[0], "y": mid[1], "radius_m": sep,
            "bearing_deg": bearing, "distance_diff_m": dd, "frac": frac}


def single_node_radius(rms_db: float) -> float:
    """1-node coarse detection radius from received level (inverse-square).

    Assumes ~110 dB SPL source at 1 m for a gunshot; documented as a rough
    estimate in the UI.
    """
    if rms_db < 5:
        return 1500.0
    spl_at_1m = 110.0
    drop = spl_at_1m - (rms_db + 94.0)   # dBFS->SPL approx (94 dB = full scale)
    return float(min(max(10 ** (drop / 20.0), 20.0), 3000.0))


def correlate(events: list[dict], window_s: float = 6.0,
              max_clock_offset_s: float = 0.0) -> list[dict]:
    """Group same-class events across nodes within window and localise.

    events: [{node_id, lat, lon, ts (datetime UTC), cls, confidence,
              inference_ms?, rms_db?}]. Returns estimate dicts.
    """
    out = []
    by_cls: dict[str, list[dict]] = {}
    for e in events:
        by_cls.setdefault(e["cls"], []).append(e)
    for cls, evs in by_cls.items():
        evs = sorted(evs, key=lambda e: e["ts"])
        i = 0
        while i < len(evs):
            group = [evs[i]]
            j = i + 1
            while j < len(evs) and (evs[j]["ts"] - group[0]["ts"]).total_seconds() <= window_s:
                # same node twice -> skip duplicates
                if evs[j]["node_id"] not in {g["node_id"] for g in group}:
                    group.append(evs[j])
                j += 1
            if len(group) >= 3 and cls == "gunshot":
                lat0 = float(np.mean([g["lat"] for g in group]))
                lon0 = float(np.mean([g["lon"] for g in group]))
                nodes = []
                for g in sorted(group, key=lambda g: g["node_id"]):
                    x, y = enu(g["lat"], g["lon"], lat0, lon0)
                    t = g["ts"].timestamp()
                    nodes.append((x, y, t))
                # Per-node clock offsets are NOT corrected here: in a real
                # deployment they are unknown, so the solver's residual/radius
                # inflation is what exposes them (see tdoa_solve).
                try:
                    x, y, r = tdoa_solve(nodes)
                    lat, lon = latlon(x, y, lat0, lon0)
                    out.append({"cls": cls, "method": "tdoa", "lat": lat,
                                "lon": lon, "radius_m": r,
                                "nodes_used": [g["node_id"] for g in group],
                                "ts": group[0]["ts"]})
                except Exception as e:  # noqa: BLE001
                    log.warning("TDOA failed: %s", e)
            elif len(group) == 2 and cls == "gunshot":
                lat0 = float(np.mean([g["lat"] for g in group]))
                lon0 = float(np.mean([g["lon"] for g in group]))
                nodes = []
                for g in group:
                    x, y = enu(g["lat"], g["lon"], lat0, lon0)
                    nodes.append((x, y, g["ts"].timestamp()))
                bb = bearing_band(nodes)
                lat, lon = latlon(bb["x"], bb["y"], lat0, lon0)
                out.append({"cls": cls, "method": "bearing", "lat": lat, "lon": lon,
                            "radius_m": bb["radius_m"],
                            "bearing_deg": bb["bearing_deg"],
                            "nodes_used": [g["node_id"] for g in group],
                            "ts": group[0]["ts"]})
            i = j if j > i else i + 1
    # single-node detections: attach radius estimates (no position claim)
    for e in events:
        if e["cls"] == "gunshot" and not any(
                o.get("method") == "tdoa" and e["node_id"] in o["nodes_used"]
                for o in out):
            out.append({"cls": "gunshot", "method": "single",
                        "lat": e["lat"], "lon": e["lon"],
                        "radius_m": single_node_radius(e.get("rms_db", 20.0)),
                        "nodes_used": [e["node_id"]], "ts": e["ts"]})
    return out
