"""Fold-discipline tests: split_of mapping and leakage guards."""
from __future__ import annotations

import pandas as pd
import pytest

from ml.window_cache import split_of


class TestSplitOf:
    def test_esc50_folds(self):
        assert split_of({"source": "esc50", "fold": 1}) == "train"
        assert split_of({"source": "esc50", "fold": 3}) == "train"
        assert split_of({"source": "esc50", "fold": 4}) == "val"
        assert split_of({"source": "esc50", "fold": 5}) == "test"

    def test_us8k_folds(self):
        assert split_of({"source": "urbansound8k", "fold": 1}) == "train"
        assert split_of({"source": "urbansound8k", "fold": 6}) == "train"
        assert split_of({"source": "urbansound8k", "fold": 7}) == "val"
        assert split_of({"source": "urbansound8k", "fold": 8}) == "test"
        assert split_of({"source": "urbansound8k", "fold": 10}) == "test"

    def test_fsd50k_folds(self):
        assert split_of({"source": "fsd50k", "fold": 1}) == "train"
        assert split_of({"source": "fsd50k", "fold": 6}) == "val"
        assert split_of({"source": "fsd50k", "fold": 9}) == "test"

    def test_synthetic_folds(self):
        assert split_of({"source": "synthetic", "fold": 1}) == "train"
        assert split_of({"source": "synthetic", "fold": 6}) == "val"
        assert split_of({"source": "synthetic", "fold": 9}) == "test"

    def test_row_style_access(self):
        """split_of must accept attribute-style rows too (namedtuples)."""
        Row = pd.DataFrame([{"source": "esc50", "fold": 5}]
                           ).itertuples(index=False, name="Row")
        assert split_of(next(iter(Row))) == "test"

    def test_every_fold_maps(self):
        """Every fold a dataset can actually have maps to a valid split."""
        valid = {
            "esc50": {1, 2, 3, 4, 5},
            "urbansound8k": set(range(1, 11)),
            "synthetic": {1, 6, 9},
            "fsd50k": {1, 6, 9},
        }
        for src, folds in valid.items():
            for f in folds:
                assert split_of({"source": src, "fold": f}) in (
                    "train", "val", "test")


@pytest.mark.skipif(not __import__("pathlib").Path(
    __import__("pathlib").Path(__file__).resolve().parents[1],
    "data", "dataset", "manifest.csv").exists(),
    reason="dataset manifest not built (run make dataset)")
class TestManifestDiscipline:
    MAN = __import__("pandas").read_csv(
        __import__("pathlib").Path(__file__).resolve().parents[1]
        / "data" / "dataset" / "manifest.csv")

    def test_no_origin_spans_folds(self):
        dup = self.MAN.groupby("origin_id")["fold"].nunique()
        assert (dup == 1).all(), "LEAKAGE: an origin appears in multiple folds"

    def test_no_origin_spans_splits(self):
        sp = self.MAN.apply(lambda r: split_of(r.to_dict()), axis=1)
        per_origin = sp.groupby(self.MAN["origin_id"]).nunique()
        assert (per_origin == 1).all(), "LEAKAGE: an origin spans splits"

    def test_all_classes_present(self):
        expected = {"chainsaw", "gunshot", "vehicle", "human", "benign",
                    "weather_rain", "weather_wind", "weather_thunder"}
        assert expected <= set(self.MAN["class"].unique())

    def test_split_of_agrees_with_pandas(self):
        for r in self.MAN.to_dict("records"):
            assert split_of(r) in ("train", "val", "test")
