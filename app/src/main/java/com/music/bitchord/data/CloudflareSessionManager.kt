package com.music.bitchord.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent Cloudflare session store (`cf_clearance` + session cookies + exact
 * WebView `User-Agent`) keyed by host (e.g. `api.zarz.moe`), plus a single-flight
 * in-app verification coordinator for `.sflx` Download Providers.
 */
object CloudflareSessionManager {
    private const val TAG = "CloudflareSession"
    private const val PREFS_NAME = "cloudflare_sessions"
    private const val KEY_COOKIE_PREFIX = "cookie_"
    private const val KEY_UA_PREFIX = "ua_"
    private const val KEY_TS_PREFIX = "ts_"
    private const val VERIFY_TIMEOUT_MS = 45_000L
    private const val SESSION_MAX_AGE_MS = 2 * 60 * 60 * 1000L // 2 hours
    private const val COOLDOWN_ON_CANCEL_MS = 20_000L

    data class StoredSession(
        val cookieHeader: String,
        val userAgent: String,
        val savedAtMs: Long,
    )

    data class VerificationRequest(
        val url: String,
        val host: String,
        val deferred: CompletableDeferred<Boolean>,
    )

    @Volatile
    private var prefs: SharedPreferences? = null

    private val memoryCache = ConcurrentHashMap<String, StoredSession>()
    private val failedCooldowns = ConcurrentHashMap<String, Long>()
    private val verifyMutex = Mutex()

    private val _pendingVerification = MutableStateFlow<VerificationRequest?>(null)
    val pendingVerification: StateFlow<VerificationRequest?> = _pendingVerification.asStateFlow()

    private val _verificationEpoch = MutableStateFlow(0L)
    val verificationEpoch: StateFlow<Long> = _verificationEpoch.asStateFlow()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun hostOf(url: String): String =
        runCatching { URI(LosslessAudioProvider.forceHttps(url)).host?.lowercase(Locale.ROOT) }.getOrNull().orEmpty()

    fun sessionForHost(host: String): StoredSession? {
        val normHost = host.lowercase(Locale.ROOT).trim()
        if (normHost.isBlank()) return null

        val cached = memoryCache[normHost]
        if (cached != null && System.currentTimeMillis() - cached.savedAtMs < SESSION_MAX_AGE_MS) {
            return cached
        }

        val p = prefs ?: return null
        val cookie = p.getString("$KEY_COOKIE_PREFIX$normHost", null)?.takeIf { it.isNotBlank() } ?: return null
        val ua = p.getString("$KEY_UA_PREFIX$normHost", null)?.takeIf { it.isNotBlank() }
            ?: LosslessAudioProvider.BROWSER_USER_AGENT
        val ts = p.getLong("$KEY_TS_PREFIX$normHost", 0L)
        if (ts > 0L && System.currentTimeMillis() - ts >= SESSION_MAX_AGE_MS) {
            return null
        }

        val loaded = StoredSession(cookieHeader = cookie, userAgent = ua, savedAtMs = ts.takeIf { it > 0L } ?: System.currentTimeMillis())
        memoryCache[normHost] = loaded
        return loaded
    }

    fun cookieForHost(host: String): String? = sessionForHost(host)?.cookieHeader

    fun userAgentForHost(host: String): String? = sessionForHost(host)?.userAgent

    fun saveSession(host: String, cookieHeader: String, userAgent: String) {
        val normHost = host.lowercase(Locale.ROOT).trim()
        if (normHost.isBlank() || cookieHeader.isBlank()) return
        val cleanUa = userAgent.takeIf { it.isNotBlank() } ?: LosslessAudioProvider.BROWSER_USER_AGENT
        val now = System.currentTimeMillis()
        val session = StoredSession(cookieHeader = cookieHeader, userAgent = cleanUa, savedAtMs = now)
        memoryCache[normHost] = session
        failedCooldowns.remove(normHost)
        _verificationEpoch.value += 1L
        prefs?.edit()
            ?.putString("$KEY_COOKIE_PREFIX$normHost", cookieHeader)
            ?.putString("$KEY_UA_PREFIX$normHost", cleanUa)
            ?.putLong("$KEY_TS_PREFIX$normHost", now)
            ?.apply()
        Log.d(TAG, "Saved Cloudflare clearance session for $normHost (${cookieHeader.length} chars)")
    }

