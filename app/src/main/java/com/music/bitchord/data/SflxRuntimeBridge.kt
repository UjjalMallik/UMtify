package com.music.bitchord.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.binding.define
import com.music.bitchord.data.sources.SourceHealthTracker
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.download.DownloadFormatOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * QuickJS Host Runtime Bridge for executing extracted Mornye `.sflx` packages
 * (`manifest.json` + `index.js`) on `Dispatchers.IO`.
 */
object SflxRuntimeBridge {
    private const val TAG = "SflxRuntimeBridge"
    private const val PREFS_NAME = "sflx_ext_storage"
    private const val DEFAULT_RESOLUTION_BUDGET_MS = 4000L

    @Volatile
    private var appContext: Context? = null
    private var storagePrefs: SharedPreferences? = null
    private val cookieJar = ConcurrentHashMap<String, String>()

    fun init(context: Context) {
        appContext = context.applicationContext
        storagePrefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val syncHttpClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(2500, TimeUnit.MILLISECONDS)
            .readTimeout(3500, TimeUnit.MILLISECONDS)
            .callTimeout(4000, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .build()
    }

    private data class CapturedDownload(
        val url: String,
        val headers: Map<String, String>,
        val isManifest: Boolean,
    )

    /**
     * Verifies `.sflx` extraction, loads `index.js` inside QuickJS, checks manifest `serviceHealth`
     * + registered provider exports, and returns latency on success.
     */
    suspend fun probeHealth(extensionId: String, configId: String): Result<Long> = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val indexJs = ExtensionRepoManager.getExtractedIndexJs(extensionId)
        val manifestJson = ExtensionRepoManager.getExtractedManifestJson(extensionId)

        if (indexJs.isNullOrBlank()) {
            val reason = "Not installed"
            SourceHealthTracker.recordError(configId, reason)
            return@withContext Result.failure(IllegalStateException(reason))
        }

        // Verify serviceHealth endpoint from manifest.json when declared
        val serviceHealthUrl = runCatching {
            if (manifestJson.isNullOrBlank()) return@runCatching null
            val arr = JSONObject(manifestJson).optJSONArray("serviceHealth") ?: return@runCatching null
            arr.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
        }.getOrNull()

        var healthEndpointOk = false
        var serviceHealthError: String? = null
        if (serviceHealthUrl != null) {
            runCatching {
                val safeUrl = LosslessAudioProvider.forceHttps(serviceHealthUrl)
                val req = Request.Builder()
                    .url(safeUrl)
                    .header("User-Agent", LosslessAudioProvider.BROWSER_USER_AGENT)
                    .header("Accept", "application/json")
                    .build()
                syncHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val text = resp.body?.string().orEmpty()
                        val services = runCatching { JSONObject(text).optJSONObject("services") }.getOrNull()
                        if (services != null) {
                            val svcKey = manifestJson?.let {
                                runCatching {
                                    JSONObject(it).optJSONArray("serviceHealth")?.optJSONObject(0)?.optString("serviceKey")
                                }.getOrNull()
                            }?.takeIf { it.isNotBlank() } ?: extensionId.substringBefore('-')
                            val svcObj = services.optJSONObject(svcKey)
                            if (svcObj != null) {
                                healthEndpointOk = svcObj.optBoolean("ok", true)
                                if (!healthEndpointOk) {
                                    serviceHealthError = svcObj.optString("error").ifBlank { "Auth required" }
                                }
                            } else {
                                healthEndpointOk = true
                            }
                        } else {
                            healthEndpointOk = true
                        }
                    } else if (resp.code == 429) {
                        serviceHealthError = "Rate limited"
                    } else {
                        serviceHealthError = "HTTP ${resp.code}"
                    }
                }
            }.onFailure {
                serviceHealthError = "Timeout"
            }
        }

        // Verify script loads cleanly inside QuickJS and exports extension handlers
        val loadCheck = withTimeoutOrNull(2500L) {
            runCatching {
                val qjs = QuickJs.create(Dispatchers.IO)
                qjs.maxStackSize = 1024 * 1024L
                try {
                    val captured = mutableListOf<CapturedDownload>()
                    bindHostApis(qjs, extensionId, manifestJson, System.currentTimeMillis() + 2200L, captured)
                    evaluateScriptWithBridge(qjs, manifestJson, indexJs)
                    val summaryJson = qjs.evaluate<String>(
                        """
                        (function() {
                            var ext = globalThis.__sflx_ext || {};
                            var hasSearch = typeof ext.customSearch === 'function' || typeof ext.searchTracks === 'function' || typeof ext.checkAvailability === 'function';
                            var hasDownload = typeof ext.download === 'function' || typeof ext.getDownloadUrl === 'function';
                            var initOk = true;
                            if (typeof ext.initialize === 'function') {
                                try { initOk = ext.initialize(globalThis.settings || {}) !== false; } catch (_) { initOk = false; }
                            }
                            return JSON.stringify({ hasSearch: hasSearch, hasDownload: hasDownload, initOk: initOk });
                        })();
                        """.trimIndent()
                    )
                    val obj = JSONObject(summaryJson)
                    obj.optBoolean("hasSearch") || obj.optBoolean("hasDownload")
                } finally {
                    runCatching { qjs.close() }
                }
            }.getOrElse { false }
        } ?: false

        val latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L)
        return@withContext if (loadCheck && (serviceHealthUrl == null || healthEndpointOk)) {
            SourceHealthTracker.recordReady(configId, latencyMs)
            Result.success(latencyMs)
        } else {
            val reason = when {
                !loadCheck -> "Script init error"
                !serviceHealthError.isNullOrBlank() -> serviceHealthError!!
                else -> "Unavailable"
            }
            SourceHealthTracker.recordError(configId, reason)
            Result.failure(IllegalStateException(reason))
        }
    }

    /**
     * Executes the `.sflx` JavaScript extension inside QuickJS to search & resolve
     * real downloadable [DownloadFormatOption] candidates (Hi-Res FLAC / 16-bit FLAC / M4A / Atmos).
     */
    suspend fun resolveDownloadFormats(
        extensionId: String,
        displayName: String,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): List<DownloadFormatOption> = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext emptyList()
        val indexJs = ExtensionRepoManager.getExtractedIndexJs(extensionId) ?: return@withContext emptyList()
        val manifestJson = ExtensionRepoManager.getExtractedManifestJson(extensionId)

        withTimeoutOrNull(DEFAULT_RESOLUTION_BUDGET_MS) {
            runCatching {
                val deadlineMs = System.currentTimeMillis() + DEFAULT_RESOLUTION_BUDGET_MS - 150L
                val qjs = QuickJs.create(Dispatchers.IO)
                qjs.maxStackSize = 1024 * 1024L
                val capturedDownloads = mutableListOf<CapturedDownload>()
                val configId = SourceRegistry.repoExtensionConfigId(extensionId)
                val startMs = System.currentTimeMillis()

                try {
                    bindHostApis(qjs, extensionId, manifestJson, deadlineMs, capturedDownloads)
                    evaluateScriptWithBridge(qjs, manifestJson, indexJs)

                    val tiersJson = buildString {
                        val arr = JSONArray()
                        val manifestTiers = runCatching {
                            manifestJson?.let { JSONObject(it).optJSONArray("qualityOptions") }
                        }.getOrNull()
                        if (manifestTiers != null && manifestTiers.length() > 0) {
                            for (i in 0 until manifestTiers.length()) {
                                val id = manifestTiers.optJSONObject(i)?.optString("id").orEmpty()
                                if (id.isNotBlank() && id != "LOW" && arr.length() < 3) arr.put(id)
                            }
                        }
                        if (arr.length() == 0) {
                            arr.put("HI_RES_LOSSLESS")
                            arr.put("LOSSLESS")
                        }
                        append(arr.toString())
                    }

                    val queryTitle = JSONObject.quote(title.trim())
                    val queryArtist = JSONObject.quote(artist.trim())
                    val queryAlbum = JSONObject.quote((album ?: "").trim())
                    val durationMs = durationSec.coerceAtLeast(0) * 1000L

                    val resultJson = qjs.evaluate<String>(
                        """
                        (function() {
                            var ext = globalThis.__sflx_ext || {};
                            if (typeof ext.initialize === 'function') {
                                try { ext.initialize(globalThis.settings || {}); } catch (_) {}
                            }
                            var tTitle = $queryTitle;
                            var tArtist = $queryArtist;
                            var tAlbum = $queryAlbum;
                            var tDurationMs = $durationMs;
                            var searchQuery = (tTitle + " " + tArtist).trim();
                            var candidates = [];

                            try {
                                if (typeof ext.customSearch === 'function') {
                                    var cs = ext.customSearch(searchQuery, { filter: "track", limit: 6 });
                                    if (Array.isArray(cs)) candidates = cs;
                                    else if (cs && Array.isArray(cs.tracks)) candidates = cs.tracks;
                                    else if (cs && Array.isArray(cs.items)) candidates = cs.items;
                                }
                            } catch (_) {}

                            if (!candidates.length && typeof ext.searchTracks === 'function') {
                                try {
                                    var st = ext.searchTracks(searchQuery, 6);
                                    if (Array.isArray(st)) candidates = st;
                                    else if (st && Array.isArray(st.tracks)) candidates = st.tracks;
                                    else if (st && Array.isArray(st.items)) candidates = st.items;
                                } catch (_) {}
                            }

                            var bestTrack = null;
                            var normTargetTitle = tTitle.toLowerCase().replace(/[^a-z0-9]/g, "");
                            var normTargetArtist = tArtist.toLowerCase().replace(/[^a-z0-9]/g, "");
                            var bestScore = -999999;

                            for (var i = 0; i < candidates.length; i++) {
                                var c = candidates[i] || {};
                                var cTitle = String(c.name || c.title || "");
                                var cArtist = String(c.artists || c.artist || c.artistName || "");
                                var nTitle = cTitle.toLowerCase().replace(/[^a-z0-9]/g, "");
                                var nArtist = cArtist.toLowerCase().replace(/[^a-z0-9]/g, "");
                                var score = 0;
                                if (nTitle === normTargetTitle) score += 100;
                                else if (nTitle.indexOf(normTargetTitle) >= 0 || normTargetTitle.indexOf(nTitle) >= 0) score += 50;
                                if (normTargetArtist && (nArtist === normTargetArtist || nArtist.indexOf(normTargetArtist) >= 0 || normTargetArtist.indexOf(nArtist) >= 0)) score += 60;
                                var cDur = Number(c.duration_ms || (c.duration ? Number(c.duration) * 1000 : 0) || 0);
                                if (tDurationMs > 0 && cDur > 0) {
                                    var diffSec = Math.abs(cDur - tDurationMs) / 1000;
                                    if (diffSec <= 4) score += 40;
                                    else if (diffSec > 20) score -= 50;
                                }
                                if (score > bestScore) {
                                    bestScore = score;
                                    bestTrack = c;
                                }
                            }

                            var trackId = bestTrack ? String(bestTrack.id || bestTrack.track_id || bestTrack.provider_id || "") : "";
                            var preparedContext = null;

                            if (!trackId && typeof ext.checkAvailability === 'function') {
                                try {
                                    var avail = ext.checkAvailability("", tTitle, tArtist, { duration_ms: tDurationMs, album: tAlbum });
                                    if (avail && avail.available && avail.track_id) {
                                        trackId = String(avail.track_id);
                                        preparedContext = avail.prepared_context || null;
                                    }
                                } catch (_) {}
                            }

                            if (!trackId) {
                                return JSON.stringify({ ok: false, reason: "No matching track", formats: [] });
                            }

                            var tiers = $tiersJson;
                            var resolved = [];
                            for (var j = 0; j < tiers.length; j++) {
                                var q = tiers[j];
                                var directUrl = null;
                                if (typeof ext.getDownloadUrl === 'function') {
                                    try {
                                        var gdu = ext.getDownloadUrl(trackId, q);
                                        if (typeof gdu === 'string' && gdu.indexOf('http') === 0) {
                                            directUrl = gdu;
                                        } else if (gdu && typeof gdu.url === 'string') {
                                            directUrl = gdu.url;
                                        }
                                    } catch (_) {}
                                }
                                if (directUrl) {
                                    resolved.push({
                                        quality: q,
                                        url: directUrl,
                                        headers: {},
                                        bit_depth: q === "HI_RES_LOSSLESS" ? 24 : 16,
                                        sample_rate: q === "HI_RES_LOSSLESS" ? 96000 : 44100,
                                        audio_codec: "flac",
                                        actual_extension: "flac"
                                    });
                                    continue;
                                }
                                if (typeof ext.download === 'function') {
                                    var capBefore = globalThis.__sflx_captures ? globalThis.__sflx_captures.length : 0;
                                    var dlRes = null;
                                    try {
                                        dlRes = ext.download(trackId, q, "/virtual/" + extensionId + "_" + q + ".flac", null, {
                                            allowFallback: true,
                                            preparedContext: preparedContext || (bestTrack ? { track_id: trackId, raw_track: bestTrack, formatted_track: bestTrack, track: bestTrack, host_track: bestTrack } : {})
                                        });
                                    } catch (_) {}
                                    var capAfter = globalThis.__sflx_captures ? globalThis.__sflx_captures.length : 0;
                                    if (capAfter > capBefore) {
                                        var latest = globalThis.__sflx_captures[capAfter - 1];
                                        if (latest && latest.url) {
                                            resolved.push({
                                                quality: q,
                                                url: latest.url,
                                                headers: latest.headers || {},
                                                bit_depth: Number((dlRes && (dlRes.bit_depth || dlRes.bitDepth)) || (q === "HI_RES_LOSSLESS" ? 24 : 16)),
                                                sample_rate: Number((dlRes && (dlRes.sample_rate || dlRes.sampleRate)) || (q === "HI_RES_LOSSLESS" ? 96000 : 44100)),
                                                audio_codec: String((dlRes && (dlRes.audio_codec || dlRes.codec)) || (q === "DOLBY_ATMOS" ? "eac3-joc" : "flac")),
                                                actual_extension: String((dlRes && (dlRes.actual_extension || dlRes.output_extension)) || (q === "DOLBY_ATMOS" || q === "HIGH" ? "m4a" : "flac")).replace(/^\./, "")
                                            });
                                        }
                                    }
                                }
                            }
                            return JSON.stringify({ ok: resolved.length > 0, formats: resolved });
                        })();
                        """.trimIndent()
                    )

                    val root = JSONObject(resultJson)
                    val formatsArr = root.optJSONArray("formats") ?: JSONArray()
                    val latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)

                    val out = mutableListOf<DownloadFormatOption>()
                    val seen = HashSet<String>()

                    fun mapUrlToOption(
                        url: String,
                        headersMap: Map<String, String>,
                        quality: String,
                        bitDepthRaw: Int,
                        sampleRateRaw: Int,
                        codecRaw: String,
                        extRaw: String,
                        idx: Int,
                    ) {
                        val safeUrl = LosslessAudioProvider.forceHttps(url)
                        if (!safeUrl.startsWith("https://") || !seen.add(safeUrl)) return

                        val isAtmos = quality.contains("ATMOS", ignoreCase = true) ||
                            codecRaw.contains("eac3", ignoreCase = true) ||
                            codecRaw.contains("ec-3", ignoreCase = true)
                        val isLossy = quality.equals("HIGH", ignoreCase = true) ||
                            codecRaw.contains("aac", ignoreCase = true) ||
                            codecRaw.contains("mp3", ignoreCase = true) ||
                            extRaw.equals("mp3", ignoreCase = true)
                        val isLossless = !isAtmos && !isLossy

                        val bitDepth = when {
                            bitDepthRaw > 0 -> bitDepthRaw
                            quality.contains("HI_RES", ignoreCase = true) -> 24
                            else -> 16
                        }
                        val sampleRate = when {
                            sampleRateRaw > 0 -> sampleRateRaw
                            quality.contains("HI_RES", ignoreCase = true) -> 96000
                            isAtmos -> 48000
                            else -> 44100
                        }
                        val fileExt = when {
                            isAtmos -> "m4a"
                            isLossy -> if (extRaw == "mp3") "mp3" else "m4a"
                            else -> "flac"
                        }
                        val estKbps = when {
                            isAtmos -> 768
                            isLossy -> 320
                            bitDepth > 16 || sampleRate > 48000 -> 2304
                            else -> 1411
                        }
                        val khz = String.format(Locale.US, "%.1f", sampleRate / 1000.0)
                        val label = when {
                            isAtmos -> "Dolby Atmos • E-AC-3 JOC ($displayName)"
                            isLossy -> "M4A / AAC • $estKbps kbps ($displayName)"
                            else -> "FLAC • $bitDepth-bit • $khz kHz • $estKbps kbps ($displayName)"
                        }
                        val estBytes = (estKbps * 1000L / 8L) * durationSec.coerceAtLeast(30)
                        val mb = estBytes / (1024.0 * 1024.0)
                        val formattedSize = String.format(Locale.US, "~%.1f MB", mb)
                        val normalizedHeaders = if (headersMap.any { it.key.equals("User-Agent", ignoreCase = true) }) {
                            headersMap
                        } else {
                            headersMap + ("User-Agent" to LosslessAudioProvider.BROWSER_USER_AGENT)
                        }

                        out.add(
                            DownloadFormatOption(
                                id = "sflx_${extensionId}_${quality.lowercase(Locale.ROOT)}_$idx",
                                label = label,
                                codec = if (isAtmos) "eac3-joc" else if (isLossy) "aac" else "flac",
                                bitrateKbps = estKbps,
                                sampleRateHz = sampleRate,
                                bitDepth = bitDepth,
                                sizeBytes = estBytes,
                                formattedSize = formattedSize,
                                streamUrl = safeUrl,
                                fileExtension = fileExt,
                                isLossless = isLossless,
                                isDolbyAtmos = isAtmos,
                                headers = normalizedHeaders,
                                sourceName = displayName,
                            )
                        )
                    }

                    for (i in 0 until formatsArr.length()) {
                        val obj = formatsArr.optJSONObject(i) ?: continue
                        val url = obj.optString("url")
                        if (url.isBlank()) continue
                        val hObj = obj.optJSONObject("headers")
                        val hMap = buildMap<String, String> {
                            hObj?.keys()?.forEach { k ->
                                val v = hObj.optString(k)
                                if (v.isNotBlank()) put(k, v)
                            }
                        }
                        mapUrlToOption(
                            url = url,
                            headersMap = hMap,
                            quality = obj.optString("quality", "LOSSLESS"),
                            bitDepthRaw = obj.optInt("bit_depth", 0),
                            sampleRateRaw = obj.optInt("sample_rate", 0),
                            codecRaw = obj.optString("audio_codec", "flac"),
                            extRaw = obj.optString("actual_extension", "flac"),
                            idx = i,
                        )
                    }

                    // Include any direct URLs captured by file.download / downloadSegments if download() threw late
                    capturedDownloads.forEachIndexed { idx, cap ->
                        mapUrlToOption(
                            url = cap.url,
                            headersMap = cap.headers,
                            quality = "LOSSLESS",
                            bitDepthRaw = 16,
                            sampleRateRaw = 44100,
                            codecRaw = "flac",
                            extRaw = "flac",
                            idx = formatsArr.length() + idx,
                        )
                    }

                    if (out.isNotEmpty()) {
                        SourceHealthTracker.recordReady(configId, latencyMs)
                    }
                    out
                } finally {
                    runCatching { qjs.close() }
                }
            }.getOrElse { emptyList() }
        }.orEmpty()
    }

    private suspend fun bindHostApis(
        qjs: QuickJs,
        extensionId: String,
        manifestJson: String?,
        deadlineMs: Long,
        capturedDownloads: MutableList<CapturedDownload>,
    ) {
        val signedSessionObj = runCatching {
            manifestJson?.let { JSONObject(it).optJSONObject("signedSession") }
        }.getOrNull()
        val signedBaseUrl = signedSessionObj?.optString("baseUrl").orEmpty().trimEnd('/')

        qjs.define("__host") {
            function("httpRequest", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val method = args.getOrNull(0)?.toString()?.uppercase(Locale.ROOT) ?: "GET"
                    val rawUrl = args.getOrNull(1)?.toString().orEmpty()
                    val bodyStr = args.getOrNull(2)?.toString()
                    val headersJson = args.getOrNull(3)?.toString() ?: "{}"
                    return executeHttpJson(rawUrl, method, bodyStr, headersJson, deadlineMs)
                }
            })

            function("clearCookies", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    cookieJar.clear()
                    return "ok"
                }
            })

            function("utilsHash", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val alg = args.getOrNull(0)?.toString() ?: "SHA-256"
                    val input = args.getOrNull(1)?.toString().orEmpty()
                    return runCatching {
                        MessageDigest.getInstance(alg)
                            .digest(input.toByteArray(Charsets.UTF_8))
                            .joinToString("") { "%02x".format(it) }
                    }.getOrDefault("")
                }
            })

            function("base64Encode", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val input = args.getOrNull(0)?.toString().orEmpty()
                    return Base64.encodeToString(input.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                }
            })

            function("base64Decode", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val input = args.getOrNull(0)?.toString().orEmpty()
                    return runCatching {
                        String(Base64.decode(input, Base64.DEFAULT), Charsets.UTF_8)
                    }.getOrDefault("")
                }
            })

            function("sleep", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val ms = args.getOrNull(0)?.toString()?.toLongOrNull() ?: 0L
                    val remaining = (deadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
                    val clamped = minOf(ms.coerceAtLeast(0L), minOf(remaining, 1000L))
                    if (clamped > 0L) runCatching { Thread.sleep(clamped) }
                    return "ok"
                }
            })

            function("remainingMs", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String =
                    (deadlineMs - System.currentTimeMillis()).coerceAtLeast(0L).toString()
            })

            function("storageGet", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val key = args.getOrNull(0)?.toString().orEmpty()
                    return storagePrefs?.getString("${extensionId}:$key", null) ?: ""
                }
            })

            function("storageSet", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val key = args.getOrNull(0)?.toString().orEmpty()
                    val value = args.getOrNull(1)?.toString()
                    storagePrefs?.edit()?.putString("${extensionId}:$key", value)?.apply()
                    return "ok"
                }
            })

            function("logMsg", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val level = args.getOrNull(0)?.toString() ?: "info"
                    val msg = args.getOrNull(1)?.toString().orEmpty()
                    when (level) {
                        "error" -> TrackLog.e(TAG, "[$extensionId] $msg")
                        "warn" -> TrackLog.w(TAG, "[$extensionId] $msg")
                        else -> TrackLog.d(TAG, "[$extensionId] $msg")
                    }
                    return "ok"
                }
            })

            function("sessionSignedFetch", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val method = args.getOrNull(0)?.toString()?.uppercase(Locale.ROOT) ?: "GET"
                    val path = args.getOrNull(1)?.toString().orEmpty()
                    val bodyStr = args.getOrNull(2)?.toString()
                    val headersJson = args.getOrNull(3)?.toString() ?: "{}"
                    if (signedBaseUrl.isBlank()) {
                        return JSONObject().apply {
                            put("statusCode", 403)
                            put("status", 403)
                            put("error", "No signedSession baseUrl")
                        }.toString()
                    }
                    val fullUrl = if (path.startsWith("http", ignoreCase = true)) {
                        path
                    } else {
                        "$signedBaseUrl/${path.trimStart('/')}"
                    }
                    return executeHttpJson(fullUrl, method, bodyStr, headersJson, deadlineMs)
                }
            })

            function("captureDownload", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val rawUrl = args.getOrNull(0)?.toString().orEmpty()
                    val headersJson = args.getOrNull(1)?.toString() ?: "{}"
                    val safeUrl = LosslessAudioProvider.forceHttps(rawUrl)
                    if (safeUrl.startsWith("https://")) {
                        val map = mutableMapOf<String, String>()
                        runCatching {
                            val obj = JSONObject(headersJson)
                            obj.keys().forEach { k ->
                                val v = obj.optString(k)
                                if (v.isNotBlank()) map[k] = v
                            }
                        }
                        capturedDownloads.add(
                            CapturedDownload(
                                url = safeUrl,
                                headers = map,
                                isManifest = safeUrl.contains(".m3u8", ignoreCase = true) || safeUrl.contains(".mpd", ignoreCase = true),
                            )
                        )
                    }
                    return JSONObject().apply {
                        put("success", safeUrl.startsWith("https://"))
                        put("url", safeUrl)
                    }.toString()
                }
            })
        }

        val extSettingsJson = buildSettingsJson(manifestJson)
        qjs.evaluate<Unit>(
            """
            var globalThis = typeof globalThis !== 'undefined' ? globalThis : this;
            var window = globalThis;
            var self = globalThis;
            var __sflx_ext = {};
            var __sflx_captures = [];
            var extensionId = ${JSONObject.quote(extensionId)};
            var settings = $extSettingsJson;
            settings.get = function(k) { return settings[k]; };
            settings.set = function(k, v) { settings[k] = v; };

            function registerExtension(ext) {
                globalThis.__sflx_ext = ext || {};
            }

            var gobackend = {
                checkISRCExists: function() { return null; },
                getAudioQuality: function() { return null; }
            };

            var ffmpeg = {
                mov_key: function() { return { success: false }; }
            };

            var log = {
                info: function() { __host.logMsg("info", Array.prototype.slice.call(arguments).join(" ")); },
                debug: function() { __host.logMsg("debug", Array.prototype.slice.call(arguments).join(" ")); },
                warn: function() { __host.logMsg("warn", Array.prototype.slice.call(arguments).join(" ")); },
                error: function() { __host.logMsg("error", Array.prototype.slice.call(arguments).join(" ")); }
            };
            var console = {
                log: log.info,
                info: log.info,
                debug: log.debug,
                warn: log.warn,
                error: log.error
            };

            var storage = {
                get: function(k) {
                    var v = __host.storageGet(String(k || ""));
                    if (!v) return null;
                    try { return JSON.parse(v); } catch (_) { return v; }
                },
                set: function(k, v) {
                    __host.storageSet(String(k || ""), typeof v === "string" ? v : JSON.stringify(v));
                }
            };

            var utils = {
                base64Decode: function(s) { return __host.base64Decode(String(s || "")); },
                base64Encode: function(s) { return __host.base64Encode(String(s || "")); },
                md5: function(s) { return __host.utilsHash("MD5", String(s || "")); },
                sha256: function(s) { return __host.utilsHash("SHA-256", String(s || "")); },
                sleep: function(ms) { __host.sleep(String(ms || 0)); },
                getResolutionRemainingMs: function() { return Number(__host.remainingMs() || 0); },
                isDownloadCancelled: function() { return false; },
                appUserAgent: function() { return ${JSONObject.quote(LosslessAudioProvider.BROWSER_USER_AGENT)}; },
                randomUserAgent: function() { return ${JSONObject.quote(LosslessAudioProvider.BROWSER_USER_AGENT)}; },
                appVersion: function() { return "5.3.0"; }
            };

            if (typeof atob === 'undefined') {
                var atob = utils.base64Decode;
            }
            if (typeof btoa === 'undefined') {
                var btoa = utils.base64Encode;
            }
            if (typeof Object.assign !== 'function') {
                Object.assign = function(target) {
                    var to = Object(target);
                    for (var i = 1; i < arguments.length; i++) {
                        var src = arguments[i];
                        if (src != null) {
                            for (var k in src) {
                                if (Object.prototype.hasOwnProperty.call(src, k)) to[k] = src[k];
                            }
                        }
                    }
                    return to;
                };
            }

            var http = {
                get: function(url, headers) {
                    return JSON.parse(__host.httpRequest("GET", String(url || ""), null, JSON.stringify(headers || {})));
                },
                post: function(url, body, headers) {
                    var bodyArg = (body === undefined || body === null) ? "" : (typeof body === "string" ? body : JSON.stringify(body));
                    return JSON.parse(__host.httpRequest("POST", String(url || ""), bodyArg, JSON.stringify(headers || {})));
                },
                request: function(opts) {
                    opts = opts || {};
                    var bodyArg = (opts.body === undefined || opts.body === null) ? null : (typeof opts.body === "string" ? opts.body : JSON.stringify(opts.body));
                    return JSON.parse(__host.httpRequest(String(opts.method || "GET"), String(opts.url || ""), bodyArg, JSON.stringify(opts.headers || {})));
                },
                clearCookies: function() { __host.clearCookies(); }
            };

            var session = {
                signedFetch: function(method, path, body, headers) {
                    var bodyArg = (body === undefined || body === null) ? null : (typeof body === "string" ? body : JSON.stringify(body));
                    return JSON.parse(__host.sessionSignedFetch(String(method || "GET"), String(path || ""), bodyArg, JSON.stringify(headers || {})));
                },
                completeGrant: function() { return { success: false }; }
            };

            var file = {
                download: function(url, outputPath, opts) {
                    var headers = (opts && opts.headers) || {};
                    __sflx_captures.push({ url: String(url || ""), headers: headers });
                    __host.captureDownload(String(url || ""), JSON.stringify(headers));
                    return { success: true, path: String(outputPath || "/virtual/out.flac"), size: 42000000 };
                },
                downloadSegments: function(opts) {
                    opts = opts || {};
                    var u = String(opts.manifestUrl || opts.url || (opts.segments && opts.segments[0]) || "");
                    var headers = opts.headers || {};
                    __sflx_captures.push({ url: u, headers: headers });
                    __host.captureDownload(u, JSON.stringify(headers));
                    return { success: Boolean(u), path: String(opts.outputPath || "/virtual/out.flac"), size: 42000000 };
                },
                exists: function() { return false; },
                getSize: function() { return 42000000; },
                delete: function() { return true; },
                transformPatternedBlocks: function() { return { success: false }; }
            };
            """.trimIndent()
        )
    }

    private suspend fun evaluateScriptWithBridge(qjs: QuickJs, manifestJson: String?, indexJs: String) {
        qjs.evaluate<Unit>(indexJs)
    }

    private fun buildSettingsJson(manifestJson: String?): String {
        val obj = JSONObject().apply {
            put("countryCode", "US")
            put("homeCountry", "US")
            put("locale", "en_US")
            put("deviceType", "BROWSER")
            put("customEndpoint", "")
            put("customEndpointApiKey", "")
        }
        runCatching {
            if (!manifestJson.isNullOrBlank()) {
                val manifest = JSONObject(manifestJson)
                val defaults = manifest.optJSONArray("settings")
                if (defaults != null) {
                    for (i in 0 until defaults.length()) {
                        val s = defaults.optJSONObject(i) ?: continue
                        val k = s.optString("key").ifBlank { s.optString("id") }
                        if (k.isNotBlank() && s.has("default") && !obj.has(k)) {
                            obj.put(k, s.get("default"))
                        }
                    }
                }
            }
        }
        return obj.toString()
    }

    private fun executeHttpJson(
        rawUrl: String,
        method: String,
        bodyStr: String?,
        headersJson: String,
        deadlineMs: Long,
    ): String {
        val safeUrl = LosslessAudioProvider.forceHttps(rawUrl)
        if (!safeUrl.startsWith("https://")) {
            return JSONObject().apply {
                put("statusCode", 0)
                put("status", 0)
                put("ok", false)
                put("error", "Invalid URL: $rawUrl")
                put("body", "")
            }.toString()
        }

        val remainingMs = (deadlineMs - System.currentTimeMillis()).coerceIn(600L, 4000L)
        val client = syncHttpClient.newBuilder()
            .connectTimeout(minOf(remainingMs, 2500L), TimeUnit.MILLISECONDS)
            .readTimeout(remainingMs, TimeUnit.MILLISECONDS)
            .callTimeout(remainingMs, TimeUnit.MILLISECONDS)
            .build()

        val builder = Request.Builder().url(safeUrl)
        var hasUa = false
        var contentType = "application/json; charset=utf-8"
        runCatching {
            val hObj = JSONObject(headersJson)
            hObj.keys().forEach { k ->
                val v = hObj.optString(k)
                builder.header(k, v)
                if (k.equals("User-Agent", ignoreCase = true)) hasUa = true
                if (k.equals("Content-Type", ignoreCase = true) && v.isNotBlank()) contentType = v
            }
        }
        if (!hasUa) {
            builder.header("User-Agent", LosslessAudioProvider.BROWSER_USER_AGENT)
        }

        when (method.uppercase(Locale.ROOT)) {
            "POST" -> builder.post((bodyStr ?: "").toRequestBody(contentType.toMediaType()))
            "PUT" -> builder.put((bodyStr ?: "").toRequestBody(contentType.toMediaType()))
            "DELETE" -> builder.delete()
            "HEAD" -> builder.head()
            else -> builder.get()
        }

        return runCatching {
            client.newCall(builder.build()).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                val respHeaders = JSONObject()
                resp.headers.names().forEach { name ->
                    resp.header(name)?.let { respHeaders.put(name, it) }
                }
                JSONObject().apply {
                    put("statusCode", resp.code)
                    put("status", resp.code)
                    put("ok", resp.isSuccessful)
                    put("headers", respHeaders)
                    put("body", respBody)
                    if (!resp.isSuccessful) {
                        put("error", "HTTP ${resp.code}")
                    }
                }.toString()
            }
        }.getOrElse { err ->
            JSONObject().apply {
                put("statusCode", 0)
                put("status", 0)
                put("ok", false)
                put("error", err.message ?: "Request failed")
                put("body", "")
            }.toString()
        }
    }
}
