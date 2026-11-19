# ForestEar Node — setting up a new phone (Windows or macOS)

Ordered, copy-pasteable commands. Run them in order. Anything not actually run
stays **NOT RUN / NOT VERIFIED** in the evidence — do not report a PASS for a
command you skipped.

> **Repo note:** at the time of writing, the GitHub repo contains `forestear/`
> plus the report PDF/generator. The `android/` tree is still blocked by iCloud
> (dataless files) and is not yet pushed. Until it is pushed, use your local
> `android/` checkout for these commands.

---

## 0. What you need (both operating systems)

- Android **8.0+ (API 26)** phone/tablet with a microphone. No Play Services needed.
- USB cable that carries data (not charge-only).
- JDK **17+** (21 recommended — AGP 8.5 breaks on JDK 24), Android SDK `platform-tools`, Git.
- A charger for long runs; inspect the battery for swelling before leaving it powered.
- Backend running on a laptop (same Wi-Fi), or `adb reverse` for USB-only work.

**Phone-side preparation (do this on the phone first):**
1. Settings → About phone → tap **Build number** 7 times → Developer options enabled.
2. Developer options → **USB debugging: ON**.
3. Developer options → **USB configuration: File transfer (MTP)**.
4. Accept the "Allow USB debugging?" prompt (tick *Always allow* for this computer).
5. Some OEMs need extra switches: Xiaomi **Install via USB** / **USB debugging (Security settings)**, Oppo/OnePlus **Allow USB debugging**, Samsung **Allow USB debugging**.
6. Charge to ≥50 % before the benchmark and soak.

---

## 1. Install host tools

### macOS

```bash
# Homebrew (if not installed)
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

brew install --cask android-platform-tools
brew install openjdk@21 git

# Java 21 -> the path used by the scripts
sudo ln -sfn /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk \
  /Library/Java/JavaVirtualMachines/openjdk-21.jdk

# Android SDK location used by scripts (no full Android Studio needed for adb;
# Gradle needs the SDK, so install Android Studio if gradle complains)
mkdir -p "$HOME/Library/Android/sdk"

java -version
adb version
```

### Windows (PowerShell, run as your normal user)

```powershell
# winget (built into Windows 11 / modern Windows 10)
winget install --id Google.AndroidSdkPlatformTools -e
winget install --id EclipseAdoptium.Temurin.21.JDK -e
winget install --id Git.Git -e

# Android Studio installs the SDK + command-line tools (needed by Gradle)
winget install --id Google.AndroidStudio -e
```

Then set persistent environment variables (new terminal after this):

```powershell
[Environment]::SetEnvironmentVariable("ANDROID_HOME", "$env:LOCALAPPDATA\Android\Sdk", "User")
[Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Eclipse Adoptium\jdk-21", "User")
[Environment]::SetEnvironmentVariable("Path", "$env:Path;$env:LOCALAPPDATA\Android\Sdk\platform-tools", "User")
```

Verify in a **new** PowerShell:

```powershell
java -version
adb version
```

---

## 2. Get the code

### macOS

```bash
git clone https://github.com/Tanishk042/Minor_project.git Minor_project
cd Minor_project
# Until android/ is pushed, copy your local checkout in if the clone lacks it:
# cp -R /path/to/local/android ./android
```

### Windows

Use **Git Bash** (installed with Git) for the `android/scripts/*.sh` helpers —
they are bash scripts. Open Git Bash:

```bash
git clone https://github.com/Tanishk042/Minor_project.git Minor_project
cd Minor_project
# ANDROID_HOME in Git Bash form:
export ANDROID_HOME="/c/Users/$USERNAME/AppData/Local/Android/Sdk"
```

Pure-PowerShell users can skip the `.sh` scripts and use the direct `adb` /
`gradlew.bat` commands given in steps 4–8 below.

---

## 3. Connect and verify the phone

### macOS / Git Bash (same command)

```bash
adb kill-server
adb start-server
adb devices -l
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
```

Expect exactly one device in state `device`. If it says `unauthorized`, accept the
prompt on the phone. If `offline`, re-plug and revoke/accept USB debugging.

---

## 4. Start the backend (needed for the wizard's /health check)

### macOS

```bash
cd Minor_project/forestear
python3 -m venv ../.venv
source ../.venv/bin/activate
pip install -r requirements.txt
make backend            # terminal 1: FastAPI + dashboard on :8000
```

In a second terminal, feed synthetic traffic so the dashboard has data:

```bash
cd Minor_project/forestear && source ../.venv/bin/activate && make smoke
```

### Windows (PowerShell)

```powershell
cd Minor_project\forestear
py -m venv ..\.venv
..\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
# run the FastAPI app directly (or use make if you have it):
python -m uvicorn backend.main:app --host 0.0.0.0 --port 8000
```

Allow port 8000 through the Windows Firewall on Private networks. Find the laptop
LAN IP with `ipconfig`; the phone will use `http://<laptop-lan-ip>:8000`.

