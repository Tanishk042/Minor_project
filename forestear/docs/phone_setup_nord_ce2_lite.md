# Phone node setup — OnePlus Nord CE 2 Lite 5G (OxygenOS 13 / Android 13)

This is the deployment guide for turning one retired Nord CE 2 Lite 5G into a
ForestEar acoustic node. The app is built against `compileSdk 34` /
`targetSdk 34` and runs on the phone's Android 13, including the Android 14
foreground-service rules for the microphone.

Everything runs on the phone: the 9-model pipeline, decision logic, offline
queue and SMS fallback. The network is only used for JSON alerts/telemetry and
(if explicitly enabled) raw PCM for debugging.

---

## 1. What you need

- OnePlus Nord CE 2 Lite 5G, factory reset or clean user profile recommended.
- A SIM with SMS credit (only if you enable the SMS fallback). 4G-only mode is
  recommended so the radio does not hunt for 5G.
- A laptop with `adb` (Android platform-tools) and JDK 17+.
- A charger / power bank and a dry, shaded mounting point.
- Optional: a TRRS headset microphone for better placement.

The phone must **not** be relied on as the only alert path. Treat it as a
sensor that improves coverage; the backend and Telegram path remain the
primary notification channel.

---

## 2. Build and install

```bash
cd android/ForestEarNode
export JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or use the helper, which also grants the runtime permissions:

```bash
android/scripts/install.sh
```

The first build downloads Gradle, AGP and the TFLite AARs. Model assets are
~63 MB and are packaged uncompressed so TFLite can memory-map them.

### Permissions

| Permission | Why | Required |
|---|---|---|
| `RECORD_AUDIO` | capture | yes |
| `POST_NOTIFICATIONS` | foreground-service + alert notifications | yes on Android 13+ |
| `ACCESS_FINE_LOCATION` | platform location (optional; fixed coordinates are the default) | no |
| `READ_PHONE_STATE` | dual-SIM / signal metadata | no |
| `SEND_SMS` | SMS fallback for CRITICAL events | no |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Doze exemption prompt | yes for reliability |

```bash
android/scripts/grant_permissions.sh        # no SMS
android/scripts/grant_permissions.sh --sms  # include SMS fallback
```

---

## 3. First-run wizard (OxygenOS-specific)

Open the app once after install. On Android 14 the service cannot acquire the
`microphone` foreground type from a boot receiver, so the app starts with the
`dataSync` type and upgrades when the activity is visible. On Android 13 this
is not strictly required, but the same flow is used.

The Setup tab walks through:

1. **Welcome** — what the node does, what leaves the phone.
2. **Permissions** — microphone + notifications (SMS/location optional).
3. **Battery exemption** — uses the proper
   `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intent and verifies the
   result with `PowerManager.isIgnoringBatteryOptimizations`. If the intent is
   unavailable, it falls back to the app-details screen.
4. **OxygenOS background controls** — three separate switches that adb cannot
   set:
   - **Auto-launch / startup manager**: allow ForestEar Node.
   - **Background activity**: allow.
   - **Recents lock**: open the recents card menu and lock the app.
   The app links to the relevant Settings screens where the intent is public,
   and otherwise shows exact tap paths.
5. **Network + backend** — set the backend URL and shared token. The default
   `http://127.0.0.1:8000` only works with `adb reverse`.
6. **Audio input** — built-in vs wired headset (see below).
7. **Finish** — starts the foreground service.

A persistent amber banner remains on the Status tab while any protection is
missing. The app never hides a missing exemption behind a one-time toast.

### adb equivalent for the Doze allowlist

```bash
android/scripts/whitelist_battery.sh
```

This adds the package to the device-idle allowlist (the same list as
Settings → Battery → Unrestricted) and verifies it. Auto-launch, background
activity and recents lock must still be set by hand.

---

## 4. Network: prefer 4G-only

OxygenOS may keep 5G enabled by default. On a marginal forest cell this makes
the modem hunt between 5G/4G, which costs power and produces gaps in
heartbeats. Recommended:

1. Settings → Mobile network → Preferred network type → **4G/LTE only**.
2. Disable Wi-Fi scanning / "Wi-Fi scanning always available" if you do not
   use Wi-Fi.
