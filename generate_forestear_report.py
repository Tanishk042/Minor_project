#!/usr/bin/env python3
"""Generate the full ForestEar project report as a layperson-friendly PDF."""

from __future__ import annotations

from html import escape
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_JUSTIFY, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import (
    ListFlowable,
    ListItem,
    PageBreak,
    Paragraph,
    Preformatted,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)
from reportlab.platypus.tableofcontents import TableOfContents

OUT = Path(__file__).resolve().parent / "ForestEar_Project_Report.pdf"

# ---------- palette ----------
FOREST = colors.HexColor("#17492F")
FOREST_LIGHT = colors.HexColor("#2F7D4F")
MOSS = colors.HexColor("#E8F1E9")
AMBER = colors.HexColor("#B36B00")
AMBER_LIGHT = colors.HexColor("#FFF3DF")
RED = colors.HexColor("#9B2C2C")
RED_LIGHT = colors.HexColor("#FDECEC")
BLUE = colors.HexColor("#1D4E89")
BLUE_LIGHT = colors.HexColor("#EAF1FA")
SLATE = colors.HexColor("#263238")
MUTED = colors.HexColor("#60746B")
LINE = colors.HexColor("#CBD8CF")
WHITE = colors.white

# ---------- styles ----------
ss = getSampleStyleSheet()

BODY = ParagraphStyle(
    "BodyLayman",
    parent=ss["BodyText"],
    fontName="Helvetica",
    fontSize=9.6,
    leading=14.2,
    textColor=SLATE,
    alignment=TA_JUSTIFY,
    spaceAfter=6,
)
BODY_L = ParagraphStyle("BodyLeft", parent=BODY, alignment=TA_LEFT)
SMALL = ParagraphStyle("Small", parent=BODY, fontSize=8.2, leading=11.5, spaceAfter=3)
CELL = ParagraphStyle("Cell", parent=BODY, fontSize=8.1, leading=10.8, spaceAfter=0, alignment=TA_LEFT)
CELL_B = ParagraphStyle("CellBold", parent=CELL, fontName="Helvetica-Bold")
CODE = ParagraphStyle("Code", parent=BODY, fontName="Courier", fontSize=7.8, leading=10.5, textColor=colors.HexColor("#1B3A2C"), alignment=TA_LEFT)

H1 = ParagraphStyle(
    "H1",
    parent=ss["Heading1"],
    fontName="Helvetica-Bold",
    fontSize=17,
    leading=21,
    textColor=FOREST,
    spaceBefore=8,
    spaceAfter=10,
    outlineLevel=0,
)
H2 = ParagraphStyle(
    "H2",
    parent=ss["Heading2"],
    fontName="Helvetica-Bold",
    fontSize=12.3,
    leading=16,
    textColor=FOREST_LIGHT,
    spaceBefore=10,
    spaceAfter=6,
    outlineLevel=1,
)
H3 = ParagraphStyle(
    "H3",
    parent=ss["Heading3"],
    fontName="Helvetica-Bold",
    fontSize=10.2,
    leading=13.5,
    textColor=SLATE,
    spaceBefore=8,
    spaceAfter=4,
    outlineLevel=2,
)
TITLE = ParagraphStyle("TitleBig", parent=ss["Title"], fontName="Helvetica-Bold", fontSize=28, leading=33, textColor=WHITE, alignment=TA_LEFT)
SUBTITLE = ParagraphStyle("Subtitle", parent=BODY, fontName="Helvetica", fontSize=13, leading=18, textColor=WHITE, alignment=TA_LEFT)
COVER_SMALL = ParagraphStyle("CoverSmall", parent=BODY, fontName="Helvetica", fontSize=9.5, leading=14, textColor=WHITE, alignment=TA_LEFT)
TOC1 = ParagraphStyle("TOC1", parent=BODY, fontName="Helvetica-Bold", fontSize=9.5, leading=14, textColor=FOREST, alignment=TA_LEFT)
TOC2 = ParagraphStyle("TOC2", parent=BODY, fontSize=8.8, leading=12.5, leftIndent=12, textColor=SLATE, alignment=TA_LEFT)


# ---------- helpers ----------
def P(text: str, style=BODY) -> Paragraph:
    return Paragraph(escape(str(text)), style)


def bullets(items: list[str], style=BODY, bullet_color=FOREST_LIGHT) -> ListFlowable:
    return ListFlowable(
        [ListItem(P(i, style), leftIndent=10) for i in items],
        bulletType="bullet",
        start="-",
        bulletFontName="Helvetica",
        bulletColor=bullet_color,
        bulletFontSize=9,
        leftIndent=14,
        spaceAfter=4,
    )


def numbered(items: list[str], style=BODY) -> ListFlowable:
    return ListFlowable(
        [ListItem(P(i, style), leftIndent=12) for i in items],
        bulletType="1",
        bulletFontName="Helvetica-Bold",
        bulletFontSize=8,
        leftIndent=16,
        spaceAfter=4,
    )


def tbl(data: list[list[str]], widths: list[float], header: bool = True, font_size: float = 8.1) -> Table:
    rows = []
    for r_i, row in enumerate(data):
        rows.append([
            P(c, CELL_B if (header and r_i == 0) else CELL)
            for c in row
        ])
    t = Table(rows, colWidths=widths, repeatRows=1 if header else 0, hAlign="LEFT")
    style = [
        ("GRID", (0, 0), (-1, -1), 0.45, LINE),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 5),
        ("RIGHTPADDING", (0, 0), (-1, -1), 5),
        ("TOPPADDING", (0, 0), (-1, -1), 4),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
        ("ROWBACKGROUNDS", (0, 1 if header else 0), (-1, -1), [WHITE, colors.HexColor("#F7FAF7")]),
    ]
    if header:
        style += [
            ("BACKGROUND", (0, 0), (-1, 0), FOREST),
            ("TEXTCOLOR", (0, 0), (-1, 0), WHITE),
        ]
    t.setStyle(TableStyle(style))
    return t


