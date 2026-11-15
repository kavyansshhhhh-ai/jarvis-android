package app.shilex.android.control

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramMatcherTest {

    @Test
    fun matchesMessagesInboxAndRejectsShareOrPostButtons() {
        val elements = listOf(
            ScreenElement(1, "button", "Instagram logo", ""),
            ScreenElement(2, "button", "Direct messages, 2 unread", ""),
            ScreenElement(3, "button", "Share post", ""),
            ScreenElement(4, "button", "Comment", ""),
            ScreenElement(5, "button", "Like", ""),
        )
        val meta = mapOf(
            2 to ControlService.NodeMeta(2, "action_bar_inbox_button", "Direct messages, 2 unread", "button", "com.instagram.android", Rect(900, 50, 1050, 150)),
            3 to ControlService.NodeMeta(3, "button_share", "Share post", "button", "com.instagram.android", Rect(100, 600, 200, 700)),
        )

        val outcome = InstagramMatcher.resolveControl(elements, meta, InstagramMatcher.TargetIntent.Messages)
        assertTrue(outcome is InstagramMatcher.MatchOutcome.Matched)
        val matched = outcome as InstagramMatcher.MatchOutcome.Matched
        assertEquals(2, matched.element.number)
    }

    @Test
    fun infersIntentsFromNaturalQueries() {
        assertEquals(InstagramMatcher.TargetIntent.Messages, InstagramMatcher.inferIntent("open my dms"))
        assertEquals(InstagramMatcher.TargetIntent.Messages, InstagramMatcher.inferIntent("messages"))
        assertEquals(InstagramMatcher.TargetIntent.Search, InstagramMatcher.inferIntent("search page"))
        assertEquals(InstagramMatcher.TargetIntent.Reels, InstagramMatcher.inferIntent("watch reels"))
        assertEquals(InstagramMatcher.TargetIntent.Profile, InstagramMatcher.inferIntent("my profile"))
    }

    @Test
    fun selectsMainVerticalScrollContainerOverHorizontalStoryRow() {
        val storyList = InstagramMatcher.ScrollableBox(
            id = 2,
            widthPx = 1080,
            heightPx = 300,
            viewId = "stories_recycler_view",
        )
        val feedList = InstagramMatcher.ScrollableBox(
            id = 3,
            widthPx = 1080,
            heightPx = 2050,
            viewId = "feed_recycler_view",
        )

        val selected = InstagramMatcher.pickMainVerticalScrollContainer(listOf(storyList, feedList))
        assertEquals(3, selected)
    }
}
