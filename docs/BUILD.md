# Build TesAir

## Requirements

- JDK 25 and the checked-in Gradle wrapper.
- Android SDK 37 and NDK `28.2.13676358`; set `ANDROID_HOME` to your SDK.
- Node.js 22.16 or later for browser/service tests and Python 3 for repository checks.

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
node --test tests/browser-player.test.cjs tests/browser-telemetry.test.cjs
python3 scripts/verify-browser-manifests.py \
  --debug mobile/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml \
  --release mobile/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml
```

Keep changes focused, add regression coverage for behaviour changes, and describe
what you tested in your pull request. Never commit credentials, build outputs or
device reports. The source guard checks tracked and untracked files; generated
build directories are excluded unless tracked.

## Release build

Release application ID: `co.tesair.app`. The version and version code are defined in
[`mobile/build.gradle.kts`](../mobile/build.gradle.kts).

### Release checklist

Follow these in order so installed apps receive the update alert and reporting keeps working.

1. **Bump the version.** Set `versionCode` (must increase) and `versionName` in
   [`mobile/build.gradle.kts`](../mobile/build.gradle.kts).
2. **Build and sign.** With the environment variables below set, run
   `./gradlew :mobile:lintRelease :mobile:assembleStandaloneRelease`.
3. **Check the artifact.** Confirm `mobile/build/outputs/apk/release/mobile-release.apk`
   is `co.tesair.app`, carries the new version, and is signed by the established key.
4. **Publish the release yourself.** Create a stable, non-draft GitHub release tagged
   `vX.Y.Z`, with a `## What's new` section and the APK attached as `TesAir-vX.Y.Z.apk`.
   Publish with your own GitHub account: a release created by an action's `GITHUB_TOKEN`
   does not trigger the metadata workflow.
5. **Let automation attach `update.json`.** The
   [Release update metadata](../.github/workflows/release-updates.yml) workflow verifies
   the APK and uploads `update.json` automatically (or generate it by hand, below).
6. **Confirm the update is live.** `https://telemetry.tesair.co/v1/update` should report
   the new version (cached up to one hour). Installed apps on v1.0.4+ then show it in
   **Settings → App updates** on their next daily check.
7. **Watch the rollout.** Opted-in installs report the new version to the
   [telemetry dashboard](https://telemetry.tesair.co/dashboard).

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
in GitHub Releases. Lead release notes with user-visible improvements and any
required update steps. Keep detailed validation in the pull request; mention
device testing when it adds useful compatibility information, and known issues
when they affect users.

Generate the update metadata from that verified APK, using the Android SDK tools:

```sh
python3 scripts/generate-update-manifest.py \
  mobile/build/outputs/apk/release/mobile-release.apk \
  --aapt "$ANDROID_HOME/build-tools/36.0.0/aapt" \
  --apksigner "$ANDROID_HOME/build-tools/36.0.0/apksigner" \
  --notes ../dist/release-summary.txt \
  --output ../dist/update.json
```

Use a short plain-text changelog (up to 1,000 characters) in `release-summary.txt`.
Attach `update.json` beside `TesAir-vVERSION.apk` before publishing the matching
stable GitHub release. Keep these generated files outside the source tree.

The **Release update metadata** workflow also verifies the published APK and
generates its metadata when a stable release is published or edited. It takes the
changelog from the release's **What's new** section. Publish releases with your
GitHub account; events created using `GITHUB_TOKEN` do not trigger this workflow.

From v1.0.4, the running app checks daily for releases, retries failed checks after
15 minutes, and shows the changelog before downloading. Users can also check
manually in **Settings → App updates**. The APK's package, version, checksum and
existing signing key are verified; installation requires Android confirmation.
The optional reporting service is maintained privately, outside this repository; the
app-side behaviour is described in [PRIVACY.md](PRIVACY.md).
