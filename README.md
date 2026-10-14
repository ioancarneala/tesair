# TesAir — Wireless Apple CarPlay for Tesla

**Turn your spare Android phone into wireless CarPlay for your Tesla.**

Your iPhone apps on the Tesla screen, with sharp video, responsive touch and sound
through the car's speakers. Install TesAir on Android and open **go.tesair.co** in
the Tesla browser.

[**Download TesAir v1.0.3**](https://github.com/ioancarneala/tesair/releases/download/v1.0.3/TesAir-v1.0.3.apk) · [Setup](#setup) · [Release notes](https://github.com/ioancarneala/tesair/releases)

Used daily in a **Tesla Model 3 Highland (HW4)**, with smooth **60 fps** video and
a stable connection. Tested Android phones: **OnePlus 7T Pro**
and **Xiaomi 12X**.

## Why TesAir

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

An iPhone, a compatible Android phone running **Android 9 or later**, and a Tesla
with a browser. The Android phone provides a hotspot with internet access; keep it
powered during use.

The browser needs WebCodecs video decoding. The confirmed Tesla setup above uses
AMD Ryzen infotainment.

## Setup

1. **Install TesAir on Android.** Open the app and follow its permission steps.
2. **Turn on the Android hotspot.** Enter its name and password in TesAir once.
3. **Connect the iPhone and Tesla to that hotspot.** Follow the iPhone connection
   prompts. Pair the iPhone directly with Tesla Bluetooth for music and calls.
4. **Open [go.tesair.co](https://go.tesair.co/) in the Tesla browser.** Bookmark it
   for next time.

The short address opens the phone's local HTTPS connection at
`carplay.tesair.co:8443`.

## Help and development

Follow the next action shown in TesAir. For a recurring problem,
[report your app version, devices and what happened](https://github.com/ioancarneala/tesair/issues/new/choose).
Review diagnostic reports before sharing them.

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
