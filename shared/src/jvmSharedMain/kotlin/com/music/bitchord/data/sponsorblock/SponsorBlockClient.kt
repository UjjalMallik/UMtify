package com.music.bitchord.data.sponsorblock

import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

data class SponsorSegment(
    val category: String,
    val startMs: Long,
    val endMs: Long,
) {
    val displayLabel: String
        get() = when (category) {
            "music_offtopic" -> "Non-Music / Intro"
            "sponsor" -> "Sponsor"
            "intro" -> "Intro"
            "outro" -> "Outro"
            "selfpromo" -> "Promo"
            "interaction" -> "Reminder"
            else -> "Segment"
        }
}

object SponsorBlockClient {
    private val cache = ConcurrentHashMap<String, List<SponsorSegment>>()

    suspend fun getSegments(videoId: String): List<SponsorSegment> {
        if (videoId.isBlank()) return emptyList()
        cache[videoId]?.let { return it }

        return withContext(Dispatchers.IO) {
            runCatching {
                val categories = """["sponsor","selfpromo","interaction","intro","outro","music_offtopic"]"""
                val encodedCat = URLEncoder.encode(categories, "UTF-8")
                val url = "https://sponsor.ajay.app/api/skipSegments?videoID=$videoId&categories=$encodedCat"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "UMtify/1.0")
                    .get()
                    .build()

                Http.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        cache[videoId] = emptyList()
                        return@use emptyList<SponsorSegment>()
                    }
                    val body = response.body?.string() ?: return@use emptyList<SponsorSegment>()
                    val jsonArray = Json.parseToJsonElement(body).jsonArray
                    val list = jsonArray.mapNotNull { element ->
                        val obj = element.jsonObject
                        val category = obj["category"]?.jsonPrimitive?.content ?: "sponsor"
                        val seg = obj["segment"]?.jsonArray
                        if (seg != null && seg.size >= 2) {
                            val startSec = seg[0].jsonPrimitive.doubleOrNull ?: 0.0
                            val endSec = seg[1].jsonPrimitive.doubleOrNull ?: 0.0
                            SponsorSegment(
                                category = category,
                                startMs = (startSec * 1000).toLong(),
                                endMs = (endSec * 1000).toLong(),
                            )
                        } else null
                    }
                    cache[videoId] = list
                    list
                }
            }.getOrDefault(emptyList())
        }
    }
}
