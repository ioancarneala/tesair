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

Automatic diagnostic reports stay on the phone. They can include app and device
versions, connection state, display settings and recent errors. Review reports
before using the explicit share/export action; automatic redaction may not remove
every personal detail. Exported files remain wherever you shared them.

## Online requests

The app contacts `certificates.tesair.co` to download and renew the browser server's
HTTPS identity. Opening `go.tesair.co` contacts the redirect service. These services
and their hosting provider can receive IP addresses, request times and HTTP headers.
This traffic is separate from local video streaming.

The HTTPS identity is shared between installations and does not identify an
individual user. See [Security](../SECURITY.md) for access and credential details.
