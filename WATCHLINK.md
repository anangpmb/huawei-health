# WatchLink

WatchLink is a slimmed-down companion app for Huawei wearables (target: Huawei Watch Fit 4 and newer).
It is a **modified version of [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge)** and is
licensed under the **GNU AGPL v3** (see `LICENSE`). Any distributed build must ship its source.

- Based on upstream Gadgetbridge commit `f75031bb` (2026-10-19); remote `upstream`.
- Modified by WatchLink contributors starting 2026-10-19. All WatchLink changes are marked with
  `WatchLink:` comments or live in `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/watchlink/`.

Feature overview (Indonesian): [FEATURES.md](FEATURES.md).

## Build

Requirements: **JDK 21** and the Android SDK with **platform 37** (`compileSdk 37`).

1. Create `local.properties` in the repo root:
   ```properties
   sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
   ```
2. Build the debug APK:
   ```sh
   ./gradlew assembleMainlineDebug        # Windows: gradlew.bat assembleMainlineDebug
   ```
   Output: `app/build/outputs/apk/mainline/debug/app-mainline-debug.apk`
3. Install: `adb install -r app/build/outputs/apk/mainline/debug/app-mainline-debug.apk`

The application ID is `com.watchlink.app`, so it installs alongside upstream Gadgetbridge.

## Pairing a Watch Fit 4

The Fit 4 talks over **Bluetooth Classic (RFCOMM)**, not BLE; it is matched by `HuaweiWatchFit4ProCoordinator`
(marked experimental upstream). If the watch was used with Huawei Health, either:

