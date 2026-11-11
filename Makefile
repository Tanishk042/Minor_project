# ForestEar — one-command workflows.
# Python: use the venv in the parent directory (.venv). If it doesn't exist:
#   make setup
PY := $(if $(wildcard ../.venv/bin/python),../.venv/bin/python,python3)
PIP := $(if $(wildcard ../.venv/bin/pip),../.venv/bin/pip,pip3)

.DEFAULT_GOAL := help

.PHONY: help setup data dataset cache train export eval test demo backend \
        smoke clean sim-clean

help: ## show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}'

# ------------------------------------------------------------- environment
setup: ## create ../.venv and install pinned deps
	python3 -m venv ../.venv
	$(PIP) install --upgrade pip wheel
	$(PIP) install -r requirements.txt
	@echo "venv ready: ../.venv/bin/python"

# ------------------------------------------------------------- data
data: ## download datasets + pretrained models (idempotent)
	$(PY) scripts/download_data.py

data-small: ## smaller caps for slow connections
	$(PY) scripts/download_data.py --small

dataset: ## build data/processed manifest + balanced window dataset
	$(PY) scripts/build_dataset.py

backgrounds: ## build the background/ambient bank for mixing
	$(PY) scripts/make_background_bank.py

cache: ## precompute the window cache (log-mel, YAMNet emb, handcrafted)
	$(PY) ml/window_cache.py

# ------------------------------------------------------------- training
train: ## train M2..M7 then M9 fusion
	$(PY) ml/train_m2_m7.py
	$(PY) ml/train_m9_fusion.py

export: ## export trained models to TFLite (+ parity check + benchmark)
	$(PY) ml/export_tflite.py

eval: ## full evaluation -> reports/ (per-model, ensemble, ablations)
	$(PY) scripts/evaluate.py

# ------------------------------------------------------------- runtime
backend: ## run the FastAPI backend + dashboard (http://127.0.0.1:8000)
	$(PY) -m backend.main --port 8000

smoke: ## push synthetic traffic into a running backend (dashboard check)
	$(PY) scripts/smoke_ingest.py

demo: ## full simulator demo: 4 virtual nodes -> backend (needs `make train`)
	$(PY) simulator/run_nodes.py --scenario chainsaw_approach

demo-gunfire: ## gunfire burst demo (TDOA localisation)
	$(PY) simulator/run_nodes.py --scenario gunfire_burst

demo-thunder: ## thunderstorm: must NOT raise a false gunshot alert
	$(PY) simulator/run_nodes.py --scenario thunderstorm_with_distant_thunder

agent-mic: ## edge agent with the laptop microphone
	$(PY) edge_agent/agent.py --mode mic

agent-file: ## edge agent replaying a wav file/dir
	$(PY) edge_agent/agent.py --mode file --path $(F)

# ------------------------------------------------------------- quality
test: ## run the pytest suite
	$(PY) -m pytest tests/ -q

clean: ## remove caches and scratch (keeps data/, models_store/)
	find . -name __pycache__ -type d -exec rm -rf {} + 2>/dev/null || true
	rm -rf .pytest_cache

sim-clean: ## remove simulator artifacts (queues, ground truth)
	rm -f data/edge_queue_*.db
	rm -rf data/sim

# ------------------------------------------------------------- pipeline
all: dataset backgrounds cache train export eval ## data is a prerequisite, run it first
