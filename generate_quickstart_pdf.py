#!/usr/bin/env python3
"""Generate a plain-language ForestEar quick-start PDF (services + phone setup).

The PDF is deliberately written for a non-specialist: each command is preceded
by what it does and followed by how to tell whether it worked. Windows and macOS
are kept in separate, self-contained sections.
"""

from __future__ import annotations

from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import (
    PageBreak,
    Paragraph,
    Preformatted,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)

OUT = Path(__file__).resolve().parent / "ForestEar_Quick_Start_Guide.pdf"

FOREST = colors.HexColor("#17492F")
GREEN = colors.HexColor("#2F7D4F")
PALE = colors.HexColor("#E8F1E9")
AMBER = colors.HexColor("#B36B00")
AMBER_BG = colors.HexColor("#FFF3DF")
RED = colors.HexColor("#9B2C2C")
RED_BG = colors.HexColor("#FDECEC")
BLUE = colors.HexColor("#1D4E89")
BLUE_BG = colors.HexColor("#EAF1FA")
INK = colors.HexColor("#263238")
GREY = colors.HexColor("#60746B")
LINE = colors.HexColor("#CBD8CF")
WHITE = colors.white

ss = getSampleStyleSheet()

BODY = ParagraphStyle(
    "Body", parent=ss["BodyText"], fontName="Helvetica", fontSize=9.7,
    leading=14, textColor=INK, alignment=TA_LEFT, spaceAfter=6,
)
SMALL = ParagraphStyle("Small", parent=BODY, fontSize=8.4, leading=11.8, spaceAfter=4)
CELL = ParagraphStyle("Cell", parent=BODY, fontSize=8.4, leading=11.5, spaceAfter=0)
CELLB = ParagraphStyle("CellB", parent=CELL, fontName="Helvetica-Bold")
H1 = ParagraphStyle("H1", parent=ss["Heading1"], fontName="Helvetica-Bold",
                    fontSize=16, leading=20, textColor=FOREST,
                    spaceBefore=10, spaceAfter=9, outlineLevel=0)
H2 = ParagraphStyle("H2", parent=ss["Heading2"], fontName="Helvetica-Bold",
                    fontSize=12, leading=16, textColor=GREEN,
                    spaceBefore=8, spaceAfter=6, outlineLevel=1)
H3 = ParagraphStyle("H3", parent=ss["Heading3"], fontName="Helvetica-Bold",
                    fontSize=10.2, leading=13.5, textColor=INK,
                    spaceBefore=7, spaceAfter=4, outlineLevel=2)
STEP = ParagraphStyle("Step", parent=BODY, fontName="Helvetica-Bold",
                      fontSize=10.2, leading=14, textColor=FOREST,
                      spaceBefore=8, spaceAfter=3)
CODE = ParagraphStyle("Code", parent=BODY, fontName="Courier", fontSize=7.0,
                      leading=9.6, textColor=colors.HexColor("#1B3A2C"),
                      alignment=TA_LEFT, spaceAfter=0)
COVER_T = ParagraphStyle("CoverT", fontName="Helvetica-Bold", fontSize=26,
                         leading=32, textColor=WHITE, alignment=TA_CENTER)
COVER_S = ParagraphStyle("CoverS", fontName="Helvetica", fontSize=13,
                         leading=19, textColor=colors.HexColor("#D7E8DC"),
                         alignment=TA_CENTER)


def p(text: str, style=BODY) -> Paragraph:
    return Paragraph(text, style)


def step(text: str) -> Paragraph:
    return p(text, STEP)


def note(text: str) -> Paragraph:
    return p(text, SMALL)


def code(text: str) -> Preformatted:
    return Preformatted(text, CODE, maxLineLength=100)


def sp(h: float = 4) -> Spacer:
    return Spacer(1, h)


def h1(t: str) -> Paragraph:
    return p(t, H1)


def h2(t: str) -> Paragraph:
    return p(t, H2)


def h3(t: str) -> Paragraph:
    return p(t, H3)


