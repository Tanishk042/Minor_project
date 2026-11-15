# Phone setup — generic Android (8.0 to 15)

This is the device-agnostic setup guide for **ForestEar Node**. It applies to any
Android 8.0+ (API 26) phone or tablet with a microphone. The OnePlus Nord CE 2
Lite 5G is one entry in the OEM table, not a code path; its device-specific notes
are in [`phone_setup_nord_ce2_lite.md`](../../../forestear/docs/phone_setup_nord_ce2_lite.md).

The app probes the handset instead of assuming a model. Everything below is
either verified by the OS or explicitly confirmed by you; the app never claims a
switch is on when Android does not expose it.

---

## What you need

- Android 8.0+ (API 26), no Play Services required.
- A microphone: built-in, a 3.5 mm headset, or a USB/USB-C microphone.
- A charger for long runs, or a battery you have inspected for swelling.
- Wi-Fi or a SIM with a small data plan. A Wi-Fi-only tablet works fully over
  Wi-Fi; only SMS fallback is unavailable.
- For adb tooling: platform-tools, and JDK 17+ if you build from source.

The debug APK in this checkout is **100,268,087 bytes** (about 100 MB); the
models are packaged uncompressed so TFLite can memory-map them.

---

## 1. Install and first launch

```bash
cd android/ForestEarNode
export JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home
./gradlew :app:assembleDebug
../scripts/install.sh          # build, install, grant the safe permissions
```

`install.sh` does **not** grant SMS by default. Pass `--sms` only when you want
the SMS fallback.

Open the app once. The first-run wizard probes the device and writes
`device_profile.json` under the app's private files. The probe runs again after
every app update. A compact summary is attached to every heartbeat.

If you are installing a fork, the adb scripts accept:

```bash
FORESTEAR_PACKAGE=com.example.forestear \
FORESTEAR_ACTION_PREFIX=org.forestear.node \
  ../scripts/install.sh
```

The action prefix is a manifest constant; changing only the application id does
not change it.

---

## 2. Wizard step 1–2: permissions

| Permission | Why | Required |
|---|---|---|
| `RECORD_AUDIO` | the whole node | yes |
| `POST_NOTIFICATIONS` (13+) | persistent foreground notification | yes |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | phone's last known fix | no |
| `READ_PHONE_STATE` | SIM slots, signal, network type | no |
| `SEND_SMS` | CRITICAL SMS fallback | no |

Fixed coordinates are the default and always work without location permission.
Denying an optional permission keeps the node working; the capability profile
records what is missing.

Android 14+ additionally requires the foreground service to declare
`FOREGROUND_SERVICE_MICROPHONE` (the app does) and the service must be started
while the app is visible. Opening the app once after boot is therefore expected,
not a workaround.

---

## 3. Wizard step 3–4: battery and OEM protections

The app verifies the battery-optimisation exemption with
`isIgnoringBatteryOptimizations`. That is the only kill-protection switch with a
public read API. Everything else is maker-specific and has no public API.

Use the guide for your maker: [oem_guides.md](oem_guides.md). The app's Setup
step 4 shows the same steps and tries each deep link in order, with a resolve
check and try/catch. If none resolve, it opens the app-details screen and then
the standard battery-optimisation screen.

Checklist that matters on aggressive ROMs:

1. Battery optimisation: **Don't optimise / Unrestricted** (verified by the app).
2. Background activity: **Allow**.
3. Auto-launch / startup manager: **enabled**.
4. Recents: **lock** the ForestEar card so a swipe cannot remove it.
5. Optional: **Display over other apps**; the app records whether it seemed to
   help on your phone, but never treats it as verified.
6. Optional: charge limit 80–85 % for a permanently powered node.
7. Battery swelling inspection: a bulging cover, screen lift or wobble is a stop
   signal. Replace the pack.

The Status tab keeps a warning visible while a required protection is missing.

---

## 4. Wizard step 5: screen-off continuity

The most common failure is the maker freezing the app after the screen turns
off. Start monitoring, then run **Setup step 5** or **Tools → Screen-off
continuity test**:

