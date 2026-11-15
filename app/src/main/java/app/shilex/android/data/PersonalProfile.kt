package app.shilex.android.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Editable local-only personal profile saved privately on this device.
 * Stores harmless personalization preferences (never PINs, passwords, OTPs, or secrets).
 */
data class PersonalProfile(
    val preferredName: String = "",
    val addressAs: String = "",
    val favoriteSongs: List<String> = emptyList(),
    val favoriteArtists: List<String> = emptyList(),
    val favoriteGenres: List<String> = emptyList(),
    val preferredMusicApp: String = "",
    val frequentApps: List<String> = emptyList(),
    val preferredLanguage: String = "",
    val responseStyle: String = "",
    val customNotes: String = "",
) {
    val isEmpty: Boolean
        get() = preferredName.isBlank() &&
            addressAs.isBlank() &&
            favoriteSongs.isEmpty() &&
            favoriteArtists.isEmpty() &&
            favoriteGenres.isEmpty() &&
            preferredMusicApp.isBlank() &&
            frequentApps.isEmpty() &&
            preferredLanguage.isBlank() &&
            responseStyle.isBlank() &&
            customNotes.isBlank()

    fun sanitized(): PersonalProfile = copy(
        preferredName = preferredName.trim().take(60),
        addressAs = addressAs.trim().take(40),
        favoriteSongs = favoriteSongs.map { it.trim().take(80) }.filter { it.isNotEmpty() }.distinct().take(20),
        favoriteArtists = favoriteArtists.map { it.trim().take(60) }.filter { it.isNotEmpty() }.distinct().take(20),
        favoriteGenres = favoriteGenres.map { it.trim().take(40) }.filter { it.isNotEmpty() }.distinct().take(15),
        preferredMusicApp = preferredMusicApp.trim().take(40),
        frequentApps = frequentApps.map { it.trim().take(40) }.filter { it.isNotEmpty() }.distinct().take(20),
        preferredLanguage = preferredLanguage.trim().take(30),
        responseStyle = responseStyle.trim().take(120),
        customNotes = customNotes.trim().take(300),
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("preferredName", preferredName)
        put("addressAs", addressAs)
        put("favoriteSongs", JSONArray(favoriteSongs))
        put("favoriteArtists", JSONArray(favoriteArtists))
        put("favoriteGenres", JSONArray(favoriteGenres))
        put("preferredMusicApp", preferredMusicApp)
        put("frequentApps", JSONArray(frequentApps))
        put("preferredLanguage", preferredLanguage)
        put("responseStyle", responseStyle)
        put("customNotes", customNotes)
    }

    companion object {
        fun fromJson(json: JSONObject): PersonalProfile = PersonalProfile(
            preferredName = json.optString("preferredName", ""),
            addressAs = json.optString("addressAs", ""),
            favoriteSongs = readList(json.optJSONArray("favoriteSongs")),
            favoriteArtists = readList(json.optJSONArray("favoriteArtists")),
            favoriteGenres = readList(json.optJSONArray("favoriteGenres")),
            preferredMusicApp = json.optString("preferredMusicApp", ""),
            frequentApps = readList(json.optJSONArray("frequentApps")),
            preferredLanguage = json.optString("preferredLanguage", ""),
            responseStyle = json.optString("responseStyle", ""),
            customNotes = json.optString("customNotes", ""),
        ).sanitized()

        private fun readList(array: JSONArray?): List<String> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                array.optString(i).trim().takeIf { it.isNotEmpty() }
            }
        }
    }
}