def box(title: str, text: str, kind: str = "green") -> Table:
    bg, bar, fg = {
        "green": (PALE, GREEN, FOREST),
        "amber": (AMBER_BG, AMBER, AMBER),
        "red": (RED_BG, RED, RED),
        "blue": (BLUE_BG, BLUE, BLUE),
    }[kind]
    inner = [
        [p(title, ParagraphStyle("BT", parent=CELLB, textColor=fg, fontSize=9.4))],
        [p(text, ParagraphStyle("BB", parent=CELL, fontSize=8.7, leading=12.3))],
    ]
    t = Table(inner, colWidths=[170 * mm], hAlign="LEFT")
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), bg),
        ("LINEBEFORE", (0, 0), (0, -1), 3, bar),
        ("LEFTPADDING", (0, 0), (-1, -1), 9),
        ("RIGHTPADDING", (0, 0), (-1, -1), 8),
        ("TOPPADDING", (0, 0), (-1, 0), 7),
        ("BOTTOMPADDING", (0, -1), (-1, -1), 7),
        ("BOX", (0, 0), (-1, -1), 0.4, LINE),
    ]))
    return t


def cmd(command: str, what: str, success: str) -> Table:
    rows = [
        [p("<b>What this does:</b> " + what, CELL)],
        [Preformatted(command, CODE, maxLineLength=108)],
        [p("<b>You know it worked when:</b> " + success, CELL)],
    ]
    t = Table(rows, colWidths=[170 * mm], hAlign="LEFT")
    t.setStyle(TableStyle([
        ("GRID", (0, 0), (-1, -1), 0.4, LINE),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("BACKGROUND", (0, 0), (0, 0), colors.HexColor("#F3F7F3")),
        ("BACKGROUND", (0, 1), (0, 1), colors.HexColor("#EAF2EA")),
        ("BACKGROUND", (0, 2), (0, 2), colors.HexColor("#F3F7F3")),
        ("LEFTPADDING", (0, 0), (-1, -1), 6),
        ("RIGHTPADDING", (0, 0), (-1, -1), 6),
        ("TOPPADDING", (0, 0), (-1, -1), 4),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
    ]))
    return t


S: list = []

# ---------- cover ----------
S += [
    sp(28),
    p("FORESTEAR", COVER_T),
    p("Start the services &amp; set up your phone", COVER_T),
    sp(8),
    p("A plain-language, step-by-step guide<br/>for Windows and macOS", COVER_S),
    sp(20),
    box("Who this is for",
        "Someone who has the ForestEar folder on a laptop and a new Android phone, "
        "and wants to get the system running without guessing. Every step says what to "
        "type, what it means in everyday words, and how to tell if it worked.", "blue"),
    sp(10),
    box("The one idea to remember",
        "The <b>laptop is the office</b>: it runs the backend (the front desk) and shows "
        "the dashboard (the notice board). The <b>phone is the listener</b> in the forest. "
        "The USB cable plus <b>adb</b> is the bridge that lets the office talk to the "
        "listener. Nothing is uploaded to the internet for detection — all listening "
        "happens on the phone.", "green"),
    PageBreak(),
]