1. Choose a length (1–60 min).
2. Start the test.
3. Turn the screen off and leave the phone still.
4. Stop the test.

A pass requires **≥90 % audio-window coverage and zero gap events**. The result
is stored and included in the compatibility report. If it fails, recheck steps
3–4 for your OEM; do not assume the phone is suitable.

Screen state cannot be forced by an app, so this is a user-driven test.

---

## 5. Wizard step 6: backend and network

| Field | Meaning |
|---|---|
| Node ID | unique name in alerts and heartbeats (e.g. `node-07`) |
| Backend URL | `http://<laptop-lan-ip>:8000`; `127.0.0.1` only with `adb reverse` |
| Auth token | must match `FORESTEAR_TOKEN` on the backend (default `forestear-dev-token`) |

The phone sends JSON alerts and telemetry only. It does not upload audio by
default; ambient collection is a separate explicit opt-in and never stores
speech.

Connectivity callbacks drive a persistent offline queue with exponential
backoff. Alerts survive a network drop and are retried; the queue is visible in
**Tools → Offline alert queue**.

If you use a laptop on the same Wi-Fi, allow port 8000 through the laptop
firewall. Verify with **Test /health** in the wizard or
`curl http://<ip>:8000/health`.

---

## 6. Wizard step 7: finish and benchmark

Finish the wizard, then run **Diagnose → Benchmark** once. The benchmark warms up
and times every bundled model, then selects a tier:

| Tier | Models | Hop | When |
|---|---|---|---|
| A | all 9 | 0.5 s | measured timing ≤60 % of budget, ≥4 cores, ≥2500 MB RAM, all models loaded |
| B | all 9 | 1.0 s | timing ≤100 % of budget, all models loaded; M6/M8 duty-cycled |
| C | core (M1–M5, M9) | 1–2 s | over budget, or a model is missing |
| D | core | long hop | far over budget; inference paused and raw PCM streamed to a laptop |

Before the first benchmark the app starts conservatively at **tier C**. A manual
override in Settings is always honoured, with a note when it exceeds the budget.
The runtime latency governor lengthens the hop first, then disables M6, then M8,
then switches to streaming-only; it relaxes after 12 fast windows. Every change
is logged and shown on Status.

**Do not copy the laptop's 5.56 ms/hop figure into a phone report.** On-phone
latency is unmeasured until you run the benchmark on that handset.

---

## 7. Audio input and calibration

**Settings → Audio input**: Auto, Built-in, 3.5 mm, USB. Auto prefers USB, then a
wired headset, then the built-in mic. Bluetooth SCO is never selected
automatically because it band-limits the audio.

The app requests 16 kHz mono; if the device refuses, it negotiates the nearest
supported rate and resamples. It prefers `UNPROCESSED`, falls back to `MIC`, and
tries to disable AGC/NS/AEC, logging which effects were actually active.

**Calibration** (non-bar tab): live RMS/peak dBFS, clipping percentage, detected
input, effects, and a per-input gain (`builtin` / `headset` / `usb`). Target
roughly **−30 to −12 dBFS** for ambience; avoid clipping. Stop monitoring for a
clean calibration because the service owns the microphone.

Plugging in a headset or USB mic triggers a route-change callback; the app offers
the switch and never silently changes the input mid-deployment.

---

## 8. Optional: SMS fallback

SMS is off by default. When enabled:

- only **CRITICAL** events are sent;
- the message is compact (`FE|...`, under 160 characters);
- on dual-SIM you choose the sending subscription;
- rate limits are persisted (`minIntervalS`, `maxPerHour`);
- delivery status is recorded.

A Wi-Fi-only device reports telephony as missing and runs without SMS. This is
not an error.

---

## 9. Optional: location

Fixed coordinates are the default and need no permission. If you grant location,
the app uses the platform provider and falls back to `LocationManager` when no
fused provider exists. It never crashes when location is unavailable; the
capability profile records `fused_provider` as missing.

---

## 10. Recovery-path tests

Run these on every new handset. They answer the question "does this phone survive
as a node?".

