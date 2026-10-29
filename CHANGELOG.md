# Changelog

All notable changes to CarDash. Versions follow `MAJOR.MINOR[.PATCH]`; pre-releases are tagged
`vX.Y-betaN`. Each `## vX.Y` section below becomes the notes of the matching GitHub release.

## v3.3 — 2026-10-29

### Fixed
- **The screensaver now goes away when you drive off.** Before, only a touch closed it. Now the
  dashboard comes back as soon as GPS reads two fixes in a row above about 8 km/h (so standstill
  jitter can't do it), and the sleeping cat bolts off the roof first. It also closes when a head unit
  that kept CarDash on screen through its sleep wakes up on ignition.

### Added
- *Settings → Screensaver started by hand closes when you drive off: yes / no* (default yes). The
  screensaver you start yourself (moon key, *Start now*) can stay up while you drive if you prefer;
  the automatic one always closes.

### Changed
- **Smoother on slow units:**
  - The road scene stays paused behind *any* cover (screensaver, All apps, dash cam) and resumes in one
    place when it's uncovered, so it never runs unseen.
  - The cloud drift wakes the UI thread about 30 times a second instead of on every screen refresh.
  - Coming back from the screensaver, opening All apps and the start-up rise each wait a frame or
    two for the heavy first frame (layout, textures) to finish, so the animation itself doesn't stutter.
  - Returning to the home screen no longer re-reads the app list when nothing was installed or removed.
- Diagnostic logs: if root isn't available after all, the log capture falls back to CarDash's own
  log instead of capturing nothing. Frame tracking is detached when the home screen closes.

## v3.2.1 — 2026-10-28

(First published as v3.2, which by mistake contained the v3.1 build; v3.2.1 is the real one.)

### Added
- **Diagnostic logs** (*Settings → Diagnostic logs*, off by default) into a `cardash` folder on
  internal storage: device / display / settings snapshot, a timestamped event log (lifecycle,
  screensaver, theme, GPS, media, updates, RAM optimiser, dash cam, errors and crashes with stack
  traces), a performance line every 30 s (frames, slow / janky frames, frame-time percentiles, CPU,
  memory, threads, temperature) and Android's own log for CarDash (whole system log with root).
  *Mark the problem now* drops a marker at the exact moment. Rotating files, newest 12 kept.

### Changed
- **Crisper picture.** The car pictures (chase cam, top-down, screensaver) are drawn at whole-pixel
  positions, so the GPU no longer resamples them into a soft image; their lit-lamp layers line up to
  the pixel. The rear car art is decoded at full resolution, scaled down in clean halving steps and
  gently sharpened. Album art is prepared at exactly the size it's shown, and dock icons are drawn
  1:1 instead of being shrunk by a couple of pixels.

## v3.1 — 2026-10-27

### Changed
- **Colours that hold up on head-unit screens.** Dark theme: true black background, cards lifted
  clearly off it, visible outlines, brighter text and a brighter blue. Light theme: a grey
  background with off-white cards instead of pure white (which blooms and washes out), darker text,
  stronger outlines, a deeper blue. The screensaver's dim ticks and date are a touch brighter too.
- **Back and Recent apps moved to All apps.** They're no longer on the dashboard. Open All apps and
  the dock stays on screen underneath it, with Back and Recent apps on its left and the grid key
  turned into a close key. Launching from the dock or starting the screensaver closes All apps.
- Menus that are just lists (Settings, Updates, Greeting, Music card, Rear badge, Dash cam, RAM
  optimiser) have a **Close** button, for head units without a system Back key.

## v3.0 — 2026-10-27

### Added
- **Back and Recent apps on the dock** (left), **Screensaver now** and **Shortcuts** (right).
  Back closes the drawer or returns to the app you were last in; Recent apps opens Android's recent
  apps, or CarDash's own list of apps you opened. Shortcuts: Notifications, Quick settings, Recent
  apps, Screenshot, Screensaver, Volume, Wi-Fi, Bluetooth, Screen off, Power menu, Android settings,
  CarDash settings.
- **CarDash buttons**, an accessibility service you switch on once so those keys can press
  Android's own Back / Recents / Notifications / Quick settings / Screenshot / Lock / Power menu. It
  reads nothing; with root (Deep mode) CarDash turns it on for you.
- **Media controls on the screensaver** (previous / play-pause / next inside the ring). Using them
  keeps the screensaver up; a touch anywhere else wakes the dashboard as before.
- *Settings → Screensaver → Start now*.

### Changed
- **The whole screen is used**: CarDash draws edge to edge and leaves room only for bars that are
  really showing. A hidden status bar no longer leaves an empty strip at the top.
- **Smoother clouds, less work**: the chase-cam sky is now its own layer under the road. Clouds glide
  at 30 updates a second by moving that layer alone (no road, building or car redraw); before they
  stepped at 8-12 fps and redrew the whole scene each time. Stars twinkle in the sky layer only.
- The road scene no longer redraws on every GPS fix while parked (that was 2 redraws a second).
- The screensaver keeps showing the right track when a music app's widget is in the music card.

## v2.9.1 — 2026-10-27

### Fixed
- **Sleepy cat drawn properly from above**: its face was drawn as if it were looking up at the
  camera (eyes on top of its head). Now the ears sit at the front-sides, tabby marks run back over
  the top of the head, the muzzle and nose stick out in front with the eyes just visible on the
  front edge, and when it sleeps it tucks its nose towards its tail.

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
