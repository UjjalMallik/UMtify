package com.music.bitchord.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Headless Android WebView (Chromium V8) Runtime for executing Mornye `.sflx`
 * JavaScript extensions (`manifest.json` + `index.js`).
 *
 * Uses ONE Mutex-guarded persistent headless [WebView] and a non-blocking async
 * `@JavascriptInterface` HTTP bridge (`httpRequestAsync` / `sessionSignedFetchAsync`)
 * running `OkHttpClient` on `Dispatchers.IO` and resolving V8 Promises via
 * `evaluateJavascript("window.__resolveHttp(...)")` on `Dispatchers.Main`.
 */
@Keep
object WebViewSflxRuntime {
    private const val TAG = "WebViewSflxRuntime"
    private const val PREFS_NAME = "sflx_ext_storage"
    private const val DEFAULT_BUDGET_MS = 12_000L
    private const val HEALTH_CACHE_TTL_MS = 10 * 60 * 1000L

    @Volatile
    private var appContext: Context? = null
    private var storagePrefs: SharedPreferences? = null

    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val webViewInitMutex = Mutex()

    @Volatile
    private var persistentWebView: WebView? = null

    @Volatile
    private var bootstrapInstalled = false

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

    private val asyncHttpClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(4000, TimeUnit.MILLISECONDS)
            .readTimeout(8000, TimeUnit.MILLISECONDS)
            .callTimeout(9000, TimeUnit.MILLISECONDS)
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
        fun httpRequestAsync(reqId: String, reqJson: String) {
            runtimeScope.launch(Dispatchers.IO) {
                val respJson = runCatching {
                    val obj = JSONObject(reqJson)
                    val rawUrl = obj.optString("url")
                    val method = obj.optString("method", "GET").uppercase(Locale.ROOT)
                    val bodyStr = if (obj.isNull("body")) null else obj.optString("body")
                    val headersJson = obj.optJSONObject("headers")?.toString() ?: "{}"
                    executeHttpJson(rawUrl, method, bodyStr, headersJson)
                }.getOrElse { err ->
                    JSONObject().apply {
                        put("status", 0)
                        put("statusCode", 0)
                        put("ok", false)
                        put("headers", JSONObject())
                        put("body", "")
                        put("error", err.message ?: "httpRequestAsync error")
                    }.toString()
                }
                dispatchHttpResolution(reqId, respJson)
            }
        }

