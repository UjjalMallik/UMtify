---
last_mapped_commit: cec238fda333abee0a65060f20cbb71cdce336fb
last_mapped_at: 2026-11-07
---
# Codebase Structure

**Analysis Date:** 2026-11-07

## Directory Layout

```
UMtify/
├── app/                               # Android application module
│   ├── src/androidTest/              # Android instrumented tests
│   ├── src/main/
│   │   ├── cpp/                       # C++ native audio algorithms
│   │   ├── java/com/music/bitchord/   # Android platform code & services
│   │   │   ├── auth/                  # Encrypted storage & session cookies
│   │   │   ├── data/                  # Repositories, APIs, SMB/WebDAV
│   │   │   ├── download/              # Offline download managers
│   │   │   ├── image/                 # Image loading & cover fallbacks
│   │   │   ├── playback/              # MediaLibraryService & audio DSP
│   │   │   ├── ui/                    # Android-specific ViewModels & UI
│   │   │   ├── wear/                  # Wear OS companion sync
│   │   │   └── widget/                # Liquid Crystal Glass widgets
│   │   ├── jniLibs/arm64-v8a/         # Prebuilt libffmpegJNI native library
│   │   └── res/                       # Drawables, layouts, strings, widget XML
│   └── src/test/                      # JVM unit tests for Android module
├── backend/                           # Standalone Go Listen Together service
│   ├── hub/                           # WebSocket room & party manager
│   ├── party/                         # Party state synchronization
│   ├── protocol/                      # Network message schema
│   └── main.go                        # Backend service entry point
├── shared/                            # Kotlin Multiplatform core domain module
│   └── src/
│       ├── commonMain/                # Pure Kotlin domain models
│       ├── jvmSharedMain/             # Shared JVM/Android business logic
│       └── commonTest/                # Multiplatform test suites
├── sharedUi/                          # Kotlin Compose Multiplatform UI module
│   └── src/
│       ├── jvmSharedMain/kotlin/com/music/bitchord/ui/
│       │   ├── components/            # Reusable glass UI components
│       │   ├── player/                # Now Playing screen, transport, waveforms
│       │   ├── replay/                # Yearly listening rewind cards
│       │   ├── screens/               # Home, Search, Library, Explore screens
│       │   └── theme/                 # Dynamic ArtworkPalette & styling
│       └── jvmTest/                   # Compose & UI unit tests
├── gradle/                            # Gradle wrapper configurations
├── .github/                           # CI/CD workflows and automation scripts
└── build.gradle.kts                   # Root build script & R8 compiler overrides
```

## Directory Purposes

**`app/src/main/java/com/music/bitchord/playback/`:**
- Purpose: Houses the audio engine, ExoPlayer lifecycle, and DSP filters.
- Contains: `PlaybackService.kt`, `AudioCache.kt`, `CrossfadeController.kt`, `EqualizerProcessor.kt`, `SpatialAudioProcessor.kt`.
- Key files: `PlaybackService.kt` (MediaLibraryService entry point), `PlayerConnection.kt`.

**`sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/`:**
- Purpose: Liquid Crystal Glass player interface and music-reactive graphics.
- Contains: Composables for player controls, animated wave ribbons, queue views, and lyrics.
- Key files: `NowPlayingScreen.kt`, `PlayerControls.kt`, `MeshGradient.kt`, `WavySeekBar.kt`.

**`app/src/main/java/com/music/bitchord/data/`:**
- Purpose: External integrations and local metadata storage.
- Contains: Innertube client, Spotify canvas fetcher, Discord RPC, scrobblers, and network shares.
- Key files: `Innertube.kt`, `LocalMediaRepository.kt`, `SourceRegistry.kt`, `SongEditStore.kt`.

**`app/src/main/java/com/music/bitchord/widget/`:**
- Purpose: Android launcher widgets with translucent frosted glass styling.
- Contains: `CrystalVinylWidget.kt`, `NowPlaying2x2Widget.kt`, `NowPlaying4x1Widget.kt`.

**`backend/`:**
- Purpose: Lightweight Go backend powering synchronized group listening ("Listen Together").
- Contains: Goroutine-based room coordinator, WebSocket protocol parsers, and test cases.
- Key files: `backend/main.go`, `backend/hub/hub.go`.

## Key File Locations

**Entry Points:**
- `app/src/main/java/com/music/bitchord/BitChordApplication.kt`: Application class and dependency setup.
- `app/src/main/java/com/music/bitchord/MainActivity.kt`: Android Activity entry point for user UI.
- `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt`: Service entry point for audio and media session.
- `backend/main.go`: Entry point for Listen Together server.

**Configuration:**
- `build.gradle.kts`: Root Gradle build script with R8 `8.13.23` compiler fix.
- `app/build.gradle.kts`: Android packaging, SDK limits, dependency graph.
- `gradle.properties`: JVM arguments, AndroidX flags, Kotlin daemon config.
- `AndroidManifest.xml` (`app/src/main/AndroidManifest.xml`): App permissions, services, widget receivers.

**Core Logic:**
- `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt`: Core playback orchestrator.
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/theme/ArtworkPalette.kt`: Cover art color extraction engine.
- `app/src/main/java/com/music/bitchord/data/innertube/Innertube.kt`: YouTube Music search and stream resolution.

**Testing:**
- `app/src/test/java/com/music/bitchord/`: Unit tests for playback, queue, and network logic.
- `sharedUi/src/jvmTest/kotlin/com/music/bitchord/`: Tests for lyrics sync, artwork palette, and UI state.

## Naming Conventions

**Files:**
- Kotlin Source Files: PascalCase matching primary class/composable (e.g. `NowPlayingScreen.kt`, `PlaybackService.kt`).
- Tests: Suffix with `Test` (e.g. `AudioPipelineTest.kt`, `ArtworkPaletteTest.kt`).
- Layout / XML Resources: snake_case (e.g. `widget_crystal_vinyl.xml`, `ic_launcher_foreground.xml`).

**Directories:**
- Package Folders: lowercase single words or combined words without hyphens (e.g. `playback`, `sharedUi`, `listentogether`).

## Where to Add New Code

**New UI Feature / Screen:**
- Primary code: Create composable under `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/`
- State integration: Wire into `app/src/main/java/com/music/bitchord/ui/MainViewModel.kt`
- Tests: Add corresponding test in `sharedUi/src/jvmTest/`

**New Audio Source / Provider:**
- Implementation: Add provider in `app/src/main/java/com/music/bitchord/data/sources/`
- Registration: Register provider in `SourceRegistry.kt`
- Tests: Unit tests in `app/src/test/java/com/music/bitchord/SourcesTest.kt`

**New Audio DSP Filter:**
- Implementation: Implement `AudioProcessor` under `app/src/main/java/com/music/bitchord/playback/audio/`
- Pipeline Hook: Attach processor to `PlaybackService.kt` custom audio sink pipeline
- Tests: Unit tests under `app/src/test/java/com/music/bitchord/playback/audio/`

**Shared Helper / Utility:**
- Platform-agnostic: Place in `shared/src/commonMain/kotlin/`
- UI-related helpers: Place in `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/utils/`

## Special Directories

**`app/src/main/jniLibs/arm64-v8a/`:**
- Purpose: Contains precompiled native shared libraries (including `libffmpegJNI.so`).
- Generated: Yes (built via native compilation scripts).
- Committed: Yes.

**`backend/`:**
- Purpose: Standalone Go server for the Listen Together feature.
- Generated: No.
- Committed: Yes.

---

*Structure analysis: 2026-11-07*
