"""Synthetic sound generator: fallback data when downloads fail, and the
waveform source library for the forest simulator.

These are physically-motivated but clearly *synthetic* signals:
- gunshot: exponentially decaying broadband impulse + resonances
- chainsaw: sawtooth ~90-115 Hz with harmonics, amplitude buzz, wobble
- vehicle: engine fundamental + harmonics + broadband rumble, slow AM
- human voice-like: formant-filtered pulse train (NO speech content)
- thunder / rain / wind / birds: texture generators for weather + benign
Every generator is seeded for reproducibility.
"""
from __future__ import annotations

import numpy as np

SR = 16000


def _rng(seed: int | np.random.Generator | None):
    if isinstance(seed, np.random.Generator):
        return seed
    return np.random.default_rng(seed)


def noise(n: int, rng: np.random.Generator) -> np.ndarray:
    return rng.standard_normal(n).astype(np.float32)


def _lowpass(x: np.ndarray, sr: int, cutoff_hz: float) -> np.ndarray:
    """One-pole lowpass (cheap, stable)."""
    a = float(np.exp(-2.0 * np.pi * cutoff_hz / sr))
    y = np.empty_like(x)
    acc = 0.0
    for i, v in enumerate(x):
        acc = (1.0 - a) * v + a * acc
        y[i] = acc
    return y


def _fast_lowpass(x: np.ndarray, sr: int, cutoff_hz: float) -> np.ndarray:
    """Vectorised approximation of a one-pole lowpass via FFT brick-wall."""
    X = np.fft.rfft(x)
    freqs = np.fft.rfftfreq(len(x), 1.0 / sr)
    X[freqs > cutoff_hz] = 0.0
    return np.fft.irfft(X, n=len(x)).astype(np.float32)


def gunshot(seed: int | None = None, dur: float = 0.9, sr: int = SR,
            brightness: float = 1.0) -> np.ndarray:
    """Impulsive crack: sharp broadband transient, short echo tail, muzzle thump."""
    rng = _rng(seed)
    n = int(dur * sr)
    t = np.arange(n) / sr
    # dominant broadband crack: very fast attack, ~8 ms decay
    burst = noise(n, rng) * np.exp(-t / 0.008)
    # a couple of early reflections (outdoor echo)
    for d_ms, g in ((28.0, 0.35), (61.0, 0.18), (103.0, 0.09)):
        d = int(sr * d_ms / 1000)
        if d < n:
            burst[d:] += g * noise(n - d, rng) * np.exp(-t[: n - d] / 0.008)
    # low muzzle thump (sub-bass pulse)
    thump = np.sin(2 * np.pi * rng.uniform(55, 75) * t) * np.exp(-t / 0.08)
    x = 2.2 * burst + 0.35 * thump
    x[: int(0.0005 * sr)] *= np.linspace(0, 1, int(0.0005 * sr))
    if brightness < 1.0:
        x = _fast_lowpass(x, sr, 1500 + 5500 * brightness)
    x = x / (np.max(np.abs(x)) + 1e-9)
    return x.astype(np.float32)


def _saw(f0: float, n: int, sr: int) -> np.ndarray:
    """Band-limited-ish sawtooth via naive + lowpass."""
    t = np.arange(n) / sr
    saw = 2.0 * ((t * f0) % 1.0) - 1.0
    return saw


def chainsaw(seed: int | None = None, dur: float = 1.0, sr: int = SR,
             f0: float | None = None) -> np.ndarray:
    """Harmonic engine-like buzz: sawtooth + harmonics + 30 Hz cutting AM + wobble."""
    rng = _rng(seed)
    n = int(dur * sr)
    t = np.arange(n) / sr
    f = f0 if f0 is not None else rng.uniform(92, 112)
    wobble = 1.0 + 0.015 * np.sin(2 * np.pi * rng.uniform(0.8, 1.6) * t)
    phase = 2 * np.pi * f * np.cumsum(np.ones(n) / sr * wobble)
    x = 0.0
    for h in range(1, 9):
        x += np.sin(h * phase) / h
    # cutting-load amplitude modulation
    am = 1.0 + 0.25 * np.sin(2 * np.pi * rng.uniform(22, 34) * t)
    x = x * am
    # broadband mechanical noise
    x = x + 0.25 * noise(n, rng) * (0.4 + 0.6 * am)
    x = _fast_lowpass(x, sr, 3800)
    x = x / (np.max(np.abs(x)) + 1e-9)
    return x.astype(np.float32)