/** Pure resolver helpers for personal preferences (unit-tested without Android). */
object PersonalProfileResolver {
    private val FAVOURITE_SONG_QUERY = Regex(
        """\b(my\s+)?favou?rite\s+(song|songs|music|track|gaana|gana)\b|\b(mera|meri)\s+favou?rite\s+(song|gaana|gana|music)\b|\b(pasandida|manpasand)\s+(gaana|gana|song)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val FAVOURITE_ARTIST_QUERY = Regex(
        """\b(my\s+)?favou?rite\s+(artist|singer|gayak)\b|\b(mera|meri)\s+favou?rite\s+(singer|artist)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun isFavouriteSongRequest(query: String): Boolean = FAVOURITE_SONG_QUERY.containsMatchIn(query)

    fun isFavouriteArtistRequest(query: String): Boolean = FAVOURITE_ARTIST_QUERY.containsMatchIn(query)

    /** Resolves "favourite song" or generic query against the saved profile; null if missing. */
    fun resolveMusicQuery(query: String, profile: PersonalProfile): String? {
        val clean = query.trim()
        if (clean.isEmpty() || isFavouriteSongRequest(clean)) {
            val topSong = profile.favoriteSongs.firstOrNull()
            if (!topSong.isNullOrBlank()) return topSong
            val topArtist = profile.favoriteArtists.firstOrNull()
            if (!topArtist.isNullOrBlank()) return topArtist
            val topGenre = profile.favoriteGenres.firstOrNull()
            if (!topGenre.isNullOrBlank()) return "$topGenre mix"
            return if (clean.isEmpty() || isFavouriteSongRequest(clean)) null else clean
        }
        if (isFavouriteArtistRequest(clean)) {
            return profile.favoriteArtists.firstOrNull()
        }
        return clean
    }

    /** Maps a user/profile app name to canonical key ("spotify", "youtube_music", or normalized app name such as "lastwave"). */
    fun resolveMusicAppKey(explicitApp: String, profile: PersonalProfile): String {
        val candidate = explicitApp.ifBlank { profile.preferredMusicApp }.lowercase().trim()
        if (candidate.isEmpty()) return ""
        return when {
            candidate.contains("spotify") -> "spotify"
            candidate.contains("youtube") || candidate == "yt" || candidate.contains("yt music") -> "youtube_music"
            else -> candidate
        }
    }

    /** Safe single-block summary injected into the assistant system prompt. */
    fun assistantContextSummary(profile: PersonalProfile): String {
        val p = profile.sanitized()
        if (p.isEmpty) return ""
        return buildString {
            if (p.preferredName.isNotEmpty()) append("Name: ${p.preferredName}. ")
            if (p.addressAs.isNotEmpty()) append("Address user as: ${p.addressAs}. ")
            if (p.favoriteSongs.isNotEmpty()) append("Favourite songs: ${p.favoriteSongs.joinToString(", ")}. ")
            if (p.favoriteArtists.isNotEmpty()) append("Favourite artists: ${p.favoriteArtists.joinToString(", ")}. ")
            if (p.favoriteGenres.isNotEmpty()) append("Favourite genres: ${p.favoriteGenres.joinToString(", ")}. ")
            if (p.preferredMusicApp.isNotEmpty()) append("Preferred music app: ${p.preferredMusicApp}. ")
            if (p.frequentApps.isNotEmpty()) append("Frequent apps: ${p.frequentApps.joinToString(", ")}. ")
            if (p.preferredLanguage.isNotEmpty()) append("Preferred language: ${p.preferredLanguage}. ")
            if (p.responseStyle.isNotEmpty()) append("Response style: ${p.responseStyle}. ")
            if (p.customNotes.isNotEmpty()) append("User preferences: ${p.customNotes}.")
        }.trim()
    }
}

/** Private on-device SharedPreferences store for [PersonalProfile]. */
class PersonalProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): PersonalProfile {
        val raw = prefs.getString(KEY_PROFILE, null) ?: return PersonalProfile()
        return runCatching { PersonalProfile.fromJson(JSONObject(raw)) }.getOrElse { PersonalProfile() }
    }

    fun save(profile: PersonalProfile) {
        val clean = profile.sanitized()
        prefs.edit().putString(KEY_PROFILE, clean.toJson().toString()).apply()
    }

    fun update(transform: (PersonalProfile) -> PersonalProfile): PersonalProfile {
        val updated = transform(load()).sanitized()
        save(updated)
        return updated
    }

    fun clear() {
        prefs.edit().remove(KEY_PROFILE).apply()
    }

    companion object {
        private const val PREFS_NAME = "personal_profile"
        private const val KEY_PROFILE = "profile_json"
    }
}