- factory-reset the watch, then pair it in WatchLink; or
- keep it paired and enter your 17–19 digit Huawei account ID (`huid`) when pairing. Do **not** unpair it
  in Huawei Health first; that invalidates the key. See
  [Gadgetbridge: Huawei/Honor pairing](https://gadgetbridge.org/basics/pairing/huawei-honor-pairing/).

## Scope

Only Huawei/Honor wearables are recognised (headphones excluded). Non-MVP features are switched off, not
deleted, in [`WatchLinkScope.kt`](app/src/main/java/nodomain/freeyourgadget/gadgetbridge/watchlink/WatchLinkScope.kt):

| Kept (MVP) | Switched off (flag) |
|---|---|
| Pairing, battery, device info, time sync | Weather (`WEATHER`) |
| Notification relay | Calendar (`CALENDAR`) |
| Health/activity sync (steps, HR, sleep, SpO2, stress, workouts) | Contacts (`CONTACTS`) |
| Workout export (GPX/FIT auto-export to a folder) and Strava upload | |
| Music control | Alarms (`ALARMS`) |
| Watch faces | Navigation (`NAVIGATION`), watch apps (`WATCH_APPS`), find device (`FIND_DEVICE`) |

## Design ("Cocoa" look)

Warm off-white pages with white cards, deep cocoa as the action colour, soft accents (sage, orange,
lavender, yellow, pink), bold Poppins numbers, rounded-step sparklines and pill bars. Applied from a few
central points so upstream screens follow automatically. The earlier "Soft" look lives on as the Sage
accent (Settings → User interface → Accent colour).

| What | Where |
|---|---|
| Palette (cream page, warm-white cards, forest/sage accent, pastel data colours) | `app/src/mainline/res/values/colors_watchlink.xml` overrides upstream colours by name |
| Accent **Cocoa** (default; Sage and the upstream accents still selectable in Settings → User interface) | `styles_watchlink.xml`, `GBApplication.getAccentColorOverlay()` |
| Poppins type scale, corners (8/14/20/24/32dp), card fill | `ThemeOverlay.WatchLink` in `styles_watchlink.xml`, applied in `AbstractGBActivity.init()` |
| Spline lines + soft gradient for every chart | `activities/charts/mpchart/ChartDataBuilder.kt`, axes/grid in `GbChartView.kt` |
| Dashboard metric rows (icon chip, value, sparkline): heart rate, steps, sleep | `widgets/impl/SparklineWidget.kt`, `WatchLinkTrendWidgets.kt`, `watchlink/SparklineView.kt` |
| Default dashboard layout | `widgets/WidgetLayoutStore.kt` (`DEFAULT_ORDER`) |

Only the light theme gets the new palette; the dark themes keep upstream colours with the new type,
shapes and charts. Poppins is SIL OFL 1.1 (`assets/licenses/Poppins-OFL.txt`, listed under Licenses).

## Demo mode

Side menu → **Demo mode** shows the app with sample data and no watch. It adds Gadgetbridge's simulated
test device (limited to what a Huawei watch offers) plus seven workouts with GPS routes from the last two
weeks, so the dashboard, charts, workouts and the share video can all be tried. The demo watch "connects"
without Bluetooth and its sync button works. Demo workouts are never uploaded to Strava. Turning demo mode
off deletes the demo watch and all of its sample data; a real watch is not touched.
Code: `watchlink/DemoMode.kt`.

## Workout share (route video & card)

Workout details → ⋮ → **Share workout** (shown for workouts with a GPS route).

- **Video** (MP4, 1080×1920 or 720×1280 when the encoder can't do 1080p, 30 fps, 16.5 s, silent):
  start point blooms in a close-up → the head runs the route at the workout's own tempo for 12 s while
  the camera follows and widens → finish pops and a highlight sweeps the line → the live counter panel
  morphs into the summary card (3.7 s hold).
- **Card** (PNG 1080×1920): route + distance + up to four stats, background map / pastel / **transparent**
  (light or dark text, for overlaying on a photo).
- Background **map** uses the offline mapsforge maps (Settings → Maps folder), washed to pastel; **pastel**
  is the white glassmorphism look. Without map files, pastel is used.
- **Hide the first and last 200 m** (default on) so the start/finish (e.g. home) is not shared.
- Everything is rendered and encoded on the phone (`MediaCodec` + `MediaMuxer`), nothing is uploaded.
  Every frame is a pure function of time, so the preview equals the export.

Code: `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/watchlink/share/` (model, renderer, offline map
layers, encoder, preview view, activity). Display font: Instrument Serif (SIL OFL 1.1).

## Strava upload

Workouts can be uploaded to Strava as FIT files, automatically after each sync or from a workout's ⋮ menu
(**Upload to Strava**). It plugs into Gadgetbridge's existing online-tracker upload framework, so a workout is
never uploaded twice. Code: `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/externalservices/strava/`.

**This is the only feature that sends data off the phone.** For it, the `mainline` build declares the
`INTERNET` permission (`app/src/mainline/AndroidManifest.xml`); upstream Gadgetbridge has none.

Setup (once):

1. At <https://www.strava.com/settings/api>, create an API application. Set **Authorization Callback Domain** to
   `localhost`; the other fields can be anything.
2. In WatchLink: *Settings → Online fitness trackers → Strava → Connect to Strava*. Paste the **Client ID** and
   **Client Secret**, then approve in the Strava app or browser. Keep the "upload activities" permission ticked.
3. Turn on **Upload workouts automatically**.

Behaviour:

- Syncing is manual: tap the sync icon in the Dashboard toolbar (or pull down to refresh). There is no
  automatic sync on app open by design.
- After each sync, workouts from the last 14 days that are not on Strava yet are uploaded. A workout that Strava
  already holds is recognised by Strava's duplicate check and recorded, not uploaded again.
- A name set in WatchLink is pushed to Strava; without one, Strava names the activity itself. A changed GPS
  track or a header photo cannot be pushed afterwards, because the Strava API has no way to do either.
- The Client Secret and tokens are stored encrypted on the phone only. **Never commit a Client Secret**:
  this repository must be published under the AGPL.
- If Strava reports `invalid redirect_uri`, check that the callback domain is exactly `localhost`
  (the app uses `watchlink://localhost`).

## Watch faces

- **`.hwt` files** use the existing Gadgetbridge installer (open the file with WatchLink). Since watch firmware
  6.x / HarmonyOS 6.1, the watch requires a Huawei-server RSA signature (`contentSign`) per face and silently
  discards unsigned uploads (Gadgetbridge issue [#6005](https://codeberg.org/Freeyourgadget/Gadgetbridge/issues/6005)).
  This cannot be generated locally; `.hwt` install only works on older firmware.
- **Photo faces** (pick images, apply to the built-in Gallery face) are planned: service `0x27` commands
  `0x08`/`0x09` plus file upload type `3` (PNG). Not implemented upstream.

### Capturing a reference log for photo faces

To pin down exact packet values, record the official app doing it once:

1. Phone: *Developer options → Enable Bluetooth HCI snoop log* → toggle Bluetooth off/on.
2. In Huawei Health: *Devices → Watch faces → Gallery*, add one photo and apply it to the Fit 4.
3. Note the time, then collect the log: `adb bugreport watchlink-gallery.zip`
   (the log is `FS/data/misc/bluetooth/logs/btsnoop_hci.log` inside the zip).

## Roadmap

- [x] Fork, rebrand, restrict to Huawei wearables, switch off non-MVP features
- [x] Strava upload (automatic and manual), not yet tested against Strava
- [x] "Soft" redesign: palette, Poppins, rounded cards, spline charts, sparkline dashboard rows (not yet built)
- [x] Workout share: animated route video (MP4) + transparent/pastel/map card (PNG) (not yet built)
- [ ] Redesign follow-ups: week date strip on the dashboard header, pastel dark palette
- [ ] First build and Fit 4 pairing on a real device
- [ ] Verify MVP: notifications, health sync, music control
- [ ] Hide remaining non-Huawei UI (other vendors' settings, integrations)
- [ ] Photo watch face from file picker
- [ ] `.hwt` install: firmware warning and real install result instead of a silent "100%"
