# UMtify

UMtify is a customized, lightweight, Android-only music streaming and local audio player built for personal use. It is a personal fork of the open-source [BitChord](https://github.com/kushagrasinghx/BitChord) project, rebuilt around a refined Material 3 interface, a smaller APK, and a distraction-free listening experience.

---

## Key Customizations & Features

### Redesigned Material 3 player UI

A rebuilt Now Playing screen, from the transport controls down to the progress row:

- Crystal glossy glass transport controls: a pill-shaped Play/Pause button with an internal animated liquid wave, flanked by circular previous and next buttons.
- A squiggly/wavy progress bar with a draggable scrubber.
- Responsive layout scaling that adapts cleanly across every phone screen size and DPI, without wrapping text or shrinking the cover art.
- Colours extracted from the current cover art, with smooth crossfades on every track change.

### Dynamic cover-art Material 3 theme & music-reactive wave transition

A theme option alongside **System**, **Light**, and **Dark**, enabled by default. UMtify reads the dominant and accent colours of the current cover and builds a complete Material 3 tonal palette from them — primary, secondary, tertiary, surfaces, and every container role. The whole app moves with the music, and the palette crossfades smoothly when the track changes.

While music is playing, a smooth, continuous **wavy liquid ribbon transition** animates below the transport controls, paced by playback, and fades out cleanly when playback pauses. The feature respects the **Reduce animation** setting.

### Customizable bottom action toolbar (Hide / Show controls)

A compact single-row bottom action bar for queue, sleep timer, shuffle, lyrics, repeat, and more options.

Settings carries the full customization: hide or unhide the entire bottom toolbar, or toggle which individual buttons appear in it.

### Crystal Liquid Glass home screen widgets

Three redesigned home screen music widgets — **Crystal Vinyl**, **Now playing 2×2** (square), and **Now playing 4×1** (bar) — crafted with a translucent frosted Liquid Crystal Glass aesthetic, 3D glossy crystal bubble buttons, and music-reactive wave transitions.

### Smart cache management (200 MB minimum limit & auto-reset)

The audio/media cache has a configurable maximum that can be set as low as **200 MB**. Once the limit is reached, the cache is reset and cleaned up automatically, so listening never quietly fills the phone's storage.

### Custom cover art & song metadata editor

Edit a track's title, artist, and album name, and set custom cover artwork from the gallery — for local files and downloaded tracks alike — with a one-tap reset back to the original metadata.

### Clean "Stats for Nerds" badge

Audio details are shown in a compact glassmorphic pill directly below the cover art, styled as a centered Material 3 badge with a translucent artwork-tinted background, a hairline border, an equalizer icon, and monospace typography. It reports the active codec, bitrate, and sample rate, plus Automix analysis status when that feature is enabled. The badge can be toggled in Settings.

### Lightweight and optimized (arm64-v8a & 120Hz smooth)

- Built for **arm64-v8a** Android devices only, keeping the release APK small.
- Unused multiplatform modules removed, including Desktop, Web, TV, and native CMake targets.
- Background remote update checks and update popups disabled for a faster, distraction-free experience.
- Every build is signed with the same committed key, so new APKs install over older ones without uninstalling.
- Version codes default to the GitHub Actions run number — or the value chosen on a manual release — so updates always move forward.
- Release builds run through **R8** with resource shrinking, and native libraries are stored compressed, so the download stays small without giving up speed.
- The UI is frame-paced for **120 Hz** displays and stays smooth while scrolling, animating, and playing.

### Core playback

- High-quality streaming
- Synced lyrics
- Local music playback
- Sleep timer
- Queue management

---

## Download & Builds

Every push to `main` builds an optimized **arm64-v8a** APK in **GitHub Actions** and keeps it as a downloadable artifact on the run page — see the [Actions](https://github.com/UjjalMallik/UMtify/actions) tab, artifact `UMtify-v<versionName>`. A push never publishes a release.

Official versioned releases are published to **[Releases](https://github.com/UjjalMallik/UMtify/releases)** on demand: run the **Android Production APK** workflow manually with a custom `versionName` and `versionCode` (plus optional release notes), or push a `v*` tag. Download the newest `UMtify-v<versionName>.apk` and sideload it; because every build carries the same signing key and a higher version code, it installs directly over the previous one.

### Build locally

Building requires JDK 17 and the Android SDK:

```bash
./gradlew assembleProdRelease
```

The APK is written to:

```text
app/build/outputs/apk/prod/release/app-prod-arm64-v8a-release.apk
```

---

## Credits & Acknowledgments

UMtify is built on the open-source [BitChord](https://github.com/kushagrasinghx/BitChord) project. All credit for the original application, its architecture, and its upstream playback, streaming, lyrics, and library features goes to the BitChord developers and contributors. UMtify exists only as a customized derivative for personal use.

BitChord is licensed under the **GNU General Public License v3.0 (GPLv3)**, and UMtify is distributed under the same license. Original copyright remains with the BitChord developers; any redistribution must preserve the license and provide the corresponding source.

UMtify is an unofficial personal project and is not affiliated with YouTube, Google, or the upstream BitChord maintainers.
