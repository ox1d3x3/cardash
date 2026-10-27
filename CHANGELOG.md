# Changelog

All notable changes to CarDash. Versions follow `MAJOR.MINOR[.PATCH]`; pre-releases are tagged
`vX.Y-betaN`. Each `## vX.Y` section below becomes the notes of the matching GitHub release.

## v2.9 — 2026-10-27

### Changed
- Wording: CarDash is described simply as a **lightweight** home screen everywhere (app, About,
  README, changelog, tools); references to other car brands' styles are gone. The car styles are
  just *Hatchback* and *Sedan*.
- **Play / pause morphs**: the play triangle turns into the pause bars (and back) instead of
  swapping icons. Animated on the render thread.
- **Background work never competes with animations**: app icons, album art, badge import and car
  rendering run at Android's background priority.
- **Chase-cam sky while idle**: after a minute with nothing else moving, the drifting clouds and
  twinkling stars tick at 8 fps instead of 12 (looks the same, a third less work while parked).
- **Screensaver clock**: reuses one calendar instead of creating one per frame (matters while the
  cat is moving), and still follows time-zone changes.

## v2.8 — 2026-10-27

### Added
- **Sleepy cat on the screensaver.** A moment after the parked car locks, a ginger tabby walks in,
  wiggles, jumps onto the roof, turns round and curls up asleep (breathing, drifting z's). Tap the
  screen: it startles, leaps off and runs away across the clock, then the dashboard returns.
  *Settings → Sleepy cat on the parked car* turns it off. Smooth only while it moves, 10 fps asleep.
- **Rear badge** (*Settings → Rear badge*), on the boot lid in the chase-cam view: chrome lettering
  you type (e.g. your model name), or your own image from the head unit, with its plain background
  cut away automatically and a chrome or original-colour finish. Painted into the car picture once:
  no per-frame cost. CarDash ships no badges or brand logos of its own.

## v2.7 — 2026-10-27

### Added
- **About CarDash** (*Settings → About CarDash*): version, developer **Mahabub Subhany (ox1d3x3)**,
  source code on GitHub (both open in the browser), the automatic update check switch, *What's new*
  and *Check for updates*.
- **Yellow update icon.** Update checks are on by default; when a new release is found a yellow
  *UPDATE vX.Y* icon pops up on the drive panel with a short pulse and a one-time message. Tap it:
  the release notes and one **Download & install** button, with a progress bar, then the install.

### Changed
- *Settings → Updates* is now *Check automatically* (on / off) plus *When there's an update*:
  show the yellow icon (new default), download in the background then ask, or install
  automatically when parked. Earlier choices carry over.
- **Smoother track changes**: album art is scaled and colour-picked on a background thread and only
  re-applied when the picture really changed (players re-send the same art several times per
  track); the art's grow-in animation renders through a hardware layer.
- **Day / night theme switch cross-fades** instead of snapping at 6 pm and 5 am.
- Greeting text fades without allocating offscreen layers.

## v2.6 — 2026-10-27

### Added
- **Music card: a music app's own widget** (*Settings → Music card*). Instead of the built-in
  player, the card can host Spotify's, YouTube Music's, AIMP's or any other app's home-screen
  widget, sized to the card and clipped to its rounded corners. Music apps are listed first.
  Android asks once to allow CarDash to show widgets. Long-press the widget for *Open app*,
  *Change widget* or *Back to the CarDash player*.
  - Light by design: the widget only receives updates while the home screen is visible, and the
    built-in player stops all of its own drawing and its progress timer while a widget is shown.
  - The built-in player stays the default and the lightest option.

## v2.5 — 2026-10-27

### Added
- **Greeting.** The first time after install or after the head unit boots, the speed slot says
  *Welcome, (your name)*, then turns into *Good morning / Good afternoon / Good evening*. After
  the unit has slept or been off for 10+ minutes, you get the time-of-day greeting again. It fades
  away the moment the car moves and the speed takes its place. *Settings → Greeting* sets your name
  or turns it off; tap the greeting to dismiss it, long-press to change the name.
- **Music player choice lists music apps first**: Spotify, YouTube Music, AIMP, Poweramp and
  any other app that can be a car-audio source, then everything else.

### Changed
- Pressing play with nothing playing opens your chosen default player when it isn't the app
  Android would resume (for example, Spotify chosen but AIMP played last).
- CI test builds are versioned below the release they lead up to (`2.5-dev+<commit>`), so the
  updater always moves them on to the real release.
- The updater's background thread is released when the home screen is destroyed.

## v2.4 — 2026-10-27

### Added
- **Automatic updates from GitHub Releases.** CarDash checks this repository every 6 hours,
  downloads a newer release in the background and installs it when the car is parked
  (or asks first, or stays off — your choice).
  - An **update chip** next to the RAM figure shows when an update is available, its download
    progress, and when it's ready. Tap it for the release notes and *Install now*.
  - **Settings → Updates**: check now, automatic mode (*Install when parked* / *Download, then ask*
    / *Off*), opt in to beta pre-releases, open all releases.
  - Every download is checked before installing: same app, newer version, **signed with the same
    key** as the installed copy. Anything else is discarded.
  - With **Deep mode (root)** on, updates install silently. Without root, Android asks once to
    allow CarDash to install apps; from then on Android 12+ installs updates without asking.
  - After an update CarDash reopens itself and shows *Updated to CarDash vX.Y*.
- Release pipeline: pushing a `v*` tag (or publishing a release on GitHub) builds, signs and
  attaches `CarDash-vX.Y.apk` plus its SHA-256 automatically. Every push is also built by CI.

### Changed
- Version codes are now derived from the version: `(major·10000 + minor·100 + patch)·100 + stage`
  (stage 99 for final releases, N for `-betaN`), so every release installs over the one before.
- Removed unused drawables from the old car renderer (smaller source tree, same APK).

## v2.3

### Added
- **Living sky in the chase-cam view**: blue sky, sun and drifting clouds by day; crescent moon,
  twinkling stars, the odd shooting star and lit city windows at night.
- At 6 pm the sun sets with a warm dusk glow while the moon rises (and the reverse at 5 am).

### Changed
- Bigger previous / next buttons (92 dp, play 96 dp; 78 / 82 dp in the Dash cam layout).
- Sky layers are painted once into small bitmaps; while parked the scene ticks at 12 fps instead
  of 60 and stops completely when anything covers it.

## v2.2

### Changed
- New chase-cam car: the owner's own straight-on EV design turned into a glossy render
  (perfectly symmetric, clear-coat reflections on their own layer, rebuilt lamps, badge removed).
- White and black base cars; light paint colours tint the white car, dark ones the black car.

## v2.0 – v2.1

### Added
- **Car view: behind the car** (chase cam): the road runs to the horizon, lane dashes and roadside
  blocks stream past with GPS speed.
- **Your own number plate** (*Settings → Number plate*, up to 10 characters), drawn in perspective.

### Changed
- Brake lights now pop on only for a **sudden** speed drop (measured over ≥ 0.4 s, so 1 Hz and
  10 Hz GPS behave the same); gentle slowing and GPS glitches never light them.
- Smoother animations throughout.

## v1.9.1

### Changed
- Larger media buttons; **swipe the music card** left / right to skip tracks.
- Status bar hidden on the home screen by default to reclaim the empty strip at the top
  (swipe down from the edge to peek; *Settings → System bars* to change).

## v1.9

### Added
- Glossy vector car art: **Hatchback** and **Sedan**.
- Night lighting (6 pm – 5 am): LED headlamps power on, beams light the road, tail lamps glow.
- Brake lights in the daytime when the speed drops.
- Parked car on the screensaver: rolls into a bay, brakes and flashes its indicators as it locks.
- Smoother UI motion: press feedback, track-change slide, drawer and screensaver fades.

## v1.0 – v1.8

- Lightweight home screen: GPS speed, clock, top-down car, now-playing card with album art,
  volume, 6-slot dock and app drawer.
- Auto day / night theme (fixed hours or real sunrise / sunset), speed alert, screensaver,
  Standard and Dash cam layouts.
- DDPAI dash cam live view (front over Wi-Fi, rear via the head unit's camera) with Android Auto /
  CarPlay protection.
- Conservative RAM optimiser with phone-link app protection and optional root Deep mode.
