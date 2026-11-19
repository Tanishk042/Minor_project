# Real-device evidence — OnePlus CPH2381

All numbers in this directory were measured on the adb-connected OnePlus CPH2381
(serial `732738b7`, Android 14 / API 34, arm64-v8a, 8 cores, 5390 MB RAM). Nothing
here is an estimate. Files generated on the phone were pulled with `adb`; timestamps
are UTC.

## Files

| File | What it is |
|---|---|
| `benchmark_CPH2381.json` | Detailed on-phone benchmark: 49.47 ms mean pipeline, per-model means, 160.87% CPU, 321.09 MB RAM, CPU-only (GPU delegate unavailable). |
| `selftest_CPH2381.json` | Earlier in-app hardware self-test (14 checks, 0 failed, 7 warnings, latency 145.5 ms). |
| `selftest_latest_CPH2381.json` | Latest in-app hardware self-test used for the report (14 checks, 0 failed, 6 warnings, latency 274.1 ms). |
| `selftest_postrecovery_CPH2381.txt` | Headless `self_test.sh` output after the reboot/recovery sequence: failures=0, warnings=2 (phone state and SMS, both optional). |
| `selftest_CPH2381_20261118.txt` | Fresh headless self-test on 2026-11-18 with the backend reachable over `adb reverse`: failures=0, warnings=2. |
| `instrumented_20261118.log` | On-device `./gradlew connectedAndroidTest`: 9 tests, 0 failures, 0 skipped (1.743 s). |
| `watchdog_20261118.log` | Force-stop at 15:36:39Z; 420 s later NodeService_count=0 and pid=none → watchdog FAIL. |
| `mic_theft_20261118.log` | Full microphone-theft sequence: zero signal → DEGRADED → 30 s restart → capture resumed → PASS. |
| `soak/20261118-210215/` | Pulled partial soak: 1058 samples over 1 h 29 m, USB power unplugged mid-run, no final report → INVALID. |
| `soak/20261119-212027/` | Final powered run: `soak_report.json` + 17,072 samples + battery log + `FINAL_SOAK_SUMMARY.md`. 23 h 53 m, `completed_24h=false` → PARTIAL, not a full-day PASS. |
| `recovery_paths_CPH2381.json` | Consolidated measured outcomes: kill FAIL, swipe PASS, screen-off PASS, reboot FAIL, boot receiver FAIL, watchdog FAIL, mic_theft PASS. Rows recorded on 2026-11-18 came from the app's own store; earlier rows and the watchdog row are preserved from their logs because the later `adb install -g -r` reset the app's stored recovery log. |
| `compatibility_report_CPH2381.md` | App-generated Markdown report pulled from the handset. It predates the 2026-11-18 watchdog and microphone-theft tests, so its two NOT RUN rows are stale; use `recovery_paths_CPH2381.json` and the dated logs for the current status. |
| `measured_devices.json` | Machine-readable summary consumed by `android/tools/generate_oem_docs.py` for the supported-devices table. |

## How the recovery results were obtained

- `kill_service` — `am kill` was refused by Android (the process was not killable), so
  the script recorded `NOT RUN`. A deliberate `am force-stop` then stopped the service
  and it did not restart, so the final recorded outcome is **FAIL** with that detail.
  Opening the app was required to resume monitoring.
- `swipe_away` — an automated Recents swipe was performed; NodeService was still
  running 30 s later: **PASS**.
- `screen_off` — first a 30 s process-liveness check passed, then the in-app
  screen-off continuity test measured audio windows with no gaps. The final stored
  result is **100% coverage over 303 s, 0 gap events**: **PASS**.
- `reboot` / `boot_receiver` — 30 s after boot the service was not running and no
  background restart had happened: both **FAIL**. A later manual status check did
  find NodeService running, so the restart may be delayed by boot/unlock; this does
  not change the measured 30 s result. On Android 14+ a microphone foreground
  service cannot legally start from the background without opening the app.
- `watchdog` — the app was force-stopped at 2026-11-18T15:36:39Z. After 420 s
  (longer than the 5-minute alarm) `NodeService_count=0` and `pid=none`, so the
  watchdog did **not** restart the service: **FAIL**. The app had to be opened
  manually. Evidence: `watchdog_20261118.log`. The app's stored row was lost
  when the debug APK was reinstalled with `-g` later, so this row is preserved
  from the timestamped log.
- `mic_theft` — the phone's own OnePlus recorder was started while ForestEar
  held the microphone. ForestEar logged `zero signal for 10000ms`, marked the
  node DEGRADED, reported an audio error, scheduled a restart in 30 s, reopened
  AudioRecord at 21:24:48 (device time), resumed capture and reselected the
  CPH2381 input route. `appops` then showed RECORD_AUDIO running again for
  ForestEar and no longer running for the recorder: **PASS**. Evidence:
  `mic_theft_20261118.log`. The first two attempts that failed to launch a
  recorder are recorded in the same file and are explicitly not counted.

## Harness caveats (not app bugs)

- `adb reverse tcp:8000 tcp:8000` is required because the app points at
  `http://127.0.0.1:8000` on the phone. The reverse tunnel is lost on reboot; it
  was re-established after the reboot test and `/nodes` returned `node-01 healthy`
  again. Before that, backend `last_seen` was stale.
- The compatibility report's `Generated:` field is the timestamp of the stored
  device capability probe (`device_profile.json`), not the file write time. The
  recovery rows below it carry their own later timestamps.
- The report embeds 48.72 ms from the last benchmark value in app settings. The
  detailed benchmark JSON records 49.47 ms from a separate run. Both are measured;
  neither is an estimate.
- The device profile in the pulled report predates the final permission state, so
  it shows fine/coarse location as false even though location was later granted.
  The permission row in the latest self-test shows microphone granted. This ROM
  refuses `pm grant` from the adb shell (`SecurityException`), so the final
  install used `adb install -g -r`, which grants all requested permissions,
  **including SEND_SMS**. Earlier warnings about phone state/SMS describe the
  state at the time those tests ran, not the final state.
- The CARTO map key used by the dashboard was exposed in chat and in `forestear/.env`.
  Rotate it or restrict it to the deployment domain before publishing.

## 24-hour soak

- **Invalid earlier run:** started 2026-11-17T09:26:46Z. The pulled samples stop
  at 2026-11-17T10:55:30Z after 1 h 29 m, charging changes `true → false` at
  10:55:30Z, and no `soak_report.json` was produced. The files are preserved in
  `soak/20261118-210215/` but this run is **NOT a 24-hour soak and did not pass**.
- **Final powered run:** started 2026-11-18T15:57:08Z with
  `android/scripts/soak.sh --start` after all tests and reinstalls were finished.
  It was stopped at the user's instruction on 2026-11-19T15:50:25Z, about 7
  minutes before the 24 h target, so the device report records
  `completed_24h=false`. The honest status is **PARTIAL**, not a full-day PASS.
  Files: `soak/20261119-212027/soak_report.json`, `soak_samples.csv`,
  `battery_log.csv`, and `FINAL_SOAK_SUMMARY.md`.
  Measured inside the run: 17,072 samples, first 2026-11-18T15:57:08.969Z,
  last 2026-11-19T15:50:24.749Z, duration 85,997 s (23 h 53 m), max gap 6.76 s
  (0 gaps > 90 s), `charging=true` in every sample, thermal `NONE`, governor
  `NORMAL`, battery 80–100 %, temperature 21.6–31.1 °C, restarts 1 → 1,
  dropped frames 3, alerts 93, queue 0. A full 24 h claim requires a new run.