# ---------- 1 ----------
S += [
    h1("1. The pieces, in everyday words"),
    p("Before typing anything, it helps to know what each piece is. You do not need to "
      "understand the code — just what job each piece has."),
    Table(
        [[p("<b>Piece</b>", CELLB), p("<b>Plain-English job</b>", CELLB)]]
        + [[p(a, CELL), p(b, CELL)] for a, b in [
            ("Backend", "The front desk. It receives messages from the phone, saves them, and answers questions."),
            ("Dashboard", "The notice board in your browser. It shows a map, pins, charts and alerts."),
            ("adb", "The bridge. A small tool that lets the laptop install apps and check the phone over the cable."),
            ("ForestEar Node app", "The ear. It listens with the microphone and decides if a sound is important."),
            ("NodeService", "The worker inside the app. It keeps listening even when the screen is off."),
            ("Wi-Fi / USB reverse", "The road between the ear and the front desk. Either the same Wi-Fi, or the cable via a tunnel."),
        ]],
        colWidths=[42 * mm, 128 * mm], hAlign="LEFT",
        style=TableStyle([
            ("GRID", (0, 0), (-1, -1), 0.4, LINE),
            ("BACKGROUND", (0, 0), (-1, 0), FOREST),
            ("TEXTCOLOR", (0, 0), (-1, 0), WHITE),
            ("VALIGN", (0, 0), (-1, -1), "TOP"),
            ("ROWBACKGROUNDS", (0, 1), (-1, -1), [WHITE, colors.HexColor("#F7FAF7")]),
            ("LEFTPADDING", (0, 0), (-1, -1), 6),
            ("RIGHTPADDING", (0, 0), (-1, -1), 6),
            ("TOPPADDING", (0, 0), (-1, -1), 4),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
        ]),
    ),
    sp(6),
    box("Important honesty note",
        "This guide teaches you how to <b>run</b> the system. It does not turn a prototype "
        "into a finished product. Signed releases, real Telegram/SMS delivery and real "
        "forest accuracy are still <b>NOT RUN / NOT VERIFIED</b> until someone actually "
        "tests them with real credentials and real field audio.", "amber"),
    h1("2. Before you start (same on both computers)"),
    p("Do these on the <b>phone</b> first. They are the same whether your laptop is a Mac "
      "or a Windows machine."),
    Table(
        [[p("<b>#</b>", CELLB), p("<b>On the phone</b>", CELLB), p("<b>Why</b>", CELLB)]]
        + [[p(str(i), CELL), p(a, CELL), p(b, CELL)] for i, (a, b) in enumerate([
            ("Settings → About phone → tap <b>Build number</b> 7 times", "Unlocks the hidden Developer options menu."),
            ("Developer options → <b>USB debugging: ON</b>", "Lets the laptop talk to the phone."),
            ("Developer options → <b>USB configuration: File transfer (MTP)</b>", "Makes the cable carry data, not just power."),
            ("Accept the “Allow USB debugging?” pop-up", "Trusts this laptop. Tick “Always allow”."),
        ])],
        colWidths=[10 * mm, 88 * mm, 72 * mm], hAlign="LEFT",
        style=TableStyle([
            ("GRID", (0, 0), (-1, -1), 0.4, LINE),
            ("BACKGROUND", (0, 0), (-1, 0), FOREST),
            ("TEXTCOLOR", (0, 0), (-1, 0), WHITE),
            ("VALIGN", (0, 0), (-1, -1), "TOP"),
            ("ROWBACKGROUNDS", (0, 1), (-1, -1), [WHITE, colors.HexColor("#F7FAF7")]),
            ("LEFTPADDING", (0, 0), (-1, -1), 6),
            ("RIGHTPADDING", (0, 0), (-1, -1), 6),
            ("TOPPADDING", (0, 0), (-1, -1), 4),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
        ]),
    ),
    sp(5),
    note("Some brands add one more switch: Xiaomi calls it “Install via USB” or "
         "“USB debugging (Security settings)”. OnePlus/Oppo may say “Allow USB debugging”. "
         "Turn those on too. Charge the phone to at least 50% before the benchmark."),
    box("Repo note",
        "The GitHub repo currently contains the Python project and the report. The "
        "<b>android/</b> folder is still stuck in iCloud and is not pushed yet. For now, "
        "use the local copy on the laptop; the commands below assume the folder is there.", "amber"),
    PageBreak(),
]

