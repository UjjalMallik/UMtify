package com.music.bitchord.data.sources

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Live runtime health state for a source or synced repo extension.
 */
sealed interface SourceRuntimeStatus {
    data object Idle : SourceRuntimeStatus
    data class Ready(val latencyMs: Long, val timestampMs: Long = System.currentTimeMillis()) : SourceRuntimeStatus
    data class Error(val reason: String, val timestampMs: Long = System.currentTimeMillis()) : SourceRuntimeStatus
}

/**
 * Tracks real runtime successes and failures for every source in memory so:
 *  - `SourcesScreen` can display live green latency / red error badges on every row.
 *  - Top-3 parallel racing skips sources that threw a hard error in the last 5 minutes.
 */
object SourceHealthTracker {
    private const val HARD_ERROR_COOLDOWN_MS = 5 * 60 * 1000L
    const val HEALTH_CACHE_TTL_MS = 10 * 60 * 1000L

    private val _states = MutableStateFlow<Map<String, SourceRuntimeStatus>>(emptyMap())
    val states: StateFlow<Map<String, SourceRuntimeStatus>> = _states.asStateFlow()

    fun isHealthFresh(configId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val status = _states.value[configId] ?: return false
        val ts = when (status) {
            SourceRuntimeStatus.Idle -> return false
            is SourceRuntimeStatus.Ready -> status.timestampMs
            is SourceRuntimeStatus.Error -> status.timestampMs
        }
        return (nowMs - ts) < HEALTH_CACHE_TTL_MS
    }

    fun recordReady(configId: String, latencyMs: Long) {
        if (configId.isBlank()) return
        _states.update { current ->
            current + (configId to SourceRuntimeStatus.Ready(latencyMs.coerceAtLeast(1L)))
        }
    }

    fun recordError(configId: String, reason: String) {
        if (configId.isBlank()) return
        val clean = reason.trim().ifBlank { "Request failed" }.take(72)
        _states.update { current ->
            current + (configId to SourceRuntimeStatus.Error(clean))
        }
    }

    fun clear(configId: String) {
        if (configId.isBlank()) return
        _states.update { current -> current - configId }
    }

    fun statusOf(configId: String): SourceRuntimeStatus =
        _states.value[configId] ?: SourceRuntimeStatus.Idle

    fun isInErrorCooldown(configId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val status = _states.value[configId] as? SourceRuntimeStatus.Error ?: return false
        return (nowMs - status.timestampMs) < HARD_ERROR_COOLDOWN_MS
    }
}
