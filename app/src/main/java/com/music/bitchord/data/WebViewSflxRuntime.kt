package com.music.bitchord.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.Keep
import com.music.bitchord.data.sources.SourceHealthTracker
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.download.DownloadFormatOption
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Headless Android WebView (Chromium V8) Runtime for executing real Mornye `.sflx`
 * JavaScript extensions (`manifest.json` + `index.js`) with native ES2023 `async/await`,
 * `Promise`, `atob/btoa`, `URL`, `TextEncoder`, and CORS-free `OkHttpClient` networking.
 *
 * All `@JavascriptInterface` calls cross the WebView bridge strictly using primitive
 * `String` parameters and `String` return values, with `@Keep` + ProGuard rules so
 * Release builds (`prod` / `originos`) never strip bridge methods.
 */
@Keep
object WebViewSflxRuntime {
    private const val TAG = "WebViewSflxRuntime"
    private const val PREFS_NAME = "sflx_ext_storage"
    private const val MAX_POOL_SIZE = 3
    private const val DEFAULT_BUDGET_MS = 7000L
    private const val HEALTH_CACHE_TTL_MS = 10 * 60 * 1000L

    @Volatile
    private var appContext: Context? = null
    private var storagePrefs: SharedPreferences? = null

    private val pool = Channel<WebView>(Channel.UNLIMITED)
    private val poolLock = Mutex()
    private var createdCount = 0

    private val cookieJar = ConcurrentHashMap<String, String>()
    private val pendingCalls = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val capturedDownloadsMap = ConcurrentHashMap<String, MutableList<CapturedDownload>>()
    private val activeCallDeadlines = ConcurrentHashMap<String, Long>()
    private val callSeq = AtomicInteger(0)

    private data class CachedHealthResponse(val code: Int, val body: String, val timestampMs: Long)
    private val sharedHealthResponses = ConcurrentHashMap<String, CachedHealthResponse>()

    private data class CapturedDownload(
        val url: String,
        val headers: Map<String, String>,
    )

    data class SflxResolutionResult(
        val options: List<DownloadFormatOption>,
        val failureReason: String? = null,
    )

