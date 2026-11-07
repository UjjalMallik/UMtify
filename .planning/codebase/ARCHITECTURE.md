---
last_mapped_commit: cec238fda333abee0a65060f20cbb71cdce336fb
last_mapped_at: 2026-11-07
---
<!-- refreshed: 2026-11-07 -->
# Architecture

**Analysis Date:** 2026-11-07

## System Overview

```text
┌─────────────────────────────────────────────────────────────┐
│                       Presentation Layer                    │
├──────────────────┬──────────────────┬───────────────────────┤
│   Android App    │    Shared UI     │   System Widgets &    │
│  `MainActivity`  │  `NowPlaying`    │     Android Auto      │
│  `MainViewModel` │  `MeshGradient`  │ `PlaybackService`     │
│   `app/.../ui`   │   `sharedUi/...` │   `app/.../widget`    │
└────────┬─────────┴────────┬─────────┴──────────┬────────────┘
         │                  │                     │
         ▼                  ▼                     ▼
┌─────────────────────────────────────────────────────────────┐
│                    Playback & Business Layer                │
│  `PlaybackService` (Media3 MediaLibraryService)             │
│  `AudioCache`, `CrossfadeController`, `EqualizerProcessor`  │
│  `app/src/main/java/com/music/bitchord/playback/`           │
└────────────────────────────────┬────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────┐
│                 Data, Storage & Network Layer               │
│  `Innertube`, `NewPipe`, `SourceRegistry`, `LocalMedia`     │
│  `shared/src/jvmSharedMain/.../data/`                       │
│  `app/src/main/java/com/music/bitchord/data/`               │
└────────────────────────────────┬────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────┐
│             External Services & Native Audio DSP            │
│  YouTube Music, Spotify, LRCLIB, SMB/WebDAV, libffmpegJNI   │
└─────────────────────────────────────────────────────────────┘
```

## Component Responsibilities

| Component | Responsibility | File |
|-----------|----------------|------|
| `BitChordApplication` | Application bootstrap, image loader configuration, global caches, background jobs | `app/src/main/java/com/music/bitchord/BitChordApplication.kt` |
| `MainActivity` | Main Android Activity, edge-to-edge Compose container, navigation root | `app/src/main/java/com/music/bitchord/MainActivity.kt` |
| `MainViewModel` | UI presentation state, search queries, screen navigation, coordinator | `app/src/main/java/com/music/bitchord/ui/MainViewModel.kt` |
| `PlaybackService` | AndroidX MediaLibraryService, ExoPlayer instance, background audio playback | `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` |
| `PlayerConnection` | IPC bridge between Activity / UI and background `PlaybackService` | `app/src/main/java/com/music/bitchord/playback/PlayerConnection.kt` |
| `NowPlayingScreen` | Liquid Crystal Glass player interface, gesture handling, cover art view | `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt` |
| `AudioCache` | ExoPlayer SimpleCache wrapper with dynamic LRU ceiling and disk management | `app/src/main/java/com/music/bitchord/playback/AudioCache.kt` |
| `Innertube` | High-level YouTube Music client API for search, albums, streams | `app/src/main/java/com/music/bitchord/data/innertube/Innertube.kt` |
| `SourceRegistry` | Manages extensible audio source plugins and quality resolution | `app/src/main/java/com/music/bitchord/data/sources/SourceRegistry.kt` |
| `MeshGradient` | Real-time dynamic gradient rendering driven by extracted album palette | `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/MeshGradient.kt` |

## Pattern Overview

**Overall:** Modular Layered Architecture with Reactive UI (MVI / MVVM) and Android Foreground Media Service.

**Key Characteristics:**
- Unidirectional Data Flow (UDF) powered by Kotlin Coroutines `StateFlow` and Compose state.
- Separation of Concerns across Multiplatform modules: `app` (platform integration), `shared` (business models & networking), `sharedUi` (declarative views).
- Robust IPC communication via AndroidX Media3 Session between UI process and MediaLibraryService.

## Layers

**UI Layer (`sharedUi/` and `app/src/main/java/.../ui/`):**
- Purpose: Render screens, handle touch interactions, display dynamic theme gradients.
- Location: `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/`
- Contains: Composables, themes, canvas renderers, animations, UI state holders.
- Depends on: `shared/` domain models, Compose Multiplatform, Haze, Coil.
- Used by: `MainActivity` and system UI hosts.

**Service / Playback Layer (`app/src/main/java/.../playback/`):**
- Purpose: Maintain media playback, audio DSP pipelines, queue management, and lockscreen/Auto controls.
- Location: `app/src/main/java/com/music/bitchord/playback/`
- Contains: `PlaybackService`, audio processors (`EqualizerProcessor`, `SpatialAudioProcessor`), caching logic.
- Depends on: Media3 ExoPlayer, `data/` modules.
- Used by: UI layer via `PlayerConnection`.

