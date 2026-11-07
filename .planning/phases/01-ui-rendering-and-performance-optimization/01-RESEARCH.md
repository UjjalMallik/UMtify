# Phase 1: UI Rendering and Performance Optimization - Research

**Researched:** 2026-11-07
**Domain:** Jetpack Compose & Compose Multiplatform 1.10 rendering, 120Hz frame pacing, RenderEffect shaders, and list recycling
**Confidence:** HIGH

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- Target: 120Hz smooth rendering across player and navigation flows.
- Optimize `NowPlayingScreen.kt` and `PlayerControls.kt` recomposition trees.
- Streamline `MeshGradient.kt` dynamic album artwork palette shaders and fluid wave animations.
- Enforce stable item keys and content types across `HomeScreen`, `LibraryScreen`, and `SearchScreen` lazy lists.
- Optimize Haze blur application and RenderEffect boundaries.

### the agent's Discretion
- Exact refactoring of parameter groups into `@Stable` / `@Immutable` state holder interfaces.
- Allocation reduction inside Compose Canvas draw scopes.
- Specific thumbnail pre-sizing and layout measure caching.
</user_constraints>

<architectural_responsibility_map>
## Architectural Responsibility Map

Single-tier Android client application — all capabilities reside in the Presentation and Compose UI layer (`sharedUi/` and `app/src/main/java/com/music/bitchord/ui/`).
</architectural_responsibility_map>

<research_summary>
## Summary

This research investigates the critical performance bottlenecks affecting UI rendering fluidity in UMtify:
1. **Compose Recomposition Overhead:** `NowPlayingScreen.kt` is a large composable (~5,500 dex instructions). Passing unstable types or rapidly changing playback progress directly as arguments triggers frequent recomposition passes across child controls. Wrapping progress updates into lambda providers (`() -> Float`) and grouping arguments into `@Stable` state holders confines recomposition strictly to the specific widget that requires invalidation.
2. **Shader and Canvas Draw-Scope Allocations:** `MeshGradient.kt` and wave animations run within high-frequency draw loops. Allocating `Offset` lists, `Path` instances, or color arrays inside the `Canvas { ... }` draw lambda generates garbage collection pressure that causes stutter at 120Hz. Pre-allocating reusable geometry and reading animatables directly in the draw phase bypasses composition invalidation entirely.
3. **Lazy List Optimization:** `HomeScreen`, `LibraryScreen`, and `SearchScreen` rely on `LazyColumn` carousels and shelves. Supplying explicit `contentType` alongside deterministic `key` parameters allows Compose to recycle item compositions efficiently during rapid scrolling flings. Fixed-dimension image containers prevent asynchronous layout reflows as thumbnails load.

**Primary recommendation:** Decouple high-frequency state (playback position, animated phases) from composition by reading state in the draw/layout phase, zero allocations in draw lambdas, and enforce strict item keys/content types on all lazy lists.
</research_summary>

<standard_stack>
## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---|---|---|---|
| Jetpack Compose Foundation / UI | 1.10.0 / 1.10.3 | Reactive declarative UI | Primary Android UI toolkit |
| Compose Multiplatform Runtime | 1.10.3 | State snapshot & compiler plugin | Governs recomposition and state stability |
| Dev Chrisbanes Haze | 1.3.1 | Frosted glass & progressive blur | GPU RenderEffect abstraction with scrim fallbacks |
| Coil 3 | 3.0.4 | Async image pipeline | Optimized memory caching for artwork thumbnails |

### Key Optimizations
- **Recomposition Skipping:** Ensure all data model parameters passed into composables are `@Immutable` or `@Stable`.
- **Draw Phase State Reads:** Use `Modifier.graphicsLayer { ... }` and `Modifier.drawWithContent { ... }` to defer state reads to the draw phase, bypassing recomposition and relayout passes.
- **Zero Allocations in Draw Scope:** Move all `listOf()`, `Path()`, and temporary geometry objects outside `Canvas { ... }` lambdas.
</standard_stack>

<architecture_patterns>
## Architecture Patterns

### High-Frequency State Isolation Pattern

```text
Playback Progress / Animated Phase (Updates at 60Hz - 120Hz)
       │
       ▼
State Holder (StateFlow or SnapshotState)
       │
       ├──► Standard Composable (DOES NOT RECOMPOSE — receives () -> Float lambda)
       │
       └──► Canvas Draw Scope / graphicsLayer (DEFERRED READ)
               │
               ▼
       Direct GPU / RenderNode invalidation (Zero Composition Cost)
```

### Don'ts and Pitfalls
- **Don't** read `phase.value` or `positionMillis` at the top level of `NowPlayingScreen()` or `PlayerControls()`. Read them inside `Canvas` or `Modifier.graphicsLayer` blocks.
- **Don't** create `val anchors = listOf(Offset(...))` inside `Canvas { ... }`.
- **Don't** omit `contentType` on `LazyColumn` items when items have different visual structures (e.g. headers vs cards vs lists).
</architecture_patterns>

---

*Research complete: 2026-11-07*
