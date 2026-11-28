package com.music.bitchord.data

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.sources.SourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

data class RepoExtension(
    val id: String,
    val name: String,
    val displayName: String,
    val version: String,
    val description: String,
    val downloadUrl: String,
    val iconUrl: String? = null,
    val category: String = "general",
    val tags: List<String> = emptyList(),
)

object ExtensionRepoManager {
    private const val TAG = "ExtensionRepoManager"
    const val REGISTRY_URL = "https://raw.githubusercontent.com/mornyeapp/Mornye-Extension/main/registry.json"

    private const val PREFS_NAME = "extension_repo_prefs"
    private const val KEY_INSTALLED_IDS = "installed_ids"
    private const val KEY_ENABLED_IDS = "enabled_ids"
    private const val KEY_INSTALLED_VERSIONS = "installed_versions"
    private const val KEY_CACHED_REGISTRY = "cached_registry_json"
    private const val KEY_INITIALIZED = "repo_initialized_v2"

    val CORE_DEFAULT_IDS = setOf(
        "spotify-web",
        "amazon",
        "apple-music",
        "qobuz-web",
        "tidal-web",
        "deezer",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefs: SharedPreferences? = null
    @Volatile
    private var appContext: Context? = null

    private val _extensions = MutableStateFlow<List<RepoExtension>>(emptyList())
    val extensions: StateFlow<List<RepoExtension>> = _extensions.asStateFlow()

    private val _installedIds = MutableStateFlow<Set<String>>(emptySet())
    val installedIds: StateFlow<Set<String>> = _installedIds.asStateFlow()

    private val _enabledIds = MutableStateFlow<Set<String>>(emptySet())
    val enabledIds: StateFlow<Set<String>> = _enabledIds.asStateFlow()

    private val _installedVersions = MutableStateFlow<Map<String, String>>(emptyMap())
    val installedVersions: StateFlow<Map<String, String>> = _installedVersions.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p

        val savedInstalled = p.getStringSet(KEY_INSTALLED_IDS, null)
        val savedEnabled = p.getStringSet(KEY_ENABLED_IDS, null)
        val isInitialized = p.getBoolean(KEY_INITIALIZED, false)

        val initialInstalled: Set<String>
        val initialEnabled: Set<String>

        if (!isInitialized || savedInstalled == null) {
            initialInstalled = CORE_DEFAULT_IDS - "jellyfin"
            initialEnabled = CORE_DEFAULT_IDS - "jellyfin"
            p.edit()
                .putStringSet(KEY_INSTALLED_IDS, initialInstalled)
                .putStringSet(KEY_ENABLED_IDS, initialEnabled)
                .putBoolean(KEY_INITIALIZED, true)
                .apply()
        } else {
            initialInstalled = savedInstalled - "jellyfin"
            initialEnabled = (savedEnabled ?: savedInstalled) - "jellyfin"
        }

        _installedIds.value = initialInstalled
        _enabledIds.value = initialEnabled

        // Load installed versions
        val versionsMap = mutableMapOf<String, String>()
        val versionsJson = p.getString(KEY_INSTALLED_VERSIONS, null)
        if (!versionsJson.isNullOrBlank()) {
            runCatching {
                val obj = JSONObject(versionsJson)
                obj.keys().forEach { key ->
                    versionsMap[key] = obj.optString(key)
                }
            }
        }
        _installedVersions.value = versionsMap

        // Load cached registry if present
        val cached = p.getString(KEY_CACHED_REGISTRY, null)
        if (!cached.isNullOrBlank()) {
            val parsed = parseRegistry(cached)
            if (parsed.isNotEmpty()) {
                _extensions.value = parsed
                // Populate default versions if missing
                var updatedVersions = false
                val updatedMap = versionsMap.toMutableMap()
                for (id in initialInstalled) {
                    if (!updatedMap.containsKey(id)) {
                        parsed.firstOrNull { it.id == id }?.let {
                            updatedMap[id] = it.version
                            updatedVersions = true
                        }
                    }
                }
                if (updatedVersions) {
                    _installedVersions.value = updatedMap
                    persistVersions(updatedMap)
                }
            }
        }

        // Trigger live refresh, auto-update, and smart server benchmark on background Dispatchers.IO
        scope.launch {
            refreshRegistry(context, autoUpdate = true)
            LosslessAudioProvider.benchmarkTopServers("repo-init")
        }
    }

