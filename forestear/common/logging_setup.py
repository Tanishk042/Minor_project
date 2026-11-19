"""Logging setup: structured console logging, no print debugging."""
from __future__ import annotations

import logging
import sys

_CONFIGURED = False


def setup_logging(level: str = "INFO", name: str = "forestear") -> None:
    """Configure root handler once; call from every entry point."""
    global _CONFIGURED
    if _CONFIGURED:
        logging.getLogger(name).setLevel(level.upper())
        return
    handler = logging.StreamHandler(sys.stderr)
    handler.setFormatter(
        logging.Formatter("%(asctime)s %(levelname)-7s %(name)s :: %(message)s", "%H:%M:%S")
    )
    root = logging.getLogger()
    root.addHandler(handler)
    root.setLevel(level.upper())
    # silence noisy third-party loggers
    for noisy in ("urllib3", "httpx", "httpcore", "matplotlib", "PIL", "numba"):
        logging.getLogger(noisy).setLevel(logging.WARNING)
    _CONFIGURED = True
