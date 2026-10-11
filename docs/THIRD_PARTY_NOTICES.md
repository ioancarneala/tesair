# Credits and third-party notices

## Receiver foundation

TesAir is a fork of [DiPlay](https://github.com/shihabal3amri/DiPlay), based on [xcertplay by shilapi](https://github.com/shilapi/xcertplay). Their receiver, transport and protocol work forms this project's foundation. The root [GNU GPL version 3 licence](../LICENSE), source headers and attribution are retained.

Upstream also credits [LIVI](https://github.com/f-io/LIVI) and [Showcase](https://github.com/amineross/showcase) for protocol research.

TesAir's additions focus on browser video delivery and input, display sizing, Tesla-oriented setup and recovery. This does not claim authorship of the inherited receiver or exclusive rights to those techniques. Existing DiPlay protocol identifiers remain unchanged for compatibility.

## Inherited interface work

`common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt` adapts palette, arrangement and interface copy from [DiAuto](https://github.com/shihabal3amri/DiAuto). Its AGPL-3.0-only notice remains; the full licence is in [DiAuto-AGPL-3.0.txt](licenses/DiAuto-AGPL-3.0.txt).

The root GPL licence does not replace AGPL obligations. Redistribution of combined work must comply with applicable licences and corresponding-source requirements.

## Device illustrations and marks

`tesair_android.webp`, `tesair_iphone.webp` and `tesair_display.webp` were generated for TesAir to identify devices and setup roles. AI generation and the source licence do not grant new rights to depicted third-party interfaces or marks.

TesAir's app icon includes the Tesla symbol. Tesla, iPhone, CarPlay and Android remain their owners' marks. TesAir is independent and is not affiliated with or endorsed by Tesla, Apple or Google. This acknowledgment does not grant trademark permission.

The retained `common/src/main/res/drawable/ic_carplay.png` came from [Apple's developer asset](https://developer.apple.com/assets/elements/icons/carplay/carplay-96x96_2x.png). It is an Apple asset, not project code under GPL. Its presence does not establish approval or certification.

The `common/src/main/res/raw/ic_car_home.png` depicts BYD branding and remains referenced as a receiver configuration fallback. It is inherited material, not TesAir's public app mark; no independent redistribution permission is asserted here.

Additional resource attribution:

> The Android robot is reproduced or modified from work created and shared by Google and used according to terms described in the Creative Commons 3.0 Attribution License.

Source: [Google's Android robot asset](https://developer.android.com/static/images/brand/android-head_flat.svg); [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/). The Tesla symbol comes from [Simple Icons](https://github.com/simple-icons/simple-icons/blob/develop/icons/tesla.svg), under [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/). These terms do not grant trademark rights or imply endorsement.

## Runtime dependencies

| Component | Licence and notices |
| --- | --- |
| AndroidX, Jetpack Compose and Kotlin standard library | Apache 2.0; [licence text](licenses/dependencies/Apache-2.0.txt). |
| Bouncy Castle | [Bouncy Castle licence](licenses/dependencies/Bouncy-Castle-LICENSE.txt). |
| JmDNS | Apache 2.0. |
| SLF4J | [MIT licence notice](licenses/dependencies/SLF4J-LICENSE.txt). |
| NanoHTTPD and NanoHTTPD WebSocket | [BSD 3-Clause notice](licenses/NANOHTTPD.txt). |
| ZXing Core | Apache 2.0; [project](https://github.com/zxing/zxing). |

Versions are recorded in the Gradle catalog and module build files. These notices cover named components, not a claim that every transitive dependency has been independently relicensed. Retain dependency licences in distributions.

## Native SpeexDSP source

The inherited native library vendors a subset of [SpeexDSP 1.2.1](https://github.com/xiph/speexdsp/tree/SpeexDSP-1.2.1), including echo cancellation and supporting code. It remains in the inherited module even though the browser product uses direct iPhone Bluetooth audio.

Its BSD-style licence, copyright notices and disclaimer are in [COPYING](../shared/src/main/jni/speexdsp/COPYING). Component headers retain additional notices, including KISS FFT attribution. See the [vendoring notes](../shared/src/main/jni/speexdsp/README.md).