3. Keep the backend URL on the LAN when testing, and on the reachable
   hostname/VPN when deployed.

Heartbeats carry signal dBm, network type, operator, battery %, charging
state, current draw, temperature and thermal status, so coverage gaps are
visible in the backend even when alerts are not firing.

---

## 5. Microphone selection and calibration

The Nord CE 2 Lite has no 3.5 mm jack, so a wired microphone means a
**USB-C → TRRS adapter** with a CTIA/OMTP-compatible headset mic. The app:

- enumerates input devices and logs type, address and channel count;
- prefers a wired/USB external input when `InputChoice.AUTO` is selected;
- lets you force **Built-in** or **Wired headset** in Settings;
- applies a separate gain per input (0.25×–8×).

Calibration (Setup → Calibration, also a tab):

- live dBFS RMS and peak, clipping ratio, per-input gain;
- shows which audio source and effects are active (`UNPROCESSED`, AGC/NS/AEC
  disabled or unavailable);
- use it to set gain so normal forest ambience sits around **−45 to −30 dBFS**
  and a close chainsaw peaks below 0 dBFS. If clipping is non-zero, lower the
  gain.

`AudioRecord` is opened at 16 kHz mono 16-bit, `UNPROCESSED` when advertised,
otherwise `MIC`; AGC/NS/AEC are explicitly disabled when present. The capture
thread restarts `AudioRecord` after errors and reports the restart count.

---

## 6. Running and watching the node

Start/stop from the app, or over adb:

```bash
# start (re-enables auto-start)
adb shell am broadcast -a org.forestear.node.START \
  -n org.forestear.node/.service.ControlReceiver

# stop (disables auto-start, cancels watchdogs; a debug stop stays stopped)
adb shell am broadcast -a org.forestear.node.STOP \
  -n org.forestear.node/.service.ControlReceiver
```

Status tab shows uptime, restart count, dropped frames, active input, effects,
battery, signal, governor level, the last window's scores and backend/queue
state. Diagnose tab adds the live spectrogram, score bars, threat score,
self-test and delegate benchmark.

### Watchdog and boot

- `WorkManager` every 15 min and `AlarmManager` every 5 min check whether the
  service is alive and restart it if `autoStart` is true.
- `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` and vendor
  quick-boot actions re-arm the node.
- Removing the app from recents triggers `onTaskRemoved`, which schedules a
  watchdog restart. The service is `stopWithTask=false`.
- Restart count excludes the first-ever launch; uptime and restart count are
  persisted and included in heartbeats.

---

## 7. Thermal and battery behaviour

Thresholds (config `android.thermal`):

| Level | Trigger | Behaviour |
|---|---|---|
| NORMAL | < 40 °C | full rate |
| WARM | ≥ 40 °C | hop ×2, M8 duty 1/20, M6 every 2nd window |
| HOT | ≥ 45 °C | hop ×4, M8 off, M6 off |
| CRITICAL | ≥ 50 °C | inference paused, warning notification |
| PAUSED | ≥ 55 °C | hard pause, resume only after hysteresis (3 °C) |

Low battery maps into the same governor: ≤ 20 % behaves like WARM, ≤ 10 %
like HOT, and a critical notification is emitted. Every level change is sent
to the backend as a system event.

Battery CSV (`Android/data/org.forestear.node/files/logs/battery_log.csv`) is
written once a minute and rotated at ~5 MB. Pull it with
`android/scripts/capture_logs.sh`.

**Do not** put the phone in a sealed black box in the sun. Use a ventilated,
shaded enclosure and keep the charger outside the hot zone. The governor will
protect the phone by reducing analysis rate, which means it can miss short
events; shade is part of the deployment, not an optimisation.

---

## 8. Opt-in ambient collection (privacy)

Collection is **off by default**. When enabled in Settings or with
`COLLECT_ON`:

- 60 s chunks are written to `Android/data/org.forestear.node/files/collection`;
- files are capped and rotated (2 GB default);
- a chunk is **deleted in full** if the human-presence model fires inside it;
- collection pauses while inference is paused;
- no speech clips are ever attached to alerts or uploaded by the alert path.

Export and optionally clear:

```bash
android/scripts/pull_collection.sh
android/scripts/pull_collection.sh --clear
```

