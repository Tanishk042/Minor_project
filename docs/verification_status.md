# Verification status — Python platform

This is the honest gap list for the Python/backend/dashboard side of ForestEar.
It complements the measured results in `reports/` and `README.md`.

**Rule:** a row changes from `NOT RUN` or `NOT VERIFIED` only after the command
in that row actually ran and its output was saved. `FAIL` means it ran and did
not meet the target. `NOT RUN` means nobody exercised it.

| Item | Status now | Blocker | How to close it | Evidence to save | Do not claim until then |
|---|---|---|---|---|---|
| Real Telegram delivery | **NOT VERIFIED** | No real bot token or chat ID was supplied. Only the local fake-server tests ran. | Follow `docs/telegram_setup.md` §4 with a real bot, then send one controlled alert and test `/status`, `/events`, `/ack`. | `reports/telegram_e2e.md` with UTC time, masked chat ID and message ID. | "Telegram alerts verified end to end." |
| Real labelled field audio | **NOT RUN** | No labelled forest recordings were provided. The only field-harness run used synthetic smoke WAVs. | Put labelled clips in `data/field/` and run `make field-eval` (see `docs/field_eval.md`). | `reports/field_eval.json` from real clips. | Any real-forest accuracy or recall number. |
| TDOA with real clock offsets | **SIMULATION ONLY** | The simulator injects known clock offsets; the backend does not estimate or correct real per-node drift. | Add clock sync/offset estimation and test with at least 3 real nodes at known positions. | `reports/tdoa_real.json` with node offsets and error. | Accurate real-world localisation. |
| GitHub Actions CI | **NOT RUN on GitHub** | Workflow exists but the repository was not pushed or triggered. | Push when authorised, or run the workflow manually in GitHub. | Workflow run URL and green status. | "CI is green on GitHub." |
| Backend production hardening | **NOT DONE** | Development token, HTTP, single shared auth, SQLite, no retention policy. | Work through `docs/security.md`. | Completed checklist plus review notes. | "Production-ready" or "secure deployment". |
| CARTO key rotation | **ACTION REQUIRED** | The key was shared in chat; rotation needs CARTO account access. | Regenerate, domain-restrict, move new value to `.env`, revoke old key. | `make security-check` output and rotation note with dates (no key value). | "The exposed key is safe." |
| M6 anomaly model | **MEASURED BELOW CHANCE; REMOVAL TESTED, NOT ADOPTED** | AUROC 0.482 on the held-out split. Masking M6 from M9 gave macro-F1 0.8173 vs 0.8186, 111 vs 105 simulated threat false alerts and 1 false veto vs 0. | Retrain with better normal data and evaluate before changing the deployed M9. | `reports/model_quality/comparison.md` plus the `baseline_*` / `no_m6_*` evidence files. | "Anomaly detection works reliably" or "removing M6 improved the system." |
| M5 weather model | **MEASURED WEAK; CALIBRATION TESTED, NOT ADOPTED** | Macro-F1 0.5761. A validation-only log-probability bias raised M5 to 0.6728 held-out, but lowered deployed M9 to 0.8079; retraining M9 was a wash (0.8199 / 0.8138). | Collect locally labelled rain/wind/thunder audio and retest the veto. | New `reports/metrics_per_model.json` plus decision-sim results. | "Weather veto is reliable." |
| System recall / false alarms | **MEASURED, NOT PRODUCTION** | Simulation recall 0.16–0.22; stress extrapolation 2,744 false alarms per node-day. | Tune thresholds against real field audio; accept the precision/recall trade-off explicitly. | New `reports/decision_sim.json` from field-tuned settings. | "Ready for unattended field use." |
| Final 24 h soak | **PENDING** | Started 2026-11-17T09:26:46Z, but the scheduled auto-stop job was cancelled and the phone is disconnected. | Reconnect the phone and run `../android/scripts/soak.sh --stop` after the full 24 h, or `--report` if it already stopped. | `../android/device_evidence/soak/` plus updated `measured_devices.json`. | "24-hour soak passed." |

## Measured findings are not "remaining work"

The following are already measured and should not be re-labelled as unknowns:

- Python pytest: 145 collected checks (all passed).
- M6 removal comparison: measured and rejected; see
  `reports/model_quality/comparison.md`.
- M5 calibration comparison: M5 improved (0.5761 to 0.6728 held-out) but M9 did
  not, so it was not adopted; see `reports/model_quality/comparison.md` and
  `m5_calibration_m9_tradeoff.json`.
- M9 macro-F1 0.8186, accuracy 0.82, calibrated ECE 0.0506 on 6,612 held-out windows.
- Robustness: ESC-50 0.5147, UrbanSound8K 0.6701, synthetic 0.8393 macro-F1.
- Backend load test: 0 failed requests in both 20-node scenarios.
- Dashboard: keyed tiles HTTP 200, 0 console errors, 0 CDN requests.

Keep these separate from field results. A synthetic or internal test-split
number is not evidence of real-forest performance.

## Promotion checklist

Before changing any status above:

1. The command or device action really ran, with a UTC timestamp.
2. Output is saved under `reports/` or `../android/device_evidence/`.
3. Any credential in the output is masked.
4. The generator (`generate_forestear_report.py`, if used) is rerun so the PDF
   matches the evidence.
5. The change is committed locally. Do not push unless explicitly authorised.