**Data / Domain Layer (`shared/` and `app/src/main/java/.../data/`):**
- Purpose: Fetch and parse music metadata, stream URLs, lyrics, and handle persistence.
- Location: `app/src/main/java/com/music/bitchord/data/` and `shared/src/jvmSharedMain/`
- Contains: Repositories, API wrappers, SQLite/Prefs storage, network drivers.
- Depends on: Ktor, OkHttp, InnerTubeX, NewPipeExtractor.
- Used by: `MainViewModel` and `PlaybackService`.

## Data Flow

### Primary Request Path

1. User selects a track or playlist in `HomeScreen.kt` / `SearchScreen.kt` (`sharedUi/src/jvmSharedMain/.../HomeScreen.kt:250`)
2. `MainViewModel` resolves track metadata and dispatches play command (`app/src/main/java/.../MainViewModel.kt:400`)
3. `PlayerConnection` communicates with `PlaybackService` over `MediaController` IPC (`app/src/main/java/.../PlayerConnection.kt:120`)
4. `PlaybackService` checks `AudioCache`; if uncached, requests stream URL from `Innertube` / `SourceRegistry` (`app/src/main/java/.../PlaybackService.kt:850`)
5. ExoPlayer loads the media source, feeds audio samples through custom DSP pipeline (`EqualizerProcessor`, `SpatialAudioProcessor`), and streams to system audio sink.
6. Playback state (position, buffering, metadata) publishes back via `StateFlow` to update UI in real time.

### Secondary Flow: Real-Time Cover Palette Extraction

1. Current track changes; `SessionArtworkLoader` loads artwork bitmap via Coil.
2. `ArtworkPalette.kt` analyzes image colors using AndroidX Palette (`sharedUi/.../ArtworkPalette.kt:45`).
3. Dominant colors are passed to `rememberArtworkColors` and synthesized into a dynamic `MeshPalette`.
4. `MeshGradientBackground` renders smooth multi-point fluid gradients behind the player controls.

**State Management:**
- Application state is held in `StateFlow` within `MainViewModel` and `PlaybackService`.
- Persistent settings are saved to `SharedPreferences` via `AppSettings`.
- Ephemeral UI transitions leverage Compose `remember` and animated transitions.

## Key Abstractions

**`PlayerConnection`:**
- Purpose: Abstracts MediaBrowser and MediaController lifecycle for Compose components.
- Examples: `app/src/main/java/com/music/bitchord/playback/PlayerConnection.kt`
- Pattern: Service Gateway / Facade.

**`AudioSource`:**
- Purpose: Extensible provider interface for resolving audio streams across multiple platforms.
- Examples: `app/src/main/java/com/music/bitchord/data/sources/SourceRegistry.kt`
- Pattern: Strategy / Plugin Registry.

**`LyricsProvider`:**
- Purpose: Unified interface for fetching synced and unsynced lyrics from different providers.
- Examples: `app/src/main/java/com/music/bitchord/data/lyrics/LyricsTranslation.kt`
- Pattern: Provider Chain / Fallback Strategy.

## Entry Points

**`MainActivity`:**
- Location: `app/src/main/java/com/music/bitchord/MainActivity.kt`
- Triggers: User launching the app icon or tapping playback notification.
- Responsibilities: Initialize Compose view hierarchy, bind `PlayerConnection`, manage window insets.

**`PlaybackService`:**
- Location: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt`
- Triggers: Android Media3 service intent, Android Auto connection, media button press.
- Responsibilities: Manage foreground service lifecycle, notification updates, audio focus, ExoPlayer.

## Architectural Constraints

- **Threading:** Network and I/O operations are strictly performed on `Dispatchers.IO`; UI updates remain on `Dispatchers.Main`. ExoPlayer operations run on its dedicated internal playback looper.
- **Global state:** Singletons in `BitChordApplication` initialize caches (`AudioCache`, `CanvasCache`); media state is synchronized through `PlaybackService`.
- **Target Architecture:** Strictly compiled and packed for `arm64-v8a` with native FFmpeg JNI binaries.

## Anti-Patterns

### Blocking Calls on Main Thread

**What happens:** Direct file I/O or network calls inside Composable functions.
**Why it's wrong:** Triggers frame drops and breaks the 120Hz smooth rendering target.
**Do this instead:** Launch via `LaunchedEffect` or ViewModel `viewModelScope.launch(Dispatchers.IO)`.

### Direct Access to Service Internals

**What happens:** Bypassing `PlayerConnection` / `MediaController` to directly mutate player properties.
**Why it's wrong:** Breaks Android Auto, MediaSession synchronization, and background state.
**Do this instead:** Use `PlayerConnection.play()`, `pause()`, `seekTo()` methods.

## Error Handling

**Strategy:** Graceful degradation with fallback sources and silent retry mechanisms.

**Patterns:**
- Stream Fallback: If Innertube primary stream fails with 403 or signature error, automatically fallback to NewPipe cipher solver or alternative quality tiers.
- Cache Fallback: If network drops, automatically route to offline downloads and cached segments.

## Cross-Cutting Concerns

**Logging:** Centralized `DebugLog.kt` with tag-scoped logging.
**Validation:** Keystore verification for cookies and deep link URI sanitization.
**Authentication:** Secure token storage via `EncryptedSharedPreferences`.

---

*Architecture analysis: 2026-11-07*
