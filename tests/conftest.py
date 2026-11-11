"""Shared fixtures: repo on sys.path, temp configs, synthetic audio."""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pytest

REPO = Path(__file__).resolve().parents[1]
if str(REPO) not in sys.path:
    sys.path.insert(0, str(REPO))

SR = 16000


@pytest.fixture(scope="session")
def rng() -> np.random.Generator:
    return np.random.default_rng(1234)


@pytest.fixture()
def sine_1k() -> np.ndarray:
    """1 second of 1 kHz sine at -6 dBFS."""
    t = np.arange(SR) / SR
    return (0.5 * np.sin(2 * np.pi * 1000.0 * t)).astype(np.float32)


@pytest.fixture()
def white_noise(rng) -> np.ndarray:
    return (rng.uniform(-1, 1, SR)).astype(np.float32)


@pytest.fixture()
def event_payload() -> dict:
    """A well-formed node event payload (contract: Section 5.4).
    per_model_scores keys mirror the edge agent's _emit_event exactly."""
    return {
        "node_id": "node-test",
        "timestamp_utc": "2026-01-01T00:00:00Z",
        "lat": 12.9716,
        "lon": 77.5946,
        "class": "chainsaw",
        "severity": "WARNING",
        "confidence": 0.82,
        "per_model_scores": {
            "m2_gunshot": 0.1, "m3_chainsaw": 0.9,
            "m9_fusion": {"chainsaw": 0.82}},
        "rms_db": 41.5,
        "persistence": "3/5",
        "escalated": False,
        "battery": 87.0,
        "cpu": 31.0,
        "inference_ms": {"total": 96.0},
    }