    fun clearSession(host: String) {
        val normHost = host.lowercase(Locale.ROOT).trim()
        if (normHost.isBlank()) return
        memoryCache.remove(normHost)
        prefs?.edit()
            ?.remove("$KEY_COOKIE_PREFIX$normHost")
            ?.remove("$KEY_UA_PREFIX$normHost")
            ?.remove("$KEY_TS_PREFIX$normHost")
            ?.apply()
    }

    fun isChallengeResponse(statusCode: Int, headers: Headers?, body: String): Boolean {
        val cfMitigated = headers?.get("cf-mitigated")
        if (cfMitigated?.contains("challenge", ignoreCase = true) == true) return true

        val lower = body.take(4096).lowercase(Locale.ROOT)
        val hasChallengeMarker =
            lower.contains("turnstile") ||
                lower.contains("just a moment") ||
                lower.contains("cf-browser-verification") ||
                lower.contains("challenge-platform") ||
                lower.contains("complete verification") ||
                lower.contains("needsverification") ||
                lower.contains("verification_required") ||
                lower.contains("verify_required")

        if ((statusCode == 403 || statusCode == 503) &&
            (hasChallengeMarker || lower.contains("cloudflare"))
        ) {
            return true
        }
        return hasChallengeMarker
    }

    /**
     * Triggers the in-app Cloudflare verification dialog (single-flight via [verifyMutex])
     * and suspends until verified (`true`) or dismissed/timed out (`false`).
     */
    suspend fun requestVerification(url: String, forceInteractive: Boolean = false): Boolean {
        val safeUrl = LosslessAudioProvider.forceHttps(url)
        val host = hostOf(safeUrl).ifBlank { "api.zarz.moe" }

        if (!forceInteractive) {
            val cooldownUntil = failedCooldowns[host] ?: 0L
            if (System.currentTimeMillis() < cooldownUntil) return false
        }

        return verifyMutex.withLock {
            if (!forceInteractive) {
                // Another concurrent provider just completed verification while we waited for the lock
                val existing = sessionForHost(host)
                if (existing != null &&
                    existing.cookieHeader.contains("cf_clearance=", ignoreCase = true) &&
                    System.currentTimeMillis() - existing.savedAtMs < 60_000L
                ) {
                    return@withLock true
                }
            } else {
                failedCooldowns.remove(host)
            }

            val deferred = CompletableDeferred<Boolean>()
            val request = VerificationRequest(url = safeUrl, host = host, deferred = deferred)
            _pendingVerification.value = request

            val result = withTimeoutOrNull(VERIFY_TIMEOUT_MS) {
                deferred.await()
            } ?: false

            if (!result) {
                failedCooldowns[host] = System.currentTimeMillis() + COOLDOWN_ON_CANCEL_MS
            }
            if (_pendingVerification.value === request) {
                _pendingVerification.value = null
            }
            result
        }
    }

    fun completeVerification(host: String, cookieHeader: String, userAgent: String) {
        saveSession(host, cookieHeader, userAgent)
        val pending = _pendingVerification.value
        if (pending != null && (pending.host.equals(host, ignoreCase = true) || host.isNotBlank())) {
            pending.deferred.complete(true)
            _pendingVerification.value = null
        }
    }

    fun dismissVerification() {
        val pending = _pendingVerification.value ?: return
        failedCooldowns[pending.host] = System.currentTimeMillis() + COOLDOWN_ON_CANCEL_MS
        pending.deferred.complete(false)
        _pendingVerification.value = null
    }
}