These recordings are sensitive field data. Handle them under whatever consent
and data-protection rules apply to your deployment.

---

## 9. SMS fallback (dual SIM)

Only **CRITICAL** events (gunshot / escalated chainsaw) trigger SMS. The
payload is compact (`FE|...`) and kept under 160 characters. The app:

- lists subscriptions and lets you select the sending SIM;
- uses `SmsManager.getSmsManagerForSubscriptionId` (falling back to the
  default manager);
- enforces a persisted rate limit (default one per 5 min, six per hour);
- records sent/delivered status via `SmsDeliveryReceiver`;
- shows the last SMS status on the Status tab.

SMS is a last resort: it costs money, has no delivery guarantee, and may be
delayed. The backend/Telegram path should still be configured.

---

## 10. Diagnostics and benchmarking

- **Hardware self-test**: microphone + clipping, headset detection,
  permissions, protections, network, backend, model loading, SMS capability,
  thermal readings. Each item is PASS/WARN/FAIL.
- **Delegate benchmark**: runs each model with XNNPACK CPU and the GPU
  delegate, records mean/p95 latency, CPU % and RAM, chooses the faster stable
  delegate per model, and writes `Android/data/org.forestear.node/files/logs/benchmark.json`.
- **Adaptive hop**: if a window exceeds the 400 ms analysis budget, the hop is
  lengthened (up to ×8); after 10 fast windows it steps back down.
- **Instrumented parity/latency tests** (`app/src/androidTest`) compare the
  on-device pipeline against the Python golden vectors and enforce a 1500 ms
  sanity budget. They need a device/emulator:

  ```bash
  ./gradlew :app:connectedDebugAndroidTest
  ```

  These tests are written but have **not** been run in this repository; no
  on-phone latency or parity number is claimed until they are.

---

## 11. 24-hour soak

```bash
android/scripts/soak.sh --start
android/scripts/soak.sh --status
android/scripts/soak.sh --watch 3600
android/scripts/soak.sh --stop      # pulls soak_report.json + CSVs
```

The report contains duration, restarts during the run, dropped frames, alerts
sent, queue depth, min/max battery, min/max temperature and whether 24 h was
completed. Start it on a full battery with the charger connected and the phone
mounted in its final enclosure.

---

## 12. Helper scripts

| Script | Purpose |
|---|---|
| `android/scripts/install.sh` | build + install + grant permissions |
| `android/scripts/grant_permissions.sh` | grant mic/notifications/location/phone (opt-in SMS) |
| `android/scripts/whitelist_battery.sh` | Doze allowlist + verification |
| `android/scripts/self_test.sh` | headless PASS/FAIL checks over adb |
| `android/scripts/capture_logs.sh` | logcat + battery/thermal + reports |
| `android/scripts/pull_collection.sh` | export/clear ambient chunks |
| `android/scripts/soak.sh` | drive and collect a 24 h soak |
| `android/tools/stream_receiver.py` | receive the raw PCM WebSocket on a laptop |

---

## 13. Troubleshooting

| Symptom | Check |
|---|---|
| Service dies after a while | battery exemption, auto-launch, background activity, recents lock; check `self_test.sh` |
| No microphone audio | `RECORD_AUDIO`; another app holding the mic; try Built-in vs Wired in Settings |
| Clipping / distorted | lower per-input gain in Calibration; move the mic away from wind |
| No alerts reaching backend | backend URL/token, `adb reverse tcp:8000 tcp:8000` for localhost, Wi-Fi/4G state |
| Queue grows | backend unreachable; queue is capped and flushes with exponential backoff |
| Phone hot / throttled | shade, ventilate, move charger; check governor level and `battery_log.csv` |
| SMS not sent | `SEND_SMS`, selected SIM, credit, rate limit, last SMS status |
| Boot does not restart | auto-start enabled? `BOOT_COMPLETED` not blocked by OEM? open app once after install |

---

## Honest limits of this guide

- No on-phone latency, battery-life or thermal number is claimed here. The
  instrumented tests and 24 h soak exist to produce them; run them on your
  device and record the output.
- The Android 14 microphone-foreground rule is implemented defensively even
  though the target phone runs Android 13.
- OxygenOS auto-launch/background controls vary by build and cannot be set by
  adb; the wizard links to them but the operator must confirm.
