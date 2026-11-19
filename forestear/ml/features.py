"""Feature extraction shared by training and the edge agent.

THE SAME FUNCTIONS run at train time and at inference time (unit-tested in
tests/test_features.py): log-mel (64x98), handcrafted descriptors, and the
YAMNet wrapper (521 class scores + 1024-d embedding).

Log-mel spec: 64 mel bins, 25 ms window, 10 ms hop, fmin 50 Hz, fmax 7800 Hz,
log(mel + 1e-6), per-window normalisation, centre=False so a 1.0 s window at
16 kHz yields exactly 64 x 98 (spec Section 4).
"""
from __future__ import annotations

import logging
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import librosa

logger = logging.getLogger("forestear.features")

SR = 16000
N_MELS = 64
WIN_MS = 25
HOP_MS = 10
FMIN = 50
FMAX = 7800
LOG_OFFSET = 1e-6

N_FFT = 512            # 32 ms at 16 kHz; next pow2 above 25 ms
WIN_LEN = int(SR * WIN_MS / 1000)   # 400 samples
HOP_LEN = int(SR * HOP_MS / 1000)   # 160 samples
WINDOW_S = 1.0
HOP_S = 0.5
FRAME_COUNT = 98       # frames in a 1.0 s window with centre=False


# ---------------------------------------------------------------------------
# log-mel
# ---------------------------------------------------------------------------

def log_mel(wav: np.ndarray, n_mels: int = N_MELS, fmin: int = FMIN, fmax: int = FMAX,
            per_window_norm: bool = True, sr: int = SR) -> np.ndarray:
    """Log-mel spectrogram of a <=1 s waveform. Returns (n_mels, frames).

    A 1.0 s @16 kHz window gives (64, 98): the waveform is padded by one hop
    (160 samples -> 16160) so librosa's centre=False STFT yields exactly
    1 + floor((16160-512)/160) = 98 frames, matching the spec. Shorter inputs
    are padded and longer inputs are TRUNCATED to 16160 so every model always
    sees exactly the same shape (total function, verified by unit test).
    """
    wav = np.asarray(wav, dtype=np.float32)
    need = int(sr * WINDOW_S) + HOP_LEN
    if len(wav) < need:
        wav = np.pad(wav, (0, need - len(wav)))
    elif len(wav) > need:
        wav = wav[:need]
    mel = librosa.feature.melspectrogram(
        y=wav, sr=sr, n_fft=N_FFT, hop_length=HOP_LEN, win_length=WIN_LEN,
        n_mels=n_mels, fmin=fmin, fmax=fmax, center=False, power=2.0,
    )
    logmel = np.log(mel + LOG_OFFSET).astype(np.float32)
    if per_window_norm:
        logmel = _normalize(logmel)
    return logmel


def _normalize(logmel: np.ndarray) -> np.ndarray:
    """Per-window normalisation: zero-mean, unit-variance over the whole window."""
    mu = float(logmel.mean())
    sd = float(logmel.std() + 1e-6)
    return (logmel - mu) / sd


def logmel_tf_input(logmel: np.ndarray) -> np.ndarray:
    """(n_mels, frames) -> (frames, n_mels, 1) float32 for conv models."""
    x = np.asarray(logmel, dtype=np.float32)
    if x.ndim == 2:
        x = x.T
    return np.expand_dims(x, axis=-1)


# ---------------------------------------------------------------------------
# handcrafted
# ---------------------------------------------------------------------------

@dataclass
class Handcrafted:
    """Handcrafted descriptors of one analysis window (all float32 scalars)."""
    rms_db: float = 0.0
    crest: float = 0.0
    spectral_flux: float = 0.0
    spectral_flatness: float = 0.0
    onset_strength: float = 0.0
    zcr: float = 0.0

    def as_vector(self) -> np.ndarray:
        return np.array(
            [self.rms_db, self.crest, self.spectral_flux, self.spectral_flatness,
             self.onset_strength, self.zcr], dtype=np.float32)


def handcrafted(wav: np.ndarray, sr: int = SR) -> Handcrafted:
    """RMS dB, crest factor, spectral flux/flatness, onset strength, ZCR."""
    wav64 = np.asarray(wav, dtype=np.float64)
    rms = float(np.sqrt(np.mean(wav64 ** 2) + 1e-20))
    peak = float(np.max(np.abs(wav64)) + 1e-10)
    crest = peak / (rms + 1e-10)
    # spectral flux: mean positive change of magnitude spectrogram
    S = np.abs(librosa.stft(wav64, n_fft=N_FFT, hop_length=HOP_LEN, center=False))
    if S.shape[1] > 1:
        flux = float(np.mean(np.maximum(0.0, np.diff(S, axis=1))))
    else:
        flux = 0.0
    flat = float(np.mean(librosa.feature.spectral_flatness(y=wav64, n_fft=N_FFT,
                                                          hop_length=HOP_LEN, center=False)))
    onset = float(np.mean(librosa.onset.onset_strength(
        y=wav64, sr=sr, n_fft=N_FFT, hop_length=HOP_LEN, center=False)))
    zcr = float(np.mean(librosa.feature.zero_crossing_rate(wav64, frame_length=WIN_LEN,
                                                            hop_length=HOP_LEN, center=False)))
    return Handcrafted(
        rms_db=20.0 * np.log10(rms + 1e-10), crest=crest, spectral_flux=flux,
        spectral_flatness=flat, onset_strength=onset, zcr=zcr,
    )


# ---------------------------------------------------------------------------
# YAMNet wrapper
# ---------------------------------------------------------------------------

