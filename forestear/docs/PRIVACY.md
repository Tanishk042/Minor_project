# Privacy, consent and retention

ForestEar listens to the environment continuously. That makes privacy a design
constraint, not a footnote. This document states what the system does, what it
deliberately does not do, and what an operator must decide before deploying it.

## 1. What the node captures and keeps

| Data | Default | Where it goes | Retention |
|---|---|---|---|
| 1 s analysis windows | always | RAM only, overwritten each hop | not stored |
| Per-window scores / telemetry | always | backend live feed (WebSocket), **not persisted** | not stored |
| Events (class, severity, confidence, position, time) | always | backend SQLite `events` | operator policy, no auto-delete |
| Alert clips (5 s) | only for non-speech classes | backend `data/clips/` | operator policy |
| Ambient collection WAVs | **off by default** (`collection_enabled=false`) | phone-local directory | 2 GB cap, oldest deleted by chunk rotation |
| Heartbeats (CPU, RAM, temperature, battery) | always | backend SQLite `heartbeats` | operator policy |
| Biodiversity detections (species, time) | always | backend SQLite | operator policy |

The node never stores raw audio unless the operator explicitly turns on
collection. There is no cloud speech service, no wake-word model, and no
recording upload path for human speech.

## 2. Speech protection

- The human/speech model (M7) and the speech-contaminated-window rule suppress
  alerts for windows dominated by speech.
- The collector drops chunks flagged as human speech instead of saving them,
  and counts them in the UI as `dropped_for_human`.
- Alert clips are attached only for non-speech classes (`clip_enable: true`
  applies to chainsaw/gunshot/vehicle, never to `human`).

Speech suppression is a best-effort classifier, **not a guarantee**. A person
talking near the microphone can still influence scores. Do not deploy this
where private conversations are expected.

## 3. Consent and notice

Before any deployment, the operator must:

1. Check local law on recording ambient audio, including one-party/two-party
   consent rules and rules for recording in public or private land.
2. Obtain permission from the landowner or land manager.
3. Post a visible notice at the site and in any app store listing, stating
   that acoustic sensors are operating, what they detect, and who to contact.
4. Tell people who carry or host a node what the device records and when.
5. If audio is collected, get informed consent from anyone whose voice could
   be captured, or do not collect.

The app shows a consent gate on first launch and a privacy screen that repeats
these points. The gate is a reminder, not legal cover.

## 4. Retention policy (operator decision)

The software does not enforce a retention period for events or clips. Choose
one and write it down before deployment. Suggested baseline for a prototype:

- alert clips: 30 days, then delete;
- events and heartbeats: 180 days, then aggregate or delete;
- collected ambient audio: off unless needed for a bounded field study, then
  delete at the end of the study;
- exports (CSV): delete after the report is produced.

To enforce it manually:

```bash
# clips older than 30 days
find forestear/data/clips -type f -mtime +30 -delete
```

The Android app can delete all local collection audio from **Tools → Delete
collected audio**. That action is immediate and cannot be undone.

## 5. Access control

- The backend uses a shared token (`FORESTEAR_TOKEN`), sent as
  `X-ForestEar-Token`. It is a prototype credential over cleartext HTTP.
- On the phone the token is stored with Android Keystore AES/GCM encryption
  when available; otherwise the app falls back to plaintext SharedPreferences
  and says so in the privacy screen. Never rely on the fallback in production.
- Settings export excludes the token by default. Do not paste tokens into bug
  reports, screenshots or chat.
- Put the backend behind TLS and a reverse proxy with real authentication
  before exposing it beyond a trusted LAN.

## 6. Data minimisation choices already in the code

- Telemetry (`/ingest/scores`) is relayed to the live dashboard and never
  written to the database.
- Events carry scores and metadata, not audio. Audio only leaves the phone as
  a short clip for non-speech alerts, and only if clip upload is enabled.
- The phone stores a bounded amount of collection audio (2 GB) and rotates the
  oldest chunks.
- No analytics SDK, no Play Services dependency, no third-party telemetry.

## 7. What is still missing

- No automated retention job; the policy is documentation plus manual commands.
- No per-user accounts or audit log on the backend.
- No encryption at rest for the backend database or clips.
- No data-deletion request workflow.

Treat this as a research prototype. For a real deployment, add TLS, real auth,
encrypted storage, automated retention and a written DPIA or equivalent.