    fun syncWithSourceRegistry() {
        val installed = _installedIds.value
        val enabled = _enabledIds.value
        val catalogById = _extensions.value.associateBy { it.id }

        val entries = installed.mapNotNull { extId ->
            val ext = catalogById[extId]
            if (ext != null) {
                if (!isAudioDownloadExtension(ext)) return@mapNotNull null
                SourceRegistry.RepoSyncEntry(
                    extensionId = extId,
                    displayName = ext.displayName,
                    enabled = extId in enabled,
                    endpointUrl = getExtractedEndpointsFor(extId).firstOrNull().orEmpty(),
                )
            } else {
                if (isDuplicateBuiltIn(extId)) return@mapNotNull null
                val endpoints = getExtractedEndpointsFor(extId)
                if (extId !in CORE_DEFAULT_IDS && endpoints.isEmpty()) return@mapNotNull null
                SourceRegistry.RepoSyncEntry(
                    extensionId = extId,
                    displayName = fallbackDisplayName(extId),
                    enabled = extId in enabled,
                    endpointUrl = endpoints.firstOrNull().orEmpty(),
                )
            }
        }.sortedBy { it.displayName.lowercase(Locale.ROOT) }

        SourceRegistry.syncRepoExtensions(entries)
    }

    private fun isDuplicateBuiltIn(raw: String): Boolean {
        val lower = raw.lowercase(Locale.ROOT)
        return lower.contains("jiosaavn") ||
            lower.contains("soundcloud") ||
            lower.contains("youtube") ||
            lower.contains("ytmusic") ||
            lower.contains("jamendo") ||
            lower.contains("internet-archive") ||
            lower.contains("internetarchive") ||
            lower.contains("archive.org") ||
            lower.contains("umtify")
    }

    private fun isAudioDownloadExtension(ext: RepoExtension): Boolean {
        if (isDuplicateBuiltIn(ext.id) || isDuplicateBuiltIn(ext.name) || isDuplicateBuiltIn(ext.displayName)) {
            return false
        }
        val cat = ext.category.lowercase(Locale.ROOT)
        val tags = ext.tags.map { it.lowercase(Locale.ROOT) }
        val combined = "${ext.id} ${ext.name} ${ext.displayName} ${ext.description}".lowercase(Locale.ROOT)
        val audioKeywords = listOf(
            "audio", "download", "music", "stream", "flac", "lossless", "hifi",
            "tidal", "qobuz", "deezer", "amazon", "spotify", "apple", "beatport", "bandcamp",
        )
        if (cat in setOf("metadata", "utility") &&
            tags.none { t -> audioKeywords.any { k -> t.contains(k) } } &&
            audioKeywords.none { k -> combined.contains(k) }
        ) {
            return false
        }
        return ext.id in CORE_DEFAULT_IDS ||
            cat in setOf("integration", "download", "audio", "music", "streaming", "general", "") ||
            tags.any { t -> audioKeywords.any { k -> t.contains(k) } } ||
            audioKeywords.any { k -> combined.contains(k) } ||
            getExtractedEndpointsFor(ext.id).isNotEmpty()
    }

    private fun fallbackDisplayName(id: String): String = when (id.lowercase(Locale.ROOT)) {
        "tidal-web", "tidal" -> "Tidal"
        "qobuz-web", "qobuz" -> "Qobuz"
        "deezer" -> "Deezer"
        "amazon", "amazon-music" -> "Amazon Music"
        "spotify-web", "spotify" -> "Spotify"
        "apple-music", "apple" -> "Apple Music"
        "beatport" -> "Beatport"
        else -> id.removeSuffix("-web")
            .split('-', '_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(Locale.ROOT) else c.toString() }
            }
            .ifBlank { id }
    }

