---
last_mapped_commit: cec238fda333abee0a65060f20cbb71cdce336fb
last_mapped_at: 2026-11-07
---
# Codebase Concerns

**Analysis Date:** 2026-11-07

## Tech Debt

**D8/R8 Register Allocation & Compiler Override:**
- Issue: `NowPlayingScreen.kt` is large (~36 parameters, ~5,500 dex instructions, >v287 registers), hitting a register-allocation bug in Android Gradle Plugin's default D8/R8 during debug dexing (`VerifyError` on `MeshGradientBackground`).
- Files: `build.gradle.kts:7-34`, `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt`
- Impact: Dev builds crash on player composition unless R8 is explicitly overridden to version `8.13.23`.
- Fix approach: Refactor `NowPlayingScreen.kt` to decompose parameters into scoped state holder objects and reduce method body complexity; remove R8 override once AGP defaults to R8 >= 8.11.32.

**NewPipeExtractor Jar Stripping & Pinning:**
- Issue: Pinned strictly to `v0.26.3` and ingested via custom jar-stripping task (`newPipeExtractorStripped`) because `v0.26.4` fails to parse current YouTube player JS deobfuscation functions, and NewPipe's bundled `Utils.class` conflicts with dexing.
- Files: `app/build.gradle.kts:391-411`
- Impact: Dependency upgrades must be thoroughly tested against live YouTube player JS to avoid breaking ciphered streaming formats.
- Fix approach: Transition entirely to InnerTubeX as primary cipher resolver once it covers all cipher tiers.

## Known Bugs

**DPI and Screen Scaling Edge Cases:**
- Symptoms: Translucent glass pill or single-row action bar elements may crowd on extreme non-standard density screens if user scales font beyond 135%.
- Files: `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerControls.kt`
- Trigger: Custom OS-level display zoom combined with app-level 135% font scale slider.
- Workaround: App provides live Font & Display Size sliders in Settings under Appearance to readjust scaling.

## Security Considerations

**Token Storage in SharedPreferences:**
- Risk: Session cookies and user tokens could be extracted if device is compromised or rooted.
- Files: `app/src/main/java/com/music/bitchord/auth/EncryptedPrefs.kt`, `AuthStore.kt`
- Current mitigation: Uses `androidx.security.crypto.EncryptedSharedPreferences` with AES256-GCM encryption and Android Keystore backed keys.
- Recommendations: Continue maintaining fallback to plain prefs with caution for devices with broken OEM Keystore implementations.

## Performance Bottlenecks

**Dynamic Mesh Gradient & Haze Blur:**
- Problem: Live multi-point mesh gradients combined with real-time Compose blur (`RenderEffect`) can drop frames on older GPUs.
- Files: `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/MeshGradient.kt`, `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/theme/ArtworkPalette.kt`
- Cause: Continuous per-frame shader evaluation and dynamic color extraction during active playback.
- Improvement path: Palette extraction is cached per track; "Reduce animation" toggle pauses background wave transitions and simplifies shader rendering.

## Fragile Areas

**YouTube Stream Cipher Changes:**
- Files: `app/src/main/java/com/music/bitchord/data/innertube/Innertube.kt`
- Why fragile: YouTube regularly rotates player JavaScript obfuscation and client parameters (`WEB_REMIX`, `ANDROID_MUSIC`), which can break streaming without warning.
- Safe modification: Always test stream playback with both unthrottled and ciphered audio streams across multiple YouTube client personas.
- Test coverage: `app/src/test/java/com/music/bitchord/SourcesTest.kt`, `StreamChoiceTest.kt`.

**Strict Architecture Packaging (arm64-v8a Only):**
- Files: `app/build.gradle.kts`
- Why fragile: The build is hard-configured for 64-bit ARM architectures. Attempting to run on 32-bit devices or x86 emulators will fail due to native FFmpeg JNI binaries.
- Safe modification: Retain ABI filtering for release APKs while maintaining emulator compatibility flags if needed for dev setups.

## Scaling Limits

**Listen Together Party Room Concurrency:**
- Current capacity: Handled via single Go process on free-tier Render deployment (`backend/main.go`).
- Limit: WebSocket connections and memory scaling capped by Render container limits.
- Scaling path: Horizontal scaling with Redis pub/sub backplane for cross-instance messaging.

## Dependencies at Risk

**Innertubex & NewPipeExtractor:**
- Risk: Upstream API changes from YouTube Music can deprecate extraction endpoints.
- Impact: Playback failure for streaming tracks.
- Migration plan: Modular audio source architecture allows falling back to alternative sources (JioSaavn, local files, WebDAV, SMB).

## Test Coverage Gaps

**Physical Hardware Audio Sinks:**
- What's not tested: Bluetooth LDAC/AptX codec switching and USB DAC direct streaming mode cannot be exercised in standard CI environments.
- Files: `app/src/main/java/com/music/bitchord/playback/audio/usb/`, `bluetooth/`
- Risk: Edge-case regressions on specific Android OEM audio HAL implementations.
- Priority: Medium (requires on-device regression testing during release qualification).

---

*Concerns audit: 2026-11-07*
