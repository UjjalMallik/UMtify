# Roadmap: UMtify

## Overview

Roadmap focused on system optimization, rendering smoothness, and frame-pacing guarantees across UMtify.

## Phases

- [ ] **Phase 1: UI Rendering and Performance Optimization** - Audit and optimize Compose recompositions, dynamic mesh gradient shaders, and list scrolling for buttery 120Hz playback.

## Phase Details

### Phase 1: UI Rendering and Performance Optimization
**Goal**: Eliminate UI jank, optimize Compose recomposition trees, isolate animation loops, and ensure 120Hz smooth rendering across player and navigation screens.
**Depends on**: Nothing
**Requirements**: REQ-PERF-01, REQ-PERF-02, REQ-PERF-03, REQ-PERF-04
**Success Criteria** (what must be TRUE):
  1. `NowPlayingScreen` and `PlayerControls` do not trigger full screen recompositions during seek/playback progress updates.
  2. `MeshGradient` and liquid wave ribbons animate at 120Hz without dropped frames or garbage collection spikes.
  3. Scrolling lists on `HomeScreen`, `LibraryScreen`, and `SearchScreen` maintain 120Hz without stutter during rapid flings.
  4. Haze blur and RenderEffect degrade gracefully without visual artifacting or frame stutter on lower-end GPUs.
**Plans**: 2 plans

Plans:
- [ ] 01-01: Now Playing screen recomposition isolation, scrubbing optimization, and MeshGradient frame pacing
- [ ] 01-02: Lazy list key/content-type optimization across Home, Library, and Search screens with Haze performance tuning