```bash
cd android/scripts
./recovery_test.sh status
./recovery_test.sh kill              # background kill; records NOT RUN if refused
./recovery_test.sh kill --force-stop # hard kill; Android will not restart it
./recovery_test.sh swipe --wait 60 --confirm
./recovery_test.sh screen-off --wait 30
./recovery_test.sh reboot
./recovery_test.sh report
```

Each command records PASS / FAIL / NOT RUN in `recovery_paths.json` through a
debug broadcast, so the result survives the service process dying. The Diagnostics
screen and the generated compatibility report print exactly what is stored.
Unexercised paths stay **NOT RUN**, never PASS.

Expectations:

- `kill` with `am kill` may be refused for a foreground service; the script says
  so instead of pretending.
- `reboot` on Android 14+ can legitimately record **FAIL** for
  `boot_receiver`: the platform blocks a microphone FGS from starting in the
  background. The app posts a high-priority notification asking you to open it
  and marks the node DEGRADED.
- `screen-off` checks the process; audio coverage comes from the in-app
  continuity test.
- Microphone theft (a call or another app taking the mic) pauses and resumes
  capture and logs the gap. There is no scripted test for it yet; record it with
  `./recovery_test.sh record mic_theft PASS "..."` after a manual call test.

Optional kiosk/device-owner mode can pin the app, but it is not required and is
not configured by these scripts.

---

## 11. Soak and battery safety

```bash
./soak.sh --start
./soak.sh --watch 3600
./soak.sh --stop
```

The service writes a battery/temperature CSV once a minute and a soak report on
stop. Heartbeats carry battery %, charging state, temperature, thermal status,
uptime, restart count, free storage, signal and SNTP clock offset.

The thermal governor escalates NORMAL → WARM → HOT → CRITICAL → PAUSED with
hysteresis. CRITICAL and PAUSED emit system events and notifications. Low battery
maps to WARM/HOT, and a critical battery emits a final alert before pausing. The
SNTP offset is reported only; the app never changes the system clock.

---

## Android version notes

| Version | What changes |
|---|---|
| 8–9 | no foreground-service type; start from boot is allowed |
| 10–12 | `FOREGROUND_SERVICE` + microphone access while in use; background start restrictions begin |
| 13 | `POST_NOTIFICATIONS` runtime permission; notification denial is visible in Setup |
| 14 | `FOREGROUND_SERVICE_MICROPHONE`; a background start cannot upgrade to a microphone FGS; open the app once |
| 15 | a boot receiver cannot start a microphone FGS; the app notifies instead of claiming success |

The app targets API 34 and runs on API 26+. No Play Services, no Firebase, no
network dependency for inference.

---

## Troubleshooting

| Symptom | Check |
|---|---|
| Node stops when the screen is off | OEM guide step 3–4; rerun continuity test |
| "Node DEGRADED" banner | Status shows the reason; usually a blocked background start |
| No alerts | backend URL/token, `/health`, offline queue, tier, thresholds |
| Clipped or silent audio | Calibration; input choice; per-input gain; effects log |
| Benchmark selects tier C/D | read the measured ms and reason on Status/Settings; do not override without checking thermal |
| SMS not sent | telephony missing, permission, rate limit, chosen SIM |
| App cannot start after reboot on Android 14/15 | expected; open the app once, then keep it exempt from battery optimisation |
| `recovery_test.sh` cannot read the log | release build (no `run-as`); use the in-app Diagnostics table |

---

## Limitations (read before deploying)

- This is a research prototype, not a certified security device.
- Detection accuracy was measured on the Python evaluation set, not on this
  phone's microphone. The compatibility report states this.
- On-phone latency, CPU, RAM, thermal, SMS and recovery results are **not**
  measured in this repository until you run the tools on a handset.
- OEM kill switches have no public API; user confirmation is not verification.
- Android 14+ background-start rules can require a manual app open after reboot.
- Ambient collection is opt-in, never stores speech, and deletes a whole chunk if
  human presence is detected. Treat collected audio as sensitive field data.