def vehicle(seed: int | None = None, dur: float = 1.0, sr: int = SR,
            f0: float | None = None) -> np.ndarray:
    """Engine: firing-rate roughness (comb AM), harmonics, combustion noise."""
    rng = _rng(seed)
    n = int(dur * sr)
    t = np.arange(n) / sr
    f = f0 if f0 is not None else rng.uniform(38, 65)
    # harmonic engine tone
    x = np.zeros(n)
    for h in range(1, 6):
        x += np.sin(2 * np.pi * f * h * t + rng.uniform(0, 2 * np.pi)) / h
    # firing-rate amplitude modulation -> perceived roughness (4-cyl ~ 2x f0)
    fire = 2.0 * f
    am = 1.0 + 0.45 * np.sign(np.sin(2 * np.pi * fire * t))
    x *= am
    # combustion / road broadband noise, band-limited
    rumble = _fast_lowpass(noise(n, rng), sr, 500)
    x = x + 1.2 * rumble * (0.6 + 0.4 * am)
    # slow doppler-ish level drift
    x *= 1.0 + 0.1 * np.sin(2 * np.pi * rng.uniform(1.5, 3.5) * t)
    x = x / (np.max(np.abs(x)) + 1e-9)
    return x.astype(np.float32)


def voice_like(seed: int | None = None, dur: float = 1.0, sr: int = SR) -> np.ndarray:
    """Human-presence proxy: vowel-like formant-filtered glottal pulse train.

    Contains no linguistic content; used ONLY to train/evaluate human-presence
    detection and never stored or transmitted as speech.
    """
    rng = _rng(seed)
    n = int(dur * sr)
    t = np.arange(n) / sr
    f0 = rng.uniform(105, 190)
    # glottal pulses
    pulses = np.zeros(n)
    period = sr / f0
    jitter = 0.02
    idx = 0.0
    while idx < n - 1:
        i = int(idx)
        pulses[min(i + int(period * 0.08), n - 1)] = 1.0  # narrow pulse
        idx += period * (1.0 + jitter * rng.standard_normal() * 0.1)
    X = np.fft.rfft(pulses, n=n)
    freqs = np.fft.rfftfreq(n, 1.0 / sr)
    # two-three formants drifting slowly (vowel movement)
    f1 = rng.uniform(300, 800)
    f2 = rng.uniform(900, 2400)
    f3 = rng.uniform(2400, 3200)
    H = np.zeros_like(freqs)
    for f_c, q in ((f1, 8.0), (f2, 10.0), (f3, 12.0)):
        H += 1.0 / (1.0 + ((freqs - f_c) / (f_c / q)) ** 2)
    y = np.fft.irfft(X * H, n=n)
    # syllabic envelope
    env = 0.55 + 0.45 * np.abs(np.sin(2 * np.pi * rng.uniform(1.5, 3.2) * t + rng.uniform(0, 3)))
    y *= env
    y = y / (np.max(np.abs(y)) + 1e-9)
    return y.astype(np.float32)


def thunder(seed: int | None = None, dur: float = 2.5, sr: int = SR) -> np.ndarray:
    """Deep rumble: sub-100 Hz noise, slow swell, long multi-stage tail."""
    rng = _rng(seed)
    n = int(dur * sr)
    t = np.arange(n) / sr
    # multi-stage envelope: crack -> roll -> fade
    crack = _fast_lowpass(noise(n, rng), sr, 900) * np.exp(-t / 0.03) * 0.9
    roll = _fast_lowpass(noise(n, rng), sr, 110) \
        * (t / (dur * 0.18)) * np.exp(-t / (dur / 2.8))
    roll[t > dur * 0.18] = _fast_lowpass(noise(n, rng), sr, 110)[t > dur * 0.18] \
        * np.exp(-(t[t > dur * 0.18] - dur * 0.18) / (dur / 2.8))
    x = crack + 1.6 * roll
    # low-frequency pressure wave
    x += 0.4 * np.sin(2 * np.pi * rng.uniform(28, 45) * t) * np.exp(-t / (dur / 3))
    x = x / (np.max(np.abs(x)) + 1e-9)
    return x.astype(np.float32)


