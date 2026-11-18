package app.shilex.android.assistant

import app.shilex.android.audio.BundledHindiVoices
import app.shilex.android.audio.VoiceGender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantAgentTest {

    @Test
    fun testScreenStateTextAndClickableMatching() {
        val disabledBtn = ScreenElement(
            text = "Uninstall",
            clickable = true,
            enabled = false,
        )
        val installBtn = ScreenElement(
            text = "Install",
            description = "Install Application Button",
            resourceId = "com.android.vending:id/install_button",
            clickable = true,
            enabled = true,
        )
        val state = ScreenState(
            packageName = "com.android.vending",
            screenTitle = "App Store",
            elements = listOf(disabledBtn, installBtn),
        )

        assertTrue(state.containsText("Install"))
        assertTrue(state.containsText("application"))
        assertFalse(state.containsText("Update"))

        assertTrue(state.hasClickableTarget("Install"))
        assertFalse(state.hasClickableTarget("Uninstall"))
        assertEquals(installBtn, state.findClickableTarget("Install"))
    }

    @Test
    fun testAgentRecoveryControllerResetsAndRetryLimits() {
        val controller = AgentRecoveryController(maxRetries = 2)
        val actionId = "click_install"

        // First attempt on different screen
        assertFalse(controller.shouldSkipDuplicate(actionId, "home_screen", "app_page"))

        // Duplicate action on same screen & expected destination should skip
        assertTrue(controller.shouldSkipDuplicate(actionId, "home_screen", "app_page"))

        // Already on expected destination should skip
        assertTrue(controller.shouldSkipDuplicate(actionId, "app_page", "app_page"))

        // Screen change resets attempted actions and retry counter
        assertTrue(controller.nextRetry())
        assertEquals(1, controller.currentRetryCount())
        controller.onScreenChanged("results_screen")
        assertEquals(0, controller.currentRetryCount())
        assertFalse(controller.shouldSkipDuplicate(actionId, "home_screen", "app_page"))

        // Destination change resets state
        assertTrue(controller.nextRetry())
        controller.onDestinationChanged("instagram:reels")
        assertEquals(0, controller.currentRetryCount())
        assertFalse(controller.shouldSkipDuplicate(actionId, "home_screen", "app_page"))

        // New user command resets state
        assertTrue(controller.nextRetry())
        controller.onNewUserCommand("Instagram profile kholo")
        assertEquals(0, controller.currentRetryCount())
        assertFalse(controller.shouldSkipDuplicate(actionId, "home_screen", "app_page"))

        // Retry exhaustion check
        assertTrue(controller.canRetry(0))
        assertTrue(controller.canRetry(1))
        assertFalse(controller.canRetry(2))
        assertTrue(controller.nextRetry())
        assertTrue(controller.nextRetry())
        assertFalse(controller.nextRetry())

        controller.reset()
        assertEquals(0, controller.currentRetryCount())
        assertFalse(controller.shouldSkipDuplicate(actionId, "home_screen", "app_page"))
    }

    @Test
    fun testAgentActionResultSuccessAndWrongScreen() {
        val success = AgentActionResult(
            status = ActionStatus.SUCCESS,
            message = "Opened Reels",
            expectedDestination = "REELS",
            actualDestination = "REELS",
        )
        assertTrue(success.isSuccessful)

        val already = AgentActionResult(
            status = ActionStatus.ALREADY_COMPLETED,
            message = "Already on Messages",
            expectedDestination = "MESSAGES",
            actualDestination = "MESSAGES",
        )
        assertTrue(already.isSuccessful)

        val wrong = AgentActionResult(
            status = ActionStatus.WRONG_SCREEN,
            message = "Landed on Home instead of Reels",
            expectedDestination = "REELS",
            actualDestination = "HOME",
            retryCount = 1,
        )
        assertFalse(wrong.isSuccessful)
        assertEquals(ActionStatus.WRONG_SCREEN, wrong.status)
    }

    @Test
    fun testAppNavigationIntentHindiHinglishAndEnglishMapping() {
        val msgIntent = AppNavigationIntent.fromCommand("Instagram messages kholo")
        assertNotNull(msgIntent)
        assertEquals("com.instagram.android", msgIntent!!.packageName)
        assertEquals(AppDestination.MESSAGES, msgIntent.destination)

        assertEquals(AppDestination.REELS, AppNavigationIntent.inferDestination("Reels kholo"))
        assertEquals(AppDestination.PROFILE, AppNavigationIntent.inferDestination("Instagram profile open karo"))
        assertEquals(AppDestination.HOME, AppNavigationIntent.inferDestination("go to home feed"))
        assertEquals(AppDestination.SEARCH, AppNavigationIntent.inferDestination("Instagram search kholo"))
        assertEquals(AppDestination.NOTIFICATIONS, AppNavigationIntent.inferDestination("open notifications"))
        assertNull(AppNavigationIntent.inferDestination("do something unrelated"))
    }

    @Test
    fun testVoiceGenderAndTenGenuineHindiVoices() {
        assertEquals("Female", VoiceGender.Female.label)
        assertEquals("Male", VoiceGender.Male.label)

        val female = BundledHindiVoices.ALL.filter { it.gender == VoiceGender.Female }
        val male = BundledHindiVoices.ALL.filter { it.gender == VoiceGender.Male }
        assertEquals(5, female.size)
        assertEquals(5, male.size)

        val validation = BundledHindiVoices.validateCatalog()
        assertTrue(validation.reason, validation.isValid)
        assertTrue(validation.reason.isEmpty())
    }
}

