<div align="center">
  <img src="banner.jpg" alt="UMtify Showcase" width="100%" />
</div>

<div align="center">

# UMtify

### *Your music, reimagined in Liquid Crystal Glass.*

A high-performance, fluid Android music streaming & local audio player built around a sculpted Material 3 interface, translucent crystal glass surfaces, and an uncompromising listening experience.

[![Platform](https://img.shields.io/badge/Platform-Android%20arm64--v8a-3ddc84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/UjjalMallik/UMtify)
[![UI](https://img.shields.io/badge/UI-Material%203%20%2B%20Liquid%20Glass-8b5cf6?style=for-the-badge)](https://github.com/UjjalMallik/UMtify)
[![Refresh Rate](https://img.shields.io/badge/Refresh%20Rate-120Hz%20Smooth-0ea5e9?style=for-the-badge)](https://github.com/UjjalMallik/UMtify)
[![Build](https://img.shields.io/github/actions/workflow/status/UjjalMallik/UMtify/android-build.yml?branch=main&style=for-the-badge&label=Build)](https://github.com/UjjalMallik/UMtify/actions)
[![Latest Release](https://img.shields.io/github/v/release/UjjalMallik/UMtify?display_name=tag&style=for-the-badge&label=RELEASE&color=0284c7)](https://github.com/UjjalMallik/UMtify/releases/latest)
[![License](https://img.shields.io/badge/License-GPLv3-blue?style=for-the-badge)](https://github.com/UjjalMallik/UMtify/blob/main/LICENSE)

</div>

---

## 💎 Experience Highlights

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                   UMtify Architecture                                  │
├──────────────────────────┬──────────────────────────┬──────────────────────────────────┤
│ 🔮 Liquid Glass UI      │ ⚡ Instant Engine        │ 🎧 Audiophile Fidelity           │
│ Material 3 + Frost Glass │ Cache-First Zero Shimmer │ Universal Dolby Atmos & Spatial  │
│ 1:1 Cover Art Alignment  │ 120Hz Jitter-Free Canvas │ Lossless & Hi-Res Format Ladder  │
│ Dynamic Wave Visualizer  │ Background Island Stream │ YouTube Podcasts & Audio Stories │
└──────────────────────────┴──────────────────────────┴──────────────────────────────────┘
```

### 🔮 1. Aesthetics & Liquid Glass Experience

| Feature | Description |
| :--- | :--- |
| **Adaptive Liquid Glass** | Frosted Liquid Glass cards across Home, Settings, and dialogs. Material 3 Dynamic theming with Album Cover extraction, 6 curated presets, and a custom color picker. |
| **Crystal Glossy Player** | Crystal glossy pill Play/Pause button with internal liquid wave animation, squiggly progress scrubber, strict 1:1 square cover art, and DPI-adaptive layout scaling. |
| **Wave Ribbon Visualizer** | Smooth, flowing liquid ribbon below transport controls that actively animates during playback and gently settles when paused. |
| **Floating Glass MiniPlayer** | Persistent floating glass MiniPlayer with full Previous / Play-Pause / Next controls and seamless expansion gestures. |
| **Interactive 3D Widgets** | Three home screen widgets — *Crystal Vinyl*, *Now playing 2×2*, and *Now playing 4×1* — in frosted Liquid Crystal Glass with 3D crystal bubble controls. |
| **Dynamic Scaling & Icons** | Real-time Font (80%–135%) and Display scale sliders that take effect in the very next frame without app restarts, plus 3 swappable launcher icons. |

### ⚡ 2. Instant Performance & Fluid Engine

| Feature | Description |
| :--- | :--- |
| **Instant Cache-First Home** | Pre-hydrated local disk cache ensures the Home screen renders instantaneously on launch with **zero blank screen or shimmer delay**. |
| **120Hz Jitter-Free Rendering** | Frame-paced for buttery 120Hz LTPO displays. Volume bar, seek sliders, and layout bounds are mathematically locked for zero frame-stepping jitter. |
| **Persistent Background Playback** | Continuous, uninterrupted music playback in the status bar and lockscreen even when the app task is swiped away from Recents. |
| **Smart Memory & Cache Ceiling** | User-configurable audio and media cache limit as low as **200 MB** with automatic background eviction upon reaching the ceiling. |
| **Lightweight arm64-v8a Core** | R8-shrunk binaries, native library compression, and zero background battery drain when playback is idle. |

### 🎧 3. Audiophile Fidelity & Expanded Content

| Feature | Description |
| :--- | :--- |
| **Universal Dolby Atmos & Spatializer** | Atmos and 3D spatial playback unlocked across all devices without manufacturer whitelists, backed by a high-grade software spatializer fallback. |
| **Smart Audio Format Ladder** | Prioritizes Lossless and Hi-Res streams through add-ons before YouTube Music, sorting formats by `itag`, codec, and bitrate. |
| **Seamless Mid-Track Upgrade** | Automatically upgrades an in-flight stream to higher quality if network bandwidth improves without interrupting playback. |
| **Podcasts, Audio Stories & FM Radio** | Full audio streaming for YouTube Podcast shows/episodes, audio drama stories (*Sunday Suspense*, *Golpo*), and FM radio broadcasts with smart junk filtering. |
| **"Stats for Nerds" Glass Pill** | Real-time translucent diagnostics displaying codec, bitrate, sample frequency, active stream provider, and Automix analysis status. |

### 📱 4. Ecosystem & Platform Integration

| Feature | Description |
| :--- | :--- |
| **Vivo & iQOO OriginOS Atomic Island** | Dedicated OriginOS edition (`com.tencent.qqmusic`) unlocking the native **Atomic Island** media capsule on the punch-hole status bar and lockscreen. |
| **Standard Android Edition** | Clean package (`com.music.bitchord`) optimized for OnePlus (OxygenOS), Samsung (OneUI), Xiaomi (HyperOS), Pixel, Motorola, and all Android 8.0+ devices. |
| **Full Android Auto Support** | Complete `MediaLibraryService` architecture for browsing and playing Downloads, Local Music, and Playlists directly on your car head unit. |
| **Smart Offline Hub** | Automatic offline failover with local library search across Downloads, Local Files, and Audio Cache without touching network sockets. |

---

## 📦 Downloads & APK Editions

Every release builds **two optimized arm64-v8a APK editions** sharing the exact same code, features, audio engine, and signing keys:

| Edition | Package ID | Target Devices | Highlights |
| :--- | :--- | :--- | :--- |
| **🌐 Standard Edition** | `com.music.bitchord` | OnePlus, Samsung, Xiaomi, Pixel, Motorola, etc. | Clean package ID, global Android compatibility, Android Auto support |
| **🏝️ OriginOS Edition** | `com.tencent.qqmusic` | **Exclusive for Vivo & iQOO (OriginOS)** | Unlocks native **OriginOS Atomic Island** camera punch-hole capsule & lockscreen widgets |

<div align="center">

[![Download Latest Release](https://img.shields.io/github/v/release/UjjalMallik/UMtify?display_name=tag&style=for-the-badge&label=DOWNLOAD%20LATEST%20RELEASE&color=0284c7)](https://github.com/UjjalMallik/UMtify/releases/latest)

</div>

---

## 🛠️ Building From Source

**Prerequisites:** JDK 17+ and Android SDK with build-tools.

```bash
# Clone the repository
git clone https://github.com/UjjalMallik/UMtify.git
cd UMtify

# Build Standard Release APK (com.music.bitchord)
./gradlew assembleProdRelease

# Build Vivo / iQOO OriginOS Edition APK (com.tencent.qqmusic)
./gradlew assembleOriginosRelease
```

Generated APKs are located at:
- `app/build/outputs/apk/prod/release/app-prod-arm64-v8a-release.apk`
- `app/build/outputs/apk/originos/release/app-originos-arm64-v8a-release.apk`

---

<div align="center">

## 💬 Support & Community

Have questions, feedback, bug reports, or feature ideas? Join our community or chat directly on Telegram:

[![Telegram Channel](https://img.shields.io/badge/Telegram-@ujjalmallik-229ED9?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/ujjalmallik)

---

## 📜 License & Acknowledgments

UMtify is an independent personal project created by Ujjal Mallik, built upon the open-source [BitChord](https://github.com/kushagrasinghx/BitChord) project. Sincere credit goes to the upstream BitChord contributors for the foundational architecture.

Released under the **[GNU General Public License v3.0 (GPLv3)](LICENSE)**.

*UMtify is an unofficial personal music client and is not affiliated with YouTube, Google, Vivo, or Tencent.*

[⬆ Back to top](#umtify)

</div>