def rain(seed: int | None = None, dur: float = 2.0, sr: int = SR) -> np.ndarray:
    """High-frequency droplet texture: filtered noise + random ticks."""
    rng = _rng(seed)
    n = int(dur * sr)
    x = _fast_lowpass(noise(n, rng), sr, 7000)
    hp = x - _fast_lowpass(x, sr, 400)   # keep mid-highs
    ticks = np.zeros(n)
    n_ticks = int(dur * sr * 0.02)
    for i in rng.integers(0, n, n_ticks):
        L = int(sr * 0.004)
        seg = noise(L, rng) * np.exp(-np.arange(L) / (sr * 0.001))
        end = min(i + L, n)
        ticks[i:end] += seg[: end - i]
    x = 0.7 * hp + 0.5 * ticks
    x = x / (np.max(np.abs(x)) + 1e-9)
    return x.astype(np.float32)


def wind(seed: int | None = None, dur: float = 2.0, sr: int = SR) -> np.ndarray:
    """Gusty low-frequency noise with slow envelope."""
    rng = _rng(seed)
    n = int(dur * sr)
    t = np.arange(n) / sr
    x = _fast_lowpass(noise(n, rng), sr, 500)
    gust = (1.0 + 0.9 * np.sin(2 * np.pi * 0.35 * t + rng.uniform(0, 6))
            * (0.5 + 0.5 * np.sin(2 * np.pi * 0.13 * t)))
    x *= gust
    x = x / (np.max(np.abs(x)) + 1e-9)
    return x.astype(np.float32)


def birds(seed: int | None = None, dur: float = 2.0, sr: int = SR) -> np.ndarray:
    """Chirp sequence: FM sweeps (benign / biodiversity)."""
    rng = _rng(seed)
    n = int(dur * sr)
    x = np.zeros(n, dtype=np.float32)
    t_chirp = 0
    while t_chirp < n:
        L = int(sr * rng.uniform(0.06, 0.18))
        f0 = rng.uniform(2500, 6000)
        f1 = f0 * rng.uniform(0.7, 1.5)
        t = np.arange(L) / sr
        ph = 2 * np.pi * (f0 * t + 0.5 * (f1 - f0) * t ** 2 / max(t[-1], 1e-6) * 2)
        chirp = np.sin(ph).astype(np.float32) * np.hanning(L).astype(np.float32)
        end = min(t_chirp + L, n)
        x[t_chirp:end] += chirp[: end - t_chirp] * rng.uniform(0.4, 1.0)
        t_chirp += int(sr * rng.uniform(0.12, 0.5))
    x = x / (np.max(np.abs(x)) + 1e-9)
    return x.astype(np.float32)


def forest_ambience(seed: int | None = None, dur: float = 5.0, sr: int = SR) -> np.ndarray:
    """Benign forest texture: gentle wind + distant birds + insects."""
    rng = _rng(seed)
    n = int(dur * sr)
    g = _rng(seed)
    amb = 0.35 * wind(seed=int(g.integers(1 << 30)), dur=dur, sr=sr)
    b = birds(seed=int(g.integers(1 << 30)), dur=dur, sr=sr)
    # insects: steady high tone band
    t = np.arange(n) / sr
    ins = _fast_lowpass(noise(n, rng), sr, 6500) - _fast_lowpass(noise(n, rng), sr, 5000)
    ins *= 0.15 * (1.0 + 0.2 * np.sin(2 * np.pi * 1.3 * t))
    x = amb + 0.22 * b + ins
    x = x / (np.max(np.abs(x)) + 1e-9)
    return x.astype(np.float32)


SYNTH_CLASSES = {
    "gunshot": gunshot,
    "chainsaw": chainsaw,
    "vehicle": vehicle,
    "human": voice_like,
    "thunder": thunder,
    "rain": rain,
    "wind": wind,
    "birds": birds,
    "ambience": forest_ambience,
}