# ---------- 3 macOS ----------
S += [
    h1("3. macOS — start the services, then the phone"),
    p("Work through these in order. Open a new Terminal window for step 3 and leave it "
      "running — the backend stays alive while the phone talks to it."),

    step("Step M1 — Install the tools (one time only)"),
    cmd('/bin/bash -c "$(curl -fsSL \\\n'
        '  https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"\n'
        'brew install --cask android-platform-tools\n'
        'brew install openjdk@21 git\n'
        'sudo ln -sfn /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk \\\n'
        '  /Library/Java/JavaVirtualMachines/openjdk-21.jdk\n'
        'mkdir -p "$HOME/Library/Android/sdk"',
        "Installs the USB bridge (adb), Java 21 (needed to build the app), and Git "
        "(to download the project).",
        'java -version prints “21…”, and adb version prints a number.'),

    step("Step M2 — Get the project and make a private Python environment"),
    cmd('git clone https://github.com/Tanishk042/Minor_project.git Minor_project\n'
        'cd Minor_project/forestear\n'
        'python3 -m venv ../.venv\n'
        'source ../.venv/bin/activate\n'
        'pip install -r requirements.txt',
        "Downloads the code and creates a clean place for the Python packages so they "
        "do not clash with other projects.",
        "The folder Minor_project/forestear exists and pip finishes without errors."),

    step("Step M3 — Start the backend (the front desk) and dashboard"),
    cmd('python -m backend.main --host 0.0.0.0 --port 8000',
        "Starts the front desk. The 0.0.0.0 part means “listen for the phone on the "
        "network too”, not only inside the laptop.",
        "The terminal stays open with no error, and a browser at http://127.0.0.1:8000 shows the dashboard."),
    note("Use make backend instead if you only want the laptop to see it. To let a phone "
         "on the same Wi-Fi reach it, use the command above and allow port 8000 in the "
         "macOS firewall if asked."),

    step("Step M4 — (Optional) put sample dots on the map"),
    cmd('cd Minor_project/forestear && source ../.venv/bin/activate\n'
        'make smoke',
        "Sends pretend sensor messages so the notice board is not empty. This is only "
        "for practice — it is not real data.",
        "The dashboard map gains pins and the event count goes up."),

    step("Step M5 — Connect the phone and check the bridge"),
    cmd('adb kill-server && adb start-server\n'
        'adb devices -l\n'
        'adb shell getprop ro.product.model\n'
        'adb shell getprop ro.build.version.sdk',
        "Restarts the bridge and asks it which phones are attached.",
        "Exactly one line says “device” (not “unauthorized” or “offline”), and the model/SDK print out."),
    box("If it says unauthorized",
        "Look at the phone and accept the pop-up. If it says offline, unplug and plug "
        "the cable back in, then run adb devices -l again.", "amber"),

    step("Step M6 — If you are using the USB cable instead of Wi-Fi"),
    cmd('adb reverse tcp:8000 tcp:8000',
        "Makes a tiny tunnel through the cable so the phone can reach the laptop's "
        "front desk at the address 127.0.0.1:8000.",
        "No error is printed. In the phone wizard, use http://127.0.0.1:8000 as the backend URL."),

    step("Step M7 — Install the app on the phone"),
    cmd('cd Minor_project/android\n'
        './scripts/install.sh',
        "Builds the ear app and puts it on the phone, with the safe permissions.",
        "The phone shows the app icon. The APK is about 100 MB — a tiny file means the models are missing."),
    box("OnePlus / OxygenOS note",
        "That phone refuses the “grant permission” command from the bridge. Use this "
        "instead: <b>./scripts/install.sh --grant-all</b>. Be aware this also grants SMS, "
        "so it is an explicit choice. If you do not want SMS, grant permissions by hand "
        "in Settings → Apps → ForestEar Node → Permissions.", "amber"),

    step("Step M8 — Check permissions and battery protection"),
    cmd('./scripts/grant_permissions.sh\n'
        'adb shell dumpsys package org.forestear.node | grep granted=true\n'
        './scripts/whitelist_battery.sh',
        "Checks that the microphone and notifications are allowed, and asks Android not "
        "to freeze the app to save battery.",
        "The word granted=true appears for RECORD_AUDIO and POST_NOTIFICATIONS, and the "
        "whitelist script says PASS."),
    note("Then, on the phone: Battery optimisation → Unrestricted; Auto-launch → Allow; "
         "Background activity → Allow; and lock the app card in Recents. Android does not "
         "let a cable set these, so you must do them by hand."),

    step("Step M9 — Open the app and finish the 7-step setup"),
    p("Open ForestEar Node once (Android 14+ insists the app is visible before it may "
      "start listening). Then: steps 1–2 accept permissions; steps 3–4 confirm battery "
      "settings; step 5 run the <b>Screen-off continuity test</b> (start it, turn the "
      "screen off, wait, stop — aim for ≥90% coverage and zero gaps); step 6 enter a "
      "node name like node-07, the backend URL, and the token; tap <b>Test /health</b>; "
      "step 7 finish and run <b>Diagnose → Benchmark</b> once."),

    step("Step M10 — Check the phone with the self-test"),
    cmd('BACKEND_URL=http://127.0.0.1:8000 ./scripts/self_test.sh',
        "Asks the phone a list of simple questions: is the app installed, is the "
        "microphone allowed, is the service running, can it reach the front desk?",
        "The last line says failures=0. Warnings are fine."),

    step("Step M11 — Recovery tests and a long run (only when you are ready)"),
    cmd('cd Minor_project/android/scripts\n'
        './recovery_test.sh status\n'
        './recovery_test.sh report\n'
        './soak.sh --start\n'
        './soak.sh --stop',
        "Recovery tests record what happens if the app is killed or the screen turns "
        "off. The soak is a long listening run with a report at the end.",
        "The report lists PASS, FAIL and NOT RUN honestly. Keep the phone charging "
        "throughout a soak; a power cut makes that run invalid."),
    PageBreak(),
]

