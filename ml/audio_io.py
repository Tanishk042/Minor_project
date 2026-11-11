"""Audio I/O: load any format, resample to 16 kHz mono.

Used identically by dataset building, training-time augmentation, the edge
agent's FileSource and the simulator, so every stage sees the same audio.
"""
from __future__ import annotations

import logging
from pathlib import Path

import librosa
import numpy as np
import soundfile as sf

logger = logging.getLogger("forestear.audio")

TARGET_SR = 16000


def load_audio(path: str | Path, sr: int = TARGET_SR, mono: bool = True,
              duration: float | None = None) -> np.ndarray:
    """Load an audio file as float32 [-1, 1] at the target sample rate.

    Falls back from soundfile to librosa for formats soundfile cannot read
    (e.g. MP3 without ffmpeg raises - caller decides what to do).
    """
    path = str(path)
    try:
        wav, sr_in = sf.read(path, dtype="float32", always_2d=False)
        if wav.ndim == 2:  # stereo -> mono
            wav = wav.mean(axis=1)
    except Exception:
        wav, sr_in = librosa.load(path, sr=None, mono=mono)
    wav = np.asarray(wav, dtype=np.float32)
    if sr_in != sr:
        wav = resample(wav, sr_in, sr)
    if duration is not None:
        n = int(duration * sr)
        wav = wav[:n] if len(wav) >= n else np.pad(wav, (0, n - len(wav)))
    return wav


def resample(wav: np.ndarray, sr_in: int, sr_out: int) -> np.ndarray:
    """Resample with librosa (linear-phase, high quality)."""
    if sr_in == sr_out:
        return wav
    return np.asarray(librosa.resample(wav, orig_sr=sr_in, target_sr=sr_out),
                      dtype=np.float32)


def save_wav(path: str | Path, wav: np.ndarray, sr: int = TARGET_SR) -> None:
    """Write float32 WAV."""
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    sf.write(str(path), np.clip(wav, -1.0, 1.0), sr, subtype="PCM_16")


def frame_windows(wav: np.ndarray, window_s: float, hop_s: float, sr: int = TARGET_SR):
    """Yield (start_sample, window) pairs over a waveform.

    Pads the tail with zeros so the last partial window is analysed too.
    """
    win = int(window_s * sr)
    hop = int(hop_s * sr)
    if len(wav) < win:
        wav = np.pad(wav, (0, win - len(wav)))
    for start in range(0, max(len(wav) - win, 0) + 1, hop):
        yield start, wav[start:start + win]


def rms_db(wav: np.ndarray) -> float:
    """RMS level in dBFS."""
    r = float(np.sqrt(np.mean(np.square(wav.astype(np.float64))) + 1e-20))
    return 20.0 * np.log10(r + 1e-10)


def clipping_ratio(wav: np.ndarray, thresh: float = 0.995) -> float:
    """Fraction of samples above the clipping threshold."""
    return float(np.mean(np.abs(wav) > thresh))
