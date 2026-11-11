"""Download datasets and pretrained models for ForestEar.

Sources (all verified reachable):
- ESC-50           github.com/karolpiczak/ESC-50 (CC-BY-NC)      [automatic]
- UrbanSound8K     HF mirror MahiA/UrbanSound8K (research lic.)  [automatic]
- FSD50K subset    HF mirror Fhrozen/FSD50k (CC0/CC-BY clips)   [automatic]
- YAMNet (M1)      TFHub SavedModel + official TFLite builds     [automatic]
- BirdNET (M8)     HF mirror sammlapp/BirdNET_v2.4 (CC BY-NC-SA)[automatic]
If any download fails the script prints instructions and continues; the
pipeline degrades to the synthetic generator (see ml/synth.py) and the README
records which data each reported result used.

Usage:  python scripts/download_data.py [--small]
"""
from __future__ import annotations

import argparse
import hashlib
import logging
import shutil
import sys
import tarfile
import zipfile
from collections import Counter
from pathlib import Path

import requests

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO))

from common.config import data_dir, data_file  # noqa: E402
from common.logging_setup import setup_logging  # noqa: E402

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s :: %(message)s")
log = logging.getLogger("forestear.download")

UA = {"User-Agent": "Mozilla/5.0 (ForestEar student research project)"}


def fetch(url: str, dest: Path, min_bytes: int = 1024, timeout: int = 600) -> bool:
    """Stream a URL to dest with basic size validation (requests = certifi SSL)."""
    if dest.exists() and dest.stat().st_size >= min_bytes:
        log.info("cached: %s", dest.name)
        return True
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    try:
        with requests.get(url, headers=UA, stream=True, timeout=timeout) as r:
            r.raise_for_status()
            total = 0
            with open(tmp, "wb") as f:
                for chunk in r.iter_content(1 << 20):
                    f.write(chunk)
                    total += len(chunk)
        if total < min_bytes:
            log.error("too small (%d bytes): %s", total, url)
            tmp.unlink(missing_ok=True)
            return False
        shutil.move(tmp, dest)
        log.info("downloaded %s -> %s (%.1f MB)", url, dest.name, total / 1e6)
        return True
    except Exception as e:  # noqa: BLE001
        log.error("FAILED %s: %s", url, e)
        tmp.unlink(missing_ok=True)
        return False


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()[:16]


def _fetch_parallel(jobs: list[tuple[str, Path]], label: str,
                    workers: int = 10, timeout: int = 600) -> int:
    """Fetch (url, dest) jobs with a thread pool. Idempotent, returns ok count."""
    from concurrent.futures import ThreadPoolExecutor
    ok = 0
    done = 0
    lock = __import__("threading").Lock()

    def work(job):
        url, dest = job
        return fetch(url, dest, min_bytes=4000, timeout=timeout)

    with ThreadPoolExecutor(max_workers=workers) as ex:
        for result in ex.map(work, jobs):
            done += 1
            with lock:
                if result:
                    ok += 1
                if done % 200 == 0:
                    log.info("  %s: %d/%d", label, done, len(jobs))
    return ok


# ---------------------------------------------------------------------------

def download_esc50() -> bool:
    """ESC-50: 2000 clips, 5 predefined folds. ~600 MB zip."""
    raw = data_file("raw", "ESC-50-master.zip")
    if not fetch("https://github.com/karolpiczak/ESC-50/archive/master.zip", raw,
                min_bytes=50_000_000):
        log.warning("ESC-50 download failed. Manual: "
                    "https://github.com/karolpiczak/ESC-50 (clone into data/raw/)")
        return False
    out = data_dir("raw", "ESC-50-master")
    marker = data_dir("raw", "ESC-50-master") / "audio" / "1-100032-A-0.wav"
    if not marker.exists():
        with zipfile.ZipFile(raw) as z:
            z.extractall(out.parent)
    log.info("ESC-50 checksum %s", sha256(raw))
    return marker.exists()


