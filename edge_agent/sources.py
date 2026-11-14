"""Audio sources for the edge agent: MicSource, FileSource, SimSource.

All yield (timestamp_utc, 1.0 s float32 @16k) windows at 0.5 s hop, driven by a
shared clock. Sources handle: mic unplugged, clipping, silence, file end.
"""
from __future__ import annotations

import datetime as dt
import logging
import queue
import time
from pathlib import Path

import numpy as np

from common.config import load_config
from ml.audio_io import load_audio, clipping_ratio, rms_db

log = logging.getLogger("forestear.sources")
SR = 16000
WINDOW = SR          # 1.0 s
HOP = SR // 2        # 0.5 s

# One clock base for every simulated node in this process. Creating it per
# SimSource would offset nodes by however long their model load took (seconds),
# which would make cross-node TDOA meaningless.
SIM_EPOCH = dt.datetime.now(dt.timezone.utc)


class BaseSource:
    """Common windowing logic: subclasses implement read_hop()."""

    def __init__(self, cfg: dict | None = None):
        self.cfg = cfg or load_config()
        self.silence_db = self.cfg["audio"]["silence_rms_db"]
        self.clip_ratio = self.cfg["audio"]["clip_ratio"]
        self.dropped = 0
        self.status = "ok"

    def read_hop(self) -> np.ndarray | None:
        raise NotImplementedError

    def windows(self):
        """Yield (utc_ts, window, is_silent, is_clipping) forever/at pace."""
        import datetime as dt
        while True:
            t0 = time.perf_counter()
            w = self.read_hop()
            if w is None:
                continue
            # Sources that know the true capture time (the simulator) expose
            # last_ts; wall clock is only the fallback for mic/file replay.
            ts = getattr(self, "last_ts", None) or \
                dt.datetime.now(dt.timezone.utc).isoformat()
            silent = rms_db(w) < self.silence_db
            clipping = clipping_ratio(w, self.clip_ratio) > 0.01
            yield ts, w, silent, clipping
            # pace to real time for mic; FileSource/SimSource override pacing
            pace = getattr(self, "pace_realtime", True)
            if pace:
                dt_wait = HOP / SR - (time.perf_counter() - t0)
                if dt_wait > 0:
                    time.sleep(dt_wait)


class MicSource(BaseSource):
    """sounddevice InputStream at 16 kHz mono; auto device select."""

    def __init__(self, device: int | None = None, cfg: dict | None = None):
        super().__init__(cfg)
        import sounddevice as sd
        self.sd = sd
        self.device = device
        self.q: queue.Queue = queue.Queue(maxsize=64)
        self._open_stream()

    def _callback(self, indata, frames, time_info, status):  # noqa: ANN001
        if status:
            log.debug("mic status: %s", status)
        self.q.put(indata[:, 0].copy())

    def _open_stream(self):
        try:
            self.stream = self.sd.InputStream(
                samplerate=SR, channels=1, dtype="float32",
                blocksize=HOP, device=self.device, callback=self._callback)
            self.stream.start()
            self.status = "ok"
            log.info("mic opened (device=%s)", self.device or "default")
        except Exception as e:  # noqa: BLE001
            self.status = f"mic_error: {e}"
            log.error("mic open failed: %s", e)
            raise

    def read_hop(self) -> np.ndarray | None:
        buf = np.zeros(0, dtype=np.float32)
        deadline = time.time() + 2.0
        while len(buf) < HOP:
            try:
                buf = np.concatenate([buf, self.q.get(timeout=0.5)])
            except queue.Empty:
                if time.time() > deadline:
                    self.dropped += 1
                    return None
        # maintain a 1 s window across hops: keep previous tail
        if not hasattr(self, "_tail"):
            self._tail = np.zeros(WINDOW - HOP, dtype=np.float32)
        win = np.concatenate([self._tail, buf[:HOP]])
        self._tail = buf[-(WINDOW - HOP):] if len(buf) >= WINDOW - HOP else buf
        return win.astype(np.float32)


class FileSource(BaseSource):
    """Replay a WAV/MP3/OGG file; optional looping and speed control.

    pace_realtime=False replays as fast as the pipeline can go (evaluation);
    True paces like a live feed (demo).
    """

    def __init__(self, path: str | Path, loop: bool = True, speed: float = 1.0,
                 pace_realtime: bool = True, cfg: dict | None = None):
        super().__init__(cfg)
        self.wav = load_audio(path, sr=SR)
        if speed != 1.0:
            n = int(len(self.wav) / speed)
            idx = (np.arange(n) * speed).astype(int)
            self.wav = self.wav[idx]
        self.pos = 0
        self.loop = loop
        self.pace_realtime = pace_realtime
        self.done = False

    def read_hop(self) -> np.ndarray | None:
        if self.pos + WINDOW > len(self.wav):
            if self.loop:
                self.pos = 0
            else:
                self.done = True
                return None
        win = self.wav[self.pos:self.pos + WINDOW]
        if len(win) < WINDOW:
            win = np.pad(win, (0, WINDOW - len(win)))
        self.pos += HOP
        return win.astype(np.float32)


class SimSource(BaseSource):
    """Receives pre-mixed node audio from the forest simulator (push model).

    The simulator calls push() with each hop's mixed window; windows() then
    yields them (optionally faster than real time for time-accelerated runs).
    """

    def __init__(self, cfg: dict | None = None, pace_realtime: bool = True):
        super().__init__(cfg)
        self.q: queue.Queue = queue.Queue(maxsize=512)
        self.pace_realtime = pace_realtime
        self.done = False
        self._sim_time = 0.0
        self.last_ts: str | None = None
        # all nodes share this epoch so their timestamps are comparable
        self._epoch = SIM_EPOCH
        # Rolling 1.0 s window built from 0.5 s hops. Padding each hop with
        # zeros (the old behaviour) left half of every analysis window silent
        # and could push an impulsive onset out of the window entirely for a
        # distant node, which broke both detection and TDOA.
        self._tail = np.zeros(WINDOW - HOP, dtype=np.float32)

    def push(self, win: np.ndarray, sim_t: float) -> None:
        self._sim_time = sim_t
        try:
            self.q.put((sim_t, win.astype(np.float32)), block=False)
        except queue.Full:
            self.dropped += 1

    def finish(self):
        self.done = True

    def read_hop(self) -> np.ndarray | None:
        try:
            sim_t, hop = self.q.get(timeout=0.5)
            self._sim_time = sim_t
            # The hop covers [sim_t, sim_t + 0.5 s); the 1.0 s window that
            # ends at sim_t + 0.5 s starts at sim_t - 0.5 s.
            self.last_ts = (self._epoch
                            + dt.timedelta(seconds=sim_t - (WINDOW - HOP) / SR)
                            ).isoformat()
            hop = np.asarray(hop, dtype=np.float32)
            if len(hop) < HOP:
                hop = np.pad(hop, (0, HOP - len(hop)))
            w = np.concatenate([self._tail, hop[:HOP]])
            self._tail = hop[:HOP].copy()
            return w.astype(np.float32, copy=False)
        except queue.Empty:
            return None