**USB-only alternative (no LAN):**

```bash
adb reverse tcp:8000 tcp:8000
# phone Wizard backend URL: http://127.0.0.1:8000
```

---

## 5. Build and install the app

### macOS (scripts handle JDK/SDK paths)

```bash
cd Minor_project/android
./scripts/install.sh                 # debug build + install + safe permissions
# OxygenOS / ROMs that refuse `pm grant`:
./scripts/install.sh --grant-all     # adb install -g -r -> grants ALL incl. SEND_SMS
```

`--grant-all` is an explicit operator decision: it grants **SEND_SMS** too. If you
do not want SMS, grant manually in Settings → Apps → ForestEar Node → Permissions.

### Git Bash on Windows

```bash
cd Minor_project/android
export ANDROID_HOME="/c/Users/$USERNAME/AppData/Local/Android/Sdk"
./scripts/install.sh --grant-all
```

### Pure PowerShell / cmd (no bash scripts)

```powershell
cd Minor_project\android\ForestEarNode
.\gradlew.bat :app:assembleDebug --console=plain
adb install -g -r app\build\outputs\apk\debug\app-debug.apk
```

Expected debug APK: ~100 MB (models packaged uncompressed). If the APK is only a
few MB, the model assets are missing — do not continue.

---

## 6. Verify permissions (do not assume a grant succeeded)

### macOS / Git Bash

```bash
cd Minor_project/android
./scripts/grant_permissions.sh            # checks mic, notifications, location, phone state
./scripts/grant_permissions.sh --sms      # only if you want SMS fallback
adb shell dumpsys package org.forestear.node | grep 'granted=true'
```

`grant_permissions.sh` prints `FAILED` (and exits 2) when the ROM blocks shell
grants — it never silently "skips". On OxygenOS use the `-g` install from step 5
or grant manually in Settings.

### PowerShell equivalent

```powershell
adb shell dumpsys package org.forestear.node | Select-String "granted=true"
adb shell pm grant org.forestear.node android.permission.RECORD_AUDIO
adb shell pm grant org.forestear.node android.permission.POST_NOTIFICATIONS
# OxygenOS will reject pm grant with SecurityException -> use adb install -g -r
```

---

## 7. Battery / background protections

### macOS / Git Bash

```bash
cd Minor_project/android
./scripts/whitelist_battery.sh
./scripts/whitelist_battery.sh --check
```

### PowerShell equivalent

```powershell
adb shell cmd deviceidle whitelist +org.forestear.node
adb shell dumpsys deviceidle whitelist | Select-String "org.forestear.node"
```

Then **on the phone**, because adb cannot set OEM-specific switches:
- Settings → Battery → Battery optimisation → **Don't optimise / Unrestricted**
- Auto-launch / Startup manager → **Allow**
- Background activity → **Allow**
- Recents → **Lock** the ForestEar card
- Battery → App battery management → **Unrestricted**

The app's Setup step 4 deep-links to these; verify each switch visually.

---

## 8. First launch + 7-step wizard (phone UI)

1. Open **ForestEar Node** once — Android 14+ requires a foreground start while visible.
2. Step 1–2: accept privacy/consent; confirm permissions from step 6.
3. Step 3–4: battery/OEM protections from step 7.
4. Step 5: run **Screen-off continuity test** (1–60 min). Start monitoring, turn the screen off, leave it still, stop. Pass = ≥90 % coverage and zero gap events.
5. Step 6: Node ID (e.g. `node-07`), Backend URL (`http://<laptop-ip>:8000` or `http://127.0.0.1:8000` with `adb reverse`), Auth token = backend `FORESTEAR_TOKEN` (default `forestear-dev-token`). Tap **Test /health**.
6. Step 7: finish. Open **Diagnose → Benchmark** once; read the chosen tier from the measured ms — do not copy the laptop's 5.56 ms figure.
7. Settings → Audio input: Auto/Built-in/3.5 mm/USB; use Calibration to target roughly −30 to −12 dBFS without clipping.

---

## 9. Headless self-test

### macOS / Git Bash

```bash
cd Minor_project/android
BACKEND_URL=http://127.0.0.1:8000 ./scripts/self_test.sh
```

Look for `failures=0`. Warnings are allowed (e.g. no SIM, no USB mic). On the
measured CPH2381 this produced `failures=0 warnings=2`.

### PowerShell equivalent (selected checks)

```powershell
adb devices
adb shell dumpsys package org.forestear.node | Select-String "versionName|granted=true"
adb shell dumpsys deviceidle whitelist | Select-String "org.forestear.node"
adb shell dumpsys activity services org.forestear.node | Select-String "NodeService"
adb shell dumpsys thermalservice | Select-String "Thermal Status"
(Invoke-WebRequest -Uri http://127.0.0.1:8000/health -UseBasicParsing).Content
adb logcat -d -b crash -t 200 | Select-String "org.forestear.node"
```

