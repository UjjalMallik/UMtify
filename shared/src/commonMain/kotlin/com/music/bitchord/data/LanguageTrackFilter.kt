package com.music.bitchord.data

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.ShelfItem

object LanguageTrackFilter {
    private val BENGALI_SCRIPT = Regex("[\\u0980-\\u09FF]")
    private val DEVANAGARI_SCRIPT = Regex("[\\u0900-\\u097F]")
    private val SOUTH_INDIAN_SCRIPTS = Regex("[\\u0B80-\\u0D7F]")
    private val GURMUKHI_SCRIPT = Regex("[\\u0A00-\\u0A7F]")
    private val GUJARATI_SCRIPT = Regex("[\\u0A80-\\u0AFF]")
    private val ODIA_SCRIPT = Regex("[\\u0B00-\\u0B7F]")

    private val NON_BENGALI_KEYWORDS = listOf(
        "hindi", "bollywood", "tamil", "telugu", "malayalam", "kannada",
        "punjabi", "bhojpuri", "haryanvi", "marathi", "urdu", "gujarati",
        "odia", "kollywood", "tollywood", "sandlewood", "mollywood",
        "guru randhawa", "gurjit gill", "lavish dhiman", "yo yo honey singh", "honey singh",
        "badshah", "neha kakkar", "tony kakkar", "diljit dosanjh", "diljit", "ap dhillon",
        "karan aujla", "sidhu moose wala", "sidhu moose", "hardy sandhu", "b praak", "jaani",
        "jubin nautiyal", "atif aslam", "rahat fateh ali khan", "darshan raval",
        "vishal-shekhar", "salim-sulaiman", "shankar-ehsaan-loy", "amit trivedi",
        "himesh reshammiya", "mika singh", "sunidhi chauhan", "udit narayan", "alka yagnik",
        "mohabbatein", "aankhein khuli", "azul", "dunki", "animal", "pathaan", "jawan",
        "kabir singh", "aashiqui", "t-series", "yrf", "yash raj", "sony music india",
        "zee music company", "zee music", "tips official", "saregama hindi", "speed records",
        "white hill music", "desi melodies", "sachin-jigar", "munjya", "nadiyon paar",
        "bhaag johnny", "banjaare", "khesari", "pawan singh", "jasleen royal",
    )

    private val BENGALI_KEYWORDS = listOf(
        "bangla", "bengali", "rabindra", "nazrul", "lalon", "kolkata", "dhaka",
        "anupam roy", "rupam islam", "fossils", "chandrabindoo", "warfaze", "artcell",
        "shironamhin", "tahsan", "habib wahid", "pritom hasan", "minar rahman", "arnob",
        "james", "nagar baul", "ayub bachchu", "lrb", "miles", "aurthohin", "avoidrafa",
        "asif akbar", "balam", "f a sumon", "imran mahmudul", "kona", "nancy",
        "tanveer evan", "monomoy", "lagnajita", "iman chakraborty", "nachiketa",
        "anjan dutt", "srikanto acharya", "subhamita", "lopamudra mitra", "somlata",
        "sahana bajpaie", "bhoomi", "cactus", "lakkhichhara", "crosswinds",
        "moheener ghoraguli", "hemanta mukherjee", "hemanta", "manna dey",
        "kishore kumar bangla", "arijit singh bangla", "shreya ghoshal bangla",
        "svf music", "svf", "g-series", "sangeeta", "laser vision", "soundtek",
        "cd choice", "dhruba music", "coke studio bangla", "hoichoi", "chorki",
        "saregama bengali", "bhalobasha", "valobasha", "valobashbo", "hridoyer",
        "hridoy", "tomar khola hawa", "gaan", "bondhu", "arindom", "sandhya",
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

        // 1. Any non-Bengali native scripts (Hindi/Devanagari, South Indian, Punjabi, Gujarati, Odia) are rejected
        if (DEVANAGARI_SCRIPT.containsMatchIn(full) ||
            SOUTH_INDIAN_SCRIPTS.containsMatchIn(full) ||
            GURMUKHI_SCRIPT.containsMatchIn(full) ||
            GUJARATI_SCRIPT.containsMatchIn(full) ||
            ODIA_SCRIPT.containsMatchIn(full)
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

        // Strictly NO unverified tracks in Bengali category
        return false
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
