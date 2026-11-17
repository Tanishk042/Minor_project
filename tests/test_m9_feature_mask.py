"""The M6 mask is part of the M9 feature contract, not a local magic number."""
from __future__ import annotations

import numpy as np

from ml.models.m9_fusion import (M6_FEATURE_INDICES, N_FEATURES, OFFSET,
                                 TEMPORAL_MODELS, zero_m6_features)


def test_m6_indices_match_feature_contract() -> None:
    temporal_start = OFFSET["temporal"] + TEMPORAL_MODELS.index("m6") * 3
    assert M6_FEATURE_INDICES.tolist() == [
        OFFSET["m6"], temporal_start, temporal_start + 1, temporal_start + 2,
    ]
    assert M6_FEATURE_INDICES.max() < N_FEATURES
    # the four slots are distinct and do not touch M7's direct score
    assert len(set(M6_FEATURE_INDICES.tolist())) == 4
    assert OFFSET["m7"] not in M6_FEATURE_INDICES


def test_zero_m6_features_keeps_47_slot_contract() -> None:
    X = np.arange(8 * N_FEATURES, dtype=np.float64).reshape(8, N_FEATURES)
    out = zero_m6_features(X)
    assert out.shape == X.shape
    assert np.all(out[:, M6_FEATURE_INDICES] == 0.0)
    keep = [i for i in range(N_FEATURES) if i not in M6_FEATURE_INDICES]
    assert np.array_equal(out[:, keep], X[:, keep])
    # default is a copy; the caller's array is unchanged
    assert X[0, M6_FEATURE_INDICES[0]] != 0.0
