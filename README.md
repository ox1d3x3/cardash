# CarDash

A Tesla-style home screen for cheap Android head units. Built for a 2 GB RAM / Android 13 unit:
framework-only Java, no AndroidX, no Kotlin, no web views. APK is ~50 KB.

## What's on screen
- **Drive panel**: GPS speed (tap to switch km/h ↔ mph), heading, GPS status, Wi-Fi indicator,
  big clock and date, and a live top-down **2014 Accent hatch** on the road:
  - lane markings scroll when GPS says you're moving, faster as speed rises (eased between GPS
    fixes so it glides instead of jumping at 1 Hz)
  - brake lights glow when you slow down hard and while stopped in traffic
  - headlight beams + tail-lamp glow after 6:30 pm
  - paint colour in settings (White, Silver, Grey, Black, Red, Blue, Beige)
- **Speed alert**: pick a limit in settings and the number turns red above it.
- **Now playing**: album art, title/artist, progress, prev / play-pause / next. Controls whatever
  app is actually playing (AIMP, Spotify, radio apps, YouTube Music...).
  Tap the card to open the player, long-press it to choose your default player (AIMP is picked
  automatically if installed).
- **Volume**: slider + mute for the media stream, stays in sync with the hardware knob/wheel buttons.
- **Dock**: 6 app slots + all-apps grid. Tap an empty slot to fill it, long-press any slot to change it.
  Defaults are auto-detected: maps, music player, phone/BT, settings.
- **Settings** (slider icon): player, units, media access, location, hide system bars, set as Home app,
  reset dock.

## Install
1. `adb install -r CarDash.apk` (or copy to USB and open with the file manager).
2. Press **Home** and choose **CarDash → Always**.
   If the unit never asks: Settings (slider icon) → *Set CarDash as Home app*.
3. Allow **location** when asked (for the speedometer).
4. Tap the media card → enable **notification access** for CarDash. This is how Android lets a
   launcher see the playing track; CarDash never reads notifications.
   If your unit hides that screen:
   ```
   adb shell cmd notification allow_listener xyz.ox1de.cardash/xyz.ox1de.cardash.MediaListener
   ```
5. Optional on FYT/TS-style units: in the factory settings, set the default launcher / "home app" to
   CarDash and disable the stock launcher's auto-start if it keeps coming back after ignition.

## Why it's light
- The car is rasterised once into a ~100 KB bitmap; an animation frame is one bitmap draw plus a
  few rectangles on the GPU, no allocations. Frames run only while the road is actually moving
  or a light is fading. Parked, or another app in front = zero frames, zero CPU.
- Everything stops in `onPause`: GPS, media callbacks, the progress ticker. When Maps or AIMP is in
  front, CarDash does nothing.
- App icons are rendered once into small 48 dp bitmaps on a background thread.
- Text views only update when the value actually changes (no per-second relayouts).
- Clock uses the system `TIME_TICK` broadcast instead of a timer.

## Build
**GitHub Actions (easiest):** push this folder to a GitHub repo → Actions tab → *Build APK* →
download the `CarDash-apk` artifact.

**Android Studio:** open the folder, Build → Build APK(s). (Gradle 8.7+, JDK 17.)

Both use `keystore/cardash.p12` (password `cardash`), the same key as the prebuilt APK, so new
builds install as updates. Swap in your own key if you'll share builds.

**No SDK at all:** `tools/build-local.sh <android.jar> <bundletool-all.jar>` builds with aapt2, D8 and
apksig from Google's bundletool.

## Limits
Vehicle data like reverse gear, CAN-bus speed, door state and steering-wheel learning go through
each head-unit vendor's private services (FYT `com.syu.*`, TS/Topway MCU apps) and aren't a public
Android API. CarDash uses GPS for speed. Adding one vendor's CAN data is possible once we know the
exact unit (Settings → About → MCU/firmware version).