        @JavascriptInterface
        @Keep
        fun sessionSignedFetchAsync(reqId: String, reqJson: String) {
            runtimeScope.launch(Dispatchers.IO) {
                val respJson = runCatching {
                    val obj = JSONObject(reqJson)
                    val signedBaseUrl = obj.optString("signedBaseUrl").trimEnd('/')
                    val path = obj.optString("path")
                    val method = obj.optString("method", "GET").uppercase(Locale.ROOT)
                    val bodyStr = if (obj.isNull("body")) null else obj.optString("body")
                    val headersJson = obj.optJSONObject("headers")?.toString() ?: "{}"
                    if (signedBaseUrl.isBlank() && !path.startsWith("http", ignoreCase = true)) {
                        JSONObject().apply {
                            put("status", 403)
                            put("statusCode", 403)
                            put("ok", false)
                            put("headers", JSONObject())
                            put("body", "")
                            put("error", "No signedSession baseUrl")
                        }.toString()
                    } else {
                        val fullUrl = if (path.startsWith("http", ignoreCase = true)) {
                            path
                        } else {
                            "$signedBaseUrl/${path.trimStart('/')}"
                        }
                        executeHttpJson(fullUrl, method, bodyStr, headersJson)
                    }
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
                dispatchHttpResolution(reqId, respJson)
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
            val deadline = activeCallDeadlines[callId] ?: return "8000"
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
                    Collections.synchronizedList(mutableListOf())
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

    private suspend fun dispatchHttpResolution(reqId: String, respJson: String) {
        val wv = persistentWebView ?: return
        val qReqId = JSONObject.quote(reqId)
        val qRespJson = JSONObject.quote(respJson)
        withContext(Dispatchers.Main) {
            runCatching {
                wv.evaluateJavascript("window.__resolveHttp && window.__resolveHttp($qReqId, $qRespJson);", null)
            }
        }
    }

    /**
     * Lazily initializes ONE persistent headless [WebView] on `Dispatchers.Main`,
     * guarded by [webViewInitMutex], and installs the global async Mornye host bootstrap.
     */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun getOrCreateWebView(): WebView? {
        persistentWebView?.let { existing ->
            if (bootstrapInstalled) return existing
        }
        return webViewInitMutex.withLock {
            persistentWebView?.let { existing ->
                if (bootstrapInstalled) return@withLock existing
            }
            val ctx = appContext ?: return@withLock null
            val pageReady = CompletableDeferred<Unit>()
            val wv = runCatching {
                withContext(Dispatchers.Main) {
                    (persistentWebView ?: WebView(ctx).apply {
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
                                if (!pageReady.isCompleted) pageReady.complete(Unit)
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): WebResourceResponse? {
                                val reqUrl = request?.url?.toString().orEmpty()
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
                    }).also { persistentWebView = it }
                }
            }.getOrElse { err ->
                Log.w(TAG, "Failed to initialize persistent headless WebView: ${err.message}")
                return@withLock null
            }

            withTimeoutOrNull(4000L) { pageReady.await() }

            if (!bootstrapInstalled) {
                val installed = CompletableDeferred<Unit>()
                withContext(Dispatchers.Main) {
                    wv.evaluateJavascript(buildGlobalBootstrapJs()) {
                        bootstrapInstalled = true
                        if (!installed.isCompleted) installed.complete(Unit)
                    }
                }
                withTimeoutOrNull(2000L) { installed.await() }
            }
            wv
        }
    }

    /**
     * Checks `.sflx` installation, evaluates `index.js` inside its isolated namespace in V8,
     * verifies exports + optional live serviceHealth / mirror fallback.
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

        val wv = getOrCreateWebView()
        if (wv == null) {
            val reason = "WebView init failed"
            SourceHealthTracker.recordError(configId, reason)
            return@withContext Result.failure(IllegalStateException(reason))
        }

        val callId = "health_${callSeq.incrementAndGet()}_${UUID.randomUUID().toString().take(8)}"
        val deferred = CompletableDeferred<String>()
        pendingCalls[callId] = deferred
        activeCallDeadlines[callId] = System.currentTimeMillis() + 5000L

        val scriptOk = try {
            val qCallId = JSONObject.quote(callId)
            val qSignedBase = JSONObject.quote(extractSignedBaseUrl(manifestJson))
            val settingsJson = buildSettingsJson(manifestJson)

            val probeJs = buildIsolatedInvocationJs(
                callId = callId,
                extId = extensionId,
                settingsJson = settingsJson,
                signedBaseUrl = qSignedBase,
                indexJs = indexJs,
                bodyJs = """
                    var hasSearch = typeof ext.customSearch === 'function' || typeof ext.searchTracks === 'function' || typeof ext.search === 'function' || typeof ext.checkAvailability === 'function';
                    var hasDownload = typeof ext.download === 'function' || typeof ext.getDownloadUrl === 'function';
                    var initOk = true;
                    if (typeof ext.initialize === 'function') {
                        try {
                            var initRes = await ext.initialize(settings || {});
                            initOk = initRes !== false;
                        } catch (_) {
                            initOk = false;
                        }
                    }
                    AndroidHost.onResult($qCallId, JSON.stringify({
                        ok: (hasSearch || hasDownload) && initOk,
                        hasSearch: hasSearch,
                        hasDownload: hasDownload
                    }));
                """.trimIndent(),
            )

            withContext(Dispatchers.Main) {
                wv.evaluateJavascript(probeJs, null)
            }
            val raw = withTimeoutOrNull(4500L) { deferred.await() }
            raw?.let { runCatching { JSONObject(it).optBoolean("ok", false) }.getOrDefault(false) } ?: false
        } finally {
            pendingCalls.remove(callId)
            activeCallDeadlines.remove(callId)
        }

        if (!scriptOk) {
            val reason = "V8 init failed"
            SourceHealthTracker.recordError(configId, reason)
            return@withContext Result.failure(IllegalStateException(reason))
        }

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
                asyncHttpClient.newCall(req).execute().use { resp ->
                    val b = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        sharedHealthResponses[safeUrl] = CachedHealthResponse(resp.code, b, System.currentTimeMillis())
                    }
                    resp.code
                }
            }.getOrDefault(0)

            if (code !in 200..299) {
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
     * Executes an extracted `.sflx` script inside its isolated namespace in the persistent
     * WebView V8 engine (12s budget) and awaits resolved download formats.
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

        val wv = getOrCreateWebView()
            ?: return@withContext SflxResolutionResult(emptyList(), "WebView init failed")

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

        try {
            val tiersJson = buildTiersJson(manifestJson)
            val queriesArr = JSONArray().apply {
                val qFull = "$cleanedTitle $cleanArtist".trim()
                val qPrimary = "$cleanedTitle $primaryArtist".trim()
                if (qFull.isNotBlank()) put(qFull)
                if (qPrimary.isNotBlank() && !qPrimary.equals(qFull, ignoreCase = true)) put(qPrimary)
                if (cleanedTitle.isNotBlank() && primaryArtist.isBlank()) put(cleanedTitle)
            }

            val qTitle = JSONObject.quote(cleanedTitle)
            val qArtist = JSONObject.quote(primaryArtist.ifBlank { cleanArtist })
            val qAlbum = JSONObject.quote((album ?: "").trim())
            val qIsrc = JSONObject.quote((isrc ?: "").trim())
            val qCallId = JSONObject.quote(callId)
            val qExtId = JSONObject.quote(extensionId)
            val qSignedBase = JSONObject.quote(extractSignedBaseUrl(manifestJson))
            val settingsJson = buildSettingsJson(manifestJson)
            val durationMs = durationSec.coerceAtLeast(0) * 1000L

            val bodyJs = """
                if (typeof ext.initialize === 'function') {
                    try { await ext.initialize(settings || {}); } catch (eInit) {
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
                    AndroidHost.onResult($qCallId, JSON.stringify({ ok: false, reason: "No matching track", formats: [] }));
                    return;
                }

                var tiers = $tiersJson;
                var resolved = [];
                var diagErrors = [];
                for (var j = 0; j < tiers.length; j++) {
                    if (utils.getResolutionRemainingMs() < 500) break;
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
                            diagErrors.push(eGdu && eGdu.message ? eGdu.message : String(eGdu));
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
                        var capBefore = captures.length;
                        var dlRes = null;
                        try {
                            dlRes = await ext.download(trackId, q, "/virtual/" + $qExtId + "_" + q + ".flac", null, {
                                allowFallback: false,
                                preparedContext: preparedContext || (bestTrack ? { track_id: trackId, raw_track: bestTrack, formatted_track: bestTrack, track: bestTrack, host_track: bestTrack } : {})
                            });
                        } catch (eDl) {
                            diagErrors.push(eDl && eDl.message ? eDl.message : String(eDl));
                        }
                        var capAfter = captures.length;
                        if (capAfter > capBefore) {
                            var latest = captures[capAfter - 1];
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

                AndroidHost.onResult($qCallId, JSON.stringify({
                    ok: resolved.length > 0,
                    reason: diagErrors.length ? diagErrors[0] : "",
                    formats: resolved
                }));
            """.trimIndent()

            val runnerJs = buildIsolatedInvocationJs(
                callId = callId,
                extId = extensionId,
                settingsJson = settingsJson,
                signedBaseUrl = qSignedBase,
                indexJs = indexJs,
                bodyJs = bodyJs,
            )

            withContext(Dispatchers.Main) {
                wv.evaluateJavascript(runnerJs, null)
            }

            val resultJson = withTimeoutOrNull(DEFAULT_BUDGET_MS) { deferred.await() }
                ?: return@withContext SflxResolutionResult(emptyList(), "V8 timeout")

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
        }
    }

    /**
     * Global bootstrap installed once into the persistent headless WebView:
     * manages async Promise HTTP callbacks (`window.__resolveHttp`) and creates
     * per-extension isolated host APIs (`window.__sflxMakeHost`) + module map (`window.__sflx_modules`).
     */
    private fun buildGlobalBootstrapJs(): String {
        val qUa = JSONObject.quote(LosslessAudioProvider.BROWSER_USER_AGENT)
        return """
            (function() {
                if (window.__sflx_bootstrapped) return;
                window.__sflx_bootstrapped = true;
                window.__sflx_modules = window.__sflx_modules || {};
                window.__httpPending = window.__httpPending || {};
                window.__httpSeq = 0;

                window.__resolveHttp = function(reqId, respJson) {
                    var cb = window.__httpPending[reqId];
                    if (!cb) return;
                    delete window.__httpPending[reqId];
                    try {
                        var parsed = (typeof respJson === 'string') ? JSON.parse(respJson) : respJson;
                        cb(parsed || { status: 0, statusCode: 0, ok: false, headers: {}, body: "" });
                    } catch (e) {
                        cb({ status: 0, statusCode: 0, ok: false, headers: {}, body: "", error: String(e) });
                    }
                };

                function callHttpAsync(opts) {
                    return new Promise(function(resolve) {
                        var reqId = "h_" + (++window.__httpSeq) + "_" + Date.now();
                        window.__httpPending[reqId] = resolve;
                        AndroidHost.httpRequestAsync(reqId, JSON.stringify(opts || {}));
                    });
                }

                function callSignedAsync(opts) {
                    return new Promise(function(resolve) {
                        var reqId = "s_" + (++window.__httpSeq) + "_" + Date.now();
                        window.__httpPending[reqId] = resolve;
                        AndroidHost.sessionSignedFetchAsync(reqId, JSON.stringify(opts || {}));
                    });
                }

                window.__sflxMakeHost = function(extId, callId, signedBaseUrl, settingsObj, captures) {
                    settingsObj = settingsObj || {};
                    settingsObj.get = function(k) { return settingsObj[k]; };
                    settingsObj.set = function(k, v) { settingsObj[k] = v; };

                    var http = {
                        get: function(url, headers) {
                            return callHttpAsync({
                                method: "GET",
                                url: String(url || ""),
                                body: null,
                                headers: headers || {}
                            });
                        },
                        post: function(url, body, headers) {
                            var bodyArg = (body === undefined || body === null) ? "" : (typeof body === "string" ? body : JSON.stringify(body));
                            return callHttpAsync({
                                method: "POST",
                                url: String(url || ""),
                                body: bodyArg,
                                headers: headers || {}
                            });
                        },
                        request: function(opts) {
                            opts = opts || {};
                            var bodyArg = (opts.body === undefined || opts.body === null) ? null : (typeof opts.body === "string" ? opts.body : JSON.stringify(opts.body));
                            return callHttpAsync({
                                method: String(opts.method || "GET"),
                                url: String(opts.url || ""),
                                body: bodyArg,
                                headers: opts.headers || {}
                            });
                        },
                        clearCookies: function() { AndroidHost.clearCookies(); }
                    };

                    var session = {
                        signedFetch: function(method, path, body, headers) {
                            var bodyArg = (body === undefined || body === null) ? null : (typeof body === "string" ? body : JSON.stringify(body));
                            return callSignedAsync({
                                extensionId: extId,
                                signedBaseUrl: signedBaseUrl || "",
                                method: String(method || "GET"),
                                path: String(path || ""),
                                body: bodyArg,
                                headers: headers || {}
                            });
                        },
                        completeGrant: function() { return { success: false }; }
                    };

                    var fetchFn = function(url, opts) {
                        opts = opts || {};
                        return http.request({
                            method: String(opts.method || "GET"),
                            url: String(url || ""),
                            body: opts.body,
                            headers: opts.headers || {}
                        }).then(function(res) {
                            var statusCode = Number(res.statusCode || res.status || 0);
                            var bodyText = String(res.body || "");
                            return {
                                ok: statusCode >= 200 && statusCode < 300,
                                status: statusCode,
                                statusCode: statusCode,
                                headers: res.headers || {},
                                body: bodyText,
                                text: function() { return Promise.resolve(bodyText); },
                                json: function() { return Promise.resolve(JSON.parse(bodyText || "{}")); }
                            };
                        });
                    };

                    var log = {
                        info: function() { AndroidHost.logMsg(extId, "info", Array.prototype.slice.call(arguments).join(" ")); },
                        debug: function() { AndroidHost.logMsg(extId, "debug", Array.prototype.slice.call(arguments).join(" ")); },
                        warn: function() { AndroidHost.logMsg(extId, "warn", Array.prototype.slice.call(arguments).join(" ")); },
                        error: function() { AndroidHost.logMsg(extId, "error", Array.prototype.slice.call(arguments).join(" ")); }
                    };

                    var storage = {
                        get: function(k) {
                            var v = AndroidHost.storageGet(extId, String(k || ""));
                            if (!v) return null;
                            try { return JSON.parse(v); } catch (_) { return v; }
                        },
                        set: function(k, v) {
                            AndroidHost.storageSet(extId, String(k || ""), typeof v === "string" ? v : JSON.stringify(v));
                        }
                    };

                    var utils = {
                        base64Decode: function(s) { try { return atob(String(s || "")); } catch (_) { return ""; } },
                        base64Encode: function(s) { try { return btoa(String(s || "")); } catch (_) { return ""; } },
                        md5: function(s) { return AndroidHost.utilsHash("MD5", String(s || "")); },
                        sha256: function(s) { return AndroidHost.utilsHash("SHA-256", String(s || "")); },
                        sleep: function(ms) { return new Promise(function(r) { setTimeout(r, Math.min(Number(ms || 0), 1000)); }); },
                        getResolutionRemainingMs: function() { return Number(AndroidHost.remainingMs(callId) || 0); },
                        isDownloadCancelled: function() { return false; },
                        appUserAgent: function() { return $qUa; },
                        randomUserAgent: function() { return $qUa; },
                        appVersion: function() { return "5.3.0"; }
                    };

                    var file = {
                        download: function(url, outputPath, opts) {
                            var headers = (opts && opts.headers) || {};
                            captures.push({ url: String(url || ""), headers: headers });
                            AndroidHost.captureDownload(callId, String(url || ""), JSON.stringify(headers));
                            return { success: true, path: String(outputPath || "/virtual/out.flac"), size: 42000000 };
                        },
                        downloadSegments: function(opts) {
                            opts = opts || {};
                            var u = String(opts.manifestUrl || opts.url || (opts.segments && opts.segments[0]) || "");
                            var headers = opts.headers || {};
                            captures.push({ url: u, headers: headers });
                            AndroidHost.captureDownload(callId, u, JSON.stringify(headers));
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

                    return {
                        extensionId: extId,
                        settings: settingsObj,
                        http: http,
                        session: session,
                        fetch: fetchFn,
                        log: log,
                        storage: storage,
                        utils: utils,
                        file: file,
                        gobackend: gobackend,
                        gobridge: gobackend,
                        ffmpeg: { mov_key: function() { return { success: false }; } },
                        registerExtension: function(extObj) {
                            window.__sflx_modules[extId] = extObj || {};
                        }
                    };
                };
            })();
        """.trimIndent()
    }

    /**
     * Wraps `.sflx` execution in its own isolated closure inside `window.__sflx_modules[extId]`
     * so concurrent executions share V8 without colliding on globals.
     */
    private fun buildIsolatedInvocationJs(
        callId: String,
        extId: String,
        settingsJson: String,
        signedBaseUrl: String,
        indexJs: String,
        bodyJs: String,
    ): String {
        val qCallId = JSONObject.quote(callId)
        val qExtId = JSONObject.quote(extId)
        val cleaned = indexJs
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
            .replace(Regex("""^\s*export\s+default\s+""", RegexOption.MULTILINE), "var __sflx_default_export = ")
            .replace(Regex("""^\s*export\s+(?=(?:async\s+)?(?:const|let|var|function|class)\b)""", RegexOption.MULTILINE), "")
            .replace(Regex("""^\s*export\s*\{[^}]*\}\s*;?""", RegexOption.MULTILINE), "")
            .replace(Regex("""^\s*import\s+[\s\S]*?\s+from\s+['"][^'"]*['"]\s*;?\s*$""", RegexOption.MULTILINE), "")

        return """
            (async function() {
                var __cid = $qCallId;
                var __eid = $qExtId;
                try {
                    var captures = [];
                    var __host = window.__sflxMakeHost(__eid, __cid, $signedBaseUrl, $settingsJson, captures);
                    var extensionId = __host.extensionId;
                    var settings = __host.settings;
                    var http = __host.http;
                    var session = __host.session;
                    var fetch = __host.fetch;
                    var log = __host.log;
                    var storage = __host.storage;
                    var utils = __host.utils;
                    var file = __host.file;
                    var gobackend = __host.gobackend;
                    var gobridge = __host.gobridge;
                    var ffmpeg = __host.ffmpeg;
                    var registerExtension = __host.registerExtension;
                    var module = { exports: {} };
                    var exports = module.exports;
                    var __sflx_default_export = undefined;

                    window.__sflx_modules[__eid] = window.__sflx_modules[__eid] || {};
                    $cleaned

                    var ext = window.__sflx_modules[__eid];
                    if ((!ext || !Object.keys(ext).length) && module.exports && Object.keys(module.exports).length) {
                        ext = module.exports;
                    } else if ((!ext || !Object.keys(ext).length) && __sflx_default_export) {
                        ext = (typeof __sflx_default_export === 'function') ? new __sflx_default_export() : __sflx_default_export;
                    }
                    window.__sflx_modules[__eid] = ext || {};

                    $bodyJs
                } catch (eTop) {
                    AndroidHost.onError(__cid, eTop && eTop.message ? eTop.message : String(eTop));
                }
            })();
        """.trimIndent()
    }

    private fun extractSignedBaseUrl(manifestJson: String?): String =
        runCatching {
            manifestJson?.let { JSONObject(it).optJSONObject("signedSession")?.optString("baseUrl") }.orEmpty()
        }.getOrDefault("")

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

            asyncHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
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
