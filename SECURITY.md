# Security

Report vulnerabilities privately through
[GitHub security advisories](https://github.com/ioancarneala/tesair/security/advisories/new).
Include the affected version and a minimal reproduction. Do not attach passwords,
private keys or unreviewed device logs.

## Access and credentials

- The browser connects without a PIN. Devices that can reach TesAir on the hotspot
  can view and control CarPlay. Use a private hotspot; do not expose the server to
  the internet.
- The app contains experimental CarPlay runtime credentials and access to its HTTPS
  provisioning service. These are extractable. A shared HTTPS identity means
  compromise or revocation can affect multiple installations.
- APK signing keys and cloud administration credentials are never included in the
  app or source. Source builds omit runtime provisioning as well.
- The Android VPN permission supplies the local browser address. It is not a privacy
  VPN and can conflict with another active VPN.

TesAir has not undergone an independent security audit.
See [privacy information](docs/PRIVACY.md) for data flows and diagnostic reports.
