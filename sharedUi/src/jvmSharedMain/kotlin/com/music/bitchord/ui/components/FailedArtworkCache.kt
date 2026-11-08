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

    fun markFailed(url: String?) {
        if (!url.isNullOrBlank()) {
            failed.add(url)
        }
    }

    fun isFailed(url: String?): Boolean {
        return !url.isNullOrBlank() && failed.contains(url)
    }

    fun clear() {
        failed.clear()
    }
}