    suspend fun refreshRegistry(context: Context, autoUpdate: Boolean = true): List<RepoExtension> = withContext(Dispatchers.IO) {
        _isLoading.value = true
        try {
            val request = Request.Builder()
                .url(REGISTRY_URL)
                .header("User-Agent", "UMtify/1.0 (Android; Mornye Extension Repo)")
                .header("Accept", "application/json")
                .build()

            val client = Http.client.newBuilder()
                .connectTimeout(5000, TimeUnit.MILLISECONDS)
                .readTimeout(6000, TimeUnit.MILLISECONDS)
                .build()

            val response = client.newCall(request).execute()
            val body = response.use { if (it.isSuccessful) it.body?.string() else null }

            if (!body.isNullOrBlank()) {
                val parsed = parseRegistry(body)
                if (parsed.isNotEmpty()) {
                    _extensions.value = parsed
                    prefs?.edit()?.putString(KEY_CACHED_REGISTRY, body)?.apply()

                    if (autoUpdate) {
                        checkForUpdatesAndApply(context, parsed)
                    }
                    syncWithSourceRegistry()
                    Log.i(TAG, "Successfully refreshed extension repo: ${parsed.size} extensions loaded")
                    return@withContext parsed
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh extension repo: ${e.message}")
        } finally {
            _isLoading.value = false
        }
        _extensions.value
    }

    private fun parseRegistry(jsonStr: String): List<RepoExtension> {
        val result = mutableListOf<RepoExtension>()
        runCatching {
            val root = JSONObject(jsonStr)
            val extArray = root.optJSONArray("extensions") ?: return emptyList()

            for (i in 0 until extArray.length()) {
                val item = extArray.optJSONObject(i) ?: continue
                val id = item.optString("id").ifBlank { item.optString("name") }
                if (id.isBlank()) continue

                val name = item.optString("name", id)
                val displayName = item.optString("display_name").ifBlank { name }
                // Hide duplicate JioSaavn extension since JioSaavn is built-in to the main Sources list
                if (id.contains("jiosaavn", ignoreCase = true) ||
                    name.contains("jiosaavn", ignoreCase = true) ||
                    displayName.contains("jiosaavn", ignoreCase = true)
                ) {
                    continue
                }
                val version = item.optString("version", "1.0.0")
                val description = item.optString("description")
                val downloadUrl = item.optString("download_url")
                val iconUrl = item.optString("icon_url").takeIf { it.isNotBlank() }
                val category = item.optString("category", "general")

                val tags = mutableListOf<String>()
                val tagsArray = item.optJSONArray("tags")
                if (tagsArray != null) {
                    for (t in 0 until tagsArray.length()) {
                        val tag = tagsArray.optString(t)
                        if (tag.isNotBlank()) tags.add(tag)
                    }
                }

                result.add(
                    RepoExtension(
                        id = id,
                        name = name,
                        displayName = displayName,
                        version = version,
                        description = description,
                        downloadUrl = downloadUrl,
                        iconUrl = iconUrl,
                        category = category,
                        tags = tags,
                    )
                )
            }
        }
        return result
    }

    private suspend fun checkForUpdatesAndApply(context: Context, registry: List<RepoExtension>) = withContext(Dispatchers.IO) {
        val installed = _installedIds.value
        val versions = _installedVersions.value.toMutableMap()
        var changed = false

        for (ext in registry) {
            if (ext.id in installed) {
                val currentVer = versions[ext.id]
                if (currentVer == null || isNewerVersion(ext.version, currentVer)) {
                    Log.i(TAG, "Auto-updating extension ${ext.displayName} from ${currentVer ?: "none"} to ${ext.version}")
                    // Download update in background
                    val success = downloadExtensionFile(context, ext)
                    if (success) {
                        versions[ext.id] = ext.version
                        changed = true
                    }
                }
            }
        }

        if (changed) {
            _installedVersions.value = versions
            persistVersions(versions)
        }
    }

    private fun isNewerVersion(repoVer: String, localVer: String): Boolean {
        if (repoVer == localVer) return false
        val repoParts = repoVer.split(".").mapNotNull { it.toIntOrNull() }
        val localParts = localVer.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(repoParts.size, localParts.size)) {
            val r = repoParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return repoVer != localVer
    }

    suspend fun installExtension(context: Context, extension: RepoExtension): Boolean = withContext(Dispatchers.IO) {
        val success = downloadExtensionFile(context, extension)
        if (success) {
            val newInstalled = _installedIds.value + extension.id
            val newEnabled = _enabledIds.value + extension.id
            val newVersions = _installedVersions.value.toMutableMap().apply {
                put(extension.id, extension.version)
            }

            _installedIds.value = newInstalled
            _enabledIds.value = newEnabled
            _installedVersions.value = newVersions

            prefs?.edit()
                ?.putStringSet(KEY_INSTALLED_IDS, newInstalled)
                ?.putStringSet(KEY_ENABLED_IDS, newEnabled)
                ?.apply()

            persistVersions(newVersions)
            syncWithSourceRegistry()
            Log.i(TAG, "Installed extension ${extension.displayName} (${extension.id}) v${extension.version}")
            true
        } else {
            Log.w(TAG, "Failed to download extension ${extension.displayName}")
            false
        }
    }

    fun uninstallExtension(context: Context, extensionId: String) {
        val newInstalled = _installedIds.value - extensionId
        val newEnabled = _enabledIds.value - extensionId
        val newVersions = _installedVersions.value.toMutableMap().apply {
            remove(extensionId)
        }

        _installedIds.value = newInstalled
        _enabledIds.value = newEnabled
        _installedVersions.value = newVersions

        prefs?.edit()
            ?.putStringSet(KEY_INSTALLED_IDS, newInstalled)
            ?.putStringSet(KEY_ENABLED_IDS, newEnabled)
            ?.apply()

        persistVersions(newVersions)

        // Remove downloaded file
        runCatching {
            val file = File(context.filesDir, "extensions/$extensionId.sflx")
            if (file.exists()) file.delete()
        }
        syncWithSourceRegistry()
    }

    fun toggleExtension(extensionId: String, enabled: Boolean, syncSourceRegistry: Boolean = true) {
        val newEnabled = if (enabled) {
            _enabledIds.value + extensionId
        } else {
            _enabledIds.value - extensionId
        }
        _enabledIds.value = newEnabled
        prefs?.edit()?.putStringSet(KEY_ENABLED_IDS, newEnabled)?.apply()
        if (syncSourceRegistry) {
            SourceRegistry.syncRepoExtensionEnabled(extensionId, enabled)
        }
    }

    private fun persistVersions(versions: Map<String, String>) {
        val obj = JSONObject()
        versions.forEach { (k, v) -> obj.put(k, v) }
        prefs?.edit()?.putString(KEY_INSTALLED_VERSIONS, obj.toString())?.apply()
    }

    private fun downloadExtensionFile(context: Context, extension: RepoExtension): Boolean {
        if (extension.downloadUrl.isBlank()) return true
        return runCatching {
            val request = Request.Builder()
                .url(extension.downloadUrl)
                .header("User-Agent", "UMtify/1.0 (Android; Mornye Extension Repo)")
                .build()

            val client = Http.client.newBuilder()
                .connectTimeout(8000, TimeUnit.MILLISECONDS)
                .readTimeout(10000, TimeUnit.MILLISECONDS)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return false

            val extDir = File(context.filesDir, "extensions").apply { if (!exists()) mkdirs() }
            val targetFile = File(extDir, "${extension.id}.sflx")

            response.body?.byteStream()?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            true
        }.getOrDefault(false)
    }

    fun getExtractedEndpoints(): List<String> {
        val ctx = appContext ?: return emptyList()
        val enabled = _enabledIds.value
        val endpoints = mutableListOf<String>()
        val extDir = File(ctx.filesDir, "extensions")
        if (!extDir.exists() || !extDir.isDirectory) return emptyList()

        val files = extDir.listFiles { _, name -> name.endsWith(".sflx") } ?: return emptyList()
        for (file in files) {
            val extId = file.nameWithoutExtension
            if (enabled.isNotEmpty() && extId !in enabled) continue
            extractEndpointsFromFile(file).forEach { url ->
                if (url !in endpoints) endpoints.add(url)
            }
        }
        return endpoints
    }

    fun getExtractedEndpointsFor(extensionId: String): List<String> {
        val ctx = appContext ?: return emptyList()
        val file = File(ctx.filesDir, "extensions/$extensionId.sflx")
        if (!file.exists()) return emptyList()
        return extractEndpointsFromFile(file)
    }

    private fun extractEndpointsFromFile(file: File): List<String> {
        val endpoints = mutableListOf<String>()
        val urlRegex = Regex("""https?://[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}(?::[0-9]+)?(?:/[a-zA-Z0-9._~-]+)*""")
        runCatching {
            java.util.zip.ZipFile(file).use { zip ->
                val entries = listOfNotNull(zip.getEntry("index.js"), zip.getEntry("manifest.json"))
                for (entry in entries) {
                    val text = zip.getInputStream(entry).bufferedReader().readText()
                    urlRegex.findAll(text).forEach { match ->
                        val url = match.value.trimEnd('/')
                        if (url.startsWith("http") &&
                            !url.contains("github.com") &&
                            !url.contains("w3.org") &&
                            !url.contains("schema.org") &&
                            !url.contains("google.com") &&
                            (url.contains("api") || url.contains("zarz") || url.contains("qqdl") ||
                             url.contains("tidal") || url.contains("qobuz") || url.contains("hifi") ||
                             url.contains("yeet.su") || url.contains("spotisaver") || url.contains("kinoko"))
                        ) {
                            if (url !in endpoints) endpoints.add(url)
                        }
                    }
                }
            }
        }
        return endpoints
    }
}
