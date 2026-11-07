---
last_mapped_commit: cec238fda333abee0a65060f20cbb71cdce336fb
last_mapped_at: 2026-11-07
---
# External Integrations

**Analysis Date:** 2026-11-07

## APIs & External Services

**Music Streaming & Metadata:**
- **YouTube Music / Google Innertube:**
  - Client / SDK: InnerTubeX (`com.github.MetrolistGroup.innertubex:innertubex-android:v0.7.0`) and NewPipeExtractor (`v0.26.3`)
  - Implementation: `app/src/main/java/com/music/bitchord/data/innertube/Innertube.kt`
  - Auth: Optional user account cookies managed via `app/src/main/java/com/music/bitchord/auth/AuthStore.kt`
- **Spotify Canvas & Library Import:**
  - Client / SDK: Custom OkHttp/Ktor integration (`app/src/main/java/com/music/bitchord/data/spotify/` and `canvas/`)
  - Auth: Spotify web token extraction / SP_DC cookie
- **JioSaavn:**
  - Client: `app/src/main/java/com/music/bitchord/data/jiosaavn/` for regional streaming catalog
- **Modular Audio Source Addons:**
  - Tidal, Qobuz, and other providers via QuickJS runtime scripts (`app/src/main/java/com/music/bitchord/data/sources/`)

**Lyrics Providers:**
- **LRCLIB:** Open synced lyrics API (`app/src/main/java/com/music/bitchord/data/lyrics/LrcLib.kt`)
- **Musixmatch:** Rich synchronized and translated lyrics (`app/src/main/java/com/music/bitchord/data/lyrics/Musixmatch.kt`)
- **KuGou:** Syllable-timed and word-sync lyrics API (`app/src/main/java/com/music/bitchord/data/lyrics/KuGou.kt`)
- **Genius:** Unsynced lyrics and song annotation fallback (`app/src/main/java/com/music/bitchord/data/lyrics/Genius.kt`)

**Scrobbling Services:**
- **Last.fm:** Scrobble API 2.0 with md5 auth digest (`app/src/main/java/com/music/bitchord/data/scrobbling/LastFmScrobbler.kt`)
- **ListenBrainz:** Scrobble JSON API (`app/src/main/java/com/music/bitchord/data/scrobbling/ListenBrainzScrobbler.kt`)
- **Libre.fm:** GNU social / Libre.fm scrobbling endpoint

**Rich Presence & Social:**
- **Discord:** Discord Rich Presence via WebSocket IPC/gateway (`app/src/main/java/com/music/bitchord/data/discord/DiscordRpc.kt`)
- **Listen Together:** Real-time synchronized listening rooms via Go backend (`backend/` and `app/src/main/java/com/music/bitchord/playback/PartySync.kt`)

**Network Audio Shares:**
- **SMB (Windows Share / Samba):** Listing and streaming via `smbj` (`app/src/main/java/com/music/bitchord/data/smb/`)
- **WebDAV:** HTTP/HTTPS remote folder mounting (`app/src/main/java/com/music/bitchord/data/webdav/`)

## Data Storage

**Databases & Key-Value:**
- **Preferences:** Android `SharedPreferences` for UI preferences and playback state
- **Encrypted Keystore:** `androidx.security.crypto.EncryptedSharedPreferences` for credentials, cookies, and tokens (`app/src/main/java/com/music/bitchord/auth/EncryptedPrefs.kt`)
- **Song Metadata Overrides:** JSON-persisted file storage (`app/src/main/java/com/music/bitchord/data/SongEditStore.kt`)
- **Playlists & Offline Cache Registry:** Local JSON and manifest records (`app/src/main/java/com/music/bitchord/download/Downloads.kt`)

**File Storage:**
- Android App Private Storage: Internal files and external app-specific directories (`Android/data/com.music.umtify/files`)
- Downloads: Local audio files and `.m3u8` / `.mpd` offline chunk storage
- MediaStore: Querying local on-device music via ContentResolver (`app/src/main/java/com/music/bitchord/data/LocalMediaRepository.kt`)

**Caching:**
- Audio Cache: ExoPlayer SimpleCache with custom dynamic LRU eviction ceiling (`app/src/main/java/com/music/bitchord/playback/AudioCache.kt`)
- Image Cache: Coil3 DiskCache and MemoryCache with crossfade support (`app/src/main/java/com/music/bitchord/BitChordApplication.kt`)
- Video Canvas Cache: File-based LRU cache for MP4/HLS canvas loops (`app/src/main/java/com/music/bitchord/data/canvas/CanvasCache.kt`)

## Authentication & Identity

**Auth Provider:**
- Custom multi-service authentication
  - Google / YouTube Music: Embedded web-view cookie extraction
  - Spotify: SP_DC cookie exchange
  - Last.fm / ListenBrainz: User API key and token handshakes

## Monitoring & Observability

**Error Tracking:**
- Local runtime logging (`app/src/main/java/com/music/bitchord/data/DebugLog.kt`) and logcat output
- No third-party crash reporting SDKs (privacy-first, tracker-free design)

**Logs:**
- Android `Log` statements guarded with custom tags
- On-screen "Stats for Nerds" telemetry panel showing codec, bitrate, latency, and audio sink stats

## CI/CD & Deployment

**Hosting:**
- GitHub Releases for APK distribution
- Render for Listen Together server deployment (`backend/render.yaml`)

**CI Pipeline:**
- GitHub Actions workflow (`.github/workflows/android-build.yml`):
  - Automates Gradle build, Android lint, testing, artifact signing, and GitHub release tagging

## Environment Configuration

**Required env vars:**
- `LISTEN_TOGETHER_SERVER`: Listen Together party host URL (defaults to production Render service)
- `GITHUB_RUN_NUMBER`: CI build iteration used for automated `versionCode` calculation
- `VERSION_NAME`: Target release display version string

**Secrets location:**
- Android release keystore: Configured via `keystore.properties` (or environment variables `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`)

## Webhooks & Callbacks

**Incoming:**
- Android Auto MediaBrowserService connection callbacks (`app/src/main/java/com/music/bitchord/playback/PlaybackService.kt`)
- MediaSession command callbacks from lockscreen, headset, Bluetooth, and notification controls
- Deep link intents (`bitchord://` and `umtify://`) handled in `app/src/main/java/com/music/bitchord/playback/PlayerDeepLink.kt`

**Outgoing:**
- WebSocket messages sent to Listen Together party server (`backend/hub/hub.go`)
- WebSocket packets transmitted to Discord RPC Gateway

---

*Integration audit: 2026-11-07*
