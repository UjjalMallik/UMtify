---
last_mapped_commit: cec238fda333abee0a65060f20cbb71cdce336fb
last_mapped_at: 2026-11-07
---
# Coding Conventions

**Analysis Date:** 2026-11-07

## Naming Patterns

**Files:**
- PascalCase for Kotlin files and components: `NowPlayingScreen.kt`, `PlaybackService.kt`, `AudioCache.kt`
- lowercase snake_case for Android resources: `banner.jpg`, `ic_launcher.xml`

**Functions:**
- camelCase for standard methods: `playTrack()`, `resolveStreamUrl()`, `updatePlaybackState()`
- PascalCase for `@Composable` functions: `NowPlayingScreen()`, `MeshGradientBackground()`, `PlayerControls()`

**Variables & Properties:**
- camelCase for standard variables: `currentPosition`, `isBuffering`, `audioFocusLoss`
- SCREAMING_SNAKE_CASE for compile-time constants and keys: `TAG`, `DEFAULT_SERVER`, `MAX_CACHE_BYTES`

**Types & Classes:**
- PascalCase for classes, interfaces, objects, and sealed classes: `MainViewModel`, `AudioProcessor`, `StreamChoice`

## Code Style

**Formatting:**
- Standard Kotlin style guide with 4-space indentation
- Trailing commas preferred in multi-line parameter and argument lists
- Explicit return types on public API functions and ViewModel state properties

**Linting:**
- Android Lint integrated in Gradle build (`./gradlew lint`)
- Kotlin compiler warning checks enabled; custom suppressions used only with explanatory docstrings (e.g. `@OptIn(UnstableApi::class)`)

## Import Organization

**Order:**
1. Java and Android SDK packages (`java.*`, `android.*`, `androidx.*`)
2. Third-party libraries (`coil3.*`, `io.ktor.*`, `dev.chrisbanes.haze.*`)
3. Internal project multiplatform packages (`com.music.bitchord.*`)
4. Kotlin runtime and coroutines (`kotlin.*`, `kotlinx.coroutines.*`)

**Path Aliases:**
- Standard multi-module dependencies via Gradle project paths (`project(":shared")`, `project(":sharedUi")`)

## Error Handling

**Patterns:**
- Silent fallback with recovery: When network requests fail, fall back to offline cached streams or alternate quality tiers.
- `runCatching` blocks used for parsing untrusted network payloads or JSON responses.
- Explicit try-catch wrapping for hardware-dependent features (e.g. RenderEffect blur fallback, USB DAC direct routing).

```kotlin
// Example pattern observed in codebase
val streamUrl = runCatching {
    innertube.resolveStream(trackId)
}.getOrElse { error ->
    DebugLog.w(TAG, "Innertube resolution failed, trying fallback: ${error.message}")
    newPipeFallback.resolveStream(trackId)
}
```

## Logging

**Framework:**
- Custom `DebugLog.kt` wrapper over `android.util.Log`
- Tagged with class/component constants (`private const val TAG = "PlaybackService"`)

**Patterns:**
- `DebugLog.d(TAG, "...")` for lifecycle and playback transitions
- `DebugLog.e(TAG, "...", throwable)` for network and audio decoder failures
- No sensitive user tokens, passwords, or cookies are logged

## Comments

**When to Comment:**
- Detailed architectural rationales preceding tricky workarounds (e.g., explaining why a specific R8 version is pinned in `build.gradle.kts` or why DASH manifests need `media3-exoplayer-dash`).
- Clear function docstrings detailing thread safety requirements and lifecycle constraints.

**KDoc / Documentation:**
- Public methods and shared models use standard KDoc `/** ... */` annotations describing parameters and side-effects.

## Function Design

**Size:**
- Modular functions adhering to single-responsibility principles. Large composables (like `NowPlayingScreen.kt`) are split into sub-composables (`PlayerControls`, `WavySeekBar`, `SyncedLyricsPanel`).

**Parameters:**
- Default argument values used for optional UI styling and configuration flags.
- State and event callbacks hoisted to caller (`onPlayPause: () -> Unit`, `onSeek: (Long) -> Unit`).

**Return Values:**
- Immutability favored: ViewModels expose immutable `StateFlow<UiState>` rather than mutable references.

## Module Design

**Exports:**
- `:shared` exports pure domain models without Android or Compose dependencies.
- `:sharedUi` encapsulates Compose Multiplatform UI components.
- `:app` acts as the platform orchestrator integrating native services, notifications, and widgets.

---

*Convention analysis: 2026-11-07*
