WatchLink
=========

A slim, privacy-friendly Android companion app for **Huawei wearables** — built for the
Huawei Watch Fit 4 and newer. No Huawei Health app, no Huawei account, no cloud.

WatchLink is a modified version of [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge),
narrowed down to Huawei/Honor watches, with a soft pastel redesign and workout sharing on top.

> **Status: early development.** The code has not been built or tested on a real watch yet.
> Expect rough edges, and please report problems in this repository's issues.

## Features

- **Connect** over Bluetooth to Huawei/Honor watches and bands — pairing works without Huawei Health
- **Notifications** from the phone to the watch, quick replies, do-not-disturb
- **Music control** of the phone from the watch
- **Health sync**: steps, calories, distance, heart rate, SpO2, stress, HRV, skin temperature,
  sleep with stages and score (what is available depends on the watch)
- **Workouts**: GPS route, pace and heart-rate zones; GPX/FIT export, automatic export to a folder,
  and upload to **Strava**
- **Share a workout** as an animated route video (MP4, 9:16) or as a card (PNG) with a map, pastel or
  **transparent** background — start and finish hidden by default
- **Soft design**: pastel palette, Poppins, rounded cards, spline charts and sparkline dashboard rows
- **Watch faces**: install `.hwt` files from a file picker (older firmware only, see below)

Full feature list, including what is switched off or not possible (in Indonesian): [FEATURES.md](FEATURES.md).

## Supported devices

The Huawei and Honor wearables that Gadgetbridge supports, with the **Watch Fit 4 / Fit 4 Pro** as the
target (marked experimental upstream). Devices of other brands and Huawei earbuds are not recognised.

## Known limitations

- **Watch faces on new firmware**: since watch firmware 6.x / HarmonyOS 6.1, every watch face needs a
  signature from Huawei's servers, so arbitrary `.hwt` files cannot be installed
  (see [Gadgetbridge #6005](https://codeberg.org/Freeyourgadget/Gadgetbridge/issues/6005)).
  A photo watch face picked from the gallery is planned instead.
- **Strava** upload needs your own Strava API application (free); setup is in [WATCHLINK.md](WATCHLINK.md).
- GPS on Huawei watches can take a long time to get a fix without Huawei's assisted-GPS data.

## Privacy

Your data stays on your phone. The only feature that uses the network is the optional Strava upload,
which is why this build declares the Internet permission. Workout videos and cards are rendered on the
device.

## Build

Requirements: JDK 21 and the Android SDK with platform 37.

```sh
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleMainlineDebug          # Windows: gradlew.bat assembleMainlineDebug
```

The APK is written to `app/build/outputs/apk/mainline/debug/`. Pairing a Watch Fit 4, project layout and
design notes are in [WATCHLINK.md](WATCHLINK.md).

## Relationship to Gadgetbridge

All the device protocol work comes from the Gadgetbridge project and its contributors — thank you.
This repository keeps the full upstream history and tracks it as the `upstream` remote, so protocol
fixes can be merged in.

Please report WatchLink issues **here**, not to Gadgetbridge: the upstream developers do not maintain
this modified version.

## License

WatchLink is licensed under the [AGPLv3](LICENSE), like Gadgetbridge. It is a modified version of
Gadgetbridge, changed by WatchLink contributors starting 2026-10-19; WatchLink changes are marked with
`WatchLink:` comments or live under `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/watchlink/`.
If you distribute a build, you must also offer its source code.

Gadgetbridge authors are listed in [CONTRIBUTORS.rst](CONTRIBUTORS.rst). Artwork licensing:
[LICENSE.artwork](LICENSE.artwork).

Third-party code, as listed by Gadgetbridge:

* Files in app/src/main/java/net/osmand/ and app/src/main/aidl/net/osmand/ are taken from the [OsmAnd](https://osmand.net/) project, licensed under the GPLv3 by OsmAnd BV
* Files in app/src/main/java/org/bouncycastle are taken from the [Bouncy Castle](https://www.bouncycastle.org/java.html) project, licensed under the MIT license by The Legion of the Bouncy Castle Inc.
* Files in app/src/main/java/com/android/nQuant are taken from the [nQuant.android](https://github.com/mcychan/nQuant.android/) project, licensed under the Apache license by Miller Cy Chan
* Files in app/src/main/java/lineageos/ are taken from the [LineageOS](https://lineageos.org/) platform (formerly CyanogenMod), licensed under the Apache license by The CyanogenMod Project and LineageOS contributors
* Files in app/src/main/java/org/concentus are taken from the [Concentus](https://github.com/lostromb/concentus) project, licensed under the BSD-3 license by various holding parties
* Files in GBDaoGenerator/src/de/greenrobot are taken from the [greenDAO](https://codeberg.org/Freeyourgadget/greenDAO) project (Gadgetbridge's fork), licensed under the GPLv3 by Markus Junginger, greenrobot
* File app/src/main/java/nodomain/freeyourgadget/gadgetbridge/util/SearchPreferenceHighlighter.java is taken from [SearchPreference](https://github.com/ByteHamster/SearchPreference), licensed under the MIT license by ByteHamster

Fonts added by WatchLink: [Poppins](https://github.com/itfoundry/Poppins) and
[Instrument Serif](https://github.com/Instrument/instrument-serif), both under the SIL Open Font License 1.1
(`app/src/main/assets/licenses/`).

## Trademarks

Huawei, HarmonyOS and Watch Fit are trademarks of Huawei Technologies Co., Ltd. Strava is a trademark of
Strava, Inc. WatchLink is an independent project and is not affiliated with or endorsed by Huawei or Strava.