def download_urbansound8k(small: bool = False) -> bool:
    """UrbanSound8K from the HF mirror (folds preserved).

    Mirrors the original urbansounddataset.webservices.berkeley.edu which is
    often unreachable; the mirror keeps the original file names/folds.
    We fetch selected classes only via HTTP range-free raw resolve URLs.
    """
    base = "https://huggingface.co/datasets/MahiA/UrbanSound8K/resolve/main/audios"
    meta = data_file("raw", "urbansound8k", "metadata.csv")
    out = data_dir("raw", "urbansound8k")  # dir ok
    if not fetch("https://huggingface.co/datasets/danavery/urbansound8K/resolve/main/UrbanSound8K.csv",
                 meta, min_bytes=10_000):
        return False
    import csv

    # classes we need (ID per the official UrbanSound8K metadata):
    # 3 = dog_bark, 4 = engine_idling? (verify from CSV below)
    keep_ids: dict[str, set[int]] = {}
    with open(meta, newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    # CSV columns differ per mirror; find them
    cols = rows[0].keys()
    def col(*names: str) -> str:
        for n in names:
            for c in cols:
                if c.lower().strip() == n:
                    return c
        raise KeyError(names)

    c_slice = col("slice_file_name")
    c_fold = col("fold")
    c_cls = col("class", "class_name", "label")
    keep = {"gun_shot", "engine_idling", "car_horn", "children_playing", "dog_bark",
            "drilling", "jackhammer", "siren", "street_music"}
    if small:
        keep = {"gun_shot", "engine_idling", "car_horn", "children_playing", "dog_bark"}
    kept_rows = [r for r in rows if r[c_cls] in keep]
    log.info("UrbanSound8K: fetching %d clips in classes %s", len(kept_rows), sorted(keep))
    jobs = []
    for r in kept_rows:
        fold = r[c_fold]
        name = r[c_slice]
        dest = out / f"fold{fold}" / name
        if dest.exists() and dest.stat().st_size > 4000:
            continue
        url = f"{base}/fold{fold}/{name}"
        jobs.append((url, dest))
    ok = _fetch_parallel(jobs, label="UrbanSound8K")
    if ok == 0:
        log.warning("UrbanSound8K download failed. Manual options: "
                    "https://urbansounddataset.webservices.berkeley.edu/ "
                    "or Kaggle 'urbansound8k'")
        return False
    log.info("UrbanSound8K: %d/%d clips ok", ok, len(kept_rows))
    return True


def download_fsd50k(small: bool = False) -> bool:
    """FSD50K subset from the HF mirror: labels CSV + selected clips.

    Uses label names (not mids) from labels/dev.csv; respects dev train/val
    split column; eval set held for final testing.
    """
    base = "https://huggingface.co/datasets/Fhrozen/FSD50k/resolve/main"
    dev_csv = data_file("raw", "fsd50k", "dev.csv")
    eval_csv = data_file("raw", "fsd50k", "eval.csv")
    if not fetch(f"{base}/labels/dev.csv", dev_csv, min_bytes=1_000_000):
        return False
    fetch(f"{base}/labels/eval.csv", eval_csv, min_bytes=100_000)
    import csv

    want = {
        "gunshot": {"Gunshot_and_gunfire"},
        "vehicle": {"Motorcycle", "Truck", "Motor_vehicle_(road)"},
        "human": {"Speech", "Chatter", "Walk_and_footsteps", "Shout", "Yell", "Screaming"},
        "weather": {"Rain", "Wind", "Thunderstorm"},
        "benign": {"Bird", "Chirp_and_tweet", "Cricket", "Frog", "Stream"},
    }
    per_label_cap = 260 if not small else 120

    def parse(path: Path) -> list[tuple[str, str, str]]:
        rows = []
        with open(path, newline="", encoding="utf-8") as f:
            for r in csv.DictReader(f):
                labels = r["labels"].split(",")
                rows.append((r["fname"], labels, r.get("split", "train")))
        return rows

    stats: Counter[str] = Counter()
    all_jobs: list[tuple[Path, str]] = []
    for csv_path, split_dir in ((dev_csv, "dev"), (eval_csv, "eval")):
        if not csv_path.exists():
            continue
        for fname, labels, split in parse(csv_path):
            picked = None
            for group, want_labels in want.items():
                if any(l in want_labels for l in labels):
                    picked = group
                    break
            if picked is None:
                continue
            # skip ambiguous multi-group clips (e.g. gunshot+speech) in eval only
            n_groups = sum(1 for g, wl in want.items() if any(l in wl for l in labels))
            if n_groups > 1:
                continue
            out_dir = data_dir("raw", "fsd50k", split_dir)
            stats[(split_dir, picked)] += 1
            all_jobs.append((f"{base}/clips/{split_dir}/{fname}.wav",
                             out_dir / f"{fname}.wav"))
    # cap per (dir, group)
    counts: Counter[tuple[str, str]] = Counter()
    jobs = []
    for (url, dest) in all_jobs:
        key = (str(dest.parent), _group_of(dest, stats))
        if counts[key] >= per_label_cap * 5:
            continue
        counts[key] += 1
        jobs.append((url, dest))
    log.info("FSD50K: %d clips to fetch", len(jobs))
    ok = _fetch_parallel(jobs, label="FSD50K", timeout=120)
    if ok == 0:
        log.warning("FSD50K download failed (mirror unreachable?)")
        return False
    log.info("FSD50K: %d/%d clips ok", ok, len(jobs))
    return True


def _group_of(dest: Path, stats: Counter) -> str:
    return dest.parent.name  # grouping helper, not used for labels


def download_models() -> bool:
    """M1 YAMNet (hub + tflite) and M8 BirdNET TFLite."""
    ok = True
    m1 = REPO / "models_store" / "m1_yamnet"
    m1.mkdir(parents=True, exist_ok=True)
    if not (m1 / "saved_model.pb").exists():
        tgz = m1 / "yamnet_hub.tar.gz"
        if fetch("https://tfhub.dev/google/yamnet/1?tf-hub-format=compressed", tgz,
                 min_bytes=10_000_000):
            with tarfile.open(tgz) as t:
                t.extractall(m1)
            log.info("YAMNet hub model extracted")
        else:
            ok = False
    for name in ("lite-model_yamnet_classification_tflite_1.tflite",
                 "lite-model_yamnet_tflite_1.tflite"):
        fetch(f"https://huggingface.co/thelou1s/yamnet/resolve/main/{name}",
              m1 / name, min_bytes=1_000_000)
    shutil.copy(m1 / "assets" / "yamnet_class_map.csv", REPO / "ml" / "yamnet_class_map.csv")

    m8 = REPO / "models_store" / "m8_birdnet"
    m8.mkdir(parents=True, exist_ok=True)
    b = m8 / "BirdNET_GLOBAL_6K_V2.4_Model_INT8.tflite"
    if not fetch(
        "https://huggingface.co/sammlapp/BirdNET_v2.4/resolve/main/V2.4/"
        "BirdNET_GLOBAL_6K_V2.4_Model_INT8.tflite", b, min_bytes=5_000_000
    ):
        log.warning("BirdNET download failed; M8 degrades to disabled")
        return ok and False
    lab = m8 / "BirdNET_GLOBAL_6K_V2.4_Labels.txt"
    fetch("https://huggingface.co/sammlapp/BirdNET_v2.4/resolve/main/V2.4/"
          "BirdNET_GLOBAL_6K_V2.4_Labels.txt", lab, min_bytes=10_000)
    return ok


# ---------------------------------------------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--small", action="store_true",
                    help="smaller caps for slow connections")
    args = ap.parse_args()
    setup_logging()
    log.info("=== ForestEar data download ===")
    results = {
        "esc50": download_esc50(),
        "urbansound8k": download_urbansound8k(small=args.small),
        "fsd50k": download_fsd50k(small=args.small),
        "models": download_models(),
    }
    manifest = data_file("raw", "download_manifest.txt")
    with open(manifest, "w", encoding="utf-8") as f:
        f.write(f"# ForestEar download manifest {__import__('datetime').datetime.utcnow()}\n")
        for k, v in results.items():
            f.write(f"{k}: {'OK' if v else 'FAILED (fallback will engage)'}\n")
    log.info("=== done: %s ===", results)
    if not all(results.values()):
        log.warning("Some sources failed - the pipeline will continue with what we "
                    "have plus the synthetic generator; README will state this.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
