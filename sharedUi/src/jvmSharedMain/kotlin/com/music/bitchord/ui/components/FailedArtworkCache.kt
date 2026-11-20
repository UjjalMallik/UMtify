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
            if (url.startsWith("file://") || url.startsWith("/") ||
                url.startsWith("local_art://") || url.startsWith("content://")
            ) {
                return
            }
            failed.add(url)
        }
    }

    fun isFailed(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (url.startsWith("file://") || url.startsWith("/") ||
            url.startsWith("local_art://") || url.startsWith("content://")
        ) {
            return false
        }
        return failed.contains(url)
    }

    fun clear() {
        failed.clear()
    }
}
