"""On-the-fly augmentation pipeline for training and robustness evaluation.

Implements Section 4 "Training rules":
- mix event with background bank at random SNR (-5..+20 dB)
- random gain
- pitch shift (+-2 semitones)
- time shift
- low-pass / band-limit emulating a cheap phone microphone
- wind-noise bursts
- distance simulation: inverse-square attenuation + HF roll-off + simple reverb
"""
from __future__ import annotations

import logging
from pathlib import Path

import numpy as np

from . import synth
from .audio_io import load_audio, TARGET_SR

logger = logging.getLogger("forestear.augment")


class BackgroundBank:
    """In-memory bank of benign ambience clips for SNR mixing."""

    def __init__(self, wavs: list[np.ndarray], sr: int = TARGET_SR):
        self.wavs = [np.asarray(w, dtype=np.float32) for w in wavs]
        self.sr = sr
        self._rng = np.random.default_rng(1234)

    @classmethod
    def from_dir(cls, d: str | Path, max_clips: int = 200, sr: int = TARGET_SR) -> "BackgroundBank":
        wavs = []
        for p in sorted(Path(d).glob("*.wav"))[:max_clips]:
            try:
                w = load_audio(p, sr=sr)
                if len(w) >= sr // 2:
                    wavs.append(w)
            except Exception as e:  # noqa: BLE001
                logger.warning("skipping background clip %s: %s", p, e)
        if not wavs:
            logger.warning("background bank empty -> falling back to synthetic ambience")
            rng = np.random.default_rng(7)
            wavs = [synth.forest_ambience(seed=int(rng.integers(1 << 30)),
                                          dur=5.0, sr=sr) for _ in range(24)]
        return cls(wavs, sr)

    def sample(self, n: int) -> np.ndarray:
        """Random background slice of n samples (loop-padded)."""
        if not self.wavs:
            return synth.forest_ambience(seed=11, dur=n / self.sr, sr=self.sr)
        w = self.wavs[int(self._rng.integers(0, len(self.wavs)))]
        if len(w) <= n:
            reps = int(np.ceil(n / max(len(w), 1)))
            w = np.tile(w, reps)
        start = int(self._rng.integers(0, len(w) - n + 1))
        return np.array(w[start:start + n], dtype=np.float32)


def mix_at_snr(event: np.ndarray, background: np.ndarray, snr_db: float) -> np.ndarray:
    """Mix event over background at the given signal-to-noise ratio."""
    ev = np.asarray(event, dtype=np.float32)
    bg = np.asarray(background, dtype=np.float32)
    n = max(len(ev), len(bg))
    if len(ev) < n:
        ev = np.pad(ev, (0, n - len(ev)))
    if len(bg) < n:
        bg = np.pad(bg, (0, n - len(bg)))
    p_ev = float(np.sqrt(np.mean(ev ** 2)) + 1e-12)
    p_bg = float(np.sqrt(np.mean(bg ** 2)) + 1e-12)
    target_bg = p_ev / (10 ** (snr_db / 20.0))
    bg = bg * (target_bg / p_bg)
    return ev + bg


def random_gain(x: np.ndarray, rng: np.random.Generator,
                lo_db: float = -6.0, hi_db: float = 6.0) -> np.ndarray:
    g = 10.0 ** (rng.uniform(lo_db, hi_db) / 20.0)
    return (x * g).astype(np.float32)


def time_shift(x: np.ndarray, rng: np.random.Generator, max_frac: float = 0.2) -> np.ndarray:
    shift = int(len(x) * rng.uniform(-max_frac, max_frac))
    out = np.roll(x, shift)
    if shift > 0:
        out[:shift] = 0.0
    elif shift < 0:
        out[shift:] = 0.0
    return out


def pitch_shift(x: np.ndarray, rng: np.random.Generator, sr: int = TARGET_SR,
                max_semitones: float = 2.0) -> np.ndarray:
    import librosa
    semis = float(rng.uniform(-max_semitones, max_semitones))
    return np.asarray(librosa.effects.pitch_shift(y=x, sr=sr, n_steps=semis),
                      dtype=np.float32)


