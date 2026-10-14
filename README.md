# TesAir — Wireless Apple CarPlay for Tesla

**Turn your spare Android phone into wireless CarPlay for your Tesla.**

Your iPhone apps on the Tesla screen, with sharp video, responsive touch and sound
through the car's speakers. Install TesAir on Android and open **go.tesair.co** in
the Tesla browser.

[**Download TesAir v1.0.5**](https://github.com/ioancarneala/tesair/releases/download/v1.0.5/TesAir-v1.0.5.apk) · [Setup](#setup) · [Release notes](https://github.com/ioancarneala/tesair/releases)

Clear first-time setup, an illustrated iPhone pairing guide and updates you can
download from the app. **New in v1.0.5:** more accurate optional connection reports.

Used daily in a **Tesla Model 3 Highland (HW4)**, with smooth **60 fps** video and
a stable connection. Tested Android phones: **OnePlus 7T Pro**
and **Xiaomi 12X**.

## Why TesAir

- **Guided setup.** See the next action to take, with an iPhone pairing guide
  showing your Android phone's actual Bluetooth name.
- **Direct CarPlay video.** Streams the iPhone's CarPlay video straight to the
  browser, without Android screen mirroring or another video encoding step.
- **A display that fits.** CarPlay adapts to the available browser space. Adjust
  interface size, picture quality and 30/60 fps to suit your screen.
- **Touch control.** Open apps, choose music and move around the map from the
  Tesla screen.
- **Music and calls through your Tesla.** Audio goes directly from the iPhone to
  Tesla Bluetooth, using the car's speakers and microphone.
- **Ready in the background.** Runs with the Android screen off, reconnects
  automatically and supports startup when power returns.
- **Local streaming.** Video and touch stay on the hotspot, with no cloud relay.

## What you need

An iPhone, an Android phone running **Android 9 or later** with Bluetooth and a
Wi-Fi hotspot, and a Tesla with a browser. The Android phone provides a hotspot
with internet access; keep it powered during use.

The browser needs WebCodecs video decoding. The confirmed Tesla setup above uses
AMD Ryzen infotainment.

## Setup

1. **Install TesAir on Android.** Follow the guided setup. TesAir checks the
   permissions needed for your Android version.
2. **Turn on the Android hotspot.** Enter its name and password in TesAir once.
   Disable automatic hotspot shutoff in its settings if that option is available.
3. **Pair your iPhone.** Keep iPhone Wi-Fi and Bluetooth on. In iPhone Bluetooth
   settings, select the Android name shown in TesAir and confirm the pairing.
   Allow CarPlay when prompted; TesAir sends the hotspot details automatically.
4. **Connect Tesla to the Android hotspot.** Pair the iPhone directly with Tesla
   Bluetooth for music and calls.
5. **Open [go.tesair.co](https://go.tesair.co/) in the Tesla browser.** Bookmark it
   for next time.

The short address opens the phone's local HTTPS connection at
`carplay.tesair.co:8443`.

To upgrade from v1.0.3, install the APK above over your existing TesAir app.
Your settings stay saved. For future releases, see what's new and download the
update in **Settings → App updates**, then confirm installation in Android.
Turn on **Notify me about updates** there to receive release alerts.

## Help and development

Follow the next action shown in TesAir. For a recurring problem,
[report your app version, devices and what happened](https://github.com/ioancarneala/tesair/issues/new/choose).
Review diagnostic reports before sharing them.

Optional technical reporting helps improve setup and connection reliability.
You control participation in **Settings → Usage & diagnostics**.

Developers: [build instructions](docs/BUILD.md). The app uses three modules:
`mobile` for the Android app, `common` for its interface and services, and `shared`
for the receiver and transport. Pull requests and compatibility reports are welcome.

[Privacy](docs/PRIVACY.md) · [Security](SECURITY.md)

## Credits and licence

TesAir builds on [DiPlay](https://github.com/shihabal3amri/DiPlay), based on
[xcertplay](https://github.com/shilapi/xcertplay), for the CarPlay receiver and
authentication integration. TesAir adds browser video and touch, adaptive display
sizing, Tesla setup and connection recovery. No separate DiPlay installation is needed.

See [LICENSE](LICENSE) and [third-party notices](docs/THIRD_PARTY_NOTICES.md).
TesAir is independent and is not affiliated with or endorsed by Tesla or Apple.
