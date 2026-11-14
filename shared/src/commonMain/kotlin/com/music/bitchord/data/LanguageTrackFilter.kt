package com.music.bitchord.data

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.ShelfItem

object LanguageTrackFilter {
    private val BENGALI_SCRIPT = Regex("[\\u0980-\\u09FF]")
    private val DEVANAGARI_SCRIPT = Regex("[\\u0900-\\u097F]")
    private val SOUTH_INDIAN_SCRIPTS = Regex("[\\u0B80-\\u0D7F]")
    private val GURMUKHI_SCRIPT = Regex("[\\u0A00-\\u0A7F]")

    private val NON_BENGALI_KEYWORDS = listOf(
        "hindi", "bollywood", "tamil", "telugu", "malayalam", "kannada",
        "punjabi", "bhojpuri", "haryanvi", "marathi", "urdu", "gujarati",
        "odia", "kollywood", "tollywood", "sandlewood", "mollywood",
        "neha kakkar", "honey singh", "yo yo honey singh", "badshah",
        "diljit", "darshan raval", "jubin nautiyal", "jasleen royal",
        "khesari", "pawan singh", "karan aujla", "sidhu moose", "ap dhillon",
        "sachin-jigar", "munjya", "nadiyon paar", "bhaag johnny", "banjaare",
    )

    private val BENGALI_KEYWORDS = listOf(
        "bangla", "bengali", "rabindra", "nazrul", "lalon", "kolkata",
        "dhaka", "lagnajita", "arindom", "anupam roy", "fossils",
        "chandrabindoo", "cactus", "miles", "warfaze", "ayub bachchu",
        "james", "habib wahid", "tahsan", "somlata", "rupam islam",
        "nachiketa", "hemanta", "manna dey", "sandhya", "svf",
        "svf music", "bhalobasha", "valobasha", "valobashbo",
        "hridoyer", "hridoy", "tomar khola hawa", "gaan", "bondhu",
    )

    private val NON_HINDI_KEYWORDS = listOf(
        "bangla", "bengali", "rabindra", "nazrul", "lalon", "kolkata",
        "dhaka", "tamil", "telugu", "malayalam", "kannada", "korean",
    )

    private val HINDI_KEYWORDS = listOf(
        "hindi", "bollywood", "arijit singh", "neha kakkar", "badshah",
        "honey singh", "jubin nautiyal", "shreya ghoshal", "atif aslam",
        "sonu nigam", "kumar sanu", "alka yagnik", "udit narayan",
        "lata mangeshkar", "mohammed rafi", "kishore kumar", "t-series",
        "zee music", "sony music", "yrf", "sachin-jigar", "pritam",
        "mithoon", "armaan malik", "darshan raval",
    )

    fun isBengali(title: String, artist: String?, subtitle: String? = null): Boolean {
        val full = "$title ${artist.orEmpty()} ${subtitle.orEmpty()}".lowercase()

        // 1. Any non-Bengali native scripts (Hindi/Devanagari, South Indian, Punjabi) are rejected
        if (DEVANAGARI_SCRIPT.containsMatchIn(full) ||
            SOUTH_INDIAN_SCRIPTS.containsMatchIn(full) ||
            GURMUKHI_SCRIPT.containsMatchIn(full)
        ) {
            return false
        }

        // 2. Reject explicit non-Bengali tags/artists
        for (kw in NON_BENGALI_KEYWORDS) {
            if (full.contains(kw)) {
                return false
            }
        }

        // 3. Native Bengali script is automatically accepted
        if (BENGALI_SCRIPT.containsMatchIn(full)) {
            return true
        }

        // 4. Check for known Bengali artists, keywords or tags
        for (kw in BENGALI_KEYWORDS) {
            if (full.contains(kw)) {
                return true
            }
        }

        // In a Bengali category context, if it didn't match foreign scripts/keywords, allow it
        return true
    }

    fun isHindi(title: String, artist: String?, subtitle: String? = null): Boolean {
        val full = "$title ${artist.orEmpty()} ${subtitle.orEmpty()}".lowercase()

        // 1. Reject Bengali and South Indian scripts
        if (BENGALI_SCRIPT.containsMatchIn(full) || SOUTH_INDIAN_SCRIPTS.containsMatchIn(full)) {
            return false
        }

        // 2. Reject explicit Bengali tags
        for (kw in NON_HINDI_KEYWORDS) {
            if (full.contains(kw)) {
                return false
            }
        }

        // 3. Devanagari script is accepted
        if (DEVANAGARI_SCRIPT.containsMatchIn(full)) {
            return true
        }

        // 4. Hindi keywords / artists
        for (kw in HINDI_KEYWORDS) {
            if (full.contains(kw)) {
                return true
            }
        }

        return true
    }

    fun isBengaliCategory(categoryTitle: String): Boolean {
        val cat = categoryTitle.lowercase()
        return cat.contains("bengali") || cat.contains("bangla") || cat.contains("বাংলা")
    }

    fun isHindiCategory(categoryTitle: String): Boolean {
        val cat = categoryTitle.lowercase()
        return cat.contains("hindi") || cat.contains("bollywood") || cat.contains("হিন্দি") || cat.contains("বলিউড")
    }

    fun matchesCategory(categoryTitle: String, title: String, artist: String?, subtitle: String? = null): Boolean {
        return when {
            isBengaliCategory(categoryTitle) -> isBengali(title, artist, subtitle)
            isHindiCategory(categoryTitle) -> isHindi(title, artist, subtitle)
            else -> true
        }
    }

    fun filterShelfItems(categoryTitle: String, items: List<ShelfItem>): List<ShelfItem> {
        if (!isBengaliCategory(categoryTitle) && !isHindiCategory(categoryTitle)) return items
        return items.filter { matchesCategory(categoryTitle, it.title, it.subtitle, null) }
    }

    fun filterSongs(categoryTitle: String, songs: List<Song>): List<Song> {
        if (!isBengaliCategory(categoryTitle) && !isHindiCategory(categoryTitle)) return songs
        return songs.filter { matchesCategory(categoryTitle, it.title, it.artist, it.albumName) }
    }
}
