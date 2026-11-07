# UMtify

## What This Is

UMtify is an Android-only, high-performance music streaming and local audio player built around Material 3 and Liquid Crystal Glass aesthetics. It offers seamless YouTube Music streaming, local library playback, and dynamic cover-art-driven themes.

## Core Value

Buttery smooth, distraction-free 120Hz listening experience with crystal glass surfaces and instantaneous responsiveness.

## Requirements

### Validated

- ✓ Android MediaLibraryService background playback with Media3 ExoPlayer (`app/src/main/java/com/music/bitchord/playback/PlaybackService.kt`) — existing
- ✓ Dynamic cover art tonal palette extraction and `MeshGradient` rendering (`sharedUi/src/jvmSharedMain/.../ArtworkPalette.kt`) — existing
- ✓ YouTube Music streaming and search via InnerTubeX and NewPipe cipher resolution — existing
- ✓ Synchronized and unsynced lyrics from LRCLIB, Musixmatch, and KuGou — existing
- ✓ Frosted glass surfaces with Chris Banes Haze and RenderEffect blurs — existing

### Active

- [ ] **PERF-01**: Eliminate unnecessary recompositions and stabilize state holders across `NowPlayingScreen` and `PlayerControls`.
- [ ] **PERF-02**: Optimize `MeshGradient` shader rendering, wave ribbon frame pacing, and animate seamlessly at 120Hz without GPU jitter.
- [ ] **PERF-03**: Optimize lazy list layout and item caching across `HomeScreen`, `LibraryScreen`, and `SearchScreen` for zero-stutter scrolling.
- [ ] **PERF-04**: Establish low-end hardware fallbacks and verify RenderEffect blur boundaries under resource constraints.

### Out of Scope

- Major feature additions or redesigns before performance targets are locked in.
- Non-ARM64 architecture support (project remains strictly optimized for `arm64-v8a`).

## Context

- Android targetSdk 36, compileSdk 37, minSdk 26.
- Compose Multiplatform 1.10.3 and Kotlin 2.3.20.
- `NowPlayingScreen.kt` is a large composable (~5,500 dex instructions) that requires lean parameter structures to maintain smooth frame rates.

## Key Decisions

| Decision | Rationale | Impact |
|---|---|---|
| Focus Phase 1 on UI Performance & Smoothness | User priority is to make UI rendering fluid and smooth across all devices | Direct responsiveness and 120Hz frame pacing |
