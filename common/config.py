"""Single source of truth for configuration.

Training scripts, the edge agent, the simulator and the backend all load
configs through this module so thresholds can never drift between phases.
"""
from __future__ import annotations

import logging
import os
from pathlib import Path
from typing import Any

import yaml

logger = logging.getLogger("forestear.config")

REPO_ROOT = Path(__file__).resolve().parents[1]
CONFIG_PATH = REPO_ROOT / "configs" / "default.yaml"
LABELS_PATH = REPO_ROOT / "configs" / "labels.yaml"

_cache: dict[str, dict[str, Any]] = {}


def load_config(path: str | Path | None = None) -> dict[str, Any]:
    """Load the YAML config (cached per path)."""
    p = Path(path) if path else CONFIG_PATH
    key = str(p)
    if key not in _cache:
        with open(p, "r", encoding="utf-8") as f:
            _cache[key] = yaml.safe_load(f)
    return _cache[key]


def load_labels(path: str | Path | None = None) -> dict[str, Any]:
    """Load the label taxonomy (cached per path)."""
    p = Path(path) if path else LABELS_PATH
    key = str(p)
    if key not in _cache:
        with open(p, "r", encoding="utf-8") as f:
            _cache[key] = yaml.safe_load(f)
    return _cache[key]


def get_node_token(cfg: dict[str, Any] | None = None) -> str:
    """Auth token: environment variable wins over config file (no secrets in repo)."""
    return os.environ.get("FORESTEAR_TOKEN", (cfg or load_config())["node"]["auth_token"])


def data_dir(*parts: str) -> Path:
    """Absolute path inside data/ (created on demand)."""
    d = REPO_ROOT / "data"
    for part in parts:
        d = d / part
    d.mkdir(parents=True, exist_ok=True)
    return d


def data_file(*parts: str) -> Path:
    """Path to a FILE inside data/: only parent dirs are created, never the
    file itself (unlike data_dir which would create it as a directory)."""
    d = REPO_ROOT / "data"
    for part in parts:
        d = d / part
    d.parent.mkdir(parents=True, exist_ok=True)
    return d


def models_dir(*parts: str) -> Path:
    """Absolute path inside models_store/ (created on demand)."""
    d = REPO_ROOT / "models_store"
    for part in parts:
        d = d / part
    d.mkdir(parents=True, exist_ok=True)
    return d


def reports_dir(*parts: str) -> Path:
    d = REPO_ROOT / "reports"
    for part in parts:
        d = d / part
    d.mkdir(parents=True, exist_ok=True)
    return d
