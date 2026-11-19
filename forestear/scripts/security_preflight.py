#!/usr/bin/env python3
"""Pre-commit secret check for the tracked ForestEar files.

This script deliberately prints FILE PATHS ONLY. It never prints a key, token,
password, or the contents of a secret. It is a guardrail, not a security audit:
a clean run means "no obvious secret was found in tracked text", not "the
system is production secure".

Run with:  make security-check
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# Values that are documentation placeholders rather than live credentials.
PLACEHOLDER_BITS = (
    "your_key",
    "your-key",
    "yourkey",
    "your",
    "replace",
    "example",
    "placeholder",
    "changeme",
    "change_me",
    "dummy",
    "todo",
    "<",
    "${",
    "...",
)

CARTO_KEY_RE = re.compile(
    r"cartocdn\.com[^\s\"']{0,400}?[?&]key=([^&\s\"']+)",
    re.IGNORECASE,
)
TELEGRAM_TOKEN_RE = re.compile(r"\b\d{6,12}:[A-Za-z0-9_-]{20,}\b")


def report(level: str, message: str) -> None:
    print(f"[{level}] {message}")


def git(*args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", "-C", str(ROOT), *args],
        capture_output=True,
        text=True,
        check=False,
    )


def tracked_files() -> list[Path]:
    result = git("ls-files", "-z")
    if result.returncode != 0:
        return []
    return [ROOT / name for name in result.stdout.split("\0") if name]


def is_placeholder(value: str) -> bool:
    lowered = value.strip().strip("'\"").lower()
    if not lowered:
        return True
    return any(bit in lowered for bit in PLACEHOLDER_BITS)


def check_gitignore() -> bool:
    result = git("check-ignore", "-q", ".env")
    if result.returncode == 0:
        report("PASS", ".env is gitignored")
        return True
    report("FAIL", ".env is NOT gitignored; local secrets could be committed")
    return False


def check_env_example() -> bool:
    example = ROOT / ".env.example"
    if not example.exists():
        report("FAIL", ".env.example is missing; add placeholder-only config")
        return False
    text = example.read_text(encoding="utf-8", errors="ignore")
    if "key=YOUR_KEY" in text and "replace-me" in text:
        report("PASS", ".env.example contains placeholders only")
        return True
    report("WARN", ".env.example changed; verify it still contains placeholders")
    return True


def scan_tracked_files() -> bool:
    findings: list[tuple[str, str]] = []
    for path in tracked_files():
        if not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue  # binary or unreadable; not scanned
        rel = path.relative_to(ROOT).as_posix()

        if any(not is_placeholder(m.group(1)) for m in CARTO_KEY_RE.finditer(text)):
            findings.append((rel, "possible live CARTO key"))

        if any(not is_placeholder(m.group(0)) for m in TELEGRAM_TOKEN_RE.finditer(text)):
            findings.append((rel, "possible Telegram bot token"))

    if findings:
        for rel, reason in findings:
            report("FAIL", f"{reason} in tracked file {rel} (value redacted)")
        return False
    report("PASS", "no secret-like CARTO key or Telegram token in tracked text")
    return True


def main() -> int:
    report("INFO", f"repository: {ROOT}")
    report("INFO", "this check prints paths only; values are never displayed")
    ok = True
    ok = check_gitignore() and ok
    ok = check_env_example() and ok
    ok = scan_tracked_files() and ok
    if ok:
        report("PASS", "preflight clean; still review diffs before committing")
        return 0
    report("FAIL", "resolve the findings above before committing or deploying")
    return 1


if __name__ == "__main__":
    sys.exit(main())
