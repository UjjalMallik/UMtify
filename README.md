<div align="center">
  <img src="banner.jpg" alt="UMtify Showcase" width="100%" />
</div>

<div align="center">

# UMtify

### *Your music, rebuilt in Liquid Crystal Glass.*

**UMtify** — developed by **[Ujjal Mallik](https://github.com/UjjalMallik)**.

A lightweight, Android-only music streaming & local audio player — a personal fork of the open-source [BitChord](https://github.com/kushagrasinghx/BitChord) project, rebuilt around a refined Material 3 interface, crystal glass surfaces, and a distraction-free listening experience.

[![Platform](https://img.shields.io/badge/Platform-Android%20arm64--v8a-3ddc84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/UjjalMallik/UMtify)
[![UI](https://img.shields.io/badge/UI-Material%203%20%2B%20Liquid%20Glass-8b5cf6?style=for-the-badge)](https://github.com/UjjalMallik/UMtify)
[![Refresh Rate](https://img.shields.io/badge/Refresh%20Rate-120Hz%20Smooth-0ea5e9?style=for-the-badge)](https://github.com/UjjalMallik/UMtify)
[![Build](https://img.shields.io/github/actions/workflow/status/UjjalMallik/UMtify/android-build.yml?branch=main&style=for-the-badge&label=Build)](https://github.com/UjjalMallik/UMtify/actions)
[![Latest Release](https://img.shields.io/github/v/release/UjjalMallik/UMtify?display_name=tag&style=for-the-badge&label=RELEASE&color=0284c7)](https://github.com/UjjalMallik/UMtify/releases/latest)
[![License](https://img.shields.io/badge/License-GPLv3-blue?style=for-the-badge)](https://github.com/UjjalMallik/UMtify/blob/main/LICENSE)

</div>

---

## ✨ Key Customizations & Features

| 🎨 **Adaptive Liquid Glass & Custom Themes** | 🔍 **Responsive Header & Quick Account Switcher** |
| :--- | :--- |
| Frosted Liquid Glass cards across Home, Settings, and dialogs, plus Material 3 Dynamic theming with Album Cover extraction, 6 curated presets, and a custom color picker. | Full-width frosted search bar aligned with the UMtify wordmark, paired with a bottom-bar profile avatar that launches a right-aligned Liquid Glass account card. |

| 📊 **Interactive Replay & Multi-Palette Share Cards** | ⏯️ **Enhanced MiniPlayer & Collapsible Settings** |
| :--- | :--- |
| Live theme-adaptive Listening Experience cards and a Bento-style Replay image exporter with selectable multi-tone glass color palettes. | Floating glass MiniPlayer with full Previous / Play-Pause / Next controls, and organized Settings category groups collapsed by default. |

| 🎨 **Crystal Glass Player UI** | 🌊 **Cover-Art Theme & Wave Ribbon** |
| :--- | :--- |
| Crystal glossy pill Play/Pause with an internal liquid wave, squiggly progress scrubber, strict 1:1 square cover art, and DPI-adaptive layout scaling. | A full Material You tonal palette extracted from the artwork, plus a smooth liquid ribbon below the transport controls that animates with playback and fades out when paused. |

| 💎 **Liquid Glass Widgets** | 🎛️ **Customizable Bottom Toolbar** |
| :--- | :--- |
| Three home screen widgets — *Crystal Vinyl*, *Now playing 2×2*, and *Now playing 4×1* — in frosted Liquid Crystal Glass with 3D crystal bubble buttons and live wave transitions. | A strict single-row action bar for queue, sleep timer, shuffle, lyrics, repeat, and more — hide the whole bar or toggle individual buttons in Settings. |

| 🏷️ **Metadata Editor** | 🧹 **Smart Cache Management** |
| :--- | :--- |
| Edit song title, artist, and album, set custom gallery cover art, and reset to the original metadata in one tap. | Configurable audio/media cache ceiling as low as **200 MB**, with automatic reset and cleanup the moment the limit is reached. |

| 📊 **"Stats for Nerds" Badge** | ⚡ **Lightweight & 120Hz Optimized** |
| :--- | :--- |
| A translucent, artwork-tinted glass pill reporting codec, bitrate, sample rate, **which source served the stream and which quality you asked for**, and Automix analysis status. | **arm64-v8a** only, R8-shrunk with compressed native libraries, frame-paced for buttery 120 Hz rendering, and zero background battery drain. |

| 🖼️ **UMtify Cover for Every Sleeve** | 🔠 **Live Font & Display Size** |
| :--- | :--- |
| A track with no artwork — local file, WebDAV share, dead thumbnail URL — shows UMtify's own cover instead of a blank box, in lists, the player, notification, and widgets. | Two sliders in Appearance, **80%–135%**, for type size and layout size. They stack on the system's own scales and apply in the next frame — no restart, no relaunch. |

| 🎧 **Universal Dolby Atmos & Spatial Audio** | 📶 **Smart Offline Mode & Auto Offline Home Feed** |
| :--- | :--- |
| Atmos and spatial playback unlocked on every device — no OEM whitelist, with a software decoder fallback and the app's own spatializer standing in for ordinary headphones. | Automatic network-loss switching and a Settings toggle that swaps into an offline hub with strict local-only search across Downloads, Local Music, and Cached Songs. |

| 🚗 **Android Auto Support** | 🎨 **Custom 3D App Icon Switcher** |
| :--- | :--- |
| A full `MediaLibraryService`, so Downloads, Local Music and Playlists are browsable and playable directly from your car head unit. | Three launcher icons — *Default*, *Liquid Glass*, *Liquid Glass Dark* — that swap the home screen and Recents instantly, plus a clean vector monochrome layer. |

| 🎚️ **Quality Tiers That Pick the Stream** | 🔁 **Mid-Track Quality Upgrade** |
| :--- | :--- |
| Lossless and Hi-Res ask your add-ons before YouTube; High, Medium and Low sort the format ladder by `itag`, codec and bitrate instead of taking the default rung. | A track that started on less than Settings asked for is quietly re-resolved and swapped up while it plays seamlessly without interrupting your listening. |

| 🏝️ **OriginOS Atomic Island Support** | 🎙️ **Podcasts, Audio Stories & FM Radio** |
| :--- | :--- |
| Exclusive Vivo/iQOO OriginOS edition (`com.tencent.qqmusic`) unlocking the native **OriginOS Atomic Island** media capsule on lock screen and status bar. | Full streaming for YouTube Podcast shows/episodes, audio stories (*Sunday Suspense*, *Golpo*), FM radio broadcasts, and music video audio with smart junk filtering. |

---

## 📦 Downloads & APK Editions

On every release, UMtify automatically builds **two optimized arm64-v8a APK editions** with the exact same features, version code, and signing key:

| Edition | APK File Name | Package ID | Best For |
| :--- | :--- | :--- | :--- |
| **🌐 Standard Edition** | `UMtify-vX.X-arm64-v8a.apk` | `com.music.bitchord` | All Android 8.0+ phones (OnePlus, Samsung, Xiaomi, Google Pixel, etc.) |
| **🏝️ Vivo / iQOO OriginOS Edition** | `UMtify-Vivo-Iqoo-OriginOS-arm64v8a.apk` | `com.tencent.qqmusic` | **Exclusive for Vivo & iQOO (OriginOS)** — Unlocks native **OriginOS Atomic Island** media capsule integration |

> [!TIP]
> Both editions share 100% identical features, audio engine, and release cycles. If you use a Vivo or iQOO phone running OriginOS, download the **OriginOS Edition** to enjoy full Atomic Island status bar capsules and lock screen media widgets.

<div align="center">

[![Download Latest Release](https://img.shields.io/github/v/release/UjjalMallik/UMtify?display_name=tag&style=for-the-badge&label=DOWNLOAD%20LATEST%20RELEASE&color=0284c7)](https://github.com/UjjalMallik/UMtify/releases/latest)

</div>

---

## 🛠️ Build Locally

**Requirements:** JDK 17 and Android SDK.

```bash
# Standard Release APK (com.music.bitchord)
./gradlew assembleProdRelease

# Vivo / iQOO OriginOS (Atomic Island) Release APK (com.tencent.qqmusic)
./gradlew assembleOriginosRelease
```

Compiled APKs are output to:
- `app/build/outputs/apk/prod/release/app-prod-arm64-v8a-release.apk`
- `app/build/outputs/apk/originos/release/app-originos-arm64-v8a-release.apk`

---

<div align="center">

## 💬 Support, Feedback & Community

Have questions, feedback, bug reports, or feature ideas? Connect directly with the developer:

[![Telegram](https://img.shields.io/badge/Telegram-@ujjalmallik-229ED9?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/ujjalmallik)
[![GitHub](https://img.shields.io/badge/GitHub-@UjjalMallik-181717?style=for-the-badge&logo=github&logoColor=white)](https://github.com/UjjalMallik)
[![Portfolio](https://img.shields.io/badge/Website-ujjalmallik.pro.bd-4F46E5?style=for-the-badge&logo=googlechrome&logoColor=white)](https://ujjalmallik.pro.bd)

---

## 🙏 Credits & License

UMtify is an independent personal project created by **[Ujjal Mallik](https://github.com/UjjalMallik)**, built on top of the open-source [BitChord](https://github.com/kushagrasinghx/BitChord) project. Sincere credit goes to the upstream BitChord contributors for the foundational architecture.

Distributed under the **GNU General Public License v3.0 (GPLv3)**. Original copyright remains with upstream developers; all modifications, UI redesigns, and enhancements are open source.

*UMtify is an unofficial personal music client and is not affiliated with YouTube, Google, Vivo, or Tencent.*

[⬆ Back to top](#umtify)

</div>
