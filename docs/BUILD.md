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
npm ci --prefix tests/browser-integration
npm exec --prefix tests/browser-integration -- playwright install --with-deps chromium
node --test tests/browser-integration/player.integration.test.cjs
python3 scripts/verify-browser-manifests.py \
  --debug mobile/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml \
  --release mobile/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml
```

Keep changes focused, add regression coverage for behaviour changes, and describe
what you tested in your pull request. Never commit credentials, build outputs or
device reports. The source guard checks tracked and untracked files; generated
build directories are excluded unless tracked.

The real-browser test uses a synthetic H.264 fixture and localhost HTTPS/WebSocket
server. Chromium must decode and acknowledge actual frames, survive a short main-thread
pause, and recover after control-channel congestion. Playwright and `ws` are pinned
development-only tools; they are not packaged in the Android app.

## Standby verification

After two minutes on battery with the screen off and no iPhone session or browser,
the receiver parks between connection attempts and releases its CPU and Wi-Fi locks.
The foreground service, local HTTPS server and hotspot remain available. Power
return, screen activation, a browser or selected-phone Bluetooth connection resumes
the receiver. An inexact wake alarm provides occasional fallback probes; Android
can delay these, so arrival without external power may reconnect later.

Before shipping changes to this policy, test a locked phone on battery, verify
the app wake locks are released, and restore power without touching the phone.
Confirm automatic CarPlay recovery, browser wake, and that Disconnect cancels
future probes. Restore any simulated battery/Doze state after ADB tests.

## Video and HTTPS recovery

Video configuration records are validated before selecting AVC or HEVC. Unknown records
leave the transport open for a later valid configuration. A supported H.264 configuration
may replace a temporary unsupported configuration within three seconds; persistent format
failures use the normal receiver retry policy without requiring a manual reconnect.
Accepting valid video cancels a pending format retry. Decoder memory limits and multi-second
liveness checks bound stalled playback without resetting healthy video on short delays.
Receiver replacement waits asynchronously for the old controller and video codecs to finish
closing; an elapsed teardown timeout does not permit overlapping receiver instances.
Quiet video retains the last image and asks for a fresh frame without resetting the decoder.
Status messages explicitly identify an ended video stream so a real disconnect still clears it.
Keyframe probes on startup, quiet video and server-requested recovery do not discard an
already queued fresh keyframe. Only a browser decoder reset invalidates its server credits.
Screen delivery and teardown belong to the current AirPlay session for each screen type;
an older session cannot reset or feed video into its replacement.
The session canvas derives from the initial browser viewport and saved quality setting,
with landscape room for native layout changes and the existing encoded-size limits.
Small viewports no longer inherit a 1920x1440 minimum before quality scaling.
Presentation coalesces the newest decoded frame at the next animation callback; a single
50 ms deadline also permits a real draw when a visible browser postpones that callback.
Only a completed draw releases frame credits. Local timing includes decoded-output-to-draw
waiting time without adding a reporting interval.
Outgoing browser controls have a bounded socket backlog: replaceable movement is coalesced;
an undeliverable ordered control closes the connection so a stale gesture cannot remain held.
The service publishes one connection snapshot for the dashboard and reliability monitor.
Receiver retirement stays visible until cleanup and all controller workers have stopped.

Telemetry prioritizes recent failures and connection state over routine offline samples,
protects onboarding milestones during queue eviction, and samples repeated automatic attempts.
HTTP 429/503 `Retry-After` deadlines persist across process restarts. Reports remain optional;
the existing consent, local retention, and deletion behavior still apply.

Automatic diagnostic reports include public HTTPS identity validity and sync outcomes.
Periodic snapshots keep their small journal tails; an explicit diagnostic export includes
the retained bounded journals so an incident is not lost between the sampled tails.
AirPlay event-channel closure records a reason, elapsed time and exception class only;
exception messages and protocol payloads are excluded from that diagnostic.
Opted-in telemetry reports the same state using existing diagnostic events, without
private keys, certificate contents, or download credentials. A failed renewal keeps the
working identity. On Android distributions such as Tesla Android, check system time,
internet availability for renewal, and Keystore outcomes before attributing TLS errors
to the hardware.

## Release build

Release application ID: `co.tesair.app`. The version and version code are defined in
[`mobile/build.gradle.kts`](../mobile/build.gradle.kts).

Release builds use R8 to remove unreachable classes. Optimization and renaming remain
disabled for this incremental adoption; JNI names and crypto providers are retained.
Verify the signed release on a device as well as running the source-only tests.

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
