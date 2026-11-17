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
| `recovery_paths_CPH2381.json` | Recovered outcomes actually recorded by the app: kill FAIL, swipe PASS, screen-off PASS with 100% audio coverage, reboot FAIL, boot receiver FAIL. |
| `compatibility_report_CPH2381.md` | App-generated Markdown report pulled from the handset. This is the canonical device report. |
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
- `watchdog` and `mic_theft` were **not exercised**; the report prints them as
  NOT RUN.

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
  The permission row in the latest self-test shows microphone granted; phone state
  and SMS remain deliberately off and are reported as warnings.
- The CARTO map key used by the dashboard was exposed in chat and in `forestear/.env`.
  Rotate it or restrict it to the deployment domain before publishing.

## 24-hour soak

Started at 2026-11-17T09:26:46Z with `android/scripts/soak.sh --start`. The final
soak report will replace the `soak` line in `measured_devices.json` once it is
pulled after 24 h. Status can be checked with `android/scripts/soak.sh --status`.