def phone_mic(x: np.ndarray, sr: int = TARGET_SR, rng: np.random.Generator | None = None) -> np.ndarray:
    """Cheap-microphone simulation: band-limit 150-5800 Hz + slight HF droop."""
    rng = rng or np.random.default_rng(0)
    X = np.fft.rfft(x)
    freqs = np.fft.rfftfreq(len(x), 1.0 / sr)
    mask = (freqs >= 150) & (freqs <= 5800)
    mask |= freqs < 150
    droop = 1.0 / (1.0 + (np.maximum(freqs - 3000, 0) / 2500.0) ** 2)
    y = np.fft.irfft(X * mask * droop, n=len(x))
    return y.astype(np.float32)


def wind_burst(x: np.ndarray, rng: np.random.Generator, sr: int = TARGET_SR,
               prob: float = 0.3) -> np.ndarray:
    """Overlay a wind gust with some probability."""
    if rng.uniform() > prob:
        return x
    start = int(rng.uniform(0, max(len(x) - sr, 1)))
    L = int(sr * rng.uniform(0.4, 1.2))
    gust = synth.wind(seed=int(rng.integers(1 << 30)), dur=L / sr, sr=sr)
    y = np.array(x, dtype=np.float32)
    end = min(start + len(gust), len(y))
    seg = gust[: end - start] * rng.uniform(0.3, 0.9)
    p_x = float(np.sqrt(np.mean(y[start:end] ** 2)) + 1e-12)
    p_g = float(np.sqrt(np.mean(seg ** 2)) + 1e-12)
    seg *= (p_x / p_g) * 10 ** (rng.uniform(-3, 0) / 20.0)
    y[start:end] += seg
    return y


def apply_distance(x: np.ndarray, distance_m: float, sr: int = TARGET_SR,
                   rng: np.random.Generator | None = None) -> np.ndarray:
    """Simulate distance: 1/r amplitude, air absorption HF roll-off, tiny reverb.

    Air absorption: cut frequency shrinks with distance (~ 6 dB/km at 1 kHz but
    much stronger above 4 kHz); implemented as a distance-dependent lowpass.
    """
    rng = rng or np.random.default_rng(0)
    d = max(float(distance_m), 0.5)
    atten = 1.0 / d
    cutoff = max(600.0, 8000.0 - 32.0 * d)   # crude air-absorption model
    X = np.fft.rfft(x)
    freqs = np.fft.rfftfreq(len(x), 1.0 / sr)
    H = 1.0 / (1.0 + (np.maximum(freqs - cutoff, 0) / (cutoff * 0.5)) ** 2)
    y = np.fft.irfft(X * H, n=len(x)).astype(np.float32)
    # simple early-reflection reverb: 2 taps
    y = y * atten
    for delay_ms, gain in ((37.0, 0.25), (71.0, 0.12)):
        d_samp = int(sr * delay_ms / 1000.0)
        tap = np.zeros_like(y)
        tap[d_samp:] = y[:-d_samp] if d_samp < len(y) else 0.0
        y = y + tap * gain
    return y.astype(np.float32)


def augment_event(event: np.ndarray, bank: BackgroundBank, rng: np.random.Generator,
                  snr_range_db: tuple[float, float] = (-5.0, 20.0),
                  distance_m: float | None = None, sr: int = TARGET_SR) -> np.ndarray:
    """Full augmentation chain for one event clip (output length == event)."""
    x = np.asarray(event, dtype=np.float32)
    x = random_gain(x, rng)
    if rng.uniform() < 0.5:
        x = pitch_shift(x, rng, sr=sr)
    x = time_shift(x, rng)
    if distance_m is not None:
        x = apply_distance(x, distance_m, sr=sr, rng=rng)
    if rng.uniform() < 0.6:
        x = phone_mic(x, sr=sr, rng=rng)
    n = len(x)
    bg = bank.sample(n)
    snr = float(rng.uniform(*snr_range_db))
    mixed = mix_at_snr(x, bg, snr)
    mixed = wind_burst(mixed, rng, sr=sr)
    return np.clip(mixed, -1.0, 1.0).astype(np.float32)
