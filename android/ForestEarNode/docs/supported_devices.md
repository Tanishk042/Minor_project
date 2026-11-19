# Supported devices

> Generated from `android/ForestEarNode/app/src/main/assets/config/oem_profiles.json` and `android/device_evidence/measured_devices.json` by `android/tools/generate_oem_docs.py`. Do not edit by hand: edit the JSON files and re-run the generator.

Table version: `1` (updated `2026-11-15T00:00:00Z`). Generated: `2026-11-18T15:59:27Z`.

ForestEar Node runs on **any Android 8.0+ (API 26) device with a microphone**. There is no allowlist and no Play Services dependency. "Supported" on this page means only that the app will attempt to run and will report what it finds; it is not a claim that every handset has been measured.

At first launch and after every app update the app probes the handset and writes `device_profile.json`: manufacturer, model, SoC, ABIs, cores, RAM, storage, audio inputs, UNPROCESSED support, effects, telephony/SIM, location providers, battery and thermal API. Anything the OS does not expose is recorded as **missing**, never guessed.

## Measured on real hardware

Only devices with a pulled `compatibility_report_<model>.md` and its supporting evidence are listed here. JVM parity tests alone do not qualify a handset.

| Device | Android | Tier | Measured pipeline | Recovery paths | Report |
|---|---|---|---|---|---|
| OnePlus CPH2381 | 14 (API 34), OxygenOS, security patch 2026-08-01 | A | 49.47 ms (android/device_evidence/benchmark_CPH2381.json (detailed per-model run)) | kill_service FAIL (force-stop), swipe_away PASS, screen_off PASS, watchdog FAIL (no restart after 420 s), mic_theft PASS (zero signal/DEGRADED/30 s restart), reboot FAIL, boot_receiver FAIL | [report](../../device_evidence/compatibility_report_CPH2381.md) |

## Known device profiles

A known device only selects a better OEM guide; it does not assert that the handset was tested.

| Model pattern | OEM family | Tested on hardware | Notes |
|---|---|---|---|
| `CPH2381|Nord CE 2 Lite|CPH2409` | `oneplus` | no | Android 13 / OxygenOS 13. 3.5 mm jack is absent on some variants; a USB-C to TRRS adapter is needed for a wired mic. 5000 mAh battery, non-removable. This is one entry in the table, not a code path. |

## OEM families

The table is data, not code. Adding a maker means editing the JSON asset and re-running the generator. See [oem_guides.md](oem_guides.md) for the steps.

| Profile | Matches | Deep-link steps | Notes |
|---|---|---|---|
| `oneplus` | oneplus, oppo, realme | autostart, battery, background_activity, overlay, charge_limit | ColorOS/OxygenOS/realme UI freeze background apps aggressively. Battery optimisation exemption alone is not enough; auto-launch and backg… |
| `xiaomi` | xiaomi, redmi, poco | autostart, battery, background_activity, overlay, charge_limit | MIUI/HyperOS needs Autostart, Battery saver = No restrictions, and often 'Display pop-up windows while running in background'. The recent… |
| `vivo` | vivo, iqoo | autostart, battery, background_activity, overlay, charge_limit | Funtouch/OriginOS has an Autostart manager and a separate high background power consumption allowlist. Both are needed. Some builds label… |
| `samsung` | samsung | autostart, battery, background_activity, overlay, charge_limit | One UI uses 'Sleeping apps' and 'Deep sleeping apps'. Remove ForestEar Node from both lists and set the battery usage to Unrestricted. Th… |
| `huawei` | huawei, honor | autostart, battery, background_activity, overlay, charge_limit | EMUI/MagicOS uses 'App launch' with three toggles per app (Auto-launch, Secondary launch, Run in background). All three must be enabled. … |
| `motorola` | motorola, moto | autostart, battery, background_activity, overlay, charge_limit | Near-stock Android. Battery optimisation exemption and unrestricted battery usage are usually enough. Some My UX builds add a 'Battery sa… |
| `google` | google, android | autostart, battery, background_activity, overlay, charge_limit | Stock Android is the least aggressive. Battery optimisation exemption plus unrestricted battery usage is normally sufficient. Android 15 … |
| `nothing` | nothing | autostart, battery, background_activity, overlay, charge_limit | Near-stock Android with a light battery manager. Battery exemption and unrestricted usage are normally enough. |
| `asus` | asus, zenfone, rog | autostart, battery, background_activity, overlay, charge_limit | ZenUI/ROG UI has an Auto-start manager and a Mobile Manager power-saving list. Both may need ForestEar Node allowed. |
| `generic` | generic, other | autostart, battery, background_activity, overlay, charge_limit | Unknown manufacturer. Use the standard Android battery-optimisation exemption and app-details screen, then look up the maker on dontkillm… |

## Performance tiers

The tier is chosen from a measured benchmark of the bundled models on the handset, with a manual override in Settings. The app never claims a tier it did not measure; before the first benchmark it starts at tier C.

| Tier | Models | Hop | Notes |
|---|---|---|---|
| A | all 9 | 0.5 s | requires ≥4 cores, ≥2500 MB RAM and all models loaded |
| B | all 9 | 1.0 s | anomaly (M6) and BirdNET (M8) duty-cycled |
| C | core (M1, M2, M3, M4, M5, M9) | 1–2 s | used when a model is missing or timing is over budget |
| D | core | long hop | inference paused; raw PCM streamed to a laptop/server |

A runtime latency governor lengthens the hop first, then drops M6, then M8, then switches to streaming-only. It relaxes again after a sustained run of fast windows. Every change is logged and shown on the Status tab.

## How to qualify a new phone

1. Install with `android/scripts/install.sh` and finish the 7-step Setup wizard. Use the OEM guide for your maker.
2. Run **Diagnose → Benchmark** and let the app select a tier. Record the measured milliseconds; do not estimate them.
3. Run **Diagnose → Hardware self-test**.
4. Run `android/scripts/recovery_test.sh status`, then `kill`, `swipe`, `screen-off` and `reboot`. Read the recorded outcomes; do not assume PASS.
5. Run the in-app screen-off continuity test (≥90 % coverage and zero gaps to pass).
6. Run a 24 h soak with `android/scripts/soak.sh`.
7. Write the compatibility report in Diagnose and pull `docs/compatibility_report_<model>.md`.
8. Add the measured numbers and the report to this page only after step 7 succeeds.

## Honest limitations

- Android 14+ blocks a microphone foreground service from starting in the background. After a reboot the app posts a notification and asks you to open it; this is a platform rule, not a bug. On Android 15 a boot receiver cannot start a microphone FGS at all.
- OEM auto-launch, background-activity and Recents-lock switches have no public API. The app records user confirmation, not verification.
- Detection accuracy was measured on the Python evaluation set, not on any phone microphone. The compatibility report states this explicitly.
- A Wi-Fi-only tablet or phone runs fully over Wi-Fi; SMS fallback is simply unavailable and is reported as missing.
