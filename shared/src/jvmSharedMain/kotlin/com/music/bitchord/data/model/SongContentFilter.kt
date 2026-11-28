package com.music.bitchord.data.model

import java.util.Locale

/**
 * Shared duration and teaser/trailer filter applied consistently across online catalogs
 * (Search, Home, Explore, Artist pages, Up Next/AutoPlay) and offline/cached libraries.
 */
object SongContentFilter {

    const val MIN_DURATION_MS = 30_000L
    const val MIN_DURATION_SEC = 30
    const val TEASER_MAX_DURATION_SEC = 80

    private val TEASER_KEYWORD_REGEX = Regex(
        """\b(?:teaser|trailer|promos?|glimpse|first\s+look|motion\s+poster|shorts?|snippets?|previews?)\b|#shorts\b""",
        RegexOption.IGNORE_CASE,
    )

    private val DURATION_MM_SS_REGEX = Regex("""^\d+:\d{2}(?::\d{2})?$""")
    private val MIN_PART_REGEX = Regex("""(\d+)\s*(?:hr|hours?|min|mins?|minutes?)\b""", RegexOption.IGNORE_CASE)
    private val SEC_PART_REGEX = Regex("""(\d+)\s*(?:sec|secs?|seconds?)\b""", RegexOption.IGNORE_CASE)
    private val HR_PART_REGEX = Regex("""(\d+)\s*(?:hr|hours?)\b""", RegexOption.IGNORE_CASE)

    /**
     * Parses duration text ("0:24", "1:15:00", "45 sec", "1 min 12 sec") into total seconds.
     */
    fun parseDurationSeconds(durationText: String?): Int? {
        if (durationText.isNullOrBlank()) return null
        val trimmed = durationText.trim()
        if (trimmed.matches(DURATION_MM_SS_REGEX)) {
            val parts = trimmed.split(':')
            return when (parts.size) {
                2 -> {
                    val m = parts[0].toIntOrNull() ?: return null
                    val s = parts[1].toIntOrNull() ?: return null
                    m * 60 + s
                }
                3 -> {
                    val h = parts[0].toIntOrNull() ?: return null
                    val m = parts[1].toIntOrNull() ?: return null
                    val s = parts[2].toIntOrNull() ?: return null
                    h * 3600 + m * 60 + s
                }
                else -> null
            }
        }
        val hrs = HR_PART_REGEX.find(trimmed)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val mins = MIN_PART_REGEX.find(trimmed)?.takeIf {
            !it.value.lowercase(Locale.ROOT).startsWith("hr") &&
                !it.value.lowercase(Locale.ROOT).startsWith("hour")
        }?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val secs = SEC_PART_REGEX.find(trimmed)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val total = hrs * 3600 + mins * 60 + secs
        return total.takeIf { it > 0 }
    }

    /** True if title contains word-boundary teaser/trailer/promo markers. */
    fun hasTeaserKeyword(title: String?): Boolean {
        if (title.isNullOrBlank()) return false
        return TEASER_KEYWORD_REGEX.containsMatchIn(title)
    }

    /**
     * Returns true when a track or item should be hidden:
     *  - duration is known and strictly under 30 seconds (`1..29s`, or `1..29_999ms`)
     *  - duration is known and under 80 seconds (`1..79s`) AND title contains teaser/trailer/promo keywords
     */
    fun isBlockedShortOrTeaser(
        title: String?,
        durationText: String? = null,
        durationMs: Long? = null,
    ): Boolean {
        val ms = durationMs?.takeIf { it > 0L }
            ?: durationText?.let(::parseDurationSeconds)?.takeIf { it > 0 }?.times(1000L)
            ?: return false

        if (ms < MIN_DURATION_MS) return true
        if (ms < TEASER_MAX_DURATION_SEC * 1000L && hasTeaserKeyword(title)) return true
        return false
    }

    fun isAllowed(song: Song): Boolean {
        val ms = song.durationMillis().takeIf { it > 0L }
        return !isBlockedShortOrTeaser(song.title, song.durationText, ms)
    }

    fun isAllowed(item: ShelfItem): Boolean {
        if (item.browseId != null && item.videoId == null) return true
        val rawDuration = item.subtitle
            .split(" • ", " · ")
            .map(String::trim)
            .lastOrNull { parseDurationSeconds(it) != null }
        return !isBlockedShortOrTeaser(item.title, rawDuration, null)
    }
}
