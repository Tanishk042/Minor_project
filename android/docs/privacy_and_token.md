# Privacy, consent and token storage (Android)

## Consent gate

On first launch the app shows a full-screen notice before any tab is usable.
The user must tick the acknowledgement and press **Accept and continue**. The
acceptance is stored locally with a UTC timestamp and the notice version
(`SettingsStore.CONSENT_VERSION`). Raising the version in a future release
should re-prompt; the current code checks only the boolean, so bump the version
and add a migration when the notice text changes materially.

The gate is a reminder of the operator's legal duty. It is not legal cover, and
it does not verify that permission was actually obtained.

Full text and operator obligations: `../forestear/docs/PRIVACY.md`.

## Privacy and retention screen

Reachable from **Settings → Privacy and retention**. It shows, from live device
state:

- consent accepted (yes/no), timestamp and notice version, with a revoke button;
- backend token storage mode: `encrypted (Android Keystore AES/GCM)`,
  `PLAINTEXT FALLBACK (not secure)`, or `not set`;
- stated retention in days (adjustable; **informational only**, nothing is
  auto-deleted);
- collected audio chunk count and size, with **Delete collected audio now**;
- a warning that speech suppression is best-effort, not a guarantee.

The delete button works whether or not monitoring is running. If the service is
running, the screen says the recorder may create a new chunk immediately after
deletion; turning collection off in Tools stops that.

## Token encryption

`settings/TokenStore.kt` implements:

- `KeystoreTokenCipher` — AES-256/GCM key in the Android Keystore, alias
  `forestear_backend_token`; IV is random per write; ciphertext is Base64.
- `TokenStore` — writes ciphertext under `token_enc`, removes the legacy
  plaintext `token` key, and records `token_storage_mode`.
- On first read, an existing plaintext token is migrated to ciphertext.
- If the Keystore path fails (rare device/Keystore faults), the token is stored
  in plaintext and the mode is recorded as `plaintext-fallback`. The privacy
  screen shows that in red. The fallback is never silent.

Settings **export excludes the token** (new exports contain
`"token_included": false`). Import still accepts a legacy export containing a
token and routes it through the encrypted path; re-enter the token manually if
you are moving to a new phone.

## Test status

- JVM: `TokenStoreTest` (8 tests) covers encrypted round-trip, legacy
  migration, encryption failure fallback, unreadable ciphertext, clearing and
  the plaintext cipher. These run in CI.
- Instrumented: `TokenKeystoreTest` exercises the real Android Keystore and
  asserts no plaintext appears in SharedPreferences. **NOT RUN** — no device or
  emulator is available in this workspace.

Do not claim the Keystore path is verified until `TokenKeystoreTest` passes on a
real device.

## Known limits

- No biometric or user-authentication requirement on the key; possession of the
  unlocked app is enough.
- No certificate pinning or TLS enforcement; the backend URL can be `http://`.
- No remote wipe or token rotation.
- Retention is documentation plus a manual delete button, not an automated job.
