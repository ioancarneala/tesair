# Privacy

## CarPlay and audio

CarPlay video travels from iPhone to Android and then to the browser over the local
network. Touch input travels back. TesAir's hosted services do not relay video.
Music and calls use the direct iPhone-to-Tesla Bluetooth connection.

The display may contain maps, contacts and messages. There is no browser PIN:
access to the hotspot and server provides access to the display and its controls.
The app does not request microphone access.

## Settings and diagnostics

Hotspot details, connection settings and pairing data are stored on Android.
Android backup is disabled. Uninstalling or clearing the app removes its local setup.

Automatic text diagnostic reports stay on the phone. They can include app and device
versions, connection state, display settings and recent errors. Review reports
before using the explicit share/export action; automatic redaction may not remove
every personal detail. Exported files remain wherever you shared them.

## Optional usage and diagnostics

Reporting is off until you choose to participate. You can change participation or
delete shared reports in **Settings → Usage & diagnostics**. CarPlay and update
checking work with reporting turned off.

If enabled, TesAir sends structured technical events to `telemetry.tesair.co`:
setup steps, permission results, app and Android versions, device manufacturer and
model, selected video settings, connection attempts, browser capability results,
decode counters, recovery outcomes, and battery/thermal state. Optional feedback
contains only a selected problem category; optional screen type is user-reported.
The telemetry service never receives CarPlay images, audio, messages, contacts,
locations, routes, Wi-Fi names/passwords, pairing credentials, or raw text reports.

A random installation ID links technical reports across normal app updates. This
is pseudonymous data, not an account or hardware identifier. Uninstalling, clearing
app data, or deleting shared reports resets that identity. Browser IDs last only
for the current page. There is no tracking across other apps or websites.

Reports queue privately while offline, for at most seven days and one MiB, and
upload opportunistically. Turning reporting off cancels transmission and clears
the queue. Deleting shared reports also removes the server profile and its events;
when offline, the deletion request stays private for retry when TesAir next runs
online. Already aggregated statistics contain no installation IDs.

Detailed events are deleted after 30 days. Daily aggregate statistics without
installation IDs are deleted after 90 days. Reports and technical timelines are
accessible only through the authenticated operator interface. Cloudflare hosts
the service and can process network metadata such as IP addresses; the telemetry
database does not store IP addresses or full browser user-agent strings.

## Online requests

The app contacts `certificates.tesair.co` to download and renew the browser server's
HTTPS identity. Opening `go.tesair.co` contacts the redirect service. These services
and their hosting provider can receive IP addresses, request times and HTTP headers.
This traffic is separate from local video streaming.

The app also checks `telemetry.tesair.co/v1/update` for published releases. This
request includes no installation ID and is independent of telemetry participation.
APK downloads come from the official `ioancarneala/tesair` GitHub releases and are
verified before Android asks you to install the update.

The HTTPS identity is shared between installations and does not identify an
individual user. See [Security](../SECURITY.md) for access and credential details.
