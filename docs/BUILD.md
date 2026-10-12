# Build TesAir

## Requirements

- JDK 25 and the checked-in Gradle wrapper.
- Android SDK 37 and NDK `28.2.13676358`; set `ANDROID_HOME` to your SDK.
- Node.js for browser tests and Python 3 for repository checks.

Dependency versions are in `gradle/libs.versions.toml`.

## Development build

With provisioning variables unset:

```sh
env -u DIPLAY_AUTH_ASSETS_DIR \
    -u BROWSER_CERTIFICATE_ENDPOINT \
    -u BROWSER_CERTIFICATE_DOWNLOAD_TOKEN \
    ./gradlew :mobile:assembleDebug
```

On Windows, unset those variables and use `gradlew.bat`.
The output is `mobile/build/outputs/apk/debug/mobile-debug.apk`, with application ID
`co.tesair.app.debug`. It runs the interface and server, but completing CarPlay and
trusted HTTPS requires separately supplied runtime provisioning.

## Verify changes

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest \
  :mobile:lintDebug :mobile:assembleDebug \
  :mobile:processDebugManifest :mobile:processReleaseManifest
python3 -m unittest discover -s tests -p 'test_*.py'
python3 scripts/check_public_tree.py
node --test tests/browser-player.test.cjs
python3 scripts/verify-browser-manifests.py \
  --debug mobile/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml \
  --release mobile/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml
```

Keep changes focused, add regression coverage for behaviour changes, and describe
what you tested in your pull request. Never commit credentials, build outputs or
device reports. The source guard checks tracked and untracked files; generated
build directories are excluded unless tracked.

## Release build

Release application ID: `co.tesair.app`. Version: `1.0.1` (code `64`).

Supply these environment variables outside the repository:

| Variables | Purpose |
| --- | --- |
| `DIPLAY_AUTH_ASSETS_DIR` | Authorized runtime assets: `offline-mfi/identity.pk8` and `offline-mfi/certificate.p7b`. |
| `BROWSER_CERTIFICATE_ENDPOINT`, `BROWSER_CERTIFICATE_DOWNLOAD_TOKEN` | HTTPS provisioning at `https://certificates.tesair.co/v1/bundle`. |
| `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` | Android release signing. |

```sh
./gradlew :mobile:lintRelease :mobile:assembleStandaloneRelease
```

Output: `mobile/build/outputs/apk/release/mobile-release.apk`. This task requires
runtime authentication and HTTPS provisioning; Gradle validates signing inputs.
The APK contains the selected runtime credentials, but never the APK signing key
or cloud administration credentials. Keep the same application ID and signing key
for future updates.

Verify the signed APK and publish it with its SHA-256 checksum and matching source
in GitHub Releases. Record device tests in the release notes; unit tests alone do
not establish device compatibility.