class YamNet:
    """YAMNet wrapper with two interchangeable backends.

    - backend="hub": TF-Hub SavedModel (training-time; needs tensorflow_hub).
    - backend="tflite": the official Google TFLite builds (edge deployment):
        * classification model -> 521 class scores
        * embedding model     -> 1024-d embedding
    Both produce, for one 1.0 s @16 kHz waveform:
        scores: (521,) mean over internal frames
        embedding: (1024,) mean over internal frames
    A parity unit test guarantees the two backends agree within tolerance.
    """

    def __init__(self, backend: str = "hub", model_dir: str | Path | None = None):
        self.backend = backend
        self._classes: np.ndarray | None = None
        if backend == "hub":
            # NOTE: we load the TF-Hub *SavedModel* directly with
            # tf.saved_model.load (hub.load equivalent). This avoids the
            # tensorflow-hub package, whose pkg_resources import breaks on
            # modern setuptools. The downloaded tarball under
            # models_store/m1_yamnet IS the hub module.
            import tensorflow as tf
            mdir = str(model_dir or "https://tfhub.dev/google/yamnet/1")
            if mdir.startswith("http"):
                raise ValueError(
                    "hub backend expects the local models_store/m1_yamnet dir "
                    "(downloaded by scripts/download_data.py)")
            self._model = tf.saved_model.load(mdir)
            self._infer = self._model.signatures["serving_default"]
        elif backend == "tflite":
            import tensorflow as tf
            base = Path(model_dir) if model_dir else None
            if base is None:
                raise ValueError("tflite backend needs model_dir with the .tflite file")
            # The full YAMNet TFLite build exposes BOTH scores (521) and
            # embedding (1024) from a 1-D 15600-sample input; one interpreter.
            emb_path = None
            for p in base.glob("*.tflite"):
                if p.name == "lite-model_yamnet_tflite_1.tflite":
                    emb_path = p
            if emb_path is None:
                raise FileNotFoundError(f"YAMNet tflite missing in {base}")
            self._interp = tf.lite.Interpreter(model_path=str(emb_path), num_threads=2)
            self._interp.allocate_tensors()
        else:
            raise ValueError(f"unknown YAMNet backend {backend!r}")

    @staticmethod
    def class_names(model_dir: str | Path | None = None) -> list[str]:
        """AudioSet class names in YAMNet order."""
        if model_dir is not None and Path(model_dir, "yamnet_class_map.csv").exists():
            import csv
            with open(Path(model_dir, "yamnet_class_map.csv"), "r", encoding="utf-8") as f:
                return [row["display_name"] for row in csv.DictReader(f)]
        # fallback: fetch from the hub cache is unreliable; ship a copy instead
        here = Path(__file__).resolve().parent / "yamnet_class_map.csv"
        import csv
        with open(here, "r", encoding="utf-8") as f:
            return [row["display_name"] for row in csv.DictReader(f)]

    def __call__(self, wav: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        """Returns (scores (521,), embedding (1024,)) for one <=1 s waveform.

        Both backends analyse exactly the first 15600 samples (0.975 s) so
        hub and TFLite produce numerically comparable outputs (parity test).
        """
        wav = np.asarray(wav, dtype=np.float32)
        if len(wav) < SR:  # pad partial windows
            wav = np.pad(wav, (0, SR - len(wav)))
        core = wav[:15600] if len(wav) >= 15600 else np.pad(wav, (0, 15600 - len(wav)))
        if self.backend == "hub":
            import tensorflow as tf
            out = self._infer(tf.constant(core))
            # hub SavedModel output names: output_0=scores, output_1=embedding
            scores = np.asarray(out["output_0"], dtype=np.float32)   # (frames, 521)
            emb = np.asarray(out["output_1"], dtype=np.float32)      # (frames, 1024)
            return scores.mean(axis=0), emb.mean(axis=0)
        # tflite: 1-D 15600-sample input, outputs (1,521) scores, (1,1024) emb
        core = np.asarray(core, dtype=np.float32).reshape(-1)
        inp = self._interp.get_input_details()[0]
        self._interp.resize_tensor_input(inp["index"], [len(core)])
        self._interp.allocate_tensors()
        self._interp.set_tensor(inp["index"], core)
        self._interp.invoke()
        outs = self._interp.get_output_details()
        scores = None
        emb = None
        for od in outs:
            v = np.asarray(self._interp.get_tensor(od["index"]), dtype=np.float32)
            if v.size == 521:
                scores = v.reshape(-1, 521).mean(axis=0)
            elif v.size == 1024:
                emb = v.reshape(-1, 1024).mean(axis=0)
        if scores is None or emb is None:
            raise RuntimeError(f"YAMNet tflite unexpected outputs (need 521 + 1024)")
        return scores, emb

    @staticmethod
    def _run_tflite(interpreter, x: np.ndarray) -> np.ndarray:  # unused now, kept for tests
        inp = interpreter.get_input_details()[0]
        outp = interpreter.get_output_details()[0]
        shape = list(inp["shape"])
        if len(shape) == 2 and shape[0] in (1, -1):
            x = x.reshape(1, -1)
        interpreter.set_tensor(inp["index"], np.asarray(x, dtype=np.float32))
        interpreter.invoke()
        return np.asarray(interpreter.get_tensor(outp["index"]))

    def selected_scores(self, wav: np.ndarray, names: list[str]) -> dict[str, float]:
        """Map of {class_name: score} for the AudioSet classes listed in config."""
        scores, _ = self(wav)
        if self._classes is None:
            self._classes = np.array(self.class_names())
        idx = {n: int(np.where(self._classes == n)[0][0])
               for n in names if n in self._classes}
        return {n: float(scores[i]) for n, i in idx.items()}
