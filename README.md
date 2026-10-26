# CarDash

A Tesla-style home screen for cheap Android head units. Built for a 2 GB RAM / Android 13 unit:
framework-only Java, no AndroidX, no Kotlin, no web views. APK is ~80 KB.

## What's on screen
- **Drive panel**: GPS speed (tap to switch km/h ↔ mph), heading, GPS status, Wi-Fi indicator,
  big clock and date, and a live top-down **2014 Accent hatch** on the road:
  - lane markings scroll when GPS says you're moving, faster as speed rises (eased between GPS
    fixes so it glides instead of jumping at 1 Hz)
  - brake lights glow when you slow down hard and while stopped in traffic
  - headlight beams + tail-lamp glow at night
  - paint colour: **Auto** (white car on the dark theme, black car on the light theme) or a fixed colour
- **Auto day/night theme**: light 5 am – 6 pm, dark 6 pm – 5 am. Optional *real sunrise/sunset* mode
  computes today's times offline from where GPS last saw the car. Or force always dark / always light.
  Switching is instant (colours swap in place, no restart, no flash).
- **Speed alert**: pick a limit in settings and the number turns red above it.
- **Screensaver**: after 2 min idle while parked (configurable, or long-press the clock), a dark
  clock face: thin digits inside a 60-tick seconds ring, breathing colon, date, now playing, and
  speed if you pull away. Any touch wakes the dashboard without pressing what's underneath.
  Redraws once per second; the dashboard underneath stops drawing completely; the face drifts a few
  pixels each minute against image retention. Also available as Android's system screensaver
  ("CarDash Clock" under Display → Screen saver, if your unit has that menu).
- **RAM optimiser** (RAM % chip on the drive panel: tap = clean now, long-press = settings).
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
- Frame timing uses the display's vsync timestamp, so motion stays evenly spaced.
- Speed, track title, clock etc. sit in fixed-size views: updating them redraws a few pixels and
  never triggers a full-screen re-layout mid-animation.
- One full-screen fill (window background); no overdraw from the root view.
- Drawer opens as a single GPU-layer fade; no fading-edge layers while scrolling.
- Play/pause and mute icons change only when the state actually changes (players and volume knobs
  spam callbacks). Song-progress timer is aligned to whole seconds, so it never skips a number.
- Everything stops in `onPause`: GPS, media callbacks, the progress ticker. When Maps or AIMP is in
  front, CarDash does nothing.
- App icons are rendered once at exactly their on-screen size (52 dp) on a background thread.
- Text views only update when the value actually changes (no per-second relayouts).
- Clock uses the system `TIME_TICK` broadcast instead of a timer.

## RAM optimiser: what it really does
Android already evicts cached apps when it needs memory, and stopping apps you'll reopen just makes
them cold-start (slower). So the optimiser is deliberately conservative:

- **Acts only when RAM is tight** (< 35 % free) and goes further only when it's critical (< 15 %).
  When there's plenty free it does nothing. Your "Always stop" list is enforced regardless.
- **Never touches**: whatever is playing or paused in a media session, your chosen player, dock apps
  (toggle), the keyboard, dialer/SMS, accessibility and notification-listener apps, live wallpaper,
  device admins, Google Play services, and anything whose package name looks car-critical
  (Bluetooth, CarPlay/Android Auto links, DVR/camera, radio, CAN/MCU, TPMS, OBD, TTS…).
  System apps are only ever stopped if you add them to "Always stop" yourself.
- **Learns**: apps you open from CarDash stay warm for 15 min; regulars (5+ opens, used in the last
  3 days) stay warm unless RAM is critical.
- **When**: 1.5 s after you come back home, every 3 min while home is visible, and 30 s after boot.
  Never while another app is in front. Runs on a lowest-priority background thread.
- **Normal mode (no root)** uses `killBackgroundProcesses`: by design it only removes cached /
  background processes, so it *cannot* interrupt music that's playing, running navigation, or
  anything with a foreground service. It also can't stop apps that immediately restart themselves.
- **Deep mode (root)**: sees what's actually running, reports exactly what it stopped, and detects
  apps that respawn after being stopped. Three respawns within an hour from an app you haven't
  opened this week → it is blocked from background running (`appops RUN_ANY_IN_BACKGROUND ignore`
  + `force-stop`). You get a message, and every block can be undone from the optimiser.
  Uses one persistent `su` shell, so Magisk/SuperSU asks once.

No root? You can still block a pre-installed app from the background once over ADB:
```
adb shell cmd appops set <package> RUN_ANY_IN_BACKGROUND ignore   # undo: ... allow
adb shell pm disable-user --user 0 <package>                       # disable it entirely (undo: pm enable)
```

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
