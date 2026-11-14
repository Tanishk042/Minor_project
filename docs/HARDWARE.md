# Hardware notes — phone node (OnePlus Nord CE 2 Lite 5G)

This document covers the physical node: what the phone is, how it is powered
and cooled, how the microphone is mounted, and which numbers are measured vs
estimated. It is deliberately blunt about what has **not** been measured on a
real device.

---

## Device

| Item | Value |
|---|---|
| Model | OnePlus Nord CE 2 Lite 5G (`CPH2381`) |
| SoC | Qualcomm Snapdragon 695 5G (6 nm), 2×A78 + 6×A55 |
| RAM / storage | 6 or 8 GB / 128 GB UFS 2.2 |
| Battery | 5000 mAh, non-removable, 33 W SuperVOOC |
| Display | 6.59" 1080×2412, 120 Hz (irrelevant for a node; keep it off) |
| OS | OxygenOS 12/13 (Android 12/13); app targets SDK 34 |
| Audio jack | **none** — wired mic needs a USB-C → TRRS adapter |
| Sensors | accelerometer, gyro, proximity, compass; no barometer |
| Connectivity | 4G/5G, Wi-Fi 5, Bluetooth 5.2, GPS |

The phone is retired hardware: the point is that it already exists, has a
decent microphone and a 5000 mAh battery, and costs nothing extra. It is not
rugged and is not waterproof.

---

## Power

The node is expected to run continuously. Options, in order of preference:

1. **Mains charger + USB cable** in a dry enclosure. Best for a fixed post.
2. **Power bank** (20 000 mAh+) with pass-through charging. The bank buffers
   outages and keeps the phone cooler than charging directly in the sun.
3. **Solar + battery** only if the panel and charge controller are sized for
   continuous 2–4 W load and several days of bad weather.

There is **no measured average current draw in this repository**. The app
records `currentMa` from `BatteryManager` in every heartbeat and once a minute
in `battery_log.csv`; a 24 h soak produces the real number for a given
configuration (screen off, 4G-only, governor level, M8 duty). Until you run
it, treat any battery-life figure as an estimate.

What is known qualitatively:

- The screen must stay off. The foreground service does not need it.
- 4G-only reduces modem hunting; 5G is not needed for JSON telemetry.
- The thermal governor deliberately trades analysis rate for temperature, so
  a hot node uses less CPU but may miss short events.
- Raw PCM streaming and ambient collection both increase power and storage
  use; leave them off in normal operation.

Low-battery behaviour: ≤ 20 % behaves like the WARM governor (hop ×2), ≤ 10 %
like HOT (hop ×4), and a critical notification is raised. The node does not
silently die without telling the backend.

---

## Thermal

The governor reads the **battery temperature** from `BatteryManager` and the
platform thermal status. Battery temperature is not SoC junction temperature;
it lags and is usually lower than the skin. The thresholds are therefore
conservative:

| Level | Battery temp | Effect |
|---|---|---|
| NORMAL | < 40 °C | full rate |
| WARM | ≥ 40 °C | hop ×2, M8 1/20, M6 every other window |
| HOT | ≥ 45 °C | hop ×4, M8 off, M6 off |
| CRITICAL | ≥ 50 °C | inference paused + warning |
| PAUSED | ≥ 55 °C | hard pause until hysteresis (3 °C) |

These are config values (`android.thermal` in `forestear.json`), not measured
phone limits. A sealed black enclosure in direct sun will reach them; that is
a deployment error, not a model error.

Cooling rules:

- Shade the enclosure; do not rely on ambient air alone.
- Ventilate top and bottom (convection), and keep the charger outside the hot
  zone where possible.
- Do not insulate the phone with foam.
- Mount the phone upright so the back is exposed to air, not flat on a board.
- If the governor is repeatedly at HOT, reduce the analysis rate or move the
  node; do not raise the thresholds to "fix" it.

---

## Microphone

### Built-in

The bottom-port MEMS microphone is the default. It is omnidirectional enough
for a node but has:

- no wind protection (a foam windscreen helps a lot);
- AGC/NS/AEC that the app disables when the platform exposes them;
- a frequency response that is **not characterised** in this repository.

The app logs the actual source (`UNPROCESSED` or `MIC`) and the effect state.
If `UNPROCESSED` is unavailable, the platform may apply processing that shifts
the spectral envelope the models were trained on; this is visible in the
calibration screen and in the logs, not hidden.

### Wired headset mic

The phone has no 3.5 mm jack. Use a **USB-C → 3.5 mm TRRS adapter** with a
CTIA-wired headset mic (most headsets). The app:

- detects external inputs and prefers them in `AUTO` mode;
- lets you force Built-in or Wired headset;
- applies a separate gain per input.

A wired mic lets you place the capsule away from the phone, which reduces
self-noise and thermal effects. It also adds a failure point (adapter, cable,
connector). Test it in the calibration screen before deployment.

### Calibration target

Use Calibration to set gain so that:

- quiet forest ambience is around **−45 to −30 dBFS RMS**;
- a nearby chainsaw peaks below 0 dBFS;
- clipping ratio stays at **0**.

Clipping is detected per window and logged. A clipped window is not useful for
classification; the node still processes it but the score is suspect.

---

## Enclosure and mounting

A practical build:

- IP65 vented box, light-coloured, with a drip shield.
- Phone upright, back exposed to air, screen off.
- Microphone port open to the outside through a foam windscreen; avoid a
  resonant cavity in front of the capsule.
- Cable gland for USB power; strain relief.
- Desiccant pack; replace it at each service visit.
- Security: the phone is a theft target. Use a locked box and a tamper-evident
  seal, and do not leave the SIM tray accessible.
- Height: 2–4 m above ground, clear of branches that rub the box.

The node is not lightning-safe. Do not mount it on a tall isolated mast; use a
tree or a short post and keep cabling away from strike paths.

---

## Storage and data

| Item | Size |
|---|---|
| APK + model assets | ~63 MB |
| Battery CSV | ~1 row/min, rotated at ~5 MB |
| Benchmark report | a few KB |
| Soak report + samples | a few MB per 24 h |
| Ambient collection | 60 s chunks, cap 2 GB, off by default |

Collection chunks are deleted in full when the human-presence model fires
inside them. No speech clips are attached to alerts or uploaded by the alert
path. Export with `android/scripts/pull_collection.sh`.

---

## Network

- JSON alerts/heartbeats are small (hundreds of bytes) and queued durably when
  offline.
- `/ingest/scores` is WebSocket-only and not persisted.
- Raw PCM streaming (debug only) is ~256 kbit/s at 16 kHz × 16 bit.
- SMS fallback is CRITICAL-only, compact, rate-limited, and dual-SIM aware.

Prefer 4G-only and a reachable backend hostname/VPN. For local testing,
`adb reverse tcp:8000 tcp:8000` makes `http://127.0.0.1:8000` work over USB.

---

## What is measured vs not

**Measured (this repository):**

- Python edge pipeline latency: **5.56 ms/hop** total on the Apple M1 laptop
  (not the phone).
- Python test-suite results and model metrics in `reports/`.
- JVM parity of log-mel, handcrafted features, M9 trees and decision logic
  against Python golden vectors (see the Android README).

**Not measured (needs the physical phone):**

- On-phone per-model latency and CPU/RAM (the instrumented benchmark and
  `connectedDebugAndroidTest` produce them).
- Average current draw and battery life.
- Thermal behaviour in the enclosure.
- Real-world detection range for chainsaw/gunshot on this microphone.
- SMS delivery reliability on a given carrier.

Do not quote a phone number that is not in `benchmark.json` or `soak_report.json`
from your own run.
