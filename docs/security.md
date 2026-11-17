# Security and deployment readiness

This document separates **what the prototype does today** from **what must be
done before it is exposed to the internet**. It is written in plain language:
a "secret" is anything that lets a caller use a service or identity.

## Current posture at a glance

| Area | Today | Risk | Required action |
|---|---|---|---|
| Dashboard map key | A CARTO key was shared in chat during development and is stored only in gitignored `forestear/.env`. `/dashboard-config` deliberately serves the tile URL to the browser. | Anyone who saw the key, or opens the dashboard, can use it. Quota abuse or billing is possible. | **Rotate the key now** and add a domain/referrer restriction. See below. |
| Backend token | `forestear-dev-token` is the default in `configs/default.yaml`. It is a development token, not a real credential. | Any client can ingest events if the API is reachable. | Replace with a long random token; ideally issue one key per node. |
| Transport | Local HTTP on `127.0.0.1:8000`; no TLS in the harness. | Credentials and alerts travel in clear text on a real network. | Put the API behind HTTPS/TLS before deployment. |
| Authentication | One shared token; no per-user roles or audit trail. | No accountability and coarse revocation. | Add per-node keys, log who did what, and support revocation. |
| Data storage | SQLite, single process; no retention policy defined. | Unbounded growth; unsuitable for concurrent production load. | Use a production database and define retention/deletion rules. |
| Dashboard config | Public by design; key is visible in the browser. | Expected for raster tiles; still needs restriction. | Restrict the key to your dashboard domain; never put a private secret here. |
| Android token | Stored in Android Keystore with AES-256/GCM; plaintext fallback is reported as `plaintext-fallback`. Exports exclude the token. | Good for a prototype; depends on device Keystore health. | Verify on more devices; never log the token. |
| Alert delivery | Telegram is dry-run by default; SMS permission is deliberately off. | No real delivery has been verified. | Run the controlled tests in `docs/verification_status.md`. |
| Release APK | Only a debug APK was built. | Not suitable for distribution. | Create a keystore you control and verify with `apksigner` (see `android/docs/release_build.md`). |

## Rotate and restrict the CARTO key

The key cannot be rotated from this repository — that requires access to the
CARTO account. Do this before any deployment or publication:

1. Sign in to CARTO and **create a new key or regenerate the existing one**. Do
   not reuse the value that was pasted into chat.
2. Add a **domain / HTTP referrer restriction** for the dashboard host. For
   local development include `localhost` and `127.0.0.1`; for production use
   only the real dashboard domain. Add a usage/quota limit if available.
3. Put the new value only in `forestear/.env`:

   ```bash
   FORESTEAR_TILE_URL="https://basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png?key=REPLACE_WITH_NEW_KEY"
   FORESTEAR_TILE_ATTRIBUTION="&copy; OpenStreetMap &copy; CARTO"
   FORESTEAR_TILE_MAX_ZOOM=19
   ```

   `.env` is gitignored. Do not paste `NEW_KEY` into a chat, ticket, screenshot,
   or commit.
4. Restart the backend (`make backend`) and confirm keyed tiles still return
   HTTP 200 in the browser (see `docs/map.md` for the measured check).
5. **Revoke the old key** in CARTO once the new one works.
6. Run `make security-check`. It verifies `.env` is ignored and scans *tracked*
   text files for secret-like values. It prints paths only, never values.

A browser map key is never truly hidden. The restriction, not obscurity, is the
control that matters.

## Backend hardening checklist

Do not mark the project production-ready until each box is true:

- [ ] HTTPS/TLS terminated in front of the API; local plaintext only for development.
- [ ] `FORESTEAR_TOKEN` replaced with a long random value; old dev token revoked.
- [ ] One key per node, with revocation and rotation documented.
- [ ] Rate limiting on ingest and dashboard endpoints.
- [ ] Production database with backups and a tested restore.
- [ ] Retention/deletion policy for events, scores and any optional clips.
- [ ] Audit log for ingest, alert delivery and configuration changes.
- [ ] CARTO key rotated, domain-restricted and quota-limited.
- [ ] Telegram delivery tested with a real bot/chat; bot token stored only in env.
- [ ] Signed Android release built and verified with `apksigner`.
- [ ] Dependency versions reviewed; pinned requirements kept up to date.
- [ ] `make security-check` and `make test` pass on the final commit.

## What this check does not prove

`make security-check` is a simple pre-commit guard. It does **not**:

- scan git history for old secrets;
- know whether a third-party key has already been abused;
- verify TLS, authentication, database hardening or phone Keystore behaviour;
- replace a professional security review.

Treat the current deployment as a **prototype**. The open verification items
are listed in `docs/verification_status.md` (Python side) and
`../android/docs/verification_status.md` (phone side).
