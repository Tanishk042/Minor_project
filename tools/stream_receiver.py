#!/usr/bin/env python3
"""Receive ForestEar Node's raw 16 kHz PCM WebSocket stream on the laptop.

The phone sends:
  * one text frame on connect:
      {"type":"hello","node_id":"node-01","sample_rate":16000,
       "channels":1,"format":"s16le"}
  * binary frames, each one capture block (100 ms = 1600 samples = 3200 bytes)
    of little-endian signed 16-bit mono PCM at 16 kHz.

This tool is a debugging aid only. The phone still runs all inference locally;
audio never needs to leave the device for alerts.

Usage:
  python android/tools/stream_receiver.py
  python android/tools/stream_receiver.py --port 8765 --out field_node01.wav
  python android/tools/stream_receiver.py --max-seconds 120 --quiet

Then set the streaming URL in the app to:
  ws://<laptop-lan-ip>:8765/stream
and enable Tools > Raw PCM streaming (or broadcast STREAM_ON).

Requires: pip install websockets
"""

from __future__ import annotations

import argparse
import datetime as _dt
import math
import signal
import struct
import sys
import threading
import time
import wave
from pathlib import Path

try:
    from websockets.sync.server import serve
except ImportError as exc:  # pragma: no cover - dependency error path
    raise SystemExit(
        "websockets is required: pip install websockets\n"
        f"(import error: {exc})"
    ) from exc

SAMPLE_RATE = 16_000
CHANNELS = 1
SAMPLE_WIDTH = 2


class Session:
    """One connected phone. Owns its WAV file and level meter."""

    def __init__(self, out: Path, quiet: bool) -> None:
        self.out = out
        self.quiet = quiet
        self.started = time.monotonic()
        self.bytes_written = 0
        self.frames = 0
        self.peak = 0
        self.clipped_samples = 0
        self._sum_sq = 0.0
        self._sum_n = 0
        self._last_report = time.monotonic()
        self._lock = threading.Lock()
        self._wav = wave.open(str(out), "wb")
        self._wav.setnchannels(CHANNELS)
        self._wav.setsampwidth(SAMPLE_WIDTH)
        self._wav.setframerate(SAMPLE_RATE)

    # -- audio ---------------------------------------------------------
    def write(self, payload: bytes) -> None:
        if len(payload) % SAMPLE_WIDTH:
            payload = payload[: len(payload) - (len(payload) % SAMPLE_WIDTH)]
        if not payload:
            return
        with self._lock:
            self._wav.writeframes(payload)
            self.bytes_written += len(payload)
            self.frames += 1
            samples = struct.unpack(f"<{len(payload) // 2}h", payload)
            for s in samples:
                a = abs(s)
                if a > self.peak:
                    self.peak = a
                if a >= 32767:
                    self.clipped_samples += 1
                self._sum_sq += float(s) * float(s)
            self._sum_n += len(samples)

    def maybe_report(self) -> None:
        """Print a one-line level report once a second."""
        now = time.monotonic()
        elapsed = now - self.started
        if now - self._last_report >= 1.0 and not self.quiet:
            with self._lock:
                rms = math.sqrt(self._sum_sq / self._sum_n) if self._sum_n else 0.0
                dbfs = 20.0 * math.log10(rms / 32768.0) if rms > 0 else -120.0
                peak_dbfs = (
                    20.0 * math.log10(self.peak / 32768.0) if self.peak else -120.0
                )
                self._sum_sq = 0.0
                self._sum_n = 0
                self.peak = 0
                seconds = self.bytes_written / (SAMPLE_RATE * SAMPLE_WIDTH)
            print(
                f"[{elapsed:7.1f}s] audio={seconds:7.1f}s rms={dbfs:6.1f} dBFS "
                f"peak={peak_dbfs:6.1f} dBFS clipped={self.clipped_samples}",
                flush=True,
            )
            self._last_report = now

    def close(self) -> None:
        with self._lock:
            self._wav.close()
            duration = self.bytes_written / (SAMPLE_RATE * SAMPLE_WIDTH)
            print(
                f"\nSaved {self.out}  {duration:.1f}s  "
                f"{self.bytes_written / 1e6:.2f} MB  frames={self.frames}",
                flush=True,
            )


def make_handler(args: argparse.Namespace, stop: threading.Event):
    def handler(ws) -> None:
        peer = getattr(ws, "remote_address", ("?", 0))
        print(f"connect from {peer[0]}:{peer[1]}", flush=True)
        stamp = _dt.datetime.now().strftime("%Y%m%d-%H%M%S")
        out = Path(args.out) if args.out else Path(f"stream_{stamp}.wav")
        if not out.is_absolute():
            out = Path.cwd() / out
        out.parent.mkdir(parents=True, exist_ok=True)
        session = Session(out, args.quiet)
        try:
            for message in ws:
                if isinstance(message, str):
                    print(f"hello: {message}", flush=True)
                    continue
                session.write(message)
                session.maybe_report()
        except Exception as exc:  # noqa: BLE001 - report and keep serving
            print(f"stream error: {exc}", flush=True)
        finally:
            session.close()
            print(f"disconnect from {peer[0]}:{peer[1]}", flush=True)

    return handler


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--host", default="0.0.0.0", help="bind address (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=8765, help="bind port (default: 8765)")
    parser.add_argument("--out", default=None, help="output WAV path (default: stream_<time>.wav)")
    parser.add_argument(
        "--max-seconds",
        type=float,
        default=0,
        help="stop the server after this many seconds (0 = until Ctrl-C)",
    )
    parser.add_argument("--quiet", action="store_true", help="no per-second level lines")
    args = parser.parse_args(argv)

    stop = threading.Event()

    def on_signal(_signum, _frame):
        stop.set()

    signal.signal(signal.SIGINT, on_signal)
    signal.signal(signal.SIGTERM, on_signal)

    handler = make_handler(args, stop)
    deadline = time.monotonic() + args.max_seconds if args.max_seconds > 0 else None
    print(
        f"listening on ws://{args.host}:{args.port}/stream "
        f"(16 kHz mono s16le). Ctrl-C to stop.",
        flush=True,
    )
    with serve(handler, args.host, args.port, max_size=None) as server:
        # serve_forever() blocks; poll the stop flag so Ctrl-C and --max-seconds
        # both shut down cleanly, even if no phone ever connects.
        server_thread = threading.Thread(target=server.serve_forever, daemon=True)
        server_thread.start()
        while not stop.is_set():
            if deadline is not None and time.monotonic() >= deadline:
                stop.set()
                break
            time.sleep(0.2)
        server.shutdown()
        server_thread.join(timeout=2.0)
    return 0


if __name__ == "__main__":
    sys.exit(main())
