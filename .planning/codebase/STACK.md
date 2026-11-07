---
last_mapped_commit: cec238fda333abee0a65060f20cbb71cdce336fb
last_mapped_at: 2026-11-07
---
# Technology Stack

**Analysis Date:** 2026-11-07

## Languages

**Primary:**
- Kotlin 2.3.20 - Android app (`app/`), multiplatform core logic (`shared/`), and shared Compose UI (`sharedUi/`)

**Secondary:**
- Go 1.22+ - Backend Listen Together server (`backend/main.go`, `backend/go.mod`)
- C++ (C++20) / C - Native audio DSP and FFmpeg JNI bridge (`app/src/main/cpp/`, `app/src/main/jniLibs/arm64-v8a/libffmpegJNI.so`)
- Python 3 - CI maintenance script for contributors graphic (`.github/scripts/contributors_svg.py`)

## Runtime

**Environment:**
- Android SDK (compileSdk 37, targetSdk 36, minSdk 26)
- JVM target: Java 17 (`JvmTarget.JVM_17`)
- Android Gradle Plugin (AGP) 8.10.1 with R8 `8.13.23` compiler override in root `build.gradle.kts`

**Package Manager:**
- Gradle 8.11.1 / 9.x via Gradle Wrapper (`gradlew`, `gradle/wrapper/gradle-wrapper.properties`)
- Lockfile: Not enforced (standard Gradle dependency resolution with Maven Central & Google mirrors)

## Frameworks

**Core:**
- Jetpack Compose / Compose Multiplatform 1.10.3 (`androidx.compose.*`, `org.jetbrains.compose`) - Declarative UI
- Material 3 Compose 1.3.1 / 1.7.3 (`androidx.compose.material3`) - Theming and components
- AndroidX Media3 / ExoPlayer 1.11.0 (`androidx.media3:media3-exoplayer`, `media3-session`) - Audio streaming, HLS, DASH, playback service
- Ktor 3.5.2 (`io.ktor:ktor-client-core`, `io.ktor:ktor-client-okhttp`, `io.ktor:ktor-client-websockets`) - HTTP client & WebSocket networking
- kotlinx.coroutines 1.10.2 - Concurrency & asynchronous programming
- kotlinx.serialization 1.11.0 - JSON serialization

**Testing:**
- JUnit 4 (`junit:junit:4.13.2`) & AndroidX JUnit Test Runner (`androidx.test.ext:junit`) - Unit and instrumentation testing
- MockWebServer 5.3.2 (`com.squareup.okhttp3:mockwebserver`) - HTTP request mocking
- Kotlin Test (`kotlin.test`) - Multiplatform unit assertions

**Build/Dev:**
- Android Gradle Plugin 8.10.1 (`com.android.application`, `com.android.library`)
- Kotlin Multiplatform Plugin (`org.jetbrains.kotlin.multiplatform`)
- Kotlin Compose Compiler Plugin (`org.jetbrains.kotlin.plugin.compose`)
- ProGuard / R8 (`com.android.tools:r8:8.13.23`) with full shrinking, obfuscation, and baseline profile optimizations (`app/src/main/baseline-prof.txt`)

## Key Dependencies

**Critical:**
- `innertubex-android` (`com.github.MetrolistGroup.innertubex:innertubex-android:v0.7.0`) - YouTube Music client metadata and stream extraction
- `NewPipeExtractor` (`v0.26.3`) - Fallback YouTube stream cipher / signature resolution
- `dev.chrisbanes.haze:haze:1.3.1` & `haze-materials:1.3.1` - Frosted glass and real-time RenderEffect blur
- `io.coil-kt.coil3:coil-compose:3.0.4` - Async image loading with SVG and OkHttp support
- `com.microsoft.onnxruntime:onnxruntime-android:1.28.0` - On-device AI/ML model for Automix beat and downbeat tracking ("Beat This!")
- `com.hierynomus:smbj:0.15.0` - SMB2/SMB3 network share streaming and directory browsing
- `androidx.security:security-crypto:1.1.0-alpha06` - EncryptedSharedPreferences for tokens and cookies
- `io.github.dokar3:quickjs-kt-android:1.0.14` - Embedded QuickJS VM for running modular JS stream plugins

**Infrastructure:**
- `com.google.android.gms:play-services-cast-framework:22.2.0` & `androidx.mediarouter:mediarouter:1.8.1` - Google Cast streaming
- `com.google.android.gms:play-services-wearable:20.0.1` - Wear OS companion watch sync
- `com.google.zxing:core:3.5.3` - QR code matrix generation for Listen Together sessions

## Configuration

**Environment:**
- Configured via Android `gradle.properties`, `local.properties`, and system environment variables
- `LISTEN_TOGETHER_SERVER` sets the party relay address (defaults to `https://bitchord-listen-together.onrender.com`)
- `GITHUB_RUN_NUMBER` and `VERSION_NAME` control build numbering and version strings

**Build:**
- Root `build.gradle.kts` & `settings.gradle.kts`
- Module configurations: `app/build.gradle.kts`, `shared/build.gradle.kts`, `sharedUi/build.gradle.kts`
- Keystore config: `keystore.properties.example` for signing release APKs

## Platform Requirements

**Development:**
- JDK 17 (or JDK 21 configured with Java 17 toolchain)
- Android SDK 37 (platform-tools, build-tools 36+, CMake for native components)
- NDK version 27.2.12479018 (configured in `app/build.gradle.kts`)

**Production:**
- Android 8.0+ (API level 26+), strictly 64-bit ARM architecture (`arm64-v8a`)
- RenderEffect blur activates on Android 12+ (API 31+); fallback scrims on API 26-30

---

*Stack analysis: 2026-11-07*