    private val syncHttpClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(3500, TimeUnit.MILLISECONDS)
            .readTimeout(5500, TimeUnit.MILLISECONDS)
            .callTimeout(6000, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .build()
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        storagePrefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Keep
    private class SflxJsBridge {
        @JavascriptInterface
        @Keep
        fun httpRequest(reqJson: String): String {
            return runCatching {
                val obj = JSONObject(reqJson)
                val rawUrl = obj.optString("url")
                val method = obj.optString("method", "GET").uppercase(Locale.ROOT)
                val bodyStr = if (obj.isNull("body")) null else obj.optString("body")
                val headersObj = obj.optJSONObject("headers")
                val headersJson = headersObj?.toString() ?: "{}"
                executeHttpJson(rawUrl, method, bodyStr, headersJson)
            }.getOrElse { err ->
                JSONObject().apply {
                    put("status", 0)
                    put("statusCode", 0)
                    put("ok", false)
                    put("headers", JSONObject())
                    put("body", "")
                    put("error", err.message ?: "httpRequest bridge error")
                }.toString()
            }
        }

        @JavascriptInterface
        @Keep
        fun sessionSignedFetch(reqJson: String): String {
            return runCatching {
                val obj = JSONObject(reqJson)
                val signedBaseUrl = obj.optString("signedBaseUrl").trimEnd('/')
                val path = obj.optString("path")
                val method = obj.optString("method", "GET").uppercase(Locale.ROOT)
                val bodyStr = if (obj.isNull("body")) null else obj.optString("body")
                val headersJson = obj.optJSONObject("headers")?.toString() ?: "{}"
                if (signedBaseUrl.isBlank() && !path.startsWith("http", ignoreCase = true)) {
                    return@runCatching JSONObject().apply {
                        put("status", 403)
                        put("statusCode", 403)
                        put("ok", false)
                        put("headers", JSONObject())
                        put("body", "")
                        put("error", "No signedSession baseUrl")
                    }.toString()
                }
                val fullUrl = if (path.startsWith("http", ignoreCase = true)) {
                    path
                } else {
                    "$signedBaseUrl/${path.trimStart('/')}"
                }
                executeHttpJson(fullUrl, method, bodyStr, headersJson)
            }.getOrElse { err ->
                JSONObject().apply {
                    put("status", 0)
                    put("statusCode", 0)
                    put("ok", false)
                    put("headers", JSONObject())
                    put("body", "")
                    put("error", err.message ?: "signedFetch error")
                }.toString()
            }
        }

        @JavascriptInterface
        @Keep
        fun storageGet(extId: String, key: String): String =
            storagePrefs?.getString("${extId}:$key", null) ?: ""

        @JavascriptInterface
        @Keep
        fun storageSet(extId: String, key: String, value: String): String {
            storagePrefs?.edit()?.putString("${extId}:$key", value)?.apply()
            return "ok"
        }

        @JavascriptInterface
        @Keep
        fun clearCookies(): String {
            cookieJar.clear()
            return "ok"
        }

        @JavascriptInterface
        @Keep
        fun utilsHash(alg: String, input: String): String =
            runCatching {
                MessageDigest.getInstance(alg)
                    .digest(input.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            }.getOrDefault("")

        @JavascriptInterface
        @Keep
        fun remainingMs(callId: String): String {
            val deadline = activeCallDeadlines[callId] ?: return "5000"
            return (deadline - System.currentTimeMillis()).coerceAtLeast(0L).toString()
        }

        @JavascriptInterface
        @Keep
        fun captureDownload(callId: String, rawUrl: String, headersJson: String): String {
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
                capturedDownloadsMap.computeIfAbsent(callId) {
                    java.util.Collections.synchronizedList(mutableListOf())
                }.add(CapturedDownload(url = safeUrl, headers = map))
            }
            return JSONObject().apply {
                put("success", safeUrl.startsWith("https://"))
                put("url", safeUrl)
            }.toString()
        }

        @JavascriptInterface
        @Keep
        fun logMsg(extId: String, level: String, msg: String): String {
            when (level) {
                "error" -> Log.e(TAG, "[$extId] $msg")
                "warn" -> Log.w(TAG, "[$extId] $msg")
                else -> Log.d(TAG, "[$extId] $msg")
            }
            return "ok"
        }

        @JavascriptInterface
        @Keep
        fun onResult(callId: String, resultJson: String) {
            pendingCalls.remove(callId)?.complete(resultJson)
        }

        @JavascriptInterface
        @Keep
        fun onError(callId: String, errorMsg: String) {
            val fallback = JSONObject().apply {
                put("ok", false)
                put("reason", errorMsg)
                put("formats", JSONArray())
            }.toString()
            pendingCalls.remove(callId)?.complete(fallback)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun createHeadlessWebView(): WebView? {
        val ctx = appContext ?: return null
        val ready = CompletableDeferred<Unit>()
        val wv = withContext(Dispatchers.Main) {
            WebView(ctx).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    userAgentString = LosslessAudioProvider.BROWSER_USER_AGENT
                }
                addJavascriptInterface(SflxJsBridge(), "AndroidHost")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (!ready.isCompleted) ready.complete(Unit)
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString().orEmpty()
                        // Allow our local synthetic origin; block any stray subresource requests
                        return if (reqUrl.startsWith("https://localhost/")) {
                            super.shouldInterceptRequest(view, request)
                        } else {
                            WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                    }
                }
                loadDataWithBaseURL(
                    "https://localhost/",
                    "<!DOCTYPE html><html><head><meta charset='utf-8'></head><body></body></html>",
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        }
        withTimeoutOrNull(2500L) { ready.await() }
        return wv
    }

    private suspend fun acquireWebView(): WebView? {
        pool.tryReceive().getOrNull()?.let { return it }
        val canCreate = poolLock.withLock {
            if (createdCount < MAX_POOL_SIZE) {
                createdCount++
                true
            } else {
                false
            }
        }
        if (!canCreate) {
            return withTimeoutOrNull(3500L) { pool.receive() }
        }
        return runCatching { createHeadlessWebView() }.getOrElse { err ->
            Log.w(TAG, "Failed to create headless WebView: ${err.message}")
            poolLock.withLock { createdCount = (createdCount - 1).coerceAtLeast(0) }
            null
        }
    }

    private suspend fun releaseWebView(wv: WebView, poisoned: Boolean) {
        if (poisoned) {
            withContext(Dispatchers.Main) {
                runCatching { wv.destroy() }
            }
            poolLock.withLock { createdCount = (createdCount - 1).coerceAtLeast(0) }
        } else {
            pool.trySend(wv)
        }
    }

    /**
     * Checks `.sflx` installation, loads `index.js` inside V8 WebView, verifies exported
     * methods + optional live serviceHealth / mirror fallback.
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

        val callId = "health_${callSeq.incrementAndGet()}_${UUID.randomUUID().toString().take(8)}"
        val deferred = CompletableDeferred<String>()
        pendingCalls[callId] = deferred
        activeCallDeadlines[callId] = System.currentTimeMillis() + 3000L

        val wv = acquireWebView()
        var poisoned = false
        val scriptOk = if (wv != null) {
            try {
                val shim = buildHostShimJs(callId, extensionId, manifestJson)
                val probeJs = """
                    (async function() {
                        try {
                            $shim
                            ${wrapExtensionCode(indexJs)}
                            var ext = globalThis.__sflx_ext || {};
                            var hasSearch = typeof ext.customSearch === 'function' || typeof ext.searchTracks === 'function' || typeof ext.checkAvailability === 'function';
                            var hasDownload = typeof ext.download === 'function' || typeof ext.getDownloadUrl === 'function';
                            var initOk = true;
                            if (typeof ext.initialize === 'function') {
                                try {
                                    var initRes = await ext.initialize(globalThis.settings || {});
                                    initOk = initRes !== false;
                                } catch (_) {
                                    initOk = false;
                                }
                            }
                            AndroidHost.onResult(${JSONObject.quote(callId)}, JSON.stringify({
                                ok: (hasSearch || hasDownload) && initOk,
                                hasSearch: hasSearch,
                                hasDownload: hasDownload
                            }));
                        } catch (e) {
                            AndroidHost.onError(${JSONObject.quote(callId)}, e && e.message ? e.message : String(e));
                        }
                    })();
                """.trimIndent()
                withContext(Dispatchers.Main) {
                    wv.evaluateJavascript(probeJs, null)
                }
                val raw = withTimeoutOrNull(2800L) { deferred.await() }
                if (raw == null) {
                    poisoned = true
                    false
                } else {
                    runCatching { JSONObject(raw).optBoolean("ok", false) }.getOrDefault(false)
                }
            } finally {
                pendingCalls.remove(callId)
                activeCallDeadlines.remove(callId)
                releaseWebView(wv, poisoned)
            }
        } else {
            pendingCalls.remove(callId)
            activeCallDeadlines.remove(callId)
            false
        }

        if (!scriptOk) {
            val reason = "V8 init failed"
            SourceHealthTracker.recordError(configId, reason)
            return@withContext Result.failure(IllegalStateException(reason))
        }

        // Also check upstream health URL if manifest declares one, falling back cleanly if mirrors are healthy
        val serviceHealthUrl = runCatching {
            manifestJson?.let { JSONObject(it).optJSONArray("serviceHealth")?.optJSONObject(0)?.optString("url") }
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()

        if (serviceHealthUrl != null) {
            val safeUrl = LosslessAudioProvider.forceHttps(serviceHealthUrl)
            val cached = sharedHealthResponses[safeUrl]
                ?.takeIf { System.currentTimeMillis() - it.timestampMs < HEALTH_CACHE_TTL_MS }
            val code = cached?.code ?: runCatching {
                val req = Request.Builder()
                    .url(safeUrl)
                    .header("User-Agent", LosslessAudioProvider.BROWSER_USER_AGENT)
                    .header("Accept", "application/json")
                    .build()
                syncHttpClient.newCall(req).execute().use { resp ->
                    val b = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        sharedHealthResponses[safeUrl] = CachedHealthResponse(resp.code, b, System.currentTimeMillis())
                    }
                    resp.code
                }
            }.getOrDefault(0)

            if (code !in 200..299) {
                // Check lossless mirror availability so V8+fallback health reflects real usability
                val endpoints = LosslessAudioProvider.getPreferredEndpointsForExtension(
                    extensionId,
                    ExtensionRepoManager.getExtractedEndpointsFor(extensionId),
                )
                val mirrorHealth = LosslessAudioProvider.probeEndpointHealth(endpoints, providerKey = extensionId)
                if (mirrorHealth.isFailure) {
                    val reason = if (code == 429) "Rate limited" else if (code > 0) "HTTP $code" else "Unavailable"
                    SourceHealthTracker.recordError(configId, reason)
                    return@withContext Result.failure(IllegalStateException(reason))
                }
            }
        }

        val latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L)
        SourceHealthTracker.recordReady(configId, latencyMs)
        Result.success(latencyMs)
    }

    /**
     * Executes an extracted `.sflx` script inside Headless WebView V8 and awaits its
     * multi-tier downloadable URLs via `AndroidHost.onResult(...)`.
     */
    suspend fun resolveDownloadFormatsDetailed(
        extensionId: String,
        displayName: String,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
        isrc: String? = null,
    ): SflxResolutionResult = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext SflxResolutionResult(emptyList(), "Empty title")
        val indexJs = ExtensionRepoManager.getExtractedIndexJs(extensionId)
            ?: return@withContext SflxResolutionResult(emptyList(), "Not installed")
        val manifestJson = ExtensionRepoManager.getExtractedManifestJson(extensionId)

        val cleanedTitle = NativeDownloadProviders.cleanTrackTitle(title)
        val cleanArtist = NativeDownloadProviders.cleanTrackTitle(artist)
        val primaryArtist = NativeDownloadProviders.primaryArtistName(cleanArtist)
        val startMs = System.currentTimeMillis()
        val configId = SourceRegistry.repoExtensionConfigId(extensionId)
        val pSlug = NativeDownloadProviders.providerSlug(displayName)

        val callId = "dl_${callSeq.incrementAndGet()}_${UUID.randomUUID().toString().take(8)}"
        val deferred = CompletableDeferred<String>()
        pendingCalls[callId] = deferred
        activeCallDeadlines[callId] = System.currentTimeMillis() + DEFAULT_BUDGET_MS

        val wv = acquireWebView()
            ?: run {
                pendingCalls.remove(callId)
                activeCallDeadlines.remove(callId)
                return@withContext SflxResolutionResult(emptyList(), "WebView unavailable")
            }

        var poisoned = false
        try {
            val tiersJson = buildTiersJson(manifestJson)
            val queriesArr = JSONArray().apply {
                val qFull = "$cleanedTitle $cleanArtist".trim()
                val qPrimary = "$cleanedTitle $primaryArtist".trim()
                if (qFull.isNotBlank()) put(qFull)
                if (qPrimary.isNotBlank() && !qPrimary.equals(qFull, ignoreCase = true)) put(qPrimary)
                if (cleanedTitle.isNotBlank() && primaryArtist.isBlank()) put(cleanedTitle)
            }

            val shim = buildHostShimJs(callId, extensionId, manifestJson)
            val qTitle = JSONObject.quote(cleanedTitle)
            val qArtist = JSONObject.quote(primaryArtist.ifBlank { cleanArtist })
            val qAlbum = JSONObject.quote((album ?: "").trim())
            val qIsrc = JSONObject.quote((isrc ?: "").trim())
            val qCallId = JSONObject.quote(callId)
            val qExtId = JSONObject.quote(extensionId)
            val durationMs = durationSec.coerceAtLeast(0) * 1000L

            val runnerJs = """
                (async function() {
                    var __cid = $qCallId;
                    try {
                        $shim
                        ${wrapExtensionCode(indexJs)}
                        var ext = globalThis.__sflx_ext || {};
                        if (typeof ext.initialize === 'function') {
                            try { await ext.initialize(globalThis.settings || {}); } catch (eInit) {
                                log.warn("initialize failed: " + (eInit && eInit.message ? eInit.message : String(eInit)));
                            }
                        }

                        var tTitle = $qTitle;
                        var tArtist = $qArtist;
                        var tAlbum = $qAlbum;
                        var tIsrc = $qIsrc;
                        var tDurationMs = $durationMs;
                        var queries = $queriesArr;

                        async function extractCandidates(q) {
                            var found = [];
                            if (typeof ext.customSearch === 'function') {
                                try {
                                    var cs = await ext.customSearch(q, { filter: "track", limit: 8 });
                                    if (Array.isArray(cs)) found = cs;
                                    else if (cs && Array.isArray(cs.tracks)) found = cs.tracks;
                                    else if (cs && Array.isArray(cs.items)) found = cs.items;
                                } catch (eCs) {
                                    log.warn("customSearch error: " + (eCs && eCs.message ? eCs.message : String(eCs)));
                                }
                            }
                            if (!found.length && typeof ext.searchTracks === 'function') {
                                try {
                                    var st = await ext.searchTracks(q, 8);
                                    if (Array.isArray(st)) found = st;
                                    else if (st && Array.isArray(st.tracks)) found = st.tracks;
                                    else if (st && Array.isArray(st.items)) found = st.items;
                                } catch (eSt) {
                                    log.warn("searchTracks error: " + (eSt && eSt.message ? eSt.message : String(eSt)));
                                }
                            }
                            if (!found.length && typeof ext.search === 'function') {
                                try {
                                    var sRes = await ext.search(q);
                                    if (Array.isArray(sRes)) found = sRes;
                                    else if (sRes && Array.isArray(sRes.tracks)) found = sRes.tracks;
                                    else if (sRes && Array.isArray(sRes.items)) found = sRes.items;
                                } catch (eS) {
                                    log.warn("search error: " + (eS && eS.message ? eS.message : String(eS)));
                                }
                            }
                            return found;
                        }

                        var candidates = [];
                        if (tIsrc) {
                            candidates = await extractCandidates(tIsrc);
                        }
                        for (var qi = 0; !candidates.length && qi < queries.length; qi++) {
                            candidates = await extractCandidates(queries[qi]);
                        }

                        var bestTrack = null;
                        var normTargetTitle = tTitle.toLowerCase().replace(/[^a-z0-9]/g, "");
                        var normTargetArtist = tArtist.toLowerCase().replace(/[^a-z0-9]/g, "");
                        var bestScore = -999999;

                        for (var i = 0; i < candidates.length; i++) {
                            var c = candidates[i] || {};
                            var cTitle = String(c.name || c.title || "");
                            var cArtist = String(c.artists || c.artist || c.artistName || (Array.isArray(c.artistNames) ? c.artistNames.join(" ") : ""));
                            var nTitle = cTitle.toLowerCase().replace(/[^a-z0-9]/g, "");
                            var nArtist = cArtist.toLowerCase().replace(/[^a-z0-9]/g, "");
                            var score = 0;
                            if (tIsrc && String(c.isrc || "").toLowerCase() === tIsrc.toLowerCase()) score += 200;
                            if (nTitle === normTargetTitle) score += 100;
                            else if (normTargetTitle && (nTitle.indexOf(normTargetTitle) >= 0 || normTargetTitle.indexOf(nTitle) >= 0)) score += 50;
                            if (normTargetArtist && (nArtist === normTargetArtist || nArtist.indexOf(normTargetArtist) >= 0 || normTargetArtist.indexOf(nArtist) >= 0)) score += 60;
                            var rawDur = Number(c.duration_ms || 0);
                            if (!rawDur && c.duration) {
                                var dNum = Number(c.duration);
                                rawDur = dNum > 10000 ? dNum : dNum * 1000;
                            }
                            if (tDurationMs > 0 && rawDur > 0) {
                                var diffSec = Math.abs(rawDur - tDurationMs) / 1000;
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

                        if (typeof ext.checkAvailability === 'function') {
                            try {
                                var availOpts = { duration_ms: tDurationMs, album: tAlbum };
                                if (trackId) availOpts[$qExtId.replace(/-web${'$'}/, "") + "_id"] = trackId;
                                var avail = await ext.checkAvailability(tIsrc, tTitle, tArtist, availOpts);
                                if (avail && avail.available && avail.track_id) {
                                    trackId = String(avail.track_id);
                                    preparedContext = avail.prepared_context || null;
                                }
                            } catch (eAv) {
                                log.warn("checkAvailability error: " + (eAv && eAv.message ? eAv.message : String(eAv)));
                            }
                        }

                        if (!trackId) {
                            AndroidHost.onResult(__cid, JSON.stringify({ ok: false, reason: "No matching track", formats: [] }));
                            return;
                        }

                        var tiers = $tiersJson;
                        var resolved = [];
                        var diagErrors = [];
                        for (var j = 0; j < tiers.length; j++) {
                            if (utils.getResolutionRemainingMs() < 400) break;
                            var q = tiers[j];
                            var directUrl = null;
                            if (typeof ext.getDownloadUrl === 'function') {
                                try {
                                    var gdu = await ext.getDownloadUrl(trackId, q);
                                    if (typeof gdu === 'string' && gdu.indexOf('http') === 0) {
                                        directUrl = gdu;
                                    } else if (gdu && typeof gdu.url === 'string') {
                                        directUrl = gdu.url;
                                    }
                                } catch (eGdu) {
                                    var msgGdu = eGdu && eGdu.message ? eGdu.message : String(eGdu);
                                    diagErrors.push(msgGdu);
                                }
                            }
                            if (directUrl) {
                                resolved.push({
                                    quality: q,
                                    url: directUrl,
                                    headers: {},
                                    bit_depth: q === "HI_RES_LOSSLESS" ? 24 : 16,
                                    sample_rate: q === "HI_RES_LOSSLESS" ? 96000 : 44100,
                                    audio_codec: q === "DOLBY_ATMOS" ? "eac3-joc" : (q === "HIGH" ? "aac" : "flac"),
                                    actual_extension: (q === "DOLBY_ATMOS" || q === "HIGH") ? "m4a" : "flac"
                                });
                                continue;
                            }
                            if (typeof ext.download === 'function') {
                                var capBefore = globalThis.__sflx_captures ? globalThis.__sflx_captures.length : 0;
                                var dlRes = null;
                                try {
                                    dlRes = await ext.download(trackId, q, "/virtual/" + $qExtId + "_" + q + ".flac", null, {
                                        allowFallback: false,
                                        preparedContext: preparedContext || (bestTrack ? { track_id: trackId, raw_track: bestTrack, formatted_track: bestTrack, track: bestTrack, host_track: bestTrack } : {})
                                    });
                                } catch (eDl) {
                                    var msgDl = eDl && eDl.message ? eDl.message : String(eDl);
                                    diagErrors.push(msgDl);
                                }
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
                                            audio_codec: String((dlRes && (dlRes.audio_codec || dlRes.codec)) || (q === "DOLBY_ATMOS" ? "eac3-joc" : (q === "HIGH" ? "aac" : "flac"))),
                                            actual_extension: String((dlRes && (dlRes.actual_extension || dlRes.output_extension)) || (q === "DOLBY_ATMOS" || q === "HIGH" ? "m4a" : "flac")).replace(/^\./, "")
                                        });
                                    }
                                } else if (dlRes && (dlRes.error_message || dlRes.error)) {
                                    diagErrors.push(String(dlRes.error_message || dlRes.error));
                                }
                            }
                        }

                        AndroidHost.onResult(__cid, JSON.stringify({
                            ok: resolved.length > 0,
                            reason: diagErrors.length ? diagErrors[0] : "",
                            formats: resolved
                        }));
                    } catch (eTop) {
                        AndroidHost.onError(__cid, eTop && eTop.message ? eTop.message : String(eTop));
                    }
                })();
            """.trimIndent()

            withContext(Dispatchers.Main) {
                wv.evaluateJavascript(runnerJs, null)
            }

            val resultJson = withTimeoutOrNull(DEFAULT_BUDGET_MS) { deferred.await() }
            if (resultJson == null) {
                poisoned = true
                return@withContext SflxResolutionResult(emptyList(), "V8 timeout")
            }

            val root = runCatching { JSONObject(resultJson) }.getOrNull()
                ?: return@withContext SflxResolutionResult(emptyList(), "Invalid JSON result")
            val formatsArr = root.optJSONArray("formats") ?: JSONArray()
            val reason = root.optString("reason").takeIf { it.isNotBlank() }
            val capturedDownloads = capturedDownloadsMap[callId].orEmpty()

            val out = mutableListOf<DownloadFormatOption>()
            val seenTiers = HashSet<String>()

            fun addMappedOption(
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
                if (!safeUrl.startsWith("https://")) return

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
                    isLossy -> if (extRaw.equals("mp3", ignoreCase = true)) "mp3" else "m4a"
                    else -> "flac"
                }
                val estKbps = when {
                    isAtmos -> 768
                    isLossy -> 320
                    bitDepth > 16 || sampleRate > 48000 -> 2304
                    else -> 1411
                }
                val tierDedup = "${pSlug}_${if (isAtmos) "atmos" else if (isLossy) "lossy_$fileExt" else "flac_$bitDepth"}_$safeUrl"
                if (!seenTiers.add(tierDedup)) return

                val khz = String.format(Locale.US, "%.1f", sampleRate / 1000.0)
                val label = when {
                    isAtmos -> "Dolby Atmos • E-AC-3 ($displayName)"
                    isLossy -> "${fileExt.uppercase(Locale.ROOT)} • $estKbps kbps ($displayName)"
                    bitDepth > 16 || sampleRate > 48000 -> "FLAC • $bitDepth-bit Hi-Res • $khz kHz ($displayName)"
                    else -> "FLAC • $bitDepth-bit Lossless • $khz kHz ($displayName)"
                }
                val estBytes = (estKbps * 1000L / 8L) * durationSec.coerceAtLeast(30)
                val formattedSize = String.format(Locale.US, "~%.1f MB", estBytes / (1024.0 * 1024.0))
                val normalizedHeaders = if (headersMap.any { it.key.equals("User-Agent", ignoreCase = true) }) {
                    headersMap
                } else {
                    headersMap + ("User-Agent" to LosslessAudioProvider.BROWSER_USER_AGENT)
                }
                val codecSlug = if (isAtmos) "atmos" else if (isLossy) fileExt else "flac"
                val tierTag = if (isLossless) "${codecSlug}_$bitDepth" else "${codecSlug}_$estKbps"

                out.add(
                    DownloadFormatOption(
                        id = "${pSlug}_${tierTag}_$idx",
                        label = label,
                        codec = if (isAtmos) "eac3-joc" else if (isLossy) fileExt else "flac",
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
                    ),
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
                addMappedOption(
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

            capturedDownloads.forEachIndexed { idx, cap ->
                addMappedOption(
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

            val latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            if (out.isNotEmpty()) {
                SourceHealthTracker.recordReady(configId, latencyMs)
            } else if (reason != null) {
                Log.w(TAG, "$displayName ($extensionId) V8 returned 0 formats: $reason")
            }
            SflxResolutionResult(out, reason)
        } finally {
            pendingCalls.remove(callId)
            capturedDownloadsMap.remove(callId)
            activeCallDeadlines.remove(callId)
            releaseWebView(wv, poisoned)
        }
    }

    private fun buildTiersJson(manifestJson: String?): String {
        val arr = JSONArray()
        runCatching {
            val manifestTiers = manifestJson?.let { JSONObject(it).optJSONArray("qualityOptions") }
            if (manifestTiers != null && manifestTiers.length() > 0) {
                for (i in 0 until manifestTiers.length()) {
                    val id = manifestTiers.optJSONObject(i)?.optString("id").orEmpty()
                    if (id.isNotBlank() && !id.equals("LOW", ignoreCase = true) && arr.length() < 4) {
                        arr.put(id)
                    }
                }
            }
        }
        if (arr.length() == 0) {
            arr.put("HI_RES_LOSSLESS")
            arr.put("LOSSLESS")
            arr.put("DOLBY_ATMOS")
            arr.put("HIGH")
        }
        return arr.toString()
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

    private fun buildHostShimJs(callId: String, extensionId: String, manifestJson: String?): String {
        val signedBaseUrl = runCatching {
            manifestJson?.let { JSONObject(it).optJSONObject("signedSession")?.optString("baseUrl") }.orEmpty()
        }.getOrDefault("")
        val extSettingsJson = buildSettingsJson(manifestJson)
        val qExtId = JSONObject.quote(extensionId)
        val qCallId = JSONObject.quote(callId)
        val qSignedBase = JSONObject.quote(signedBaseUrl)
        val qUa = JSONObject.quote(LosslessAudioProvider.BROWSER_USER_AGENT)

        return """
            globalThis.__sflx_ext = {};
            globalThis.__sflx_captures = [];
            var extensionId = $qExtId;
            var settings = $extSettingsJson;
            settings.get = function(k) { return settings[k]; };
            settings.set = function(k, v) { settings[k] = v; };

            function registerExtension(ext) {
                globalThis.__sflx_ext = ext || {};
            }

            var log = {
                info: function() { AndroidHost.logMsg($qExtId, "info", Array.prototype.slice.call(arguments).join(" ")); },
                debug: function() { AndroidHost.logMsg($qExtId, "debug", Array.prototype.slice.call(arguments).join(" ")); },
                warn: function() { AndroidHost.logMsg($qExtId, "warn", Array.prototype.slice.call(arguments).join(" ")); },
                error: function() { AndroidHost.logMsg($qExtId, "error", Array.prototype.slice.call(arguments).join(" ")); }
            };

            var storage = {
                get: function(k) {
                    var v = AndroidHost.storageGet($qExtId, String(k || ""));
                    if (!v) return null;
                    try { return JSON.parse(v); } catch (_) { return v; }
                },
                set: function(k, v) {
                    AndroidHost.storageSet($qExtId, String(k || ""), typeof v === "string" ? v : JSON.stringify(v));
                }
            };

            var utils = {
                base64Decode: function(s) { try { return atob(String(s || "")); } catch (_) { return ""; } },
                base64Encode: function(s) { try { return btoa(String(s || "")); } catch (_) { return ""; } },
                md5: function(s) { return AndroidHost.utilsHash("MD5", String(s || "")); },
                sha256: function(s) { return AndroidHost.utilsHash("SHA-256", String(s || "")); },
                sleep: function(ms) { return new Promise(function(r) { setTimeout(r, Math.min(Number(ms || 0), 1000)); }); },
                getResolutionRemainingMs: function() { return Number(AndroidHost.remainingMs($qCallId) || 0); },
                isDownloadCancelled: function() { return false; },
                appUserAgent: function() { return $qUa; },
                randomUserAgent: function() { return $qUa; },
                appVersion: function() { return "5.3.0"; }
            };

            var http = {
                get: function(url, headers) {
                    return JSON.parse(AndroidHost.httpRequest(JSON.stringify({
                        method: "GET",
                        url: String(url || ""),
                        body: null,
                        headers: headers || {}
                    })));
                },
                post: function(url, body, headers) {
                    var bodyArg = (body === undefined || body === null) ? "" : (typeof body === "string" ? body : JSON.stringify(body));
                    return JSON.parse(AndroidHost.httpRequest(JSON.stringify({
                        method: "POST",
                        url: String(url || ""),
                        body: bodyArg,
                        headers: headers || {}
                    })));
                },
                request: function(opts) {
                    opts = opts || {};
                    var bodyArg = (opts.body === undefined || opts.body === null) ? null : (typeof opts.body === "string" ? opts.body : JSON.stringify(opts.body));
                    return JSON.parse(AndroidHost.httpRequest(JSON.stringify({
                        method: String(opts.method || "GET"),
                        url: String(opts.url || ""),
                        body: bodyArg,
                        headers: opts.headers || {}
                    })));
                },
                clearCookies: function() { AndroidHost.clearCookies(); }
            };

            var session = {
                signedFetch: function(method, path, body, headers) {
                    var bodyArg = (body === undefined || body === null) ? null : (typeof body === "string" ? body : JSON.stringify(body));
                    return JSON.parse(AndroidHost.sessionSignedFetch(JSON.stringify({
                        extensionId: $qExtId,
                        signedBaseUrl: $qSignedBase,
                        method: String(method || "GET"),
                        path: String(path || ""),
                        body: bodyArg,
                        headers: headers || {}
                    })));
                },
                completeGrant: function() { return { success: false }; }
            };

            var file = {
                download: function(url, outputPath, opts) {
                    var headers = (opts && opts.headers) || {};
                    globalThis.__sflx_captures.push({ url: String(url || ""), headers: headers });
                    AndroidHost.captureDownload($qCallId, String(url || ""), JSON.stringify(headers));
                    return { success: true, path: String(outputPath || "/virtual/out.flac"), size: 42000000 };
                },
                downloadSegments: function(opts) {
                    opts = opts || {};
                    var u = String(opts.manifestUrl || opts.url || (opts.segments && opts.segments[0]) || "");
                    var headers = opts.headers || {};
                    globalThis.__sflx_captures.push({ url: u, headers: headers });
                    AndroidHost.captureDownload($qCallId, u, JSON.stringify(headers));
                    return { success: Boolean(u), path: String(opts.outputPath || "/virtual/out.flac"), size: 42000000 };
                },
                exists: function() { return false; },
                getSize: function() { return 42000000; },
                delete: function() { return true; },
                transformPatternedBlocks: function() { return { success: false }; }
            };

            var gobackend = {
                checkISRCExists: function() { return null; },
                getAudioQuality: function() { return null; },
                getLyricsLRC: function() { return null; }
            };
            var gobridge = gobackend;
            var ffmpeg = {
                mov_key: function() { return { success: false }; }
            };

            globalThis.fetch = function(url, opts) {
                opts = opts || {};
                var res = http.request({
                    method: String(opts.method || "GET"),
                    url: String(url || ""),
                    body: opts.body,
                    headers: opts.headers || {}
                });
                var statusCode = Number(res.statusCode || res.status || 0);
                var bodyText = String(res.body || "");
                return Promise.resolve({
                    ok: statusCode >= 200 && statusCode < 300,
                    status: statusCode,
                    statusCode: statusCode,
                    headers: res.headers || {},
                    body: bodyText,
                    text: function() { return Promise.resolve(bodyText); },
                    json: function() { return Promise.resolve(JSON.parse(bodyText || "{}")); }
                });
            };
        """.trimIndent()
    }

    private fun wrapExtensionCode(indexJs: String): String {
        val cleaned = indexJs
            .replace(Regex("""^\s*export\s+default\s+""", RegexOption.MULTILINE), "globalThis.__sflx_default_export = ")
            .replace(Regex("""^\s*export\s+(?=(?:async\s+)?(?:const|let|var|function|class)\b)""", RegexOption.MULTILINE), "")
            .replace(Regex("""^\s*export\s*\{[^}]*\}\s*;?""", RegexOption.MULTILINE), "")
            .replace(Regex("""^\s*import\s+[\s\S]*?\s+from\s+['"][^'"]*['"]\s*;?\s*$""", RegexOption.MULTILINE), "")
        return """
            var module = { exports: {} };
            var exports = module.exports;
            globalThis.__sflx_default_export = undefined;
            $cleaned
            if ((!globalThis.__sflx_ext || !Object.keys(globalThis.__sflx_ext).length) && module.exports && Object.keys(module.exports).length) {
                globalThis.__sflx_ext = module.exports;
            } else if ((!globalThis.__sflx_ext || !Object.keys(globalThis.__sflx_ext).length) && globalThis.__sflx_default_export) {
                globalThis.__sflx_ext = typeof globalThis.__sflx_default_export === 'function' ? new globalThis.__sflx_default_export() : globalThis.__sflx_default_export;
            }
        """.trimIndent()
    }

    private fun executeHttpJson(
        rawUrl: String,
        method: String,
        bodyStr: String?,
        headersJson: String,
    ): String {
        val safeUrl = LosslessAudioProvider.forceHttps(rawUrl)
        if (!safeUrl.startsWith("https://")) {
            return JSONObject().apply {
                put("status", 400)
                put("statusCode", 400)
                put("ok", false)
                put("headers", JSONObject())
                put("body", "")
                put("error", "Invalid URL: $rawUrl")
            }.toString()
        }

        return runCatching {
            val reqBuilder = Request.Builder().url(safeUrl)
            var hasUa = false
            var contentType: String? = null
            runCatching {
                val hObj = JSONObject(headersJson)
                hObj.keys().forEach { k ->
                    val v = hObj.optString(k)
                    if (v.isNotBlank()) {
                        if (k.equals("User-Agent", ignoreCase = true)) hasUa = true
                        if (k.equals("Content-Type", ignoreCase = true)) contentType = v
                        reqBuilder.header(k, v)
                    }
                }
            }
            if (!hasUa) reqBuilder.header("User-Agent", LosslessAudioProvider.BROWSER_USER_AGENT)

            if (cookieJar.isNotEmpty()) {
                val host = runCatching { java.net.URI(safeUrl).host }.getOrNull().orEmpty()
                val cookie = cookieJar[host]
                if (!cookie.isNullOrBlank()) reqBuilder.header("Cookie", cookie)
            }

            when (method) {
                "POST", "PUT", "PATCH", "DELETE" -> {
                    val mediaType = (contentType ?: "application/json; charset=utf-8").toMediaType()
                    reqBuilder.method(method, (bodyStr ?: "").toRequestBody(mediaType))
                }
                else -> reqBuilder.get()
            }

            syncHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                val respHeaders = JSONObject()
                resp.headers.names().forEach { name ->
                    respHeaders.put(name, resp.headers[name] ?: "")
                }
                val host = runCatching { java.net.URI(safeUrl).host }.getOrNull().orEmpty()
                val setCookie = resp.headers("Set-Cookie")
                if (host.isNotBlank() && setCookie.isNotEmpty()) {
                    cookieJar[host] = setCookie.joinToString("; ") { it.substringBefore(';') }
                }
                JSONObject().apply {
                    put("status", resp.code)
                    put("statusCode", resp.code)
                    put("ok", resp.isSuccessful)
                    put("headers", respHeaders)
                    put("body", respBody)
                }.toString()
            }
        }.getOrElse { err ->
            JSONObject().apply {
                put("status", 0)
                put("statusCode", 0)
                put("ok", false)
                put("headers", JSONObject())
                put("body", "")
                put("error", err.message ?: "Network error")
            }.toString()
        }
    }
}
