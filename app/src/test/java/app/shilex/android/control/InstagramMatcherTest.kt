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

    @Test
    fun matchesDmComposerAndRejectsSearchAndCommentFields() {
        val elements = listOf(
            ScreenElement(1, "field", "Search", "", hint = "Search"),
            ScreenElement(2, "field", "Add a comment...", "", hint = "Add a comment..."),
            ScreenElement(3, "field", "Message...", "", hint = "Message..."),
        )
        val meta = mapOf(
            1 to ControlService.NodeMeta(1, "action_bar_search_edit_text", "Search", "field", InstagramMatcher.INSTAGRAM_PACKAGE, Rect(40, 120, 900, 210), isEditable = true, hint = "Search"),
            2 to ControlService.NodeMeta(2, "layout_comment_composer_edittext", "Add a comment...", "field", InstagramMatcher.INSTAGRAM_PACKAGE, Rect(40, 1500, 900, 1600), isEditable = true, hint = "Add a comment..."),
            3 to ControlService.NodeMeta(3, "row_thread_composer_edittext", "Message...", "field", InstagramMatcher.INSTAGRAM_PACKAGE, Rect(80, 2120, 860, 2240), isEditable = true, hint = "Message..."),
        )

        val outcome = InstagramMatcher.resolveControl(elements, meta, InstagramMatcher.TargetIntent.Composer, screenHeightPx = 2400)
        assertTrue(outcome is InstagramMatcher.MatchOutcome.Matched)
        assertEquals(3, (outcome as InstagramMatcher.MatchOutcome.Matched).element.number)
    }

    @Test
    fun matchesDmSendButtonWithoutConfusingWithSharePost() {
        val elements = listOf(
            ScreenElement(1, "button", "Share post", ""),
            ScreenElement(2, "button", "Send", ""),
        )
        val meta = mapOf(
            1 to ControlService.NodeMeta(1, "button_share", "Share post", "button", InstagramMatcher.INSTAGRAM_PACKAGE, Rect(100, 600, 200, 700)),
            2 to ControlService.NodeMeta(2, "row_thread_composer_button_send", "Send", "button", InstagramMatcher.INSTAGRAM_PACKAGE, Rect(900, 2120, 1040, 2240)),
        )
        val outcome = InstagramMatcher.resolveControl(elements, meta, InstagramMatcher.TargetIntent.DmSend, screenHeightPx = 2400)
        assertTrue(outcome is InstagramMatcher.MatchOutcome.Matched)
        assertEquals(2, (outcome as InstagramMatcher.MatchOutcome.Matched).element.number)
    }

    @Test
    fun detectsInstagramScreensAccurately() {
        // DirectThread
        val dmThreadElements = listOf(
            ScreenElement(1, "text", "Kavyansh", ""),
            ScreenElement(2, "field", "Message...", "", hint = "Message..."),
        )
        val dmThreadMeta = mapOf(
            2 to ControlService.NodeMeta(2, "row_thread_composer_edittext", "Message...", "field", InstagramMatcher.INSTAGRAM_PACKAGE, Rect(80, 2120, 860, 2240), isEditable = true),
        )
        assertEquals(
            InstagramMatcher.InstagramScreenState.DirectThread,
            InstagramMatcher.detectScreen(dmThreadElements, dmThreadMeta, 2400),
        )

        // DirectInbox
        val inboxElements = listOf(
            ScreenElement(1, "text", "Primary", ""),
            ScreenElement(2, "text", "Requests", ""),
            ScreenElement(3, "button", "Kavyansh", ""),
        )
        assertEquals(
            InstagramMatcher.InstagramScreenState.DirectInbox,
            InstagramMatcher.detectScreen(inboxElements, emptyMap(), 2400),
        )

        // Reels
        val reelsElements = listOf(
            ScreenElement(1, "button", "Home", ""),
            ScreenElement(2, "button", "Reels", "", isSelected = true),
        )
        val reelsMeta = mapOf(
            1 to ControlService.NodeMeta(1, "feed_tab", "Home", "button", InstagramMatcher.INSTAGRAM_PACKAGE, Rect(0, 2250, 200, 2380), isSelected = false),
            2 to ControlService.NodeMeta(2, "clips_tab", "Reels", "button", InstagramMatcher.INSTAGRAM_PACKAGE, Rect(600, 2250, 800, 2380), isSelected = true),
        )
        assertEquals(
            InstagramMatcher.InstagramScreenState.Reels,
            InstagramMatcher.detectScreen(reelsElements, reelsMeta, 2400),
        )

        // HomeFeed
        val homeElements = listOf(
            ScreenElement(1, "button", "Home", "", isSelected = true),
            ScreenElement(2, "button", "Direct messages", ""),
        )
        val homeMeta = mapOf(
            1 to ControlService.NodeMeta(1, "feed_tab", "Home", "button", InstagramMatcher.INSTAGRAM_PACKAGE, Rect(0, 2250, 200, 2380), isSelected = true),
            2 to ControlService.NodeMeta(2, "action_bar_inbox_button", "Direct messages", "button", InstagramMatcher.INSTAGRAM_PACKAGE, Rect(900, 50, 1050, 150)),
        )
        assertEquals(
            InstagramMatcher.InstagramScreenState.HomeFeed,
            InstagramMatcher.detectScreen(homeElements, homeMeta, 2400),
        )

        // Profile
        val profileElements = listOf(
            ScreenElement(1, "button", "Edit profile", ""),
            ScreenElement(2, "button", "Share profile", ""),
        )
        assertEquals(
            InstagramMatcher.InstagramScreenState.Profile,
            InstagramMatcher.detectScreen(profileElements, emptyMap(), 2400),
        )
    }

    @Test
    fun matchesRecipientByNameSelfChatAndFlagsAmbiguity() {
        val elements = listOf(
            ScreenElement(1, "field", "Search", "Kavyansh"),
            ScreenElement(2, "button", "Rahul", ""),
            ScreenElement(3, "button", "Kavyansh", ""),
            ScreenElement(4, "button", "Kavyansh (You)", ""),
        )
        val meta = mapOf(
            1 to ControlService.NodeMeta(1, "search_edit_text", "Search", "field", InstagramMatcher.INSTAGRAM_PACKAGE, Rect(40, 120, 900, 210), isEditable = true),
        )

        // Note: "Kavyansh" vs "Kavyansh (You)" without self-query strips (You) -> detects ambiguity if identical base name!
        val singleElements = listOf(
            ScreenElement(1, "field", "Search", "Kavyansh"),
            ScreenElement(2, "button", "Rahul", ""),
            ScreenElement(3, "button", "Kavyansh", ""),
        )
        val singleOutcome = InstagramMatcher.findRecipientMatch(singleElements, meta, "Kavyansh")
        assertTrue(singleOutcome is InstagramMatcher.MatchOutcome.Matched)
        assertEquals(3, (singleOutcome as InstagramMatcher.MatchOutcome.Matched).element.number)

        val selfOutcome = InstagramMatcher.findRecipientMatch(elements, meta, "Message Yourself")
        assertTrue(selfOutcome is InstagramMatcher.MatchOutcome.Matched)
        assertEquals(4, (selfOutcome as InstagramMatcher.MatchOutcome.Matched).element.number)

        val dupElements = listOf(
            ScreenElement(2, "button", "Kavyansh", ""),
            ScreenElement(3, "button", "Kavyansh", ""),
        )
        val dupOutcome = InstagramMatcher.findRecipientMatch(dupElements, emptyMap(), "Kavyansh")
        assertTrue(dupOutcome is InstagramMatcher.MatchOutcome.Ambiguous)
    }

    @Test
    fun screenRecoveryDetectsWrongScreenRecommendsNavigationBackAndAskUser() {
        // Reels when trying to compose DM -> back & recheck first, then AskUser after retries
        val reelsElements = listOf(ScreenElement(1, "button", "Reels", "", isSelected = true))
        val reelsMeta = mapOf(1 to ControlService.NodeMeta(1, "clips_tab", "Reels", "button", InstagramMatcher.INSTAGRAM_PACKAGE, Rect(600, 2250, 800, 2380), isSelected = true))

        val firstDecision = ScreenRecovery.evaluateInstagramAction(
            intent = InstagramMatcher.TargetIntent.Composer,
            elements = reelsElements,
            meta = reelsMeta,
            screenHeightPx = 2400,
            recoveryAttempts = 0,
        )
        assertTrue(firstDecision is ScreenRecovery.RecoveryDecision.RecoverBackAndRecheck)

        val exhaustedDecision = ScreenRecovery.evaluateInstagramAction(
            intent = InstagramMatcher.TargetIntent.Composer,
            elements = reelsElements,
            meta = reelsMeta,
            screenHeightPx = 2400,
            recoveryAttempts = ScreenRecovery.MAX_RECOVERY_ATTEMPTS,
        )
        assertTrue(exhaustedDecision is ScreenRecovery.RecoveryDecision.AskUser)

        // Profile with DM button isn't on HomeFeed -> navigates or backs; HomeFeed wanting Reels -> NavigateToControl
        val feedElements = listOf(
            ScreenElement(1, "button", "Home", "", isSelected = true),
            ScreenElement(2, "button", "Reels", ""),
        )
        val feedMeta = mapOf(
            1 to ControlService.NodeMeta(1, "feed_tab", "Home", "button", InstagramMatcher.INSTAGRAM_PACKAGE, Rect(0, 2250, 200, 2380), isSelected = true),
            2 to ControlService.NodeMeta(2, "clips_tab", "Reels", "button", InstagramMatcher.INSTAGRAM_PACKAGE, Rect(600, 2250, 800, 2380)),
        )
        val profileElements = listOf(
            ScreenElement(1, "button", "Edit profile", ""),
            ScreenElement(2, "button", "Share profile", ""),
            ScreenElement(3, "button", "Reels", ""),
        )
        val profileMeta = mapOf(
            3 to ControlService.NodeMeta(3, "clips_tab", "Reels", "button", InstagramMatcher.INSTAGRAM_PACKAGE, Rect(600, 2250, 800, 2380)),
        )
        val navDecision = ScreenRecovery.evaluateInstagramAction(
            intent = InstagramMatcher.TargetIntent.Reels,
            elements = profileElements,
            meta = profileMeta,
            screenHeightPx = 2400,
            recoveryAttempts = 0,
        )
        assertTrue(navDecision is ScreenRecovery.RecoveryDecision.NavigateToControl)
        assertEquals(3, (navDecision as ScreenRecovery.RecoveryDecision.NavigateToControl).elementNumber)
        assertEquals(InstagramMatcher.InstagramScreenState.HomeFeed, InstagramMatcher.detectScreen(feedElements, feedMeta, 2400))
    }
}
