package com.music.bitchord.data

import android.content.Context
import com.music.bitchord.data.model.HomeShelf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists the most recent Home feed to local disk so the home screen
 * renders instantly on app launch with zero blank shimmer / skeleton delay.
 */
object HomeCache {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private const val FILE_NAME = "umtify_cached_home_feed.json"

    @Volatile
    private var inMemory: List<HomeShelf>? = null

    fun load(context: Context): List<HomeShelf>? {
        inMemory?.let { return it }
        return runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return null
            val text = file.readText()
            if (text.isBlank()) return null
            val decoded = json.decodeFromString<List<HomeShelf>>(text)
            if (decoded.isNotEmpty()) {
                inMemory = decoded
                decoded
            } else {
                null
            }
        }.getOrNull()
    }

    suspend fun save(context: Context, shelves: List<HomeShelf>) = withContext(Dispatchers.IO) {
        if (shelves.isEmpty()) return@withContext
        inMemory = shelves
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            // Persist up to 16 shelves for instant initial hydration
            val text = json.encodeToString(shelves.take(16))
            file.writeText(text)
        }
    }
}
