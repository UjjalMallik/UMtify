package com.music.bitchord.data

import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent cache and background resolver for real official artist profile photos.
 * Stores normalizedArtistName -> officialPhotoUrl so Top Artists (Home, Replay, Share Cards)
 * always display official artist profile portraits instead of individual song sleeves.
 */
object ArtistAvatarCache {

    private val cache = ConcurrentHashMap<String, String>()
    private val browseIdCache = ConcurrentHashMap<String, String>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var cacheFile: File? = null
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var dirty = false

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun init(cacheDir: File) {
        cacheFile = File(cacheDir, "artist_avatar_cache.json")
        load()
        scope.launch {
            while (true) {
                delay(5_000L)
                save()
            }
        }
    }

    fun normalize(name: String?): String {
        if (name.isNullOrBlank()) return ""
        return name.trim().lowercase(Locale.ROOT)
    }

    fun get(artist: String?): String? {
        val key = normalize(artist)
        if (key.isEmpty()) return null
        return cache[key]
    }

    fun getBrowseId(artist: String?): String? {
        val key = normalize(artist)
        if (key.isEmpty()) return null
        return browseIdCache[key]
    }

    fun put(artist: String?, photoUrl: String?, browseId: String? = null) {
        val key = normalize(artist)
        if (key.isEmpty() || photoUrl.isNullOrBlank()) return
        if (photoUrl.startsWith("local_art://") || photoUrl.startsWith("file://") || photoUrl.startsWith("content://")) return

        val oldPhoto = cache[key]
        val oldBrowseId = browseIdCache[key]
        var changed = false

        if (oldPhoto != photoUrl) {
            cache[key] = photoUrl
            changed = true
        }
        if (browseId != null && oldBrowseId != browseId) {
            browseIdCache[key] = browseId
            changed = true
        }

        if (changed) {
            dirty = true
            _revision.value++
        }
    }

    fun resolveInBackground(artist: String?) {
        val key = normalize(artist)
        if (key.isEmpty() || key.length > 100) return
        if (cache.containsKey(key)) return
        if (!inFlight.add(key)) return

        scope.launch {
            try {
                fetchAndStore(artist!!.trim())
            } finally {
                inFlight.remove(key)
            }
        }
    }

    suspend fun fetchAndStore(artist: String): String? = withContext(Dispatchers.IO) {
        val key = normalize(artist)
        if (key.isEmpty()) return@withContext null
        cache[key]?.let { return@withContext it }

        val cleanName = artist.trim()
        val searchResult = runCatching {
            YtMusicRepository.search(cleanName, SearchFilter.ARTISTS).getOrNull()
        }.getOrNull()

        val candidate = searchResult
            ?.filterIsInstance<SearchResult.Browse>()
            ?.firstOrNull { browse ->
                val titleNorm = normalize(browse.item.title)
                titleNorm == key || titleNorm.contains(key) || key.contains(titleNorm) ||
                    (cleanName.contains(',') && titleNorm == normalize(cleanName.substringBefore(',')))
            }
            ?.item

        var photoUrl = candidate?.thumbnailUrl?.takeIf { it.isNotBlank() }
        var browseId = candidate?.browseId?.takeIf { it.isNotBlank() }

        if (photoUrl.isNullOrBlank() && !browseId.isNullOrBlank()) {
            runCatching {
                YtMusicRepository.artistPage(browseId).getOrNull()?.let { page ->
                    if (!page.thumbnailUrl.isNullOrBlank()) {
                        photoUrl = page.thumbnailUrl
                    }
                }
            }
        }

        if (photoUrl.isNullOrBlank()) {
            val foundId = runCatching { YtMusicRepository.findArtistPageId(cleanName).getOrNull() }.getOrNull()
            if (!foundId.isNullOrBlank()) {
                browseId = foundId
                runCatching {
                    YtMusicRepository.artistPage(foundId).getOrNull()?.let { page ->
                        if (!page.thumbnailUrl.isNullOrBlank()) {
                            photoUrl = page.thumbnailUrl
                        }
                    }
                }
            }
        }

        if (!photoUrl.isNullOrBlank()) {
            cache[key] = photoUrl!!
            candidate?.title?.let { cache[normalize(it)] = photoUrl!! }
            if (!browseId.isNullOrBlank()) {
                browseIdCache[key] = browseId!!
                candidate?.title?.let { browseIdCache[normalize(it)] = browseId!! }
            }
            dirty = true
            _revision.value++
            photoUrl
        } else {
            null
        }
    }

    private fun load() {
        val f = cacheFile ?: return
        if (!f.exists()) return
        runCatching {
            val stored = json.decodeFromString(StoredAvatarData.serializer(), f.readText())
            stored.entries.forEach { entry ->
                cache[entry.key] = entry.photoUrl
                entry.browseId?.let { browseIdCache[entry.key] = it }
            }
        }
    }

    private fun save() {
        val f = cacheFile ?: return
        if (!dirty) return
        dirty = false
        runCatching {
            val entries = cache.entries.map { (k, v) ->
                StoredAvatarEntry(key = k, photoUrl = v, browseId = browseIdCache[k])
            }
            val stored = StoredAvatarData(entries = entries)
            f.writeText(json.encodeToString(StoredAvatarData.serializer(), stored))
        }
    }

    @Serializable
    private data class StoredAvatarData(
        val version: Int = 1,
        val entries: List<StoredAvatarEntry> = emptyList(),
    )

    @Serializable
    private data class StoredAvatarEntry(
        val key: String,
        val photoUrl: String,
        val browseId: String? = null,
    )
}
