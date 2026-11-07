# Phase 1 Context: UI Rendering and Performance Optimization

**Phase Target:** Optimize UI rendering and performance for UMtify to make it smoother.

## User Goals & Requirements
- Target: 120Hz buttery smooth rendering across player and navigation flows.
- Recomposition Minimization: Ensure `NowPlayingScreen.kt` and `PlayerControls.kt` avoid unnecessary recompositions during playback ticks, seek events, and lyrics updates.
- Shader Frame Pacing: Optimize `MeshGradient.kt` dynamic album artwork palette shaders and fluid wave animations to avoid GPU drops and frame jitter.
- List Smoothness: Enforce stable item keys and content types across `HomeScreen`, `LibraryScreen`, and `SearchScreen` lazy lists.
- Blur Efficiency: Optimize Haze blur application and RenderEffect boundaries so low-end devices do not drop frames.
