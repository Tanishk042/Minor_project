# Release build and signing

The debug APK is for development only. A distributable APK must be signed with
a key you control and kept secret.

## 1. Create a keystore (once)

```bash
cd android
keytool -genkeypair -v -keystore forestear-release.jks \
  -alias forestear -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12
```

Back up `forestear-release.jks` and its passwords. If you lose them you cannot
ship updates to the same application ID.

## 2. Configure signing locally

```bash
cp keystore.properties.example keystore.properties
# edit storeFile / storePassword / keyAlias / keyPassword
```

`keystore.properties` and `*.jks` are gitignored. Never commit or share them,
and never paste the passwords into a chat or report.

If `keystore.properties` is absent, `assembleRelease` still works but produces
an **unsigned** APK. That is reported explicitly, not treated as success.

## 3. Build

Signed, per-ABI plus universal:

```bash
cd android
./scripts/build_release.sh
```

Single universal APK (no splits):

```bash
./scripts/build_release.sh --no-splits
```

Equivalent raw Gradle commands:

```bash
cd android/ForestEarNode
./gradlew :app:assembleRelease -PabiSplits
./gradlew :app:assembleRelease            # one universal APK
```

ABI splits are opt-in (`-PabiSplits`) so debug output paths and the existing
debug workflow are unchanged. Outputs:

```
app/build/outputs/apk/release/
  app-arm64-v8a-release.apk
  app-armeabi-v7a-release.apk
  app-x86-release.apk
  app-x86_64-release.apk
  app-universal-release.apk
```

Install one on a phone:

```bash
adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

## 4. Verify (do not skip)

```bash
# locate the SDK's apksigner
APKSIGNER=$(find "$HOME/Library/Android/sdk/build-tools" -name apksigner | sort -V | tail -1)
"$APKSIGNER" verify --print-certs app/build/outputs/apk/release/app-universal-release.apk
```

`build_release.sh` runs this automatically when it can find `apksigner`. If it
cannot, it says verification is **not confirmed** instead of claiming success.

## 5. Honest status

- The release build type, ABI splits and signing config are implemented and
  compile in CI/local builds.
- **No signed APK has been produced in this workspace** because no keystore or
  passwords were supplied. The script was syntax-checked, not run end to end.
- Do not report a signed release as done until `apksigner verify` succeeds on
  an APK built with your key.

## 6. Checklist

- [ ] Keystore created and backed up.
- [ ] `keystore.properties` present locally, gitignored.
- [ ] `./scripts/build_release.sh` produced APK(s).
- [ ] `apksigner verify` succeeds.
- [ ] APK installs and launches on a real phone.
- [ ] Version code/name bumped for each release.
- [ ] `LICENSE` and `NOTICE.md` included in the distribution.
