package com.music.bitchord.ui.components

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory cache of image URLs that failed to load (e.g. 404s, offline misses, broken links).
 * Once a URL fails, subsequent renders immediately display the default static UMtify cover
 * rather than retrying through Coil and causing blinking/flickering.
 */
object FailedArtworkCache {
    private val failed = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun isLocal(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val clean = url.trim()
        return clean.startsWith("file://") || clean.startsWith("/") ||
            clean.startsWith("local_art://") || clean.startsWith("content://")
    }

    fun markFailed(url: String?) {
        if (!url.isNullOrBlank() && !isLocal(url)) {
            failed.add(url.trim())
        }
    }

    fun isFailed(url: String?): Boolean {
        if (url.isNullOrBlank() || isLocal(url)) return false
        return failed.contains(url.trim())
    }

    fun clear() {
        failed.clear()
    }
}