def callout(title: str, text: str, kind: str = "green") -> Table:
    if kind == "amber":
        bg, bar, fg = AMBER_LIGHT, AMBER, AMBER
    elif kind == "red":
        bg, bar, fg = RED_LIGHT, RED, RED
    elif kind == "blue":
        bg, bar, fg = BLUE_LIGHT, BLUE, BLUE
    else:
        bg, bar, fg = MOSS, FOREST_LIGHT, FOREST
    inner = [
        [P(title, ParagraphStyle("CalloutTitle", parent=CELL_B, textColor=fg, fontName="Helvetica-Bold", fontSize=9.4)),],
        [P(text, ParagraphStyle("CalloutBody", parent=CELL, fontSize=8.8, leading=12.4))],
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


def code_block(text: str) -> Preformatted:
    return Preformatted(text, CODE, maxLineLength=105)


def h1(text: str) -> Paragraph:
    return P(text, H1)


def h2(text: str) -> Paragraph:
    return P(text, H2)


def h3(text: str) -> Paragraph:
    return P(text, H3)


def sp(h: float = 4) -> Spacer:
    return Spacer(1, h)


# ---------- page decoration ----------
def cover_page(canvas, doc):
    canvas.saveState()
    w, h = A4
    canvas.setFillColor(FOREST)
    canvas.rect(0, h - 62 * mm, w, 62 * mm, stroke=0, fill=1)
    canvas.setFillColor(FOREST_LIGHT)
    canvas.rect(0, h - 65 * mm, w, 3 * mm, stroke=0, fill=1)
    canvas.setFillColor(colors.HexColor("#0E3321"))
    canvas.rect(0, 0, w, 18 * mm, stroke=0, fill=1)
    canvas.setFillColor(WHITE)
    canvas.setFont("Helvetica-Bold", 9)
    canvas.drawString(18 * mm, 9 * mm, "FORESTEAR  /  FINAL PROJECT REPORT")
    canvas.drawRightString(w - 18 * mm, 9 * mm, "CONFIDENTIALITY: PROTOTYPE / ACADEMIC")
    canvas.restoreState()


def body_page(canvas, doc):
    canvas.saveState()
    w, h = A4
    canvas.setStrokeColor(LINE)
    canvas.setLineWidth(0.5)
    canvas.line(18 * mm, h - 15 * mm, w - 18 * mm, h - 15 * mm)
    canvas.setFillColor(MUTED)
    canvas.setFont("Helvetica", 7.6)
    canvas.drawString(18 * mm, h - 12.5 * mm, "ForestEar — Low-cost acoustic forest monitoring")
    canvas.drawRightString(w - 18 * mm, h - 12.5 * mm, "Final project report")
    canvas.line(18 * mm, 14 * mm, w - 18 * mm, 14 * mm)
    canvas.drawCentredString(w / 2, 9 * mm, str(canvas.getPageNumber()))
    canvas.drawString(18 * mm, 9 * mm, "Plain-language edition")
    canvas.drawRightString(w - 18 * mm, 9 * mm, "Measured results only")
    canvas.restoreState()


def after_flowable(flowable):
    if isinstance(flowable, Paragraph) and getattr(flowable.style, "outlineLevel", None) is not None:
        level = int(flowable.style.outlineLevel)
        text = flowable.getPlainText()
        # Keep TOC readable; skip the TOC heading itself.
        if text.strip().lower().startswith("table of contents"):
            return
        doc.notify("TOCEntry", (level, text, doc.page))


# ---------- document ----------
doc = SimpleDocTemplate(
    str(OUT),
    pagesize=A4,
    leftMargin=18 * mm,
    rightMargin=18 * mm,
    topMargin=20 * mm,
    bottomMargin=18 * mm,
    title="ForestEar Final Project Report",
    author="ForestEar project",
    subject="Plain-language detailed report of the ForestEar acoustic monitoring platform",
)
doc.afterFlowable = after_flowable

S: list = []

# ======================= COVER =======================
S += [
    sp(30),
    P("ForestEar", TITLE),
    P("Low-cost acoustic monitoring for forests", SUBTITLE),
    sp(10),
    P("Full Project Report — Technology, Method, Deliverables, Evidence and Honest Limitations", COVER_SMALL),
    sp(34),
    tbl([
        ["Item", "Detail"],
        ["Project", "ForestEar — nine-model acoustic surveillance platform and ForestEar Node phone app"],
        ["Report type", "Final comprehensive project documentation (plain-language edition)"],
        ["Prepared", "18 November 2026"],
        ["Code repositories", "forestear (Python/FastAPI/dashboard), android (Kotlin/Compose phone node)"],
        ["Local commits", "forestear 3a58fa8; android 2b0c46d. Neither repository was pushed."],
        ["Evidence rule", "Only measured numbers are printed. Unexercised checks are marked NOT RUN."],
    ], [38 * mm, 132 * mm]),
    sp(20),
    callout(
        "What this report is for",
        "It explains what the project is, why it was built, how it was built, what was actually tested, "
        "what was delivered, and what is still unfinished. Technical words are explained in simple language. "
        "If you only read one page, read the executive summary in Section 1.",
        "blue",
    ),
    sp(16),
    P("A listening network that helps protect forests — built, measured and documented honestly.", COVER_SMALL),
    PageBreak(),
]

# ======================= HOW TO READ + TOC =======================
S += [
    h1("How to read this report"),
    P("This document is written for a reader who does not need to be an engineer. Whenever a technical word appears, it is explained the first time it is used, and the Glossary at the end collects the important terms in one place."),
    bullets([
        "Sections 1–3 give the big picture: what the problem is and how the solution works.",
        "Sections 4–9 describe the technology, the way we built it, and the pieces we delivered.",
        "Sections 10–11 present the testing and the measured results, including failures and unknowns.",
        "Sections 12–15 cover deliverables, how to run the project, risks, and future work.",
        "The appendices are a quick reference: folder map, evidence files and commands.",
    ]),
    sp(6),
    callout(
        "The honesty rule used throughout",
        "A result is called PASS only if the test actually ran. A result is called FAIL if it ran and did not meet the target. "
        "A result is called NOT RUN if nobody exercised it. This is especially important for phone recovery, field audio and alert delivery.",
        "amber",
    ),
    sp(8),
    h1("Table of contents"),
]
toc = TableOfContents()
toc.levelStyles = [TOC1, TOC2]
S += [toc, PageBreak()]

# ======================= 1 EXECUTIVE SUMMARY =======================
S += [
    h1("1. Executive summary"),
    h2("1.1 The problem in one paragraph"),
    P("Patrols cannot be everywhere in a forest at once. Chain-saws, gunshots, vehicles and people can enter an area long before a ranger arrives. Existing camera or satellite systems can be expensive, need a lot of power, or fail in poor weather and at night. Sound travels well in forests, so a small listening device can notice trouble early. ForestEar was built to answer a simple question: can a cheap device listen locally, recognise important sounds, and send only a tiny text alert to a central map without storing people's speech?"),
    h2("1.2 What we built"),
    tbl([
        ["Piece", "What it is", "What it does in plain language"],
        ["Python edge agent", "Reference listening program on a laptop or small computer", "Turns microphone audio into sound fingerprints and runs nine small decision helpers."],
        ["Nine-model ensemble", "A team of specialised sound detectors plus a final referee", "Looks for chainsaw, gunshot, vehicle, human, weather, unusual sound and birds; the referee decides the final alert."],
        ["FastAPI backend", "A small web server", "Receives alerts and heartbeats, stores events, combines reports from several nodes, and serves the dashboard."],
        ["Live dashboard", "A website with a map and charts", "Shows node health, events, estimated sound source, bird activity and analytics."],
        ["Telegram alerts", "Optional message bot", "Sends near-real-time alerts. It is dry-run by default, so it logs instead of messaging until real credentials are supplied."],
        ["ForestEar Node app", "Android app for a phone", "Does all listening and inference on the phone itself; no cloud or Play Services needed for detection."],
        ["Device Adaptation Layer", "Phone capability checker", "Probes the handset, measures speed, picks a safe performance level, and honestly reports missing features."],
        ["Evidence and reports", "Files saved during real testing", "Contain benchmark numbers, self-test results, recovery outcomes and a generated compatibility report."],
    ], [32 * mm, 48 * mm, 90 * mm]),
    sp(6),
    h2("1.3 The headline results"),
    tbl([
        ["Area", "Measured result", "What it means"],
        ["Final model quality", "Macro-F1 0.8186; accuracy 0.82 on 6,612 held-out sound windows", "On the project's own test set, the final referee is right about 82% of the time overall."],
        ["Calibration", "ECE 0.0506 after calibration (0.1196 before)", "Its confidence numbers are much closer to real correctness after calibration."],
        ["Cross-source robustness", "F1 0.5147 on ESC-50, 0.6701 on UrbanSound8K, 0.8393 on synthetic", "Performance drops on sounds from different sources. This is a known weakness."],
        ["Laptop pipeline speed", "5.56 ms per 0.5 s hop", "Very fast on the development laptop; not a phone number."],
        ["Phone pipeline speed", "49.47 ms mean on OnePlus CPH2381, tier A", "Comfortably inside the 400 ms phone budget on one real handset, CPU-only."],
        ["Python tests", "145 collected automated checks", "Covers features, decisions, API, transport, TDOA, metrics, splits and simulator."],
        ["Android tests", "88 JVM checks + 9 on-device checks, 0 failures", "Unit and real-phone parity/keystore/device checks passed on CPH2381."],
        ["Phone self-test", "14 checks, 0 failed, 6 warnings", "Sensors, permissions, models, network and backend work; the final reinstall used -g, which also granted optional SMS."],
        ["Screen-off continuity", "100% coverage over 303 s, 0 gaps", "Audio analysis continued while the screen was off."],
        ["Recovery", "Swipe PASS; screen-off PASS; mic theft PASS; force-kill FAIL; watchdog FAIL; reboot FAIL", "The app recovers from mic theft and normal background use, but Android 14 blocks restart after a hard stop or boot."],
        ["24-hour soak", "Old run invalid; fresh run in progress", "The first run lost power after 1 h 29 m. A new 24 h run started 2026-11-18T15:57:24Z; no PASS is claimed yet."],
    ], [34 * mm, 58 * mm, 78 * mm]),
    sp(6),
    callout(
        "The most important caveat",
        "These are prototype results on internal test data and one physical phone. They are not a claim of real-field accuracy, "
        "not a production security audit, and not proof that alerts reach Telegram or SMS. The project is candid about this everywhere.",
        "red",
    ),
]

# ======================= 2 PROBLEM / SCOPE =======================
S += [
    h1("2. Problem, goals and scope"),
    h2("2.1 Why this project exists"),
    P("Conservation teams need early warning, but forests are large, power is limited, and networks are unreliable. A useful system must therefore do its thinking close to the microphone, send very little data, keep working when the network drops, and be cheap enough to place many units."),
    h2("2.2 The goals we set"),
    numbered([
        "Listen locally for four threat classes — chainsaw, gunshot, vehicle and human — plus a general 'unusual sound' signal.",
        "Ignore or down-rank natural false alarms such as thunder, rain and wind.",
        "Record birds as a biodiversity by-product, but never let bird detection create a threat alert.",
        "Send only small JSON alert and heartbeat messages; never store or transmit speech.",
        "Combine reports from at least three nodes to estimate where a sound came from.",
        "Run on ordinary hardware: a laptop or Raspberry-Pi-class computer and a low-cost Android phone.",
        "Survive poor networks with an offline queue and survive phone background limits as far as Android permits.",
        "Be honest: document what was measured, what failed, and what was never tested.",
    ]),
    h2("2.3 What is deliberately out of scope"),
    bullets([
        "No face or speech recognition, no identity tracking, and no audio upload by default.",
        "No commercial cloud inference, no paid API dependency, no Play Services requirement.",
        "No claim of production-grade security: the default token is a development token and there is no TLS in the local harness.",
        "No claim that the system is accurate in a real forest until labelled field audio is evaluated.",
        "No GPS hardware requirement; location can be configured manually, and the app records location permission honestly.",
    ]),
    h2("2.4 Who would use it"),
    tbl([
        ["User", "What they would see"],
        ["Ranger / field operator", "A phone that shows LIVE or IDLE, starts monitoring, and can run self-tests."],
        ["Control-room analyst", "A dashboard map with healthy nodes, events and an estimated source circle."],
        ["System maintainer", "Tests, scripts, reports, model files and evidence that explain exactly what happened."],
        ["Reviewer / examiner", "A clear statement of methods, deliverables, results and limitations."],
    ], [42 * mm, 128 * mm]),
]

# ======================= 3 HOW IT WORKS =======================
S += [
    h1("3. How the whole system works"),
    h2("3.1 The end-to-end flow"),
    code_block(
        "microphone / audio file / simulated forest\n"
        "        |\n"
        "        v\n"
        "EDGE AGENT (Python laptop  OR  Android phone)\n"
        "  1. capture 16 kHz mono audio\n"
        "  2. cut 1-second windows every 0.5 seconds\n"
        "  3. make a 64x98 sound fingerprint (log-mel)\n"
        "  4. run M1-M8 specialists\n"
        "  5. M9 referee combines 47 clues -> final class + confidence\n"
        "  6. apply rules: persistence, cooldown, weather veto\n"
        "        |\n"
        "        |  tiny JSON alerts / heartbeats / scores (offline queue if needed)\n"
        "        v\n"
        "FASTAPI BACKEND\n"
        "  store events -> deduplicate -> correlate nodes (TDOA) -> estimate source\n"
        "        |                         |\n"
        "        v                         v\n"
        "LIVE DASHBOARD (map/charts)   TELEGRAM BOT (dry-run by default)"
    ),
    sp(4),
    h2("3.2 What happens at each step, in plain language"),
    numbered([
        "Capture: the device listens in short slices. It asks for 16,000 samples per second (telephone-quality audio), which is enough for the sounds of interest and keeps files small.",
        "Fingerprint: raw sound waves are hard for a computer to compare directly. The app converts them into a compact picture of frequency over time, called a log-mel spectrogram. It is like a visual 'sound print' with 64 frequency bands and 98 time steps.",
        "Specialists: several small models look for different things — one knows gunshots, one knows chainsaws, one knows vehicles, one knows people, one knows weather, and one notices unusual patterns.",
        "Referee: the final model (M9) takes all those opinions plus timing and handcrafted clues and decides which of the five classes wins.",
        "Rules: a single suspicious window is not enough. The system requires several hits in a short period (persistence), waits between alerts (cooldown), and suppresses gunshot alerts during heavy rain or thunder unless confidence is very high.",
        "Transport: only short JSON messages leave the device. If the network is down, events are queued on disk and retried later.",
        "Correlation: when three or more nodes report the same class close in time, the backend uses the tiny differences in arrival time to estimate a location — like estimating where a clap came from by comparing when two people heard it.",
        "Presentation: the dashboard shows pins, alerts, a source circle, charts and health information. Telegram can also send alerts, but only after real credentials are configured.",
    ]),
    h2("3.3 Design principles we followed"),
    tbl([
        ["Principle", "How it appears in the project"],
        ["Privacy first", "No speech stored or sent; 5-second clips only for non-speech classes; human presence deletes the whole chunk."],
        ["Offline first", "Local inference; durable event queue; dashboard works for pins/circles without internet tiles."],
        ["Device agnostic", "Any Android 8+ phone with a microphone; capability probe instead of a device allowlist."],
        ["Honest degradation", "Missing capability is recorded as missing; unrun recovery paths stay NOT RUN; DEGRADED status is shown."],
        ["Reproducible", "Pinned dependencies, fixed seed 42, fold discipline tests, generated reports from actual predictions."],
        ["Low cost", "No cloud GPU, no paid APIs, no Play Services, small JSON telemetry, lightweight backend image (388 MB)."],
        ["Modular", "Features, models, decisions, transport, backend and UI are separate layers that can be tested independently."],
    ], [36 * mm, 134 * mm]),
]

# ======================= 4 TECH STACK =======================
S += [
    h1("4. Complete technology stack"),
    P("This section lists the tools and building blocks. Version numbers are included where they were pinned so the project can be rebuilt consistently."),
    h2("4.1 Python / server / machine-learning side"),
    tbl([
        ["Layer", "Technology", "Version / detail", "Why it was chosen"],
        ["Language", "Python", "3.12", "Only interpreter available on the development machine; all pinned libraries support it."],
        ["Web API", "FastAPI", "0.129.0", "Small, fast, easy-to-test HTTP API with automatic validation."],
        ["Server", "Uvicorn", "0.37.0", "Lightweight ASGI server for the API and WebSocket."],
        ["Database", "SQLAlchemy + SQLite", "2.0.45", "Simple local storage; no database server required."],
        ["ML framework", "TensorFlow", "2.18.0", "Runs YAMNet SavedModel, training and TFLite export."],
        ["Audio", "librosa / soundfile", "0.11.0 / 0.13.1", "Reading audio and computing mel features."],
        ["Classical ML", "scikit-learn / LightGBM", "1.7.0 / 4.6.0", "Calibration, metrics and the M9 fusion model."],
        ["Data", "NumPy / pandas", "2.0.2 / 2.3.3", "Array math and dataset tables."],
        ["Portable export", "TFLite + m2cgen", "TF 2.18 / m2cgen 0.10.0", "Small on-device models and pure-Python tree export."],
        ["Config", "PyYAML", "6.0.3", "Human-readable configuration in configs/default.yaml."],
        ["HTTP client", "httpx", "0.28.1", "Backend tests and load testing."],
        ["Testing", "pytest", "8.4.2", "145 collected automated checks."],
        ["System info", "psutil", "7.1.0", "CPU/RAM measurement during benchmarking."],
        ["Audio device", "sounddevice", "0.5.6", "Optional live microphone input on laptop."],
        ["Container", "Docker", "python:3.12-slim", "Backend image uses a lightweight requirements file without TensorFlow."],
    ], [24 * mm, 38 * mm, 36 * mm, 72 * mm]),
    sp(6),
    h2("4.2 Android phone side"),
    tbl([
        ["Layer", "Technology", "Version / detail", "Why it was chosen"],
        ["Language", "Kotlin", "1.9.24", "Modern Android language with concise, safe code."],
        ["UI", "Jetpack Compose", "Compose BOM 2024.06.00, compiler 1.5.14", "Declarative screens: Status, Setup, Diagnose, Settings, Tools."],
        ["Build", "Gradle + AGP", "Gradle 8.7, AGP 8.5.2", "Standard Android build system."],
        ["SDK", "minSdk 26 / targetSdk 34", "Android 8 to Android 14", "Wide device support with modern foreground-service rules."],
        ["JVM target", "Java 17", "Compiled with JDK 21 locally", "Modern language/runtime baseline."],
        ["On-device ML", "TensorFlow Lite", "2.16.1 (+ GPU delegate library)", "Runs the models locally; GPU used only if faster and stable."],
        ["Final referee", "Kotlin FusionTrees", "1,500 trees, 47 features", "Pure-Kotlin copy of M9 avoids a heavy runtime dependency."],
        ["Networking", "OkHttp", "4.12.0", "Reliable HTTP posts and optional raw PCM WebSocket."],
        ["Background work", "WorkManager + AlarmManager", "2.9.1", "Watchdogs attempt to restart monitoring after the app is removed from Recents."],
        ["Lifecycle", "AndroidX Lifecycle / Core KTX", "2.8.2 / 1.13.1", "Standard app architecture helpers."],
        ["Storage", "SQLite + SharedPreferences + Keystore", "forestear_offline.db; AES-256/GCM token", "Offline queue, settings, encrypted backend token."],
        ["Testing", "JUnit 4 + AndroidX Test", "4.13.2 / 1.6.1", "88 JVM checks and 9 on-device checks."],
        ["Package", "org.forestear.node", "v1.0.0, code 1", "Single app for all devices; no per-phone build."],
    ], [25 * mm, 39 * mm, 39 * mm, 67 * mm]),
    sp(6),
    h2("4.3 Dashboard, map and operations"),
    tbl([
        ["Tool", "Detail", "Note"],
        ["Leaflet", "1.9.4, vendored locally", "No CDN request; map still guarded if the library is missing."],
        ["Chart.js", "4.4.3, vendored locally", "Biodiversity and analytics charts."],
        ["Map tiles", "CARTO Voyager raster tiles", "Keyed URL stored in gitignored forestear/.env; env variables can override."],
        ["Static serving", "FastAPI NoCacheStaticFiles", "Prevents stale dashboard files after changes."],
        ["Config endpoint", "GET /dashboard-config", "Public tile URL, attribution and max zoom."],
        ["CI", "GitHub Actions workflow", "Python 3.12 + pytest; Android workflow runs JVM tests and builds debug APK. CI run not yet performed on GitHub."],
        ["Licensing", "MIT for original source", "Third-party models/datasets keep their own terms; several are non-commercial."],
    ], [32 * mm, 48 * mm, 90 * mm]),
]

# ======================= 5 METHODOLOGY =======================
S += [
    h1("5. How we created the project — methodology"),
    P("The work followed a practical 'build, measure, document' loop. Each layer was made testable on its own before being connected to the next layer. This mattered because a mistake in audio features or model input shape can silently ruin every later result."),
    h2("Phase 1 — Define the job and the honesty rules"),
    bullets([
        "Wrote down the five classes the final decision must distinguish: chainsaw, gunshot, vehicle, human, benign.",
        "Decided that bird detection is informational only and must never trigger a threat alert.",
        "Decided that no speech would be stored or uploaded, and thatTelegram would be dry-run until real credentials exist.",
        "Adopted the rule: PASS only after a real run; FAIL after a real failed run; NOT RUN otherwise.",
    ]),
    h2("Phase 2 — Collect and understand data"),
    bullets([
        "Used public datasets: ESC-50, UrbanSound8K and FSD50K, plus a synthetic generator for classes that were missing or rare.",
        "Recorded exactly what was available: 3,649 of 3,803 in-scope UrbanSound8K clips were on disk; 161 were unreachable after retries.",
        "FSD50K has no chainsaw label, so chainsaw examples came from ESC-50 and synthetic audio. This limitation is documented, not hidden.",
        "Built a unified manifest (8,592 lines) and a feature cache (about 284.8 MB) so training and evaluation use the same windows.",
    ]),
    h2("Phase 3 — Lock the audio contract"),
    P("Every part of the system — Python training, Python edge agent and Android app — must see the same sound in the same form. We fixed the contract at 16 kHz mono, a 1-second window every 0.5 seconds, a 64×98 log-mel fingerprint, per-window normalisation, and YAMNet input resized to 15,600 samples. Unit tests compare Android and Python features and caught real bugs, including a YAMNet input-shape mismatch and a missing pad in the M7 extra scores."),
    h2("Phase 4 — Train specialists, then a referee"),
    numbered([
        "M1 YAMNet was frozen: it already knows many general sound patterns from Google AudioSet, so it acts as a feature extractor.",
        "M2–M7 were trained as small specialists on top of either the fingerprint or YAMNet embedding.",
        "M6 was trained only on benign sound, so it learns what 'normal' looks like and flags deviations. It later proved weak and is reported as such.",
        "M8 BirdNET was added duty-cycled (one window in ten) purely for biodiversity.",
        "M9 was trained with out-of-fold stacking: each specialist's prediction on unseen training folds becomes a clue for the referee. This avoids the referee memorising the specialists' training answers.",
        "Isotonic calibration was applied so a '0.8 confidence' means roughly 80% correctness. ECE improved from 0.1196 to 0.0506.",
        "M9 was exported in several forms: LightGBM pickle, pure Python via m2cgen, a small Keras MLP and TFLite, so different platforms can run it.",
    ]),
    h2("Phase 5 — Add decision rules and a simulator"),
    P("A model alone is not a safe alerting system. We added persistence (for example 3 chainsaw detections out of 5 windows), cooldowns (do not repeat the same alert every half second), a weather veto (do not call thunder a gunshot), and sustained-chainsaw escalation. A seven-scenario simulator then exercised these rules with realistic timing, distance attenuation and clock offsets. Simulator audio is demo material and is never counted in accuracy metrics."),
    h2("Phase 6 — Build the server and dashboard"),
    P("The FastAPI server accepts authenticated JSON, stores events in SQLite, deduplicates across nodes, estimates a source with time-difference-of-arrival math, and pushes live updates over a WebSocket. The dashboard is a static page with locally stored Leaflet and Chart.js so it does not depend on a CDN. We verified the map after fixing a zero-height container, checked that keyed CARTO tiles return HTTP 200, and confirmed the page has no console errors and no CDN requests."),
    h2("Phase 7 — Port the listener to a phone"),
    P("The Android app is a full second implementation, not a wrapper around the server. It captures audio, computes the same features, loads the same nine models from assets, runs the same M9 tree ensemble in Kotlin, applies the same rules, and queues events locally. Because phones differ widely, a Device Adaptation Layer probes capabilities, benchmarks the models, selects tier A/B/C/D and records missing features honestly."),
    h2("Phase 8 — Verify on a real handset"),
    P("We installed the app on a OnePlus CPH2381 (Android 14), completed the setup wizard, granted permissions (the final install used adb install -g -r because this ROM blocks shell grants), added the Doze allowlist, and ran headless and in-app self-tests. We measured the benchmark, exercised recovery paths including watchdog and microphone theft, ran a screen-off continuity test, generated the compatibility report on the phone and pulled the evidence into the repository."),
    h2("Phase 9 — Document and hand over"),
    P("Architecture notes, model cards, map notes, privacy notes, phone setup guides, OEM guides, supported-device table and this report were produced. Generated documents come from data files, not hand editing, so they can be regenerated consistently."),
]

# ======================= 6 NINE MODELS =======================
S += [
    h1("6. The nine models, explained simply"),
    P("Think of the system as a small team. Eight members each watch for one thing. A ninth member listens to all of them and makes the final call."),
    tbl([
        ["ID", "Name", "Plain-language job", "Input", "Measured note"],
        ["M1", "YAMNet", "A pre-trained general sound listener. It does not decide alerts; it provides a rich sound description and 521 sound scores.", "Raw waveform", "Frozen AudioSet model; 12 curated scores feed M9."],
        ["M2", "Gunshot CNN", "Looks for the sharp crack pattern of a gunshot.", "64×98 fingerprint", "AP 0.7694, AUROC 0.9379."],
        ["M3", "Chainsaw head", "Looks for the repeating mechanical buzz of a chain-saw.", "YAMNet embedding", "AP 0.6701, AUROC 0.9557."],
        ["M4", "Vehicle head", "Looks for engine and movement patterns.", "YAMNet embedding", "AP 0.7051, AUROC 0.8221."],
        ["M5", "Weather CNN", "Decides whether the moment sounds like rain, wind, thunder or quiet. It is a false-alarm brake, not a threat detector.", "64×98 fingerprint", "Macro-F1 0.5761 — weak, documented."],
        ["M6", "Anomaly autoencoder", "Learns ordinary forest sound and flags 'this is unusual' without naming what it is.", "64×98 fingerprint", "AUROC 0.482 — below chance; removal from M9 tested and not adopted; INFO only."],
        ["M7", "Human head", "Looks for speech, shouting, footsteps and similar human activity.", "Embedding + voice scores", "AP 0.6561, AUROC 0.7994."],
        ["M8", "BirdNET", "Names birds for biodiversity charts. It never affects alerts.", "Raw waveform, 1 in 10 windows", "CC BY-NC-SA 4.0; dominates phone cost (71.62 ms)."],
        ["M9", "Fusion referee", "Combines 47 clues from everyone else and outputs one of five classes with a confidence.", "47 features", "Macro-F1 0.8186; calibrated ECE 0.0506."],
    ], [10 * mm, 24 * mm, 66 * mm, 30 * mm, 40 * mm]),
    sp(6),
    h2("6.1 The 47 clues given to the referee"),
    tbl([
        ["Clue group", "How many", "Plain-language meaning"],
        ["M1 general sound scores", "12", "Curated scores from the general listener (speech, animals, machinery, etc.)."],
        ["Specialist outputs", "3", "Gunshot, chainsaw and vehicle opinions."],
        ["Weather outputs", "4", "Rain, wind, thunder, quiet."],
        ["Anomaly score", "1", "How unusual the sound is compared with normal."],
        ["Human output", "1", "Human activity opinion."],
        ["Handcrafted acoustics", "6", "Simple measurable properties such as loudness and sharpness."],
        ["Temporal context", "18", "What happened in nearby time windows — crucial for persistence."],
        ["Time of day", "2", "Hour encoded as sine/cosine so night and day can be distinguished smoothly."],
        ["Total", "47", "Exactly the same contract on Python and Android."],
    ], [44 * mm, 18 * mm, 108 * mm]),
    sp(6),
    callout(
        "Why M8 BirdNET is not part of the threat decision",
        "Bird names are useful for biodiversity, but they should never make a chainsaw alert stronger or weaker. The design keeps this channel separate so conservation benefit does not interfere with safety logic.",
        "blue",
    ),
]

# ======================= 7 DATA / TRAINING =======================
S += [
    h1("7. Data, training and evaluation methodology"),
    h2("7.1 Datasets used"),
    tbl([
        ["Source", "Role", "Important detail"],
        ["ESC-50", "General environmental sounds; chainsaw source", "CC BY-NC 3.0. Folds 1–3 train, 4 validation, 5 test."],
        ["UrbanSound8K", "Urban sounds including vehicles and machinery", "CC BY-NC 3.0. 3,649 of 3,803 in-scope clips available; 161 unreachable."],
        ["FSD50K", "Large general sound ontology", "CC BY 4.0. No chainsaw label; per-label caps used to fit the laptop."],
        ["Synthetic generator", "Fills missing/rare classes and augments training", "Original MIT. 300 clips each for benign, chainsaw, gunshot, human and weather classes."],
        ["Background bank", "Ambient/benign material and M6 training", "6,870 WAVs; used for augmentation and normal-sound modelling."],
        ["Simulator", "Deterministic demos only", "7 scenarios with real dataset clips where available; never used as evaluation data."],
    ], [34 * mm, 50 * mm, 86 * mm]),
    sp(6),
    h2("7.2 Preventing leakage"),
    P("A common mistake in machine learning is to let the same recording appear in both training and test sets, which makes results look better than they are. We enforced dataset folds (for example, ESC-50 fold 5 is always test) and added a test that no origin recording crosses a fold or split. The random seed is fixed at 42 so reruns are comparable."),
    h2("7.3 How features are made"),
    numbered([
        "Take one second of audio and pad or trim it to a fixed length.",
        "Convert the waveform into 64 mel frequency bands, which roughly match how humans hear pitch.",
        "Use a logarithm so quiet details remain visible; this gives the 'log-mel' fingerprint.",
        "Keep 98 time steps per window using a 25 ms analysis window and a 10 ms step.",
        "Normalise each window to zero mean and unit variance so loud and quiet recordings are comparable.",
        "For YAMNet, resize the waveform to exactly 15,600 samples — the same rule in Python and Android.",
    ]),
    h2("7.4 How results were produced"),
    P("Evaluation runs only on held-out test windows. Reports are generated by the evaluation script from actual predictions, not typed by hand. M9 was also compared with three baselines: YAMNet-only, handcrafted logistic regression, and always choosing the majority class. Ablations removed one clue group at a time to see what mattered."),
    tbl([
        ["Check", "Result", "Interpretation"],
        ["M9 full model", "Macro-F1 0.8186, accuracy 0.82", "Best final decision maker."],
        ["YAMNet only", "Macro-F1 0.3308, accuracy 0.4109", "The referee adds substantial value."],
        ["Handcrafted only", "Macro-F1 0.3077, accuracy 0.3365", "Simple acoustic measurements alone are not enough."],
        ["Majority class", "Macro-F1 0.1156, accuracy 0.4065", "Baseline showing class imbalance."],
        ["No temporal context", "Macro-F1 0.6657", "Nearby time windows are the most important clue group."],
        ["No M1 scores", "Macro-F1 0.8142", "General scores help slightly, but the system still works."],
        ["No handcrafted", "Macro-F1 0.8121", "Small effect."],
        ["No M6 (retrained)", "Macro-F1 0.8173, accuracy 0.8162", "Slightly worse overall; not adopted (Section 11.7)."],
        ["No calibration", "Macro-F1 0.8189", "Calibration changes confidence quality, not class ranking much."],
    ], [40 * mm, 38 * mm, 92 * mm]),
]

# ======================= 8 DECISIONS / ALERTS =======================
S += [
    h1("8. Decisions, alerts and source location"),
    h2("8.1 Turning a guess into an alert"),
    tbl([
        ["Rule", "Setting", "Why"],
        ["Persistence", "Chainsaw 3/5; gunshot 1/3 with high-confidence bypass at 0.85; vehicle 3/5; human 4/6; anomaly 2/3", "Avoid one-window noise triggering action."],
        ["Cooldown", "Chainsaw 60 s; gunshot 20 s; vehicle 120 s; human 300 s; anomaly 600 s", "Prevent alert floods."],
        ["Weather veto", "Suppress gunshot when rain/wind/thunder probability >=0.6 and gunshot confidence <0.80", "Thunder is the classic false gunshot."],
        ["Sustained chainsaw", "8 hits out of 30 windows escalates to CRITICAL", "Long-running cutting is more serious than a brief sound."],
        ["Head-model bypass", "Very high specialist confidence can trigger sooner", "Do not miss an obvious gunshot because persistence is still accumulating."],
        ["Biodiversity isolation", "M8 never feeds M9", "Birds cannot create or suppress threat alerts."],
    ], [34 * mm, 68 * mm, 68 * mm]),
    sp(6),
    h2("8.2 Multi-node location (TDOA) in plain language"),
    P("If three microphones hear the same sound at slightly different moments, geometry can estimate where it came from. The backend uses the speed of sound (343 m/s), groups same-class events within six seconds, and fits a location from at least three nodes. It accepts a fit only when the median residual is about 40 m or less, rejects impossible solutions beyond 25 km, and inflates the uncertainty circle when a three-node geometry is ambiguous. With two nodes it draws a bearing band; with one node it draws a rough radius."),
    callout(
        "Important TDOA limitation",
        "The simulator gives nodes known clock offsets, but the real backend does not correct per-node clock drift. Therefore location estimates from real hardware should be treated as indicative until clock synchronisation is added.",
        "amber",
    ),
    h2("8.3 Telegram and deduplication"),
    P("Alerts are deduplicated across nodes within 45 seconds so a single gunshot reported by five phones does not become five messages. Telegram credentials come from environment variables and the API base URL is injectable for testing. Eleven tests pass against a fake server. Real delivery was never verified because no real bot token and chat were supplied."),
    h2("8.4 The dashboard"),
    bullets([
        "Map pins show node status: healthy, degraded or offline; pinned nodes poll every 20 seconds.",
        "A WebSocket pushes new events and live score streams to the page.",
        "The latest source estimate is drawn as a circle; the map was measured at 404–436 px height after the zero-height bug was fixed.",
        "Charts show bird species and hourly activity; analytics loads generated JSON reports.",
        "Leaflet and Chart.js are stored locally; 8 CARTO Voyager tiles returned HTTP 200 on the measured run, with 0 console errors and 0 CDN requests.",
        "If Leaflet is missing, the page shows a guarded message and the live ticker still works.",
    ]),
]

# ======================= 9 ANDROID =======================
S += [
    h1("9. ForestEar Node: the phone app in detail"),
    h2("9.1 What makes the phone version different"),
    P("The phone app does not call the Python models over the network. It carries the model files inside the APK and runs everything on the device. This means it works without internet for detection; the network is only needed to send alerts and heartbeats. It also means the app must adapt to hundreds of phone models, which is why the Device Adaptation Layer exists."),
    h2("9.2 Device Adaptation Layer"),
    tbl([
        ["Part", "What it records or does"],
        ["DeviceProfile / DeviceProbe", "Manufacturer, model, SoC, ABIs, cores, RAM, storage, audio inputs, UNPROCESSED support, effects, telephony, location, battery and thermal API."],
        ["Missing capabilities", "Recorded explicitly, for example network_type_permission (SecurityException). Unknown is never guessed."],
        ["OEM profile table", "10 profiles: OnePlus/Oppo/Realme, Xiaomi, Vivo, Samsung, Huawei, Motorola, Google, Nothing, Asus, generic. Shows setup steps and deep links."],
        ["TierSelector", "Chooses A/B/C/D from measured latency, core count, RAM and model availability."],
        ["RecoveryLog", "Stores recovery outcomes exactly as observed and prints NOT RUN for unexercised paths."],
        ["CompatibilityReport", "Builds a Markdown report from profile, self-test, benchmark and recovery log."],
    ], [40 * mm, 130 * mm]),
    sp(6),
    h2("9.3 Performance tiers"),
    tbl([
        ["Tier", "When it is selected", "What runs"],
        ["A", "Measured ratio <=0.60 of budget, at least 4 cores, at least 2,500 MB RAM, all models loaded", "All 9 models, 0.5 s hop."],
        ["B", "Ratio <=1.00", "All 9 models, 1.0 s hop; M6/M8 duty-cycled."],
        ["C", "Ratio <=2.00 or a model is missing", "Core models M1–M5 and M9, longer hop."],
        ["D", "Slower than 2× budget", "Core models, 2.0 s hop, streaming-only mode."],
        ["Override", "Manual setting", "User can force a tier."],
    ], [18 * mm, 92 * mm, 60 * mm]),
    sp(6),
    h2("9.4 Reliability features"),
    bullets([
        "Foreground service with a persistent notification and microphone/dataSync type.",
        "Wake lock, boot receiver, WorkManager every 15 minutes and AlarmManager every 5 minutes as watchdogs.",
        "Task-removal restart attempted after 2 seconds; explicit Stop disables auto-start and cancels watchdogs.",
        "Thermal governor: warm 40 C, hot 45 C, critical 50 C, pause at 55 C; uses battery temperature because SoC junction temperature is not exposed.",
        "Low-battery thresholds at 20% and 10%; battery CSV rotates at 5 MB.",
        "Audio capture prefers UNPROCESSED, tries to disable AGC/NS/AEC, negotiates sample rate if 16 kHz is refused, and records gaps and route changes.",
    ]),
    h2("9.5 Seven-step setup wizard"),
    numbered([
        "Consent gate: user agrees to local processing and privacy terms (consent version 1).",
        "Backend settings: node ID, backend URL and token.",
        "Permissions: microphone, notifications and optional location are requested through the UI.",
        "OEM protections: maker-specific battery/auto-launch/background steps are shown; only user confirmation is recorded.",
        "Battery allowlist: user is guided to the Doze exemption screen.",
        "Audio calibration: microphone level and headset/USB options are checked.",
        "Screen-off continuity test and final readiness confirmation.",
    ]),
    h2("9.6 Privacy and token handling"),
    tbl([
        ["Feature", "Implementation"],
        ["Token storage", "Android Keystore alias forestear_backend_token, AES-256/GCM, 12-byte IV, Base64 in token_enc."],
        ["Fallback honesty", "Plaintext fallback is reported as plaintext-fallback instead of pretending encryption worked."],
        ["Settings export", "Includes token_included: false; excludes token, audio, logs and device profile."],
        ["Ambient collection", "Opt-in only; 60-second chunks; any chunk containing human presence is deleted whole; nothing uploads automatically."],
        ["Speech", "Never stored or transmitted by the normal pipeline."],
        ["Debug stream", "Optional raw PCM WebSocket at 100 ms / 3,200-byte blocks; explicitly not part of the alert path."],
    ], [36 * mm, 134 * mm]),
    sp(6),
    h2("9.7 Helper scripts"),
    tbl([
        ["Script", "Purpose"],
        ["scripts/install.sh", "Install debug or release APK and optionally grant permissions."],
        ["scripts/grant_permissions.sh", "Verify actual grants; report FAILED when the ROM blocks shell grants. install.sh --grant-all uses -g and warns that SMS is included."],
        ["scripts/whitelist_battery.sh", "Check or request Doze allowlist."],
        ["scripts/self_test.sh", "Headless install/permission/service/network/backend/model/crash checks."],
        ["scripts/capture_logs.sh", "Capture filtered logcat for debugging."],
        ["scripts/pull_collection.sh", "Pull or clear opt-in ambient chunks."],
        ["scripts/soak.sh", "Start/status/watch/stop/report a 24-hour soak."],
        ["scripts/recovery_test.sh", "Exercise kill/swipe/reboot/screen-off recovery paths and record honest outcomes."],
        ["scripts/build_release.sh", "Sign release APK and optionally build ABI splits."],
        ["scripts/emulator_matrix.sh", "Run tests across emulator API levels when emulator tooling exists."],
    ], [46 * mm, 124 * mm]),
]

# ======================= 10 TESTING =======================
S += [
    h1("10. Testing and verification methodology"),
    P("We used several layers of testing because each layer catches a different kind of mistake. Unit tests check small functions. Parity tests prove the phone sees the same numbers as Python. Instrumented tests run on real Android. Self-tests check the installed phone. Recovery tests check what happens when Android interferes. A soak checks long-duration behaviour."),
    h2("10.1 Test inventory"),
    tbl([
        ["Test layer", "Count", "Status", "What it covers"],
        ["Python pytest", "145 collected checks across 10 test files", "Passed in development", "Features, decisions, API, TDOA, transport, splits, metrics, simulator, field harness."],
        ["Android JVM", "88 checks across 13 suites", "0 failures", "Config, decisions, parity, resampling, report, profile, latency budget, OEM table, SNTP, tier, token."],
        ["Android instrumented", "9 checks across 4 suites on CPH2381", "0 failures", "Pipeline parity with golden vectors, latency budget, device adaptation, real Keystore encryption."],
        ["Headless self-test", "Device/configuration checks", "failures=0, warnings=2", "Install, mic, notifications, Doze, service, network, backend, crash buffer."],
        ["In-app hardware self-test", "14 checks", "0 failed, 6 warnings", "Mic level/clipping, headset, USB mic, permissions, protections, network, backend, models, SMS, battery/thermal, signal."],
        ["Recovery paths", "7 exercised", "Mixed PASS/FAIL", "Kill, swipe, screen-off, reboot, boot receiver, watchdog and microphone theft all exercised on CPH2381."],
        ["Dashboard checks", "Manual + scripted browser checks", "Passed", "Map size, keyed tiles, pins, estimate circle, no console errors, no CDN, fallback behaviour."],
        ["Load test", "2 scenarios", "0 failed requests", "20 virtual nodes at 0.5 s and 2.0 s ingest intervals."],
        ["24-hour soak", "Old run invalid; new run started 2026-11-18T15:57:24Z", "PENDING", "Battery, thermal, samples, restarts and benchmark over a full day; no final report yet."],
    ], [34 * mm, 38 * mm, 30 * mm, 68 * mm]),
    sp(6),
    h2("10.2 Real handset used"),
    tbl([
        ["Field", "Value"],
        ["Device", "OnePlus CPH2381 (serial 732738b7), OP535DL1, OxygenOS"],
        ["Android", "14 / API 34, security patch 2026-08-01"],
        ["Hardware", "QTI SM6375, arm64-v8a, 8 cores, 5,390 MB RAM, about 22 GB free storage"],
        ["Audio", "Native output 48 kHz; requested capture 16 kHz; UNPROCESSED supported; built-in mic detected"],
        ["Permissions", "Final install used adb install -g -r, so all requested runtime permissions were granted, including SEND_SMS; granting is not a delivery test"],
        ["Service", "Foreground service LIVE with microphone/dataSync; Doze allowlist enabled"],
        ["Backend", "http://127.0.0.1:8000 through adb reverse; node-01 returned healthy after tunnel restoration"],
    ], [38 * mm, 132 * mm]),
    sp(6),
    h2("10.3 Phone benchmark on CPH2381"),
    tbl([
        ["Metric", "Measured value"],
        ["Mean total pipeline", "49.4735598 ms (about 12% of the 400 ms budget)"],
        ["Tier", "A — reason: measured 49.5 ms / 400 ms = 0.12x"],
        ["CPU", "160.87% (more than one core, as expected for multi-threaded inference)"],
        ["RAM", "321.09 MB"],
        ["GPU", "Unavailable — GpuDelegateFactory class resolution failed; result is CPU/XNNPACK only"],
        ["Runs", "20 per model after 3 warmups"],
        ["Slowest specialist", "M8 BirdNET at 71.62 ms mean; duty-cycling keeps overall cost acceptable"],
        ["Fastest", "M4 at 0.12 ms; M9 Kotlin trees at 0.47 ms"],
    ], [40 * mm, 130 * mm]),
    sp(6),
    h2("10.4 Recovery outcomes, exactly as recorded"),
    tbl([
        ["Path", "Outcome", "Recorded detail"],
        ["Service killed by system/adb", "FAIL", "am kill was refused; force-stop stopped the service and it did not restart; opening the app is required."],
        ["Task swiped from Recents", "PASS", "NodeService was still running 30 seconds after the swipe."],
        ["Phone rebooted", "FAIL", "Service was not running 30 seconds after boot."],
        ["Boot receiver", "FAIL", "No background restart within 30 s; expected on Android 14+ for a microphone foreground service."],
        ["Screen off", "PASS", "100% audio coverage, 0 gap events over 303 s."],
        ["Watchdog", "FAIL", "Force-stop at 15:36:39Z; after 420 s (past the 5-minute alarm) NodeService_count=0 and pid=none; the app had to be opened manually."],
        ["Another app took microphone", "PASS", "OnePlus recorder took the mic; app logged 10 s zero signal, DEGRADED and an audio error, restarted audio after 30 s, resumed capture on CPH2381 and showed RECORD_AUDIO running again."],
    ], [42 * mm, 20 * mm, 108 * mm]),
    sp(6),
    callout(
        "Why reboot FAIL is not a coding bug",
        "Android 14 deliberately stops an app from starting a microphone foreground service in the background after boot. "
        "The app shows a notification asking the user to open it. Reporting PASS would be dishonest; the app reports FAIL/DEGRADED instead.",
        "amber",
    ),
]

# ======================= 11 RESULTS =======================
S += [
    h1("11. Measured results in detail"),
    h2("11.1 Final model quality"),
    tbl([
        ["Class", "F1 score", "Plain meaning"],
        ["Chainsaw", "0.7790", "Good, but misses some cutting events."],
        ["Gunshot", "0.8730", "Strongest class."],
        ["Vehicle", "0.8585", "Strong."],
        ["Human", "0.7592", "Acceptable."],
        ["Benign", "0.8235", "Good at rejecting false alarms."],
        ["Macro average", "0.8186", "Average across classes; the headline number."],
    ], [34 * mm, 26 * mm, 110 * mm]),
    P("Test split contained 6,612 windows: 152 chainsaw, 472 gunshot, 1,692 vehicle, 1,608 human and 2,688 benign."),
    sp(4),
    h2("11.2 Robustness by source"),
    tbl([
        ["Source", "Windows", "Macro-F1", "What it tells us"],
        ["Synthetic", "960", "0.8393", "Good on generated sounds similar to training augmentation."],
        ["UrbanSound8K", "4,084", "0.6701", "Moderate when sounds come from a different dataset."],
        ["ESC-50", "1,568", "0.5147", "Large drop; chainsaw recall only 0.3125. Cross-source generalisation is a real weakness."],
    ], [34 * mm, 24 * mm, 26 * mm, 86 * mm]),
    sp(4),
    h2("11.3 Calibration"),
    tbl([
        ["Measure", "Before", "After isotonic calibration", "Meaning"],
        ["ECE", "0.1196", "0.0506", "Confidence is closer to actual correctness."],
        ["Brier score", "0.3003", "0.2717", "Probabilities are better overall."],
    ], [30 * mm, 26 * mm, 42 * mm, 72 * mm]),
    sp(4),
    h2("11.4 System-level simulation"),
    tbl([
        ["Metric", "Result", "Interpretation"],
        ["Alert precision", "0.79–0.89 by class", "When an alert fires, it is usually the right class."],
        ["Alert recall", "0.16–0.22 by class", "Many real events do not produce an alert under conservative rules."],
        ["Storm veto", "0/1 suppressions on weather windows; 0 false vetoes on gunshot", "Veto behaves in the intended direction in this test."],
        ["Stress extrapolation", "2,744 false alarms per node-day", "Unacceptable for production without better field tuning."],
    ], [34 * mm, 52 * mm, 84 * mm]),
    sp(4),
    h2("11.5 Backend load test (Apple M1 laptop, loopback)"),
    tbl([
        ["Scenario", "Requests", "Failed", "Throughput", "p50", "p95", "p99", "Max"],
        ["20 nodes @ 0.5 s", "200", "0", "9.99 req/s", "42.7 ms", "110.5 ms", "141.1 ms", "163.3 ms"],
        ["20 nodes @ 2.0 s", "760", "0", "37.98 req/s", "45.8 ms", "119.4 ms", "151.8 ms", "296.5 ms"],
    ], [32 * mm, 20 * mm, 16 * mm, 26 * mm, 20 * mm, 20 * mm, 20 * mm, 20 * mm]),
    sp(4),
    h2("11.6 Phone self-test and health"),
    tbl([
        ["Check", "Result"],
        ["Latest in-app hardware self-test", "14 checks, 0 failed, 6 warnings; mic rms about -57 dBFS; 9 models loaded; m9 probability sum 1.000; BirdNET on."],
        ["Earlier in-app self-test", "14 checks, 0 failed, 7 warnings; model latency 145.5 ms."],
        ["Post-recovery headless self-test", "failures=0, warnings=2 (phone state, SMS)."],
        ["Fresh headless self-test (2026-11-18)", "failures=0, warnings=2; backend reachable via adb reverse; NodeService foreground with microphone."],
        ["Backend telemetry", "node-01 healthy with fresh heartbeat after adb reverse was re-established."],
        ["Continuity", "100% coverage over 303 s with zero gaps while the screen was off."],
    ], [46 * mm, 124 * mm]),
    sp(4),
    h2("11.7 M6 removal test: measured, not adopted"),
    P("Because M6 scored below chance, we retrained M9 with its four M6 slots set to zero during training and inference. The model still received 47 features, and M6 still fed the INFO-only anomaly decision. Both variants were evaluated on the same 6,612 held-out windows."),
    tbl([
        ["Measure", "Deployed M9", "No-M6 variant", "Reading"],
        ["LightGBM macro-F1", "0.8186", "0.8173", "Slightly worse"],
        ["LightGBM accuracy", "0.8200", "0.8162", "Slightly worse"],
        ["MLP alternative macro-F1", "0.7889", "0.7987", "Better"],
        ["Calibrated ECE", "0.0506", "0.0442", "Better"],
        ["Threat false alerts (simulation)", "105", "111", "Worse"],
        ["False vetoes on true gunshots", "0", "1", "Worse"],
    ], [48 * mm, 26 * mm, 28 * mm, 68 * mm]),
    P("Decision: keep the deployed model. Calibration and the smaller MLP improved, but the primary classifier was slightly less accurate, the simulation produced more false alerts, and one false weather veto appeared. Brier score (0.2717 to 0.2729) and all three robustness sources also did not improve. This does not prove M6 helps; it shows that simply removing it was not an overall gain on this split. Full evidence is in forestear/reports/model_quality/comparison.md."),
    sp(4),
    h2("11.8 Weather model calibration: measured, not adopted"),
    P("The weather model M5 is a false-alarm brake, not a threat detector. A cheaper alternative to a 13.5-hour CNN retrain was tested: a fixed adjustment to its confidence scores, chosen only on validation data and then applied once to the held-out test set. This genuinely improved M5 on its own, so it was checked carefully before adoption."),
    tbl([
        ["Measure", "Deployed", "Adjusted", "Reading"],
        ["M5 macro-F1 (held-out test)", "0.5761", "0.6728", "Better"],
        ["M9 macro-F1, same trained referee", "0.8186", "0.8079", "Worse"],
        ["M9 macro-F1, referee retrained", "0.8186", "0.8199", "Effectively unchanged"],
        ["M9 accuracy, referee retrained", "0.8200", "0.8138", "Worse"],
    ], [52 * mm, 24 * mm, 24 * mm, 70 * mm]),
    P("Decision: not adopted. The better weather scores did not make the final referee better. Feeding it the adjusted values lowered its macro-F1 by about one point because it had been trained on the original values; retraining it to match was a wash (macro-F1 +0.0013, accuracy -0.0062). Keeping two different weather outputs for the phone and the referee would add complexity for a marginal result. M5 stays at 0.5761 and M9 at 0.8186, with zero false weather vetoes on real gunshots. A cheaper dense M5 model was also rejected (0.4933 vs 0.6055 on validation). Evidence: forestear/reports/model_quality/m5_calibration_m9_tradeoff.json and comparison.md."),
]

# ======================= 12 DELIVERABLES =======================
S += [
    h1("12. Deliverables"),
    P("The deliverables are the tangible things produced by the project: code, trained models, reports, documentation, scripts, test evidence and the running demonstration assets."),
    h2("12.1 Code repositories"),
    tbl([
        ["Repository / path", "What it contains", "Status"],
        ["forestear/", "Python edge agent, ML training/export, FastAPI backend, dashboard, simulator, tests, docs, reports.", "Local commit 3a58fa8; clean; not pushed."],
        ["android/", "ForestEar Node Kotlin/Compose app, OEM config, scripts, docs, device evidence, report generator.", "Local commit 2b0c46d; clean; not pushed."],
    ], [36 * mm, 104 * mm, 30 * mm]),
    sp(6),
    h2("12.2 Models, data artifacts and reports"),
    tbl([
        ["Artifact", "Location", "Purpose"],
        ["Trained model files", "forestear/models_store/", "YAMNet SavedModel, BirdNET TFLite, M2/M5 CNNs, M6 autoencoder, M3/M4/M7 heads, M9 exports."],
        ["Feature cache", "forestear/data/cache/windows.npz", "About 284.8 MB of precomputed windows for repeatable training/evaluation."],
        ["Unified manifest", "forestear/data/dataset/manifest.csv", "8,592 lines describing all dataset windows and folds."],
        ["Evaluation reports", "forestear/reports/", "summary.md, metrics_per_model.json, robustness.json, decision_sim.json, train_m9.json."],
        ["Model-quality evidence", "forestear/reports/model_quality/", "Baseline, no-M6 and M5-calibration reports plus comparison.md, which records why neither variant was adopted."],
        ["Dashboard", "forestear/dashboard/", "Static HTML/CSS/JS with local Leaflet and Chart.js."],
        ["Simulator", "forestear/simulator/", "7 named scenarios with ground-truth files."],
        ["Android model assets", "android/ForestEarNode/app/src/main/assets/", "TFLite models, M9 tree binary, runtime config and OEM profiles."],
    ], [38 * mm, 54 * mm, 78 * mm]),
    sp(6),
    h2("12.3 Android verification evidence"),
    tbl([
        ["File", "What it proves"],
        ["android/device_evidence/benchmark_CPH2381.json", "On-phone latency, CPU, RAM, tier and per-model timings."],
        ["android/device_evidence/selftest_CPH2381.json", "Earlier in-app hardware self-test with 7 warnings."],
        ["android/device_evidence/selftest_latest_CPH2381.json", "Latest in-app hardware self-test used by the compatibility report."],
        ["android/device_evidence/selftest_postrecovery_CPH2381.txt", "Headless self-test output after recovery: failures=0, warnings=2."],
        ["android/device_evidence/selftest_CPH2381_20261118.txt", "Fresh 2026-11-18 headless self-test: failures=0, warnings=2."],
        ["android/device_evidence/instrumented_20261118.log", "9 on-device tests, 0 failures, 0 skipped on CPH2381."],
        ["android/device_evidence/watchdog_20261118.log", "Force-stop and 420 s observation: service did not return (FAIL)."],
        ["android/device_evidence/mic_theft_20261118.log", "Zero signal, DEGRADED, 30 s restart and resumed capture (PASS)."],
        ["android/device_evidence/soak/20261118-210215/", "Invalid partial soak: 1 h 29 m, power unplugged, no final report."],
        ["android/device_evidence/recovery_paths_CPH2381.json", "Exact recovery outcomes with timestamps, consolidated across both test days."],
        ["android/device_evidence/compatibility_report_CPH2381.md", "Phone-generated compatibility report; canonical device report."],
        ["android/device_evidence/measured_devices.json", "Machine-readable measured-device summary used to generate docs."],
        ["android/device_evidence/README.md", "Evidence guide and harness caveats."],
    ], [68 * mm, 102 * mm]),
    sp(6),
    h2("12.4 Documentation deliverables"),
    bullets([
        "forestear/README.md — master overview, quick start, model table, assumptions and attributions.",
        "forestear/docs/architecture.md — pipeline, fold discipline, M9 stacking, decision logic.",
        "forestear/docs/model_cards.md — M1–M9 definitions, training data and limitations.",
        "forestear/docs/map.md — map behaviour, measured checks and accuracy caveats.",
        "forestear/docs/security.md — CARTO key rotation, backend hardening and the preflight check.",
        "forestear/docs/verification_status.md — Python-side NOT RUN / NOT VERIFIED gap list.",
        "android/README.md and ForestEarNode/README.md — phone app architecture and build instructions.",
        "android/ForestEarNode/docs/ — phone setup, generated OEM guides and supported-device table.",
        "android/docs/ — release build, privacy/token handling, emulator matrix status and verification gaps.",
        "ForestEar_Project_Report.pdf — this document.",
    ]),
]

# ======================= 13 HOW TO RUN =======================
S += [
    h1("13. How to run and use the project"),
    h2("13.1 Python platform — from a clean machine"),
    code_block(
        "cd forestear\n"
        "make setup          # create ../.venv and install pinned requirements\n"
        "make data           # download datasets and pretrained models (idempotent)\n"
        "make dataset        # build the unified manifest\n"
        "make backgrounds    # build benign/ambient bank\n"
        "make cache          # one heavy pass: log-mel + embeddings + handcrafted\n"
        "make train          # train M2-M7, then M9 fusion + calibration\n"
        "make export         # TFLite export, parity check, latency benchmark\n"
        "make eval           # regenerate reports/ from real predictions\n"
        "make test           # pytest suite\n"
        "make security-check # confirm no secret is tracked (prints paths only)\n"
        "make backend &      # API + dashboard at http://127.0.0.1:8000\n"
        "make smoke          # synthetic traffic into the dashboard\n"
        "make demo           # 4 virtual nodes, full pipeline"
    ),
    sp(4),
    P("If data download is slow, use make data-small. For a quick UI-only demo without training, run make backend in one terminal and make smoke in another."),
    h2("13.2 Docker"),
    code_block(
        "cd forestear\n"
        "docker compose -f docker/docker-compose.yml build\n"
        "docker compose -f docker/docker-compose.yml up -d"
    ),
    sp(4),
    P("The backend image uses docker/requirements.backend.txt and is about 388 MB; TensorFlow is not installed in the backend image."),
    h2("13.3 Android app"),
    code_block(
        "cd android/ForestEarNode\n"
        "JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home \\\n"
        "  ./gradlew :app:testDebugUnitTest assembleDebug\n"
        "adb install -r app/build/outputs/apk/debug/app-debug.apk\n"
        "adb reverse tcp:8000 tcp:8000\n"
        "../scripts/install.sh\n"
        "../scripts/self_test.sh"
    ),
    sp(4),
    P("After installation, open the app once and complete the seven-step wizard. Keep auto-start and the battery exemption enabled. The adb reverse tunnel is needed only for the test harness where the phone reaches the laptop at 127.0.0.1:8000; it is lost after reboot and must be recreated."),
    h2("13.4 Verification scripts"),
    code_block(
        "cd android\n"
        "./scripts/self_test.sh\n"
        "./scripts/recovery_test.sh status\n"
        "./scripts/recovery_test.sh swipe --auto --wait 30\n"
        "./scripts/recovery_test.sh screen-off --wait 30\n"
        "./scripts/recovery_test.sh reboot\n"
        "./scripts/recovery_test.sh report\n"
        "./scripts/soak.sh --start\n"
        "./scripts/soak.sh --status\n"
        "./scripts/soak.sh --stop"
    ),
    sp(4),
    P("On ROMs that refuse adb shell permission grants, use ./scripts/install.sh --grant-all (which explicitly warns that -g also grants SEND_SMS) or grant permissions in Settings. grant_permissions.sh now reports FAILED and exits non-zero instead of hiding the problem as 'skipped'."),
    sp(4),
    h2("13.5 Generating the compatibility report and docs"),
    code_block(
        "# On the phone: Diagnose -> Run self-test -> Write compatibility report\n"
        "adb pull /sdcard/Android/data/org.forestear.node/files/docs/ \\\n"
        "  android/device_evidence/\n"
        "cd android && python3 tools/generate_oem_docs.py"
    ),
]

# ======================= 14 LIMITATIONS =======================
S += [
    h1("14. Honest limitations and risk register"),
    P("This section is intentionally direct. A prototype that hides its weaknesses is not useful for review or for future work."),
    tbl([
        ["Item", "Status", "Impact", "Recommended action"],
        ["M6 anomaly model", "AUROC 0.482; removal tested, not adopted", "Below random chance; cannot be trusted as a threat signal. Simple removal also did not improve M9 overall.", "Retrain with better normal data and evaluate again; keep INFO-only until fixed."],
        ["Weather model M5", "Macro-F1 0.5761", "Weak veto can suppress real gunshots or miss thunder.", "Collect more labelled local weather audio."],
        ["Cross-source generalisation", "ESC-50 F1 0.5147", "Model degrades on unfamiliar recordings.", "Domain adaptation with real field audio."],
        ["System recall", "0.16–0.22 in simulation", "Many events will not alert.", "Tune thresholds per deployment; accept trade-off with false alarms."],
        ["False-alarm stress", "2,744 per node-day extrapolated", "Not production-ready.", "Field calibration and better veto logic."],
        ["Real field audio", "NOT RUN", "No claim about real forest accuracy.", "Run a labelled field trial."],
        ["Telegram real delivery", "NOT VERIFIED", "Bot path only tested against fake server.", "Supply real token/chat and send a controlled test."],
        ["SMS delivery", "NOT VERIFIED; permission granted by -g but no live SIM", "Fallback was never exercised on a carrier; handset reports OUT_OF_SERVICE.", "Test with a real SIM and carrier."],
        ["TDOA clock offsets", "Simulator only", "Real location estimates may be biased.", "Add clock synchronisation or offset estimation."],
        ["Watchdog recovery", "FAIL (recorded)", "The 5-minute watchdog did not restart the service after force-stop.", "Investigate/replace the watchdog; require manual app open until fixed."],
        ["Microphone theft recovery", "PASS (recorded)", "Recovery took a measured 30 s after 10 s of silence; brief DEGRADED window remains.", "Consider shortening the restart delay."],
        ["24-hour soak", "PENDING (old run invalid)", "Long-duration stability unknown; first run lost power after 1 h 29 m.", "Keep the fresh run powered; pull and review its report after 24 h."],
        ["Signed release APK", "NOT RUN", "No keystore/passwords supplied; only debug APK built.", "Provide keystore and run build_release.sh + apksigner verify."],
        ["Emulator matrix", "NOT RUN", "No emulator/system images installed.", "Install tooling and run emulator_matrix.sh."],
        ["Backend security", "Development posture", "Default token, no TLS, single-process SQLite, no per-user auth.", "Add TLS, real auth, hardened DB before deployment."],
        ["CARTO key exposure", "Key pasted in chat and stored in forestear/.env", "Map tile quota abuse or billing risk.", "Rotate the key and restrict it to the deployment domain; see forestear/docs/security.md."],
        ["SoC temperature", "Not exposed", "Thermal governor uses battery temperature only.", "Treat as best-effort; document on supported devices."],
        ["Microphone response", "Not characterised", "Built-in processing may differ from training data.", "Calibrate per device or use measured external mic."],
    ], [38 * mm, 30 * mm, 46 * mm, 56 * mm]),
    sp(6),
    callout(
        "What the project does NOT claim",
        "It does not claim real-forest accuracy, production security, successful Telegram/SMS delivery, successful reboot recovery, "
        "a finished 24-hour soak, or validated localisation with real clock drift. Each of these is either pending or explicitly failed in the evidence.",
        "red",
    ),
]

# ======================= 15 FUTURE WORK =======================
S += [
    h1("15. Prioritised future work"),
    numbered([
        "Pull and review the 24-hour soak report; investigate any restart, thermal throttling or sample gaps.",
        "Rotate and domain-restrict the exposed CARTO key before any deployment or publication.",
        "Fix M6 with better normal-data training. Simply masking it from M9 was measured and did not improve the system overall.",
        "Improve weather classification with locally labelled rain/wind/thunder data and retest the veto.",
        "Run a labelled field trial and report real precision/recall, not just internal test metrics.",
        "Add clock synchronisation or offset estimation so TDOA works on unsynchronised real nodes.",
        "Test real Telegram and SMS delivery with controlled credentials and a real SIM.",
        "Exercise watchdog and microphone-theft recovery paths and record the outcomes.",
        "Produce a signed release APK after receiving keystore credentials and verify it with apksigner.",
        "Install emulator tooling and complete the Android 8–15 compatibility matrix.",
        "Harden the backend: TLS, per-node keys, audit logging, retention policy and a production database.",
        "Explore MapLibre vector tiles if a style URL is supplied; current dashboard is keyed CARTO raster only.",
    ]),
]

# ======================= 16 GLOSSARY =======================
S += [
    h1("16. Glossary in plain language"),
    tbl([
        ["Term", "Plain meaning"],
        ["Edge device", "The small computer or phone next to the microphone that does the thinking locally."],
        ["Ensemble", "A team of models whose answers are combined instead of relying on one model."],
        ["Log-mel spectrogram", "A compact picture of sound frequencies over time, shaped to match human hearing."],
        ["Window / hop", "A one-second slice of audio; a new slice starts every half second."],
        ["Embedding", "A compact numerical description of a sound that a model can compare easily."],
        ["Calibration", "Adjusting confidence scores so '80% sure' really means about 80% correct."],
        ["ECE", "Expected Calibration Error — a single number for how far confidence is from reality."],
        ["Macro-F1", "An average score across classes that treats rare classes as importantly as common ones."],
        ["Precision", "Of all the alerts raised, how many were correct."],
        ["Recall", "Of all the real events, how many raised an alert."],
        ["TDOA", "Time Difference of Arrival — locating a sound by comparing when different microphones heard it."],
        ["Foreground service", "An Android service that must show a notification so the user knows it is running."],
        ["Doze allowlist", "Android setting that stops the OS from freezing the app to save battery."],
        ["Tier", "A performance level chosen from measured phone speed: A fastest, D most reduced."],
        ["Duty cycle", "Running a model only some of the time, for example BirdNET once every ten windows."],
        ["Dry-run", "Pretending to send a message but only logging it; safe default for Telegram."],
        ["Offline queue", "A local waiting list of events that could not be sent yet."],
        ["Parity", "Proof that two implementations produce the same numbers for the same input."],
        ["Instrumented test", "A test that runs on a real Android device, not just a laptop."],
        ["Soak test", "Leaving the system running for a long time to find slow failures."],
        ["PASS / FAIL / NOT RUN", "Test ran and succeeded / test ran and failed / test was never exercised."],
    ], [40 * mm, 130 * mm]),
]

# ======================= APPENDICES =======================
S += [
    PageBreak(),
    h1("Appendix A — Repository map"),
    code_block(
        "forestear/\n"
        "  backend/         FastAPI app, correlator, alerts, ORM\n"
        "  common/          config + logging\n"
        "  configs/         default.yaml, labels.yaml\n"
        "  dashboard/       index.html, app.js, styles.css, vendor/\n"
        "  data/            raw corpora, dataset manifest, feature cache\n"
        "  docker/          backend/edge Dockerfiles + compose\n"
        "  docs/            architecture, model cards, map, privacy, hardware\n"
        "  edge_agent/      zoo, decision engine, transport, audio sources\n"
        "  ml/              features, models, training, export, augmentation\n"
        "  models_store/    trained model artifacts\n"
        "  reports/         generated evaluation reports\n"
        "  scripts/         data, eval, smoke, load-test helpers\n"
        "  simulator/       7 scenarios + runner\n"
        "  tests/           145 collected pytest checks\n"
        "\n"
        "android/\n"
        "  ForestEarNode/   Kotlin/Compose app (org.forestear.node)\n"
        "    app/src/main/  service, models, device layer, UI, config assets\n"
        "    app/src/test/  88 JVM checks\n"
        "    app/src/androidTest/ 9 on-device checks\n"
        "  device_evidence/ benchmark, self-tests, recovery, compatibility report\n"
        "  docs/            release, privacy, emulator matrix\n"
        "  scripts/         install, self-test, recovery, soak, release, emulator\n"
        "  tools/           asset export, stream receiver, doc generator"
    ),
    sp(8),
    h1("Appendix B — Evidence index"),
    tbl([
        ["Evidence", "Question it answers"],
        ["forestear/reports/summary.md", "What did the final model score on the held-out test split?"],
        ["forestear/reports/robustness.json", "How does performance change across datasets?"],
        ["forestear/reports/decision_sim.json", "What happens when persistence/cooldown/weather rules are applied?"],
        ["forestear/reports/model_quality/comparison.md", "Was the below-chance M6 model removed from M9, and why was that change rejected?"],
        ["forestear/reports/load_test.json", "How many ingest requests can the backend handle on the dev laptop?"],
        ["android/device_evidence/benchmark_CPH2381.json", "How fast is the full pipeline on a real phone?"],
        ["android/device_evidence/selftest_latest_CPH2381.json", "Do the phone's sensors, permissions, models and backend work?"],
        ["android/device_evidence/recovery_paths_CPH2381.json", "Which recovery paths actually ran and what happened?"],
        ["android/device_evidence/compatibility_report_CPH2381.md", "What is the phone's official generated device report?"],
        ["android/ForestEarNode/app/build/outputs/androidTest-results/", "Raw on-device test XML showing 9 tests, 0 failures."],
    ], [72 * mm, 98 * mm]),
    sp(8),
    h1("Appendix C — Command quick reference"),
    code_block(
        "# Python\n"
        "make setup && make data && make dataset && make cache && make train\n"
        "make export && make eval && make test && make security-check\n"
        "make backend && make smoke\n"
        "\n"
        "# Android\n"
        "./gradlew :app:testDebugUnitTest assembleDebug\n"
        "adb install -r app/build/outputs/apk/debug/app-debug.apk\n"
        "adb reverse tcp:8000 tcp:8000\n"
        "\n"
        "# Device verification\n"
        "./scripts/self_test.sh\n"
        "./scripts/recovery_test.sh report\n"
        "./scripts/soak.sh --start / --status / --stop\n"
        "\n"
        "# Docs\n"
        "python3 tools/generate_oem_docs.py"
    ),
    sp(10),
    callout(
        "End of report",
        "ForestEar is a substantial, well-documented prototype. Its strongest achievements are the parity-preserving nine-model pipeline, "
        "a working backend/dashboard, a fully on-phone implementation, and unusually careful evidence. Its next step is not more features, "
        "but field validation and honest fixing of the known weak spots.",
        "green",
    ),
]

# ---------- build ----------
doc.multiBuild(S, onFirstPage=cover_page, onLaterPages=body_page)
print(f"wrote {OUT}")
