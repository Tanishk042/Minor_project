# Verification status — Android node

This is the honest gap list for the phone side of ForestEar. It complements the
measured evidence in `../device_evidence/` and the generated compatibility
report.

**Rule:** `PASS` only after the action ran successfully. `FAIL` means it ran and
did not meet the target. `NOT RUN` means nobody exercised it. A missing
capability is recorded as missing, never guessed.

| Item | Status now | Blocker / reason | How to close it | Evidence to save | Do not claim until then |
|---|---|---|---|---|---|
| Signed release APK | **NOT RUN** | No keystore or passwords were supplied. Only the debug APK was built. | Create a keystore, fill `keystore.properties`, run `./scripts/build_release.sh`, then `apksigner verify`. See `release_build.md`. | Signed APK(s) plus `apksigner verify` output. | "Release APK ready for distribution." |
| Emulator matrix (Android 8–15) | **NOT RUN** | No emulator package or system images are installed. | `./scripts/emulator_matrix.sh --install`, then run the matrix (`--full` for all instrumented tests). See `emulator_matrix.md`. | `docs/emulator_matrix_results.md` with API-level rows. | "Compatible with Android 8 through 15." |
| SMS delivery | **NOT VERIFIED** | No live SIM/carrier test was performed. The phone reports `OUT_OF_SERVICE` / "No SIM - Emergency calls only"; the final `-g` install granted `SEND_SMS`, but granting is not delivery. | Use a device with a live SIM and send a controlled CRITICAL test. | Log or screenshot with timestamp; token/number masked. | "SMS fallback works." |
| adb permission grants on this ROM | **FAIL (workaround used)** | OxygenOS on CPH2381 rejects `pm grant` from the adb shell with `SecurityException: ... has android.permission.GRANT_RUNTIME_PERMISSIONS`. The old script hid this as "skipped". | `grant_permissions.sh` now verifies actual grants and reports FAILED; use `./scripts/install.sh --grant-all` (explicitly warns that SEND_SMS is included) or grant in Settings. | Script output plus `dumpsys package ... granted=true`. | "grant_permissions.sh silently succeeded." |
| Watchdog recovery after force-kill | **FAIL (recorded)** | Force-stop at 2026-11-18T15:36:39Z; after 420 s (past the 5-minute alarm) `NodeService_count=0`, `pid=none`. | User must reopen the app. Treat as a known limitation, not a hidden PASS. | `../device_evidence/watchdog_20261118.log` and recovery JSON row. | "Watchdog restarts the service." |
| Microphone-theft recovery | **PASS (recorded)** | The OnePlus recorder took the mic; the app logged 10 s zero signal, DEGRADED, an audio error, a 30 s restart, then resumed capture and reselected CPH2381; `appops` showed RECORD_AUDIO running again. | None for this path. | `../device_evidence/mic_theft_20261118.log` and recovery JSON row. | "Microphone theft was not exercised" or a recovery time other than the measured 30 s. |
| Force-kill recovery | **FAIL (recorded)** | `am kill` was refused; force-stop stopped the service and it did not restart. Android 14 blocks this restart for a microphone foreground service. | User must reopen the app. This is an OS limitation, not a hidden PASS. | `../device_evidence/recovery_paths_CPH2381.json`. | "Service auto-restarts after force-stop." |
| Reboot / boot receiver | **FAIL (recorded)** | No background microphone foreground service restart within 30 s after boot on Android 14. The app shows a notification asking the user to open it. | User action required; document it in OEM guides. | Recovery JSON plus compatibility report. | "Monitoring resumes automatically after reboot." |
| Full 24 h soak | **PARTIAL — 23 h 53 m, not a full-day PASS** | The 2026-11-17 run is invalid (1 h 29 m, power unplugged, no report). The final powered run ran 2026-11-18T15:57:08Z → 2026-11-19T15:50:25Z (85,997 s) and was stopped at user instruction ~7 min early; `completed_24h=false`. Measured inside it: 17,072 samples, max gap 6.76 s, charging=true throughout, thermal NONE, governor NORMAL, battery 80–100 %, 21.6–31.1 °C, restarts 1→1, alerts 93, queue 0. | Repeat to a true 24 h if a full-day claim is needed; keep the phone charging throughout. | `../device_evidence/soak/20261119-212027/` with `soak_report.json`, CSVs, `FINAL_SOAK_SUMMARY.md` and updated `measured_devices.json`. | "24-hour soak passed." |
| Other physical devices | **NOT RUN** | Only CPH2381 was measured. OEM table rows are guidance, not evidence. | Install on at least one device per major OEM family and generate a compatibility report on each. | `../device_evidence/compatibility_report_<model>.md`. | "All supported phones are verified." |
| SoC junction temperature | **LIMITED** | The handset does not expose it; the thermal governor uses battery temperature. | Use an external sensor or accept and document the battery proxy. | Device evidence note. | "Precise SoC thermal monitoring." |
| Microphone response | **NOT CHARACTERISED** | Built-in processing (AGC/NS/AEC) differs from the training data. | Calibrate per device or use a measured external microphone; compare log-mel levels. | Calibration report in `../device_evidence/`. | "Device-agnostic microphone accuracy." |
| Real Telegram delivery | **NOT VERIFIED** | Telegram is a backend path; only fake-server tests ran. No real bot/chat was supplied. | Follow `../../forestear/docs/telegram_setup.md` §4 with a real bot. | `../../forestear/reports/telegram_e2e.md`. | "Alerts reached Telegram." |
| Real labelled field audio | **NOT RUN** | No labelled recordings were evaluated. Harness was only smoke-tested with synthetic WAVs. | Follow `../../forestear/docs/field_eval.md` with real clips. | `../../forestear/reports/field_eval.json`. | "Real-forest accuracy is measured." |

## Already measured — do not re-label as unknown

- JVM tests: 88 checks across 13 suites, 0 failures.
- Instrumented tests: 9 checks across 4 suites on CPH2381, 0 failures, 0 skipped
  (`../device_evidence/instrumented_20261118.log`).
- On-phone benchmark: 49.47 ms mean, tier A, CPU only; GPU delegate unavailable.
- In-app self-test: 14 checks, 0 failed, 6 warnings; headless self-test on
  2026-11-18: 0 failures, 2 warnings (`selftest_CPH2381_20261118.txt`).
- Screen-off continuity: 100% coverage over 303 s, 0 gaps.
- Recovery: swipe PASS, screen-off PASS, microphone-theft PASS (30 s restart),
  force-kill FAIL, watchdog FAIL, reboot FAIL, boot receiver FAIL.
- A phone-generated compatibility report exists at
  `../device_evidence/compatibility_report_CPH2381.md`.

## Promotion checklist

Before changing a status:

1. The device action or build command really ran, with a UTC timestamp.
2. Output is saved under `../device_evidence/` or `docs/`.
3. Tokens, phone numbers and keys are masked.
4. Generated docs (`python3 tools/generate_oem_docs.py`) and the PDF
   (`python3 ../generate_forestear_report.py`) are regenerated if the status
   affects them.
5. The change is committed locally. Do not push unless explicitly authorised.
