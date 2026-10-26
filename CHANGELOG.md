# Changelog

All notable changes to CarDash. Versions follow `MAJOR.MINOR[.PATCH]`; pre-releases are tagged
`vX.Y-betaN`. Each `## vX.Y` section below becomes the notes of the matching GitHub release.

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
- Glossy vector car art: **Hatchback** (after the 2014 Accent hatch) and **Sedan**.
- Night lighting (6 pm – 5 am): LED headlamps power on, beams light the road, tail lamps glow.
- Brake lights in the daytime when the speed drops.
- Parked car on the screensaver: rolls into a bay, brakes and flashes its indicators as it locks.
- Smoother UI motion: press feedback, track-change slide, drawer and screensaver fades.

## v1.0 – v1.8

- Tesla-style home screen: GPS speed, clock, top-down car, now-playing card with album art,
  volume, 6-slot dock and app drawer.
- Auto day / night theme (fixed hours or real sunrise / sunset), speed alert, screensaver,
  Standard and Dash cam layouts.
- DDPAI dash cam live view (front over Wi-Fi, rear via the head unit's camera) with Android Auto /
  CarPlay protection.
- Conservative RAM optimiser with phone-link app protection and optional root Deep mode.
