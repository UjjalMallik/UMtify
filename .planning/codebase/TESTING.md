---
last_mapped_commit: cec238fda333abee0a65060f20cbb71cdce336fb
last_mapped_at: 2026-11-07
---
# Testing Patterns

**Analysis Date:** 2026-11-07

## Test Framework

**Runner:**
- JUnit 4 (`junit:junit:4.13.2`) for JVM unit tests
- Kotlin Multiplatform Test (`kotlin.test`) for shared multiplatform modules
- AndroidX Test Runner (`androidx.test.runner.AndroidJUnitRunner`) for instrumentation tests
- Go testing package (`testing`) for backend unit and integration tests

**Assertion Library:**
- `kotlin.test.assertEquals`, `assertTrue`, `assertNotNull`
- JUnit 4 `Assert` methods

**Run Commands:**
```bash
./gradlew test                                   # Run all unit tests across all modules
./gradlew :app:testProdDebugUnitTest             # Run Android app unit tests
./gradlew :sharedUi:jvmTest                      # Run shared UI tests
cd backend && go test ./...                      # Run Listen Together backend tests
```

## Test File Organization

**Location:**
- Multi-module mirrored directory layout:
  - `app/src/test/java/com/music/bitchord/`
  - `shared/src/commonTest/kotlin/com/music/bitchord/`
  - `sharedUi/src/jvmTest/kotlin/com/music/bitchord/`
  - `backend/main_test.go`

**Naming:**
- Suffix with `Test` matching the target class (e.g. `AudioPipelineTest.kt`, `ArtworkPaletteTest.kt`, `QueueCoordinatorTest.kt`).

**Structure:**
```
app/src/test/java/com/music/bitchord/
├── AudioPipelineTest.kt
├── DownloadStoreTest.kt
├── EqualizerProcessorTest.kt
├── InnertubeTest.kt
├── JamInviteLinkTest.kt
├── QueueCoordinatorTest.kt
└── playback/
    └── AudioOutputPolicyTest.kt
```

## Test Structure

**Suite Organization:**
```kotlin
package com.music.bitchord

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueueCoordinatorTest {

    @Test
    fun testQueueOrderPreservation() {
        // Given
        val initialTracks = listOf("track-1", "track-2", "track-3")
        val coordinator = QueueCoordinator(initialTracks)

        // When
        coordinator.move(from = 0, to = 2)

        // Then
        assertEquals(listOf("track-2", "track-3", "track-1"), coordinator.currentTracks)
    }
}
```

**Patterns:**
- Given-When-Then / Arrange-Act-Assert structure
- Pure functions and deterministic components tested in isolation without Android framework dependencies
- Time-dependent tests use mock clocks or synthetic timestamps (`ServerClockTest.kt`)

## Mocking

**Framework:**
- `okhttp3.mockwebserver.MockWebServer` for simulating external HTTP APIs and stream responses.
- In-memory fake repositories and stubbed data providers.

**Patterns:**
```kotlin
val server = MockWebServer()
server.enqueue(MockResponse().setBody("""{"status": "ok"}"""))
server.start()
val client = ApiClient(server.url("/").toString())
```

**What to Mock:**
- External network requests (YouTube Music, LRCLIB, Spotify Canvas endpoints).
- File system I/O when validating parsing algorithms.

**What NOT to Mock:**
- Core data models and data structures.
- Audio DSP mathematics, pitch/timing algorithms, and queue sorting logic.

## Fixtures and Factories

**Test Data:**
- Synthetic audio tracks, mock playlist JSON payloads, and test LRC subtitle files located in test resources.
- Factory helper functions used to generate test track entities with customizable durations and stream URLs.

## Coverage

**Requirements:**
- High test coverage across core audio playback algorithms, queue managers, and protocol handshakes.
- Critical path coverage: Queue coordination, audio DSP chains, lyrics synchronization, and party protocol synchronization.

## Test Types

**Unit Tests:**
- Business logic in `shared/`, ViewModel state mutations, queue operations, format ladder resolutions.

**Integration Tests:**
- End-to-end WebSocket roundtrips between Listen Together client and Go backend (`backend/main_test.go`).
- Audio pipeline telemetry and output negotiator tests.

**E2E / Instrumentation Tests:**
- Android Auto MediaLibraryService contract tests (`AndroidAutoMediaLibraryTest.kt`).

---

*Testing analysis: 2026-11-07*