The in-app **Diagnose → Hardware self-test** additionally probes mic dBFS/clipping,
headset detection, TFLite delegate and thermal sensors.

---

## 10. Recovery-path tests (run on every new handset)

### macOS / Git Bash

```bash
cd Minor_project/android/scripts
./recovery_test.sh status
./recovery_test.sh kill                    # records NOT RUN if refused
./recovery_test.sh kill --force-stop       # hard kill
./recovery_test.sh swipe --wait 60 --confirm
./recovery_test.sh screen-off --wait 30
./recovery_test.sh reboot
./recovery_test.sh report
```

Manual microphone-theft test (make a call / open the voice recorder while the
node runs), then record the outcome honestly:

```bash
./recovery_test.sh record mic_theft PASS "10 s zero signal, DEGRADED, 30 s restart"
```

Expect on Android 14+: force-kill / watchdog / reboot **FAIL** (platform blocks
background microphone-FGS restart); swipe/screen-off/mic-theft PASS. Never
record an unexercised path as PASS.

### PowerShell equivalent

```powershell
# No .sh scripts: use the in-app Diagnostics table, or run the scripts via Git Bash.
adb shell am broadcast -a org.forestear.node.SOAK_ON -n org.forestear.node/.service.ControlReceiver
```

PowerShell-only users should use the in-app Diagnostics screen to read outcomes;
the recording broadcasts are easiest through Git Bash.

---

## 11. Soak / long-duration check

### macOS / Git Bash

```bash
cd Minor_project/android/scripts
./soak.sh --start
./soak.sh --status
./soak.sh --watch 3600          # optional monitoring window
./soak.sh --stop                # pulls soak_report.json, soak_samples.csv, battery_log.csv
```

Keep the phone **charging the whole time**. A run that loses power is invalid.
The last measured run reached 23 h 53 m (17,072 samples, `completed_24h=false`);
no full-day PASS is claimed. Run to a true 24 h if you need that claim.

### PowerShell equivalent

```powershell
adb shell am broadcast -a org.forestear.node.SOAK_ON -n org.forestear.node/.service.ControlReceiver
adb shell am broadcast -a org.forestear.node.SOAK_OFF -n org.forestear.node/.service.ControlReceiver
$remote = "/sdcard/Android/data/org.forestear.node/files/logs"
adb pull "$remote/soak_report.json" .\soak_report.json
adb pull "$remote/soak_samples.csv" .\soak_samples.csv
adb pull "$remote/battery_log.csv" .\battery_log.csv
```

---

## 12. Capture logs and pull evidence

### macOS / Git Bash

```bash
cd Minor_project/android/scripts
./capture_logs.sh 30             # 30 s logcat + device/battery/package dumps
./capture_logs.sh --dump-only    # skip live capture, just pull on-device reports
adb pull /sdcard/Android/data/org.forestear.node/files/logs/ benchmark_out
```

### PowerShell equivalent

```powershell
adb logcat -v time ForestEar:V AndroidRuntime:E *:S > logcat.txt
adb shell dumpsys battery > battery.txt
adb shell dumpsys thermalservice > thermal.txt
adb shell dumpsys package org.forestear.node > package.txt
adb pull /sdcard/Android/data/org.forestear.node/files/logs/ .\device_logs
```

---

## 13. Optional: signed release build (needs keystore — NOT RUN here)

```bash
# macOS / Git Bash
cd Minor_project/android
cp keystore.properties.example keystore.properties   # fill values locally, never commit
./scripts/build_release.sh
apksigner verify --verbose app-release.apk
```

```powershell
cd Minor_project\android
Copy-Item keystore.properties.example keystore.properties
# fill values locally, then run the build script via Git Bash; verify:
apksigner verify --verbose app-release.apk
```

No keystore/passwords were supplied, so this remains **NOT RUN**.

---

## 14. Final checklist before calling a node ready

- [ ] `adb devices` shows one `device`
- [ ] APK ~100 MB, installed (prefer `-g` on OxygenOS, SEND_SMS accepted explicitly)
- [ ] `granted=true` for RECORD_AUDIO and POST_NOTIFICATIONS
- [ ] Doze allowlist contains `org.forestear.node`
- [ ] Wizard completed; `/health` returned `{"status":"ok",...}`
- [ ] Benchmark run on this handset; tier recorded from measured ms
- [ ] Screen-off continuity ≥90 % coverage, zero gaps
- [ ] Self-test `failures=0`
- [ ] Recovery paths recorded (PASS/FAIL/NOT RUN honestly)
- [ ] Soak charged throughout; report pulled; gaps ≤90 s
- [ ] Logs/evidence pulled into the repo
- [ ] Release APK / Telegram / SMS / field audio still marked NOT RUN / NOT VERIFIED until actually exercised
