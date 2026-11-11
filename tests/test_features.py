"""Feature extraction contract tests: log-mel 64x98, normalisation,
handcrafted descriptors, YAMNet hub/tflite parity."""
from __future__ import annotations

import numpy as np
import pytest

from ml.features import (FRAME_COUNT, HOP_LEN, N_MELS, SR, WINDOW_S, Handcrafted,
                         handcrafted, log_mel, logmel_tf_input)

try:
    import librosa
    HAS_LIBROSA = True
except ImportError:  # pragma: no cover
    HAS_LIBROSA = False


# ------------------------------------------------------------------ log-mel
class TestLogMel:
    def test_shape_1s(self, sine_1k):
        lm = log_mel(sine_1k)
        assert lm.shape == (N_MELS, FRAME_COUNT)  # (64, 98)
        assert lm.dtype == np.float32

    def test_short_input_padded_to_same_shape(self):
        lm = log_mel(np.zeros(4000, dtype=np.float32))
        assert lm.shape == (N_MELS, FRAME_COUNT)

    def test_long_input_truncated(self):
        lm = log_mel(np.zeros(SR * 3, dtype=np.float32))
        # >1 s inputs are truncated: every model must see (64, 98)
        assert lm.shape == (N_MELS, FRAME_COUNT)

    def test_per_window_normalisation(self, white_noise):
        lm = log_mel(white_noise)
        assert abs(float(lm.mean())) < 1e-3
        assert abs(float(lm.std()) - 1.0) < 1e-2

    def test_no_norm_keeps_raw_scale(self, sine_1k):
        raw = log_mel(sine_1k, per_window_norm=False)
        norm = log_mel(sine_1k, per_window_norm=True)
        assert np.isfinite(raw).all()
        assert float(norm.std()) == pytest.approx(1.0, abs=1e-2)
        assert float(raw.std()) != pytest.approx(1.0, abs=1e-2)

    def test_deterministic(self, rng):
        w = rng.uniform(-1, 1, SR).astype(np.float32)
        assert np.array_equal(log_mel(w), log_mel(w.copy()))

    def test_sine_dominant_bin_near_1khz(self, sine_1k):
        if not HAS_LIBROSA:
            pytest.skip("librosa unavailable")
        lm = log_mel(sine_1k, per_window_norm=False)
        mel_freqs = librosa.mel_frequencies(n_mels=N_MELS, fmin=50, fmax=7800,
                                           htk=False)
        dom = mel_freqs[int(np.argmax(lm.mean(axis=1)))]
        assert abs(dom - 1000.0) < 350.0, f"dominant mel bin at {dom:.0f} Hz"

    def test_tf_input_layout(self, sine_1k):
        lm = log_mel(sine_1k)
        x = logmel_tf_input(lm)
        assert x.shape == (FRAME_COUNT, N_MELS, 1)   # (98, 64, 1) conv layout
        assert x.dtype == np.float32


# ------------------------------------------------------------- handcrafted
class TestHandcrafted:
    def test_sine_metrics(self, sine_1k):
        h = handcrafted(sine_1k)
        assert isinstance(h, Handcrafted)
        assert -9.5 < h.rms_db < -8.5           # 0.5 amp sine: rms 0.354 -> ~-9 dBFS
        assert h.crest > 1.3                     # sine crest = sqrt(2)
        assert 0.0 <= h.zcr <= 1.0
        assert h.spectral_flatness < 0.1         # tonal, not noisy
        v = h.as_vector()
        assert v.shape == (6,) and v.dtype == np.float32

    def test_noise_is_flat_and_high_zcr(self, white_noise):
        h = handcrafted(white_noise)
        assert h.spectral_flatness > 0.3
        assert h.zcr > 0.4

    def test_silence_does_not_nan(self):
        h = handcrafted(np.zeros(SR, dtype=np.float32))
        v = h.as_vector()
        assert np.all(np.isfinite(v))
        assert h.rms_db < -180.0                  # floor of the +1e-10 guard


# ------------------------------------------------------------- YAMNet parity
HUB_DIR = __import__("pathlib").Path(__file__).resolve().parents[1] / "models_store" / "m1_yamnet"


@pytest.mark.skipif(not (HUB_DIR / "saved_model.pb").exists()
                    or not (HUB_DIR / "lite-model_yamnet_tflite_1.tflite").exists(),
                    reason="YAMNet hub/tflite models not downloaded")
class TestYamNetParity:
    @pytest.fixture(scope="class")
    def yam_pair(self):
        from ml.features import YamNet
        return (YamNet(backend="hub", model_dir=str(HUB_DIR)),
                YamNet(backend="tflite", model_dir=str(HUB_DIR)))

    def test_shapes(self, yam_pair, sine_1k):
        hub, tfl = yam_pair
        s_h, e_h = hub(sine_1k)
        s_t, e_t = tfl(sine_1k)
        assert s_h.shape == (521,) and s_t.shape == (521,)
        assert e_h.shape == (1024,) and e_t.shape == (1024,)

    def test_scores_agree_within_tolerance(self, yam_pair, sine_1k, white_noise):
        hub, tfl = yam_pair
        for wav in (sine_1k, white_noise):
            s_h, e_h = hub(wav)
            s_t, e_t = tfl(wav)
            # scores are sigmoid-ish probabilities -> same tolerance as export
            assert np.max(np.abs(s_h - s_t)) < 0.05, "class scores diverge"
            # embeddings compared on cosine similarity (scale-free)
            cos = float(np.dot(e_h, e_t) /
                        (np.linalg.norm(e_h) * np.linalg.norm(e_t) + 1e-9))
            assert cos > 0.985, f"embedding cosine {cos:.4f}"

    def test_partial_window_padded(self, yam_pair):
        hub, _ = yam_pair
        s, e = hub(np.zeros(8000, dtype=np.float32))
        assert s.shape == (521,) and e.shape == (1024,)

    def test_selected_scores_keys(self, yam_pair, sine_1k):
        hub, _ = yam_pair
        from common.config import load_config
        names = load_config()["features"]["yamnet_selected_classes"]
        sel = hub.selected_scores(sine_1k, names)
        assert set(sel.keys()) == set(names)
        assert all(0.0 <= v <= 1.0 for v in sel.values())
