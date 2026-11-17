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
| SMS delivery | **NOT VERIFIED** | `SEND_SMS` is deliberately off and no real SIM/carrier test was performed. | Grant SMS permission on a device with a live SIM and send a controlled CRITICAL test. | Log or screenshot with timestamp; token/number masked. | "SMS fallback works." |
| Watchdog recovery after force-kill | **NOT RUN** | The watchdog path was not deliberately exercised on the handset. | Enable the watchdog, force-stop the app, wait, and record whether `NodeService` returns. | Updated `../device_evidence/recovery_paths_CPH2381.json` row. | "Watchdog restarts the service." |
| Microphone-theft recovery | **NOT RUN** | No second recorder app was used to take the microphone. | Start another recorder while ForestEar is live and record the app's behaviour. | Updated recovery JSON row. | "App handles microphone theft." |
| Force-kill recovery | **FAIL (recorded)** | `am kill` was refused; force-stop stopped the service and it did not restart. Android 14 blocks this restart for a microphone foreground service. | User must reopen the app. This is an OS limitation, not a hidden PASS. | `../device_evidence/recovery_paths_CPH2381.json`. | "Service auto-restarts after force-stop." |
| Reboot / boot receiver | **FAIL (recorded)** | No background microphone foreground service restart within 30 s after boot on Android 14. The app shows a notification asking the user to open it. | User action required; document it in OEM guides. | Recovery JSON plus compatibility report. | "Monitoring resumes automatically after reboot." |
| Final 24 h soak | **PENDING** | Started 2026-11-17T09:26:46Z; the scheduled auto-stop job was cancelled by a server restart and the phone is currently disconnected. | Reconnect the phone; after the full 24 h run `./scripts/soak.sh --stop` (or `--report` if already stopped). | `../device_evidence/soak/` with `soak_report.json`, CSVs and updated `measured_devices.json`. | "24-hour soak passed." |
| Other physical devices | **NOT RUN** | Only CPH2381 was measured. OEM table rows are guidance, not evidence. | Install on at least one device per major OEM family and generate a compatibility report on each. | `../device_evidence/compatibility_report_<model>.md`. | "All supported phones are verified." |
| SoC junction temperature | **LIMITED** | The handset does not expose it; the thermal governor uses battery temperature. | Use an external sensor or accept and document the battery proxy. | Device evidence note. | "Precise SoC thermal monitoring." |
| Microphone response | **NOT CHARACTERISED** | Built-in processing (AGC/NS/AEC) differs from the training data. | Calibrate per device or use a measured external microphone; compare log-mel levels. | Calibration report in `../device_evidence/`. | "Device-agnostic microphone accuracy." |
| Real Telegram delivery | **NOT VERIFIED** | Telegram is a backend path; only fake-server tests ran. No real bot/chat was supplied. | Follow `../../forestear/docs/telegram_setup.md` §4 with a real bot. | `../../forestear/reports/telegram_e2e.md`. | "Alerts reached Telegram." |
| Real labelled field audio | **NOT RUN** | No labelled recordings were evaluated. Harness was only smoke-tested with synthetic WAVs. | Follow `../../forestear/docs/field_eval.md` with real clips. | `../../forestear/reports/field_eval.json`. | "Real-forest accuracy is measured." |

## Already measured — do not re-label as unknown

- JVM tests: 88 checks across 13 suites, 0 failures.
- Instrumented tests: 9 checks across 4 suites on CPH2381, 0 failures.
- On-phone benchmark: 49.47 ms mean, tier A, CPU only; GPU delegate unavailable.
- In-app self-test: 14 checks, 0 failed, 6 warnings; headless: 0 failures, 2 warnings.
- Screen-off continuity: 100% coverage over 303 s, 0 gaps.
- Recovery: swipe PASS, screen-off PASS, force-kill FAIL, reboot FAIL, boot receiver FAIL.
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