# ---------- 4 Windows ----------
S += [
    h1("4. Windows — start the services, then the phone"),
    p("Use <b>PowerShell</b> for the laptop services. For the phone scripts, open "
      "<b>Git Bash</b> (installed with Git) — those scripts are written for bash. Both "
      "are included below so you never have to guess."),

    step("Step W1 — Install the tools (one time only)"),
    cmd('winget install --id Google.AndroidSdkPlatformTools -e\n'
        'winget install --id EclipseAdoptium.Temurin.21.JDK -e\n'
        'winget install --id Git.Git -e\n'
        'winget install --id Google.AndroidStudio -e',
        "Installs the USB bridge, Java 21, Git, and the Android SDK that the app build needs.",
        "Each install finishes without an error."),
    cmd('[Environment]::SetEnvironmentVariable("ANDROID_HOME", "$env:LOCALAPPDATA\\Android\\Sdk", "User")\n'
        '[Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\\Program Files\\Eclipse Adoptium\\jdk-21", "User")\n'
        '$pt = "$env:LOCALAPPDATA\\Android\\Sdk\\platform-tools"\n'
        '[Environment]::SetEnvironmentVariable("Path", "$env:Path;$pt", "User")',
        "Tells Windows where Java and the USB bridge live.",
        "Open a NEW PowerShell window, then java -version and adb version both print a version."),

    step("Step W2 — Get the project and make a private Python environment"),
    cmd('git clone https://github.com/Tanishk042/Minor_project.git Minor_project\n'
        'cd Minor_project\\forestear\n'
        'py -m venv ..\\.venv\n'
        '..\\.venv\\Scripts\\Activate.ps1\n'
        'pip install -r requirements.txt',
        "Downloads the code and creates a clean Python space for this project.",
        "The folder exists and pip finishes without errors."),
    note("If PowerShell blocks the Activate.ps1 script, run "
         "Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass first."),

    step("Step W3 — Start the backend (the front desk) and dashboard"),
    cmd('python -m backend.main --host 0.0.0.0 --port 8000',
        "Starts the front desk and listens for the phone on the network.",
        "The window stays open with no error; http://127.0.0.1:8000 shows the dashboard."),
    box("Windows Firewall",
        "If the phone cannot reach the laptop, Windows may be blocking it. Allow port "
        "8000 on <b>Private</b> networks. Find the laptop address with ipconfig — look "
        "for IPv4 Address, for example 192.168.1.20.", "amber"),

    step("Step W4 — (Optional) sample map dots"),
    cmd('cd Minor_project\\forestear\n'
        '..\\.venv\\Scripts\\Activate.ps1\n'
        'python scripts\\smoke_ingest.py',
        "Sends pretend sensor messages to the notice board for practice.",
        "The map gets pins and the event count rises."),

    step("Step W5 — Connect the phone and check the bridge"),
    cmd('adb kill-server\n'
        'adb start-server\n'
        'adb devices -l\n'
        'adb shell getprop ro.product.model',
        "Asks the bridge which phones are attached.",
        "One line says device, and the model prints out. If it says unauthorized, accept the pop-up on the phone."),

    step("Step W6 — USB-only road to the front desk (if no shared Wi-Fi)"),
    cmd('adb reverse tcp:8000 tcp:8000',
        "Makes a tunnel through the cable so the phone can use 127.0.0.1:8000.",
        "No error printed. In the wizard use http://127.0.0.1:8000."),

    step("Step W7 — Install the app on the phone"),
    h3("Option A — Git Bash (runs the helper scripts unchanged)"),
    cmd('cd Minor_project/android\n'
        'export ANDROID_HOME="/c/Users/$USERNAME/AppData/Local/Android/Sdk"\n'
        './scripts/install.sh --grant-all',
        "Builds and installs the ear app. --grant-all also grants SMS — an explicit choice.",
        "The app appears on the phone; the APK is about 100 MB."),
    h3("Option B — PowerShell only (no bash scripts)"),
    cmd('cd Minor_project\\android\\ForestEarNode\n'
        '.\\gradlew.bat :app:assembleDebug --console=plain\n'
        'adb install -g -r app\\build\\outputs\\apk\\debug\\app-debug.apk',
        "Builds the app with the Windows Gradle wrapper and installs it with all permissions.",
        "The install command ends with Success. The APK is about 100 MB."),
    box("OxygenOS / very strict phones",
        "pm grant is refused on some phones. The -g install above is the practical "
        "option; otherwise grant microphone and notifications by hand in "
        "Settings → Apps → ForestEar Node → Permissions.", "amber"),

    step("Step W8 — Check permissions and battery protection"),
    cmd('adb shell dumpsys package org.forestear.node | Select-String "granted=true"\n'
        'adb shell cmd deviceidle whitelist +org.forestear.node\n'
        'adb shell dumpsys deviceidle whitelist | Select-String "org.forestear.node"',
        "Checks the microphone permission and asks Android not to freeze the app.",
        "granted=true appears for RECORD_AUDIO and POST_NOTIFICATIONS, and the package appears in the whitelist."),
    note("Then on the phone: Battery → Unrestricted, Auto-launch → Allow, Background "
         "activity → Allow, and lock the app in Recents."),

    step("Step W9 — Open the app and finish the 7-step setup"),
    p("Same as on macOS: open the app once, accept permissions, confirm battery "
      "settings, run the Screen-off continuity test, enter node name / backend URL / "
      "token, tap Test /health, finish, and run Diagnose → Benchmark once. Use "
      "http://192.168.1.20:8000 (your laptop's real address) if you are on Wi-Fi, or "
      "http://127.0.0.1:8000 if you used adb reverse."),

    step("Step W10 — Check the phone"),
    cmd('adb shell dumpsys activity services org.forestear.node | Select-String "NodeService"\n'
        '(Invoke-WebRequest http://127.0.0.1:8000/health -UseBasicParsing).Content\n'
        'adb logcat -d -b crash -t 200 | Select-String "org.forestear.node"',
        "Asks whether the worker is running, whether the front desk answers, and "
        "whether the app has crashed recently.",
        "NodeService is listed, /health returns a line with “status”, and no crash lines appear."),
    note("The full self-test with a pass/fail count is a bash script. Run it from Git "
         "Bash: BACKEND_URL=http://127.0.0.1:8000 ./scripts/self_test.sh"),

    step("Step W11 — Recovery tests, soak and logs (Git Bash)"),
    cmd('cd Minor_project/android/scripts\n'
        './recovery_test.sh status\n'
        './recovery_test.sh report\n'
        './soak.sh --start\n'
        './soak.sh --stop\n'
        './capture_logs.sh 30',
        "Records how the phone behaves when interrupted, runs a long listening test, "
        "and saves the logs and reports to your laptop.",
        "A new captures/ folder appears with logcat.txt, battery.txt, package.txt and the pulled reports."),
    PageBreak(),
]

