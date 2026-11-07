# Requirements: UMtify Performance & UI Optimization

## Overview

Targeting zero-jank, 120Hz-paced UI rendering across all primary screens in UMtify.

## Requirements

### Active

- [ ] **REQ-PERF-01**: Optimize `NowPlayingScreen` recomposition scope. Decompose monolithic parameter lists into `@Stable` / `@Immutable` state holders and isolate the scrubbing seekbar.
- [ ] **REQ-PERF-02**: Profile and streamline `MeshGradient` and wave ribbon animation loop. Ensure animation tick allocations are zeroed and evaluate canvas shaders efficiently.
- [ ] **REQ-PERF-03**: Optimize scrolling performance on `HomeScreen`, `LibraryScreen`, and `SearchScreen`. Apply stable keys, derived state bounds, and item content type hints to `LazyColumn` / `LazyVerticalGrid`.
- [ ] **REQ-PERF-04**: Audit Haze blur layers and RenderEffect application, ensuring smooth fallback on devices where blur evaluation taxes the GPU.
