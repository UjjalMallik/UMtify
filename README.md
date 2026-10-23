# UMtify

UMtify is a customized, lightweight, Android-only music streaming and local audio player built for personal use. It is a personal fork of the open-source [BitChord](https://github.com/kushagrasinghx/BitChord) project, rebuilt around a refined Material 3 interface, a smaller APK, and a distraction-free listening experience.

---

## Key Customizations & Features

### Redesigned Material 3 player UI

A rebuilt Now Playing screen with artwork-themed Material You tonal controls:

- A pill-shaped Play/Pause button and circular previous/next transport buttons.
- A squiggly/wavy progress bar with a draggable scrubber.
- A compact bottom action bar for queue, sleep timer, shuffle, lyrics, repeat, share, and favourite.
- Colours extracted from the current cover art, with smooth crossfades on every track change.

### Dynamic cover-art Material 3 theme

A theme option alongside **System**, **Light**, and **Dark**, enabled by default. UMtify reads the dominant and accent colours of the current cover and builds a complete Material 3 tonal palette from them — primary, secondary, tertiary, surfaces, and every container role. The whole app moves with the music, and the palette crossfades smoothly when the track changes. The feature respects the **Reduce animation** setting.

### Dual cover art modes

The **Full-screen cover art** setting switches between two presentations without leaving the player:

- **On** — edge-to-edge artwork that reaches the top and side edges of the screen and fades smoothly into the controls below.
- **Off** — an enlarged floating rounded-square photocard that fills nearly the full width of the phone.

### Clean "Stats for Nerds" badge

Audio details are shown in a compact glassmorphic pill directly below the cover art, styled as a centered Material 3 badge with a translucent artwork-tinted background, a hairline border, an equalizer icon, and monospace typography. It reports the active codec, bitrate, and sample rate, plus Automix analysis status when that feature is enabled. The badge can be toggled in Settings.

### Lightweight and optimized (arm64-v8a)

- Built for **arm64-v8a** Android devices only, keeping the release APK small.
- Unused multiplatform modules removed, including Desktop, Web, TV, and native CMake targets.
- Background remote update checks and update popups disabled for a faster, distraction-free experience.
- Every build is signed with the same committed key, so new APKs install over older ones without uninstalling.
- Version codes follow the GitHub Actions run number, so updates always move forward.

### Core playback

- High-quality streaming
- Synced lyrics
- Local music playback
- Sleep timer
- Queue management

---

## Download & Builds

Every push to `main` builds an optimized arm64-v8a APK with **GitHub Actions** and publishes it automatically to **[Releases](https://github.com/UjjalMallik/UMtify/releases)**. Download the newest `UMtify-v1.0.<run>-arm64-v8a.apk` and sideload it; because every release carries the same signing key and a higher version code, it installs directly over the previous one.

Build results are also kept as the `android-prod-release-arm64-v8a` artifact on the [Actions](https://github.com/UjjalMallik/UMtify/actions) tab.

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
