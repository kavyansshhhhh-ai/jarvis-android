package app.shilex.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalProfileTest {
    @Test
    fun roundTripsJsonAndSanitizesFields() {
        val raw = PersonalProfile(
            preferredName = "  Aarav  ",
            addressAs = "Boss",
            favoriteSongs = listOf("Kesariya", "Blinding Lights", "Kesariya"),
            favoriteArtists = listOf("Arijit Singh"),
            favoriteGenres = listOf("Lo-fi"),
            preferredMusicApp = "Spotify",
            frequentApps = listOf("WhatsApp", "Maps"),
            preferredLanguage = "Hinglish",
            responseStyle = "Short and crisp",
            customNotes = "Prefer dark mode summaries",
        )
        val decoded = PersonalProfile.fromJson(raw.sanitized().toJson())
        assertEquals("Aarav", decoded.preferredName)
        assertEquals(listOf("Kesariya", "Blinding Lights"), decoded.favoriteSongs)
        assertEquals("Spotify", decoded.preferredMusicApp)
    }

    @Test
    fun resolvesFavouriteSongsAndMusicApp() {
        val profile = PersonalProfile(
            favoriteSongs = listOf("Tum Hi Ho"),
            favoriteArtists = listOf("Arijit Singh"),
            preferredMusicApp = "Spotify",
        )
        assertEquals("Tum Hi Ho", PersonalProfileResolver.resolveMusicQuery("play my favourite song", profile))
        assertEquals("Tum Hi Ho", PersonalProfileResolver.resolveMusicQuery("mera favourite song chalao", profile))
        assertEquals("spotify", PersonalProfileResolver.resolveMusicAppKey("", profile))
        assertEquals("youtube_music", PersonalProfileResolver.resolveMusicAppKey("YouTube Music", profile))

        assertNull(PersonalProfileResolver.resolveMusicQuery("play my favourite song", PersonalProfile()))
        assertTrue(PersonalProfileResolver.assistantContextSummary(profile).contains("Tum Hi Ho"))
    }
}
