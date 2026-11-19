"""Field-eval harness tests: manifest validation and metric helpers.

The model pipeline itself needs TensorFlow + model assets, so these tests only
cover the pure functions. A dry-run of the full script is exercised in
test_cli_dry_run (no models are loaded).
"""
from __future__ import annotations

import numpy as np
import pytest

from scripts.field_eval import (average_precision, auroc, confusion, discover,
                                ece, prf, read_manifest)

CLASSES = ["chainsaw", "gunshot", "vehicle", "human", "benign"]


def _touch(path, text="x"):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)
    return path


class TestManifest:
    def test_valid_manifest(self, tmp_path):
        _touch(tmp_path / "a.wav")
        _touch(tmp_path / "sub" / "b.wav")
        m = tmp_path / "labels.csv"
        m.write_text("file,label,start_s\n"
                     "a.wav,chainsaw,\n"
                     "sub/b.wav,benign,2.5\n")
        rows = read_manifest(m, tmp_path, CLASSES)

        assert [r["label"] for r in rows] == ["chainsaw", "benign"]
        assert rows[1]["start_s"] == 2.5
        assert rows[0]["start_s"] is None
        assert rows[0]["file"].endswith("a.wav")

    def test_unknown_label_rejected(self, tmp_path):
        _touch(tmp_path / "a.wav")
        m = tmp_path / "labels.csv"
        m.write_text("file,label\na.wav,explosion\n")
        with pytest.raises(ValueError, match="not in"):
            read_manifest(m, tmp_path, CLASSES)

    def test_missing_file_rejected(self, tmp_path):
        m = tmp_path / "labels.csv"
        m.write_text("file,label\nnope.wav,chainsaw\n")
        with pytest.raises(ValueError, match="not found"):
            read_manifest(m, tmp_path, CLASSES)

    def test_missing_columns_rejected(self, tmp_path):
        m = tmp_path / "labels.csv"
        m.write_text("path,tag\na.wav,chainsaw\n")
        with pytest.raises(ValueError, match="missing columns"):
            read_manifest(m, tmp_path, CLASSES)

    def test_labels_are_case_insensitive(self, tmp_path):
        _touch(tmp_path / "a.wav")
        m = tmp_path / "labels.csv"
        m.write_text("file,label\na.wav,CHAINSAW\n")
        assert read_manifest(m, tmp_path, CLASSES)[0]["label"] == "chainsaw"


class TestDiscover:
    def test_uses_parent_directory_as_label(self, tmp_path):
        _touch(tmp_path / "chainsaw" / "one.wav")
        _touch(tmp_path / "benign" / "two.wav")
        _touch(tmp_path / "unlabelled" / "three.wav")
        _touch(tmp_path / "chainsaw" / "notes.txt")
        rows = discover(tmp_path, CLASSES)

        assert {r["label"] for r in rows} == {"chainsaw", "benign"}
        assert len(rows) == 2

    def test_empty_dir(self, tmp_path):
        assert discover(tmp_path, CLASSES) == []


class TestMetrics:
    def test_confusion_counts(self):
        cm = confusion([0, 0, 1, 2], [0, 1, 1, 0], 3)
        assert cm == [[1, 1, 0], [0, 1, 0], [1, 0, 0]]

    def test_perfect_prf(self):
        y = np.array([0, 1, 2, 0, 1, 2])
        r = prf(y, y.copy(), ["a", "b", "c"])
        assert r["accuracy"] == 1.0
        assert r["macro_f1"] == 1.0
        assert all(m["f1"] == 1.0 for m in r["per_class"].values())

    def test_prf_counts(self):
        y = np.array([0, 0, 1, 1])
        p = np.array([0, 1, 1, 1])
        r = prf(y, p, ["a", "b"])
        # class a: tp=1 fp=0 fn=1 -> P=1.0 R=0.5
        assert r["per_class"]["a"]["precision"] == 1.0
        assert r["per_class"]["a"]["recall"] == 0.5
        # class b: tp=2 fp=1 fn=0 -> P=2/3 R=1.0
        assert r["per_class"]["b"]["precision"] == 0.6667
        assert r["per_class"]["b"]["recall"] == 1.0

    def test_auroc_perfect_and_inverted(self):
        y = np.array([1, 1, 0, 0])
        assert auroc(y, np.array([0.9, 0.8, 0.2, 0.1])) == 1.0
        assert auroc(y, np.array([0.1, 0.2, 0.8, 0.9])) == 0.0

    def test_auroc_ties_are_half(self):
        y = np.array([1, 0])
        assert auroc(y, np.array([0.5, 0.5])) == 0.5

    def test_auroc_needs_both_classes(self):
        assert auroc(np.array([1, 1]), np.array([0.2, 0.3])) is None

    def test_average_precision(self):
        y = np.array([1, 0, 1])
        # ranking 1,0,1 -> precisions at hits: 1/1, 2/3
        assert average_precision(y, np.array([0.9, 0.5, 0.4])) == pytest.approx(
            (1.0 + 2 / 3) / 2)

    def test_ece_zero_when_calibrated_perfectly(self):
        probs = np.array([[1.0, 0.0], [0.0, 1.0]])
        y = np.array([0, 1])
        assert ece(probs, y) == pytest.approx(0.0)

    def test_ece_counts_wrong_confident_predictions(self):
        probs = np.array([[0.9, 0.1]])
        y = np.array([1])
        assert ece(probs, y) == pytest.approx(0.9)
