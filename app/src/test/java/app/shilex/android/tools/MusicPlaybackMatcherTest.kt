package app.shilex.android.tools

import app.shilex.android.tools.MusicTrackMatcher.MatchOutcome
import app.shilex.android.tools.MusicTrackMatcher.TrackCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicPlaybackMatcherTest {

    @Test
    fun splitsQueryIntoTitleAndArtist() {
        val (title1, artist1) = MusicTrackMatcher.splitQuery("Tu Hi Mera, Pritam")
        assertEquals("Tu Hi Mera", title1)
        assertEquals("Pritam", artist1)

        val (title2, artist2) = MusicTrackMatcher.splitQuery("Kesariya by Arijit Singh")
        assertEquals("Kesariya", title2)
        assertEquals("Arijit Singh", artist2)

        val (title3, artist3) = MusicTrackMatcher.splitQuery("Chaleya - Anirudh")
        assertEquals("Chaleya", title3)
        assertEquals("Anirudh", artist3)

        val (title4, artist4) = MusicTrackMatcher.splitQuery("Tum Hi Ho", artistHint = "Arijit")
        assertEquals("Tum Hi Ho", title4)
        assertEquals("Arijit", artist4)
    }

    @Test
    fun matchesExactTrackAndArtist() {
        val candidates = listOf(
            TrackCandidate(1, "Tu Hi Mera", "Pritam, Shafqat Amanat Ali"),
            TrackCandidate(2, "Lost In Wave", "Unknown"),
            TrackCandidate(3, "Tu Hi Hai", "Ali Zafar"),
        )
        val outcome = MusicTrackMatcher.bestMatch("Tu Hi Mera", "Pritam", candidates)
        assertTrue(outcome is MatchOutcome.Matched)
        val matched = outcome as MatchOutcome.Matched
        assertEquals(1, matched.candidate.elementNumber)
        assertEquals("Tu Hi Mera", matched.candidate.title)
    }

    @Test
    fun rejectsUnrelatedOrMisleadingTitles() {
        // Must reject "Lost In Wave" when user wanted "Tu Hi Mera"
        val candidates = listOf(
            TrackCandidate(1, "Lost In Wave", "EDM Artists"),
            TrackCandidate(2, "Random Wave", "Producer"),
        )
        val outcome = MusicTrackMatcher.bestMatch("Tu Hi Mera", "", candidates)
        assertTrue("Must not match unrelated title", outcome is MatchOutcome.None)
    }

    @Test
    fun identifiesAmbiguityWhenSameTitleHasDifferentArtistsWithoutArtistHint() {
        val candidates = listOf(
            TrackCandidate(1, "Kaun Tujhe", "Palak Muchhal"),
            TrackCandidate(2, "Kaun Tujhe", "Armaan Malik"),
        )
        val outcome = MusicTrackMatcher.bestMatch("Kaun Tujhe", "", candidates)
        assertTrue("Must be ambiguous when multiple artists match same title", outcome is MatchOutcome.Ambiguous)
        val amb = outcome as MatchOutcome.Ambiguous
        assertEquals(2, amb.candidates.size)
    }
}
