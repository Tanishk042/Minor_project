#!/usr/bin/env python3
"""Generate the OEM setup guides and supported-device matrix from the JSON table.

The JSON asset is the single source of truth used by the app at runtime. Measured
handsets come from `device_evidence/measured_devices.json`, which must point at a
pulled compatibility report. These Markdown files are generated so the docs cannot
drift from what the app actually shows. Re-run after editing either JSON file:

    python3 android/tools/generate_oem_docs.py

Outputs:
    ForestEarNode/docs/oem_guides.md
    ForestEarNode/docs/supported_devices.md
"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSET = ROOT / "ForestEarNode" / "app" / "src" / "main" / "assets" / "config" / "oem_profiles.json"
MEASURED = ROOT / "device_evidence" / "measured_devices.json"
DOCS = ROOT / "ForestEarNode" / "docs"

STEP_TITLES = {
    "autostart": "Auto-launch / startup manager",
    "battery": "Battery optimisation",
    "background_activity": "Background activity",
    "recents": "Lock in Recents",
    "overlay": "Display over other apps (optional)",
    "charge_limit": "Charge limit / battery care (optional)",
    "battery_health": "Battery swelling inspection",
}

STEP_ORDER = list(STEP_TITLES)


def load() -> dict:
    with ASSET.open(encoding="utf-8") as fh:
        return json.load(fh)


def load_measured() -> list[dict]:
    """Real handsets with an on-device compatibility report in device_evidence/."""
    if not MEASURED.exists():
        return []
    with MEASURED.open(encoding="utf-8") as fh:
        value = json.load(fh)
    return value if isinstance(value, list) else []


def header(data: dict, title: str) -> list[str]:
    updated = data.get("updated_utc", "unknown")
    return [
        f"# {title}",
        "",
        "> Generated from "
        "`android/ForestEarNode/app/src/main/assets/config/oem_profiles.json` and "
        "`android/device_evidence/measured_devices.json` by "
        "`android/tools/generate_oem_docs.py`. Do not edit by hand: edit the JSON "
        "files and re-run the generator.",
        "",
        f"Table version: `{data.get('schema', '?')}` (updated `{updated}`). "
        f"Generated: `{datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')}`.",
        "",
    ]


def oem_guides(data: dict) -> str:
    lines = header(data, "OEM background-protection guides")
    lines += [
        "Android has no public API for most maker-specific kill switches, so the "
        "app cannot verify them. It shows the plain-language steps below, resolves "
        "each deep link before launching it, and records only what you explicitly "
        "confirm. The OS-verifiable checks are the battery-optimisation exemption "
        "and the overlay permission.",
        "",
        "Use **Setup → step 4** in the app; the same table drives that screen. "
        "Reference site for current per-maker behaviour: <https://dontkillmyapp.com/>.",
        "",
        "If every deep link fails on your build, the app falls back to the app-details "
        "screen and the standard Android battery-optimisation screen. Follow the "
        "plain-language steps manually; they are the source of truth.",
        "",
        "---",
        "",
    ]

    for profile in data.get("profiles", []):
        pid = profile.get("id", "generic")
        name = profile.get("display_name", pid)
        keys = ", ".join(profile.get("manufacturer_keys", [])) or "—"
        lines += [f"## {name}", "", f"- Profile id: `{pid}`", f"- Matches: {keys}"]
        url = profile.get("dontkillmyapp_url")
        if url:
            lines.append(f"- Reference: <{url}>")
        notes = (profile.get("notes") or "").strip()
        if notes:
            lines += ["", notes]

        steps = profile.get("steps", {})
        lines += ["", "### Steps", ""]
        for step in STEP_ORDER:
            entries = [s for s in steps.get(step, []) if s.strip()]
            if not entries:
                continue
            lines += [f"**{STEP_TITLES[step]}**", ""]
            for i, entry in enumerate(entries, 1):
                lines.append(f"{i}. {entry}")
            lines.append("")

        intents = profile.get("intents", {})
        if intents:
            counts = ", ".join(
                f"`{step}` ({len(intents.get(step, []))})"
                for step in STEP_ORDER
                if intents.get(step)
            )
            lines += [
                "Deep-link shortcuts (best effort, resolved before launch): " + counts + ".",
                "",
            ]

        known = [
            d
            for d in data.get("known_devices", [])
            if d.get("manufacturer") == pid
        ]
        if known:
            lines += ["Known devices in this family:", ""]
            for dev in known:
                tested = "tested on hardware" if dev.get("tested") else "not tested on hardware"
                note = f" — {dev['notes']}" if dev.get("notes") else ""
                lines.append(f"- `{dev.get('model_pattern', '')}` ({tested}){note}")
            lines.append("")
        lines += ["---", ""]

    lines += [
        "## Generic fallback",
        "",
        "If no profile matches, the app uses the standard Android screens:",
        "",
        "1. Settings → Battery → Battery optimisation → All apps → ForestEar Node → "
        "Don't optimise.",
        "2. Settings → Apps → ForestEar Node → Battery → Unrestricted.",
        "3. Lock the app in Recents if the maker offers it.",
        "",
        "The Status tab keeps a warning visible while a required protection is missing.",
        "",
    ]
    return "\n".join(lines)


def supported_devices(data: dict, measured: list[dict]) -> str:
    lines = header(data, "Supported devices")
    lines += [
        "ForestEar Node runs on **any Android 8.0+ (API 26) device with a "
        "microphone**. There is no allowlist and no Play Services dependency. "
        "\"Supported\" on this page means only that the app will attempt to run and "
        "will report what it finds; it is not a claim that every handset has been "
        "measured.",
        "",
        "At first launch and after every app update the app probes the handset and "
        "writes `device_profile.json`: manufacturer, model, SoC, ABIs, cores, RAM, "
        "storage, audio inputs, UNPROCESSED support, effects, telephony/SIM, location "
        "providers, battery and thermal API. Anything the OS does not expose is "
        "recorded as **missing**, never guessed.",
        "",
        "## Measured on real hardware",
        "",
    ]
    if measured:
        lines += [
            "Only devices with a pulled `compatibility_report_<model>.md` and its "
            "supporting evidence are listed here. JVM parity tests alone do not "
            "qualify a handset.",
            "",
            "| Device | Android | Tier | Measured pipeline | Recovery paths | Report |",
            "|---|---|---|---|---|---|",
        ]
        for dev in measured:
            lines.append(
                "| {device} | {android} | {tier} | {pipeline} ms ({source}) | "
                "{recovery} | [report](../../{report}) |".format(
                    device=dev.get("device", "unknown"),
                    android=dev.get("android", "unknown"),
                    tier=dev.get("tier", "unknown"),
                    pipeline=dev.get("pipeline_ms", "not measured"),
                    source=dev.get("pipeline_source", "unknown"),
                    recovery=dev.get("recovery", "not run"),
                    report=dev.get("report", "#"),
                )
            )
        lines.append("")
    else:
        lines += [
            "**No handset measurement is recorded in this repository yet.** The JVM "
            "parity tests run on the development laptop; on-phone latency, CPU, RAM, "
            "thermal, SMS and recovery-path results require a connected device. When "
            "you run them, the app writes `docs/compatibility_report_<model>.md` on "
            "the phone. Do not add a device to this section until that file exists.",
            "",
            "| Device | Android | Tier | Measured pipeline | Recovery paths | Report |",
            "|---|---|---|---|---|---|",
            "| _(none recorded)_ | — | — | not measured | not run | — |",
            "",
        ]
    lines += [
        "## Known device profiles",
        "",
        "A known device only selects a better OEM guide; it does not assert that the "
        "handset was tested.",
        "",
        "| Model pattern | OEM family | Tested on hardware | Notes |",
        "|---|---|---|---|",
    ]
    if data.get("known_devices"):
        for dev in data["known_devices"]:
            lines.append(
                f"| `{dev.get('model_pattern', '')}` | `{dev.get('manufacturer', 'generic')}` | "
                f"{'yes' if dev.get('tested') else 'no'} | {dev.get('notes', '')} |"
            )
    else:
        lines.append("| _(none)_ | — | — | — |")

    lines += [
        "",
        "## OEM families",
        "",
        "The table is data, not code. Adding a maker means editing the JSON asset and "
        "re-running the generator. See [oem_guides.md](oem_guides.md) for the steps.",
        "",
        "| Profile | Matches | Deep-link steps | Notes |",
        "|---|---|---|---|",
    ]
    for profile in data.get("profiles", []):
        pid = profile.get("id", "generic")
        keys = ", ".join(profile.get("manufacturer_keys", [])) or "—"
        intents = profile.get("intents", {})
        steps_with_links = ", ".join(
            step for step in STEP_ORDER if intents.get(step)
        ) or "none"
        note = (profile.get("notes") or "").strip().replace("\n", " ")
        if len(note) > 140:
            note = note[:137] + "…"
        lines.append(f"| `{pid}` | {keys} | {steps_with_links} | {note} |")

    lines += [
        "",
        "## Performance tiers",
        "",
        "The tier is chosen from a measured benchmark of the bundled models on the "
        "handset, with a manual override in Settings. The app never claims a tier it "
        "did not measure; before the first benchmark it starts at tier C.",
        "",
        "| Tier | Models | Hop | Notes |",
        "|---|---|---|---|",
        "| A | all 9 | 0.5 s | requires ≥4 cores, ≥2500 MB RAM and all models loaded |",
        "| B | all 9 | 1.0 s | anomaly (M6) and BirdNET (M8) duty-cycled |",
        "| C | core (M1, M2, M3, M4, M5, M9) | 1–2 s | used when a model is missing or timing is over budget |",
        "| D | core | long hop | inference paused; raw PCM streamed to a laptop/server |",
        "",
        "A runtime latency governor lengthens the hop first, then drops M6, then M8, "
        "then switches to streaming-only. It relaxes again after a sustained run of "
        "fast windows. Every change is logged and shown on the Status tab.",
        "",
        "## How to qualify a new phone",
        "",
        "1. Install with `android/scripts/install.sh` and finish the 7-step Setup "
        "wizard. Use the OEM guide for your maker.",
        "2. Run **Diagnose → Benchmark** and let the app select a tier. Record the "
        "measured milliseconds; do not estimate them.",
        "3. Run **Diagnose → Hardware self-test**.",
        "4. Run `android/scripts/recovery_test.sh status`, then `kill`, `swipe`, "
        "`screen-off` and `reboot`. Read the recorded outcomes; do not assume PASS.",
        "5. Run the in-app screen-off continuity test (≥90 % coverage and zero gaps "
        "to pass).",
        "6. Run a 24 h soak with `android/scripts/soak.sh`.",
        "7. Write the compatibility report in Diagnose and pull "
        "`docs/compatibility_report_<model>.md`.",
        "8. Add the measured numbers and the report to this page only after step 7 "
        "succeeds.",
        "",
        "## Honest limitations",
        "",
        "- Android 14+ blocks a microphone foreground service from starting in the "
        "background. After a reboot the app posts a notification and asks you to open "
        "it; this is a platform rule, not a bug. On Android 15 a boot receiver cannot "
        "start a microphone FGS at all.",
        "- OEM auto-launch, background-activity and Recents-lock switches have no "
        "public API. The app records user confirmation, not verification.",
        "- Detection accuracy was measured on the Python evaluation set, not on any "
        "phone microphone. The compatibility report states this explicitly.",
        "- A Wi-Fi-only tablet or phone runs fully over Wi-Fi; SMS fallback is simply "
        "unavailable and is reported as missing.",
        "",
    ]
    return "\n".join(lines)


def main() -> None:
    data = load()
    measured = load_measured()
    DOCS.mkdir(parents=True, exist_ok=True)
    (DOCS / "oem_guides.md").write_text(oem_guides(data), encoding="utf-8")
    (DOCS / "supported_devices.md").write_text(supported_devices(data, measured), encoding="utf-8")
    print(f"wrote {DOCS / 'oem_guides.md'}")
    print(f"wrote {DOCS / 'supported_devices.md'}")


if __name__ == "__main__":
    main()
