"""Evaluation metric helper tests (sklearn-free implementations verified
against hand-computed ground truth)."""
from __future__ import annotations

import numpy as np
import pytest

from scripts.evaluate import (average_precision, auroc, class_of, ece_brier,
                              prf, source_of)


class TestPRF:
    def test_perfect_predictions(self):
        y = np.array([0, 1, 2, 3, 4])
        m = prf(y, y.copy())
        assert m["accuracy"] == 1.0
        assert m["macro_f1"] == 1.0
        for c in m["per_class"].values():
            assert c["precision"] == 1.0 and c["recall"] == 1.0

    def test_hand_computed_case(self):
        # class 0 (chainsaw): 2 true, 1 predicted -> P=1/1? no:
        # pred 0 for one true-0 and one true-1 -> P=1/2, R=1/2, F1=1/2
        y_true = np.array([0, 0, 1, 1, 2])
        y_pred = np.array([0, 1, 0, 1, 2])
        m = prf(y_true, y_pred)
        p0 = m["per_class"]["chainsaw"]
        assert p0["tp"] == 1 and p0["fp"] == 1 and p0["fn"] == 1
        assert p0["precision"] == 0.5 and p0["recall"] == 0.5
        assert p0["f1"] == pytest.approx(0.5)
        acc = 3 / 5
        assert m["accuracy"] == pytest.approx(acc)
        assert m["n"] == 5

    def test_missing_class_zero_division(self):
        y = np.array([0, 0, 1])
        m = prf(y, np.array([0, 0, 1]))
        # classes 2,3,4 have no support and no predictions
        for c in ("vehicle", "human", "benign"):
            assert m["per_class"][c]["precision"] == 0.0
            assert m["per_class"][c]["support"] == 0


class TestAveragePrecision:
    def test_perfect_ranking(self):
        assert average_precision(np.array([1, 1, 0, 0]),
                                 np.array([0.9, 0.8, 0.2, 0.1])) == 1.0

    def test_all_positives_at_bottom(self):
        # positives ranked last -> precisions 1/3, 2/4 -> AP = 5/12
        ap = average_precision(np.array([0, 0, 1, 1]),
                               np.array([0.9, 0.8, 0.2, 0.1]))
        assert ap == pytest.approx((1 / 3 + 2 / 4) / 2)

    def test_hand_computed(self):
        # ranking: hit@1, miss@2, hit@3 -> prec = [1/1, 2/3] -> mean 0.8333
        ap = average_precision(np.array([1, 0, 1]), np.array([0.9, 0.8, 0.7]))
        assert ap == pytest.approx((1.0 + 2 / 3) / 2)

    def test_no_positives(self):
        assert average_precision(np.array([0, 0]), np.array([0.9, 0.1])) == 0.0


class TestAUROC:
    def test_separable(self):
        assert auroc(np.array([1, 1, 0, 0]),
                     np.array([0.9, 0.8, 0.2, 0.1])) == 1.0

    def test_inverted(self):
        assert auroc(np.array([0, 0, 1, 1]),
                     np.array([0.9, 0.8, 0.2, 0.1])) == 0.0

    def test_ties(self):
        # one positive, one negative, identical scores -> coin flip
        assert auroc(np.array([1, 0]), np.array([0.5, 0.5])) == 0.5
        # all tied -> 0.5
        assert auroc(np.array([1, 1, 0, 0]),
                     np.array([0.5, 0.5, 0.5, 0.5])) == 0.5

    def test_sklearn_crosscheck(self):
        sklearn = pytest.importorskip("sklearn.metrics")
        rng = np.random.default_rng(7)
        y = rng.integers(0, 2, 300)
        s = rng.uniform(0, 1, 300) + 0.3 * y
        assert auroc(y, s) == pytest.approx(
            sklearn.roc_auc_score(y, s), abs=1e-9)

    def test_sklearn_crosscheck_with_ties(self):
        sklearn = pytest.importorskip("sklearn.metrics")
        rng = np.random.default_rng(11)
        y = rng.integers(0, 2, 500)
        s = np.round(rng.uniform(0, 1, 500), 1)   # quantised -> many ties
        assert auroc(y, s) == pytest.approx(
            sklearn.roc_auc_score(y, s), abs=1e-9)


class TestCalibration:
    def test_perfect_confidence(self):
        # one-hot predictions, confidence 1.0, always right
        probs = np.eye(5)[np.array([0, 1, 2])]
        y = np.array([0, 1, 2])
        ece, brier, rel = ece_brier(probs, y)
        assert ece == 0.0
        assert brier == pytest.approx(0.0, abs=1e-9)
        assert rel[-1]["n"] == 3        # all in the top (0.9, 1.0] bin

    def test_overconfident(self):
        # predicts wrong class with confidence 1.0
        probs = np.eye(5)[np.array([1, 1])]
        y = np.array([0, 0])
        ece, brier, _ = ece_brier(probs, y)
        assert ece == pytest.approx(1.0)   # |0 - 1| in the only occupied bin
        assert brier == pytest.approx(2.0)  # both wrong one-hots: 1+1

    def test_bin_counts_partition(self):
        rng = np.random.default_rng(3)
        probs = rng.dirichlet(np.ones(5), size=200)
        y = rng.integers(0, 5, 200)
        _, _, rel = ece_brier(probs, y)
        assert sum(b["n"] for b in rel) == 200
        assert 0.0 <= ece_brier(probs, y)[0] <= 1.0


class TestSourceClassOf:
    def test_source_of(self):
        assert source_of("esc50-1-100032-A-0") == "esc50"
        assert source_of("us8k-100032.wav") == "us8k"
        assert source_of("fsd-64760") == "fsd"
        assert source_of("syn-chainsaw-3") == "syn"
        assert source_of("weird-origin") == "unknown"

    def test_class_of(self):
        assert class_of("weather_rain") == "benign"
        assert class_of("weather_wind") == "benign"
        assert class_of("weather_thunder") == "benign"
        assert class_of("benign") == "benign"
        assert class_of("chainsaw") == "chainsaw"
        assert class_of("gunshot") == "gunshot"
