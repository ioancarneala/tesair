# TesAir

**Use an Android phone to bring CarPlay to your Tesla browser.**

[Releases](https://github.com/ioancarneala/tesair/releases) · [Setup](#setup) · [Report a problem](https://github.com/ioancarneala/tesair/issues/new/choose)

**v1.0.3.** [Download the APK](https://github.com/ioancarneala/tesair/releases/tag/v1.0.3).

## What it does

- Streams CarPlay directly to the browser, without mirroring the Android screen.
- Fits the available browser space, with touch control and adjustable interface size.
- Reconnects automatically when possible and shows the next step when help is needed.
- Keeps music and calls on the direct iPhone-to-Tesla Bluetooth connection.

## What you need

An iPhone, a compatible Android phone running **Android 9 or later**, and a Tesla
with a browser. The Android phone provides a hotspot with internet access; keep it
powered during use.

Tested and working in a Tesla Model 3 (Highland, HW4) at 60 fps, and on OnePlus 7T Pro
and Xiaomi 12X phones. Other devices and firmware versions are expected to work but
are not yet individually verified.

The Tesla browser must support the WebCodecs video decoder, which in practice means
the AMD Ryzen-based system (MCU3, around 2022 and newer, including HW4 cars). The
older Intel Atom (MCU2) and Tegra (MCU1) browsers cannot decode the video and are not
yet supported.

## Setup

1. **Install TesAir on Android.** Open the app and follow its permission steps.
2. **Turn on the Android hotspot.** Enter its name and password in TesAir once.
3. **Connect the iPhone and Tesla to that hotspot.** Follow the iPhone connection
   prompts. Pair the iPhone directly with Tesla Bluetooth for music and calls.
4. **Open [go.tesair.co](https://go.tesair.co/) in the Tesla browser.** Bookmark it
   for next time.

The address redirects to `carplay.tesair.co:8443`. Video and touch stay on your local
network. Tesla controls its own browser toolbar; TesAir fills the available page.

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

The APK uses DiPlay's experimental authentication approach and is not Apple-certified.
Runtime credentials are extractable; future iOS compatibility is not guaranteed.
Source builds exclude those credentials.

See [LICENSE](LICENSE) and [third-party notices](docs/THIRD_PARTY_NOTICES.md).
TesAir is independent and is not affiliated with or endorsed by Tesla or Apple.