# ---------- 5 checklist ----------
S += [
    h1("5. “Is it working?” — a simple checklist"),
    p("Tick these off. If one is not true, go back to that step — do not move on."),
    Table(
        [[p("<b>Check</b>", CELLB), p("<b>What “good” looks like</b>", CELLB)]]
        + [[p(a, CELL), p(b, CELL)] for a, b in [
            ("Bridge", "adb devices -l shows one device, state = device."),
            ("Front desk", "Browser opens http://127.0.0.1:8000 and shows the dashboard."),
            ("Road to phone", "The wizard's Test /health succeeds, or /health prints a status line."),
            ("Ear installed", "App icon on the phone; APK about 100 MB."),
            ("Permissions", "granted=true for microphone and notifications."),
            ("Not frozen", "Package appears in the device-idle whitelist; OEM battery switches set by hand."),
            ("Listening", "NodeService appears when the app is open; self-test says failures=0."),
            ("Screen off", "Continuity test ≥90% coverage and no gaps."),
            ("Tier", "Benchmark was run on THIS phone; the tier is written down from measured milliseconds."),
            ("Evidence", "Recovery report, soak report and logs are saved on the laptop."),
        ]],
        colWidths=[42 * mm, 128 * mm], hAlign="LEFT",
        style=TableStyle([
            ("GRID", (0, 0), (-1, -1), 0.4, LINE),
            ("BACKGROUND", (0, 0), (-1, 0), FOREST),
            ("TEXTCOLOR", (0, 0), (-1, 0), WHITE),
            ("VALIGN", (0, 0), (-1, -1), "TOP"),
            ("ROWBACKGROUNDS", (0, 1), (-1, -1), [WHITE, colors.HexColor("#F7FAF7")]),
            ("LEFTPADDING", (0, 0), (-1, -1), 6),
            ("RIGHTPADDING", (0, 0), (-1, -1), 6),
            ("TOPPADDING", (0, 0), (-1, -1), 4),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
        ]),
    ),
    sp(7),
    h1("6. If something goes wrong"),
    Table(
        [[p("<b>What you see</b>", CELLB), p("<b>What to try</b>", CELLB)]]
        + [[p(a, CELL), p(b, CELL)] for a, b in [
            ("adb says unauthorized", "Accept the USB-debugging pop-up on the phone."),
            ("adb says offline", "Unplug and replug the cable; use a data cable, not charge-only."),
            ("Phone cannot open the dashboard", "Use the laptop's Wi-Fi IP with port 8000, or run adb reverse and use 127.0.0.1:8000. Check the firewall."),
            ("Mic permission fails", "On strict phones use adb install -g -r, or grant by hand in Settings."),
            ("App stops when screen is off", "Recheck battery/OEM switches and rerun the continuity test."),
            ("Node shows DEGRADED", "Read the reason on the Status tab — usually Android blocked a background start. Open the app once."),
            ("No alerts on the map", "Check backend URL and token, the offline queue, and the chosen tier."),
            ("Soak report missing", "Unplug/replug power and rerun; a run that loses charge is invalid."),
        ]],
        colWidths=[58 * mm, 112 * mm], hAlign="LEFT",
        style=TableStyle([
            ("GRID", (0, 0), (-1, -1), 0.4, LINE),
            ("BACKGROUND", (0, 0), (-1, 0), FOREST),
            ("TEXTCOLOR", (0, 0), (-1, 0), WHITE),
            ("VALIGN", (0, 0), (-1, -1), "TOP"),
            ("ROWBACKGROUNDS", (0, 1), (-1, -1), [WHITE, colors.HexColor("#F7FAF7")]),
            ("LEFTPADDING", (0, 0), (-1, -1), 6),
            ("RIGHTPADDING", (0, 0), (-1, -1), 6),
            ("TOPPADDING", (0, 0), (-1, -1), 4),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
        ]),
    ),
    sp(7),
    h1("7. What is still NOT tested"),
    box("Plain answer",
        "A signed release app (no signing key was supplied), real Telegram messages "
        "(no bot token), real SMS (no live SIM card), labelled real forest recordings, "
        "and a truly completed 24-hour soak (the latest run reached 23 h 53 m). These "
        "are written as NOT RUN / NOT VERIFIED / PARTIAL everywhere in the project. "
        "Do not change those labels until the test actually happens.", "red"),
    sp(6),
    note("Companion documents: ForestEar_Project_Report.pdf (full technical report) and "
         "NEW_PHONE_SETUP_GUIDE.md (command-only reference). Generated from "
         "generate_quickstart_pdf.py."),
]


def footer(canvas, doc):
    canvas.saveState()
    w, h = A4
    canvas.setFillColor(FOREST)
    canvas.rect(0, 0, w, 12 * mm, stroke=0, fill=1)
    canvas.setFillColor(WHITE)
    canvas.setFont("Helvetica", 7.6)
    canvas.drawString(18 * mm, 4.5 * mm, "ForestEar — quick start (plain language)")
    canvas.drawRightString(w - 18 * mm, 4.5 * mm, "Page %d" % doc.page)
    canvas.restoreState()


doc = SimpleDocTemplate(
    str(OUT), pagesize=A4,
    leftMargin=18 * mm, rightMargin=18 * mm,
    topMargin=16 * mm, bottomMargin=18 * mm,
    title="ForestEar Quick Start — Services and Phone Setup",
    author="ForestEar project",
)
doc.build(S, onFirstPage=footer, onLaterPages=footer)
print(f"wrote {OUT}")
