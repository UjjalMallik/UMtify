---
phase: "1"
slug: "ui-rendering-and-performance-optimization"
status: draft
nyquist_compliant: true
wave_0_complete: true
created: "2026-11-07"
---

# Phase 1 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|---|---|
| **Framework** | JUnit 4 + Kotlin Multiplatform Test (`kotlin.test`) |
| **Config file** | `sharedUi/build.gradle.kts` and `app/build.gradle.kts` |
| **Quick run command** | `./gradlew :sharedUi:jvmTest` |
| **Full suite command** | `./gradlew :sharedUi:jvmTest :app:testProdDebugUnitTest` |
| **Estimated runtime** | ~25 seconds |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew :sharedUi:jvmTest`
- **After every plan wave:** Run `./gradlew :sharedUi:jvmTest :app:testProdDebugUnitTest`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** ~30 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---|---|---|---|---|---|---|---|---|---|
| 01-01-01 | 01 | 1 | REQ-PERF-01 | — | N/A | unit | `./gradlew :sharedUi:jvmTest` | ✅ | ⬜ pending |
| 01-01-02 | 01 | 1 | REQ-PERF-02 | — | N/A | unit | `./gradlew :sharedUi:jvmTest` | ✅ | ⬜ pending |
| 01-02-01 | 02 | 1 | REQ-PERF-03 | — | N/A | unit | `./gradlew :sharedUi:jvmTest` | ✅ | ⬜ pending |
| 01-02-02 | 02 | 1 | REQ-PERF-04 | — | N/A | unit | `./gradlew :sharedUi:jvmTest` | ✅ | ⬜ pending |

---

## Wave 0 Requirements

Existing test infrastructure in `sharedUi/src/jvmTest/kotlin` and `app/src/test/java` covers all phase requirements.

---

## Manual-Only Verifications

| Behavior | Why Manual | Verification Procedure |
|---|---|---|
| 120Hz smooth scrolling without frame drop | Requires physical device display refresh profiling | Scroll `HomeScreen` feeds rapidly and observe GPU profiling bars |
| MeshGradient animation smoothness | Visual shader quality and GPU frame pacing | Open `NowPlayingScreen`, inspect fluid wave ribbon while audio plays |
