# ForestEar

Low-cost acoustic forest monitoring: a Python training/backend pipeline, a live
dashboard, and an Android app that does all listening and inference on the phone.

## Repository layout

| Path | Contents |
| --- | --- |
| `forestear/` | Python project — datasets, features, M1–M9 models, evaluation, FastAPI backend, dashboard, tests, docs. |
| `android/` | Android app (`org.forestear.node`), device scripts, generated OEM docs and real-device evidence. |
| `ForestEar_Project_Report.pdf` | Full 28-page project report (technical, with measured results and honest gaps). |
| `generate_forestear_report.py` | Source for the full report PDF. |
| `ForestEar_Quick_Start_Guide.pdf` | Plain-language guide: start the services, then set up the phone on macOS or Windows. |
| `generate_quickstart_pdf.py` | Source for the quick-start PDF. |
| `NEW_PHONE_SETUP_GUIDE.md` | Command-only reference for new-phone setup on Windows and macOS. |

Both `forestear/` and `android/` are included as subtree merges, so their
individual commit histories are preserved.

## Start here

- **Run it locally:** `forestear/README.md` → Quick start (`make backend`, then open
  `http://127.0.0.1:8000`). Add `make smoke` for sample dashboard traffic.
- **Set up a phone:** read `ForestEar_Quick_Start_Guide.pdf`, or follow
  `NEW_PHONE_SETUP_GUIDE.md` for commands only.
- **Understand the device results:** `android/device_evidence/README.md` and
  `android/docs/verification_status.md`.

## Honest status

This is a research prototype, not a production system. Reported numbers come
from the project's own test set and one physical handset (OnePlus CPH2381).
The following remain **NOT RUN / NOT VERIFIED / PARTIAL** and are labelled that
way in the reports:

- signed release APK (no keystore supplied);
- real Telegram and SMS delivery (no credentials / no live SIM);
- labelled real field audio and real-field accuracy;
- emulator compatibility matrix;
- full 24 h soak — the final powered run reached 23 h 53 m
  (`completed_24h: false`), so no full-day PASS is claimed;
- watchdog recovery after force-stop (**FAIL**, recorded honestly).

The dashboard basemap key used during development was exposed and must be
rotated and domain-restricted before any deployment (`forestear/docs/security.md`).

## Licence

Source is MIT; bundled models and datasets keep their own terms. See
`android/LICENSE` and `android/NOTICE.md`.
