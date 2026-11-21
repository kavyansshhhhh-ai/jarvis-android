package app.shilex.android.tools

import app.shilex.android.control.ScreenElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayStoreMatcherTest {

    @Test
    fun identifiesFreeInstallButtonOnDetailListing() {
        val elements = listOf(
            ScreenElement(1, "text", "LastWave Music", ""),
            ScreenElement(2, "text", "Shilex Audio Labs", ""),
            ScreenElement(3, "button", "Install", ""),
        )
        val decision = PlayStoreMatcher.analyzeScreen("LastWave", elements)
        assertEquals(PlayStoreMatcher.ButtonState.Install, decision.buttonState)
        assertNotNull(decision.actionElement)
        assertEquals(3, decision.actionElement?.number)
    }

    @Test
    fun stopsOnPaidAppPurchaseDialog() {
        val elements = listOf(
            ScreenElement(1, "text", "Nova Launcher Prime", ""),
            ScreenElement(2, "button", "₹149.00 Buy", ""),
        )
        val decision = PlayStoreMatcher.analyzeScreen("Nova Launcher Prime", elements)
        assertEquals(PlayStoreMatcher.ButtonState.PaidOrBlocked, decision.buttonState)
        assertTrue(decision.reason.contains("payment") || decision.reason.contains("purchase"))
    }

    @Test
    fun stopsOnAccountAgeOrAuthVerificationDialog() {
        val elements = listOf(
            ScreenElement(1, "text", "Complete account setup", ""),
            ScreenElement(2, "text", "Add a payment method to continue", ""),
            ScreenElement(3, "button", "Continue payment", ""),
        )
        val decision = PlayStoreMatcher.analyzeScreen("WhatsApp", elements)
        assertEquals(PlayStoreMatcher.ButtonState.PaidOrBlocked, decision.buttonState)
        assertTrue(decision.reason.contains("payment") || decision.reason.contains("account"))
    }

    @Test
    fun detectsAlreadyInstalledOpenOrUpdate() {
        val elements = listOf(
            ScreenElement(1, "text", "Telegram", ""),
            ScreenElement(2, "button", "Open", ""),
        )
        val decision = PlayStoreMatcher.analyzeScreen("Telegram", elements)
        assertEquals(PlayStoreMatcher.ButtonState.Open, decision.buttonState)
        assertEquals(2, decision.actionElement?.number)
    }

    @Test
    fun detectsOngoingDownloadOrInstallation() {
        val elements = listOf(
            ScreenElement(1, "text", "Spotify", ""),
            ScreenElement(2, "button", "Cancel", ""),
            ScreenElement(3, "text", "Downloading 45%", ""),
        )
        val decision = PlayStoreMatcher.analyzeScreen("Spotify", elements)
        assertEquals(PlayStoreMatcher.ButtonState.InstallingOrPending, decision.buttonState)
    }

    @Test
    fun handlesFreeInstallVariantPlayOpenStateAndWaitingPending() {
        val freeInstall = listOf(
            ScreenElement(1, "text", "WhatsApp Messenger", ""),
            ScreenElement(2, "button", "Install (free)", ""),
        )
        val d1 = PlayStoreMatcher.analyzeScreen("WhatsApp", freeInstall)
        assertEquals(PlayStoreMatcher.ButtonState.Install, d1.buttonState)
        assertEquals(2, d1.actionElement?.number)

        val playInstalled = listOf(
            ScreenElement(1, "text", "Subway Surfers", ""),
            ScreenElement(2, "button", "Uninstall", ""),
            ScreenElement(3, "button", "Play", ""),
        )
        val d2 = PlayStoreMatcher.analyzeScreen("Subway Surfers", playInstalled)
        assertEquals(PlayStoreMatcher.ButtonState.Open, d2.buttonState)
        assertTrue(d2.isVerifiedInstalledUi)

        val waitingWifi = listOf(
            ScreenElement(1, "text", "Spotify", ""),
            ScreenElement(2, "text", "Waiting for Wi-Fi", ""),
        )
        val d3 = PlayStoreMatcher.analyzeScreen("Spotify", waitingWifi)
        assertEquals(PlayStoreMatcher.ButtonState.InstallingOrPending, d3.buttonState)
    }

    @Test
    fun locatesSearchBarAndFlagsAmbiguousListings() {
        val searchElements = listOf(
            ScreenElement(1, "button", "Search apps & games", ""),
            ScreenElement(2, "button", "LastWave", ""),
            ScreenElement(3, "button", "LastWave", ""),
        )
        val searchBtn = PlayStoreMatcher.findSearchInput(searchElements)
        assertNotNull(searchBtn)
        assertEquals(1, searchBtn?.number)

        val ambiguousDecision = PlayStoreMatcher.analyzeScreen("LastWave", searchElements)
        assertEquals(PlayStoreMatcher.ButtonState.None, ambiguousDecision.buttonState)
        assertEquals(2, ambiguousDecision.ambiguousListings.size)
        assertEquals(
            StoreFlowState.RESULTS_VISIBLE,
            PlayStoreMatcher.resolveFlowState(ambiguousDecision),
        )
    }

    @Test
    fun storeFlowStateTransitionsCoverInstallVerificationAndBlockedCases() {
        val installDecision = PlayStoreMatcher.analyzeScreen(
            "Signal",
            listOf(
                ScreenElement(1, "text", "Signal Private Messenger", ""),
                ScreenElement(2, "button", "Install", ""),
            ),
        )
        assertEquals(
            StoreFlowState.APP_PAGE_VISIBLE,
            PlayStoreMatcher.resolveFlowState(installDecision),
        )
        assertEquals(
            StoreFlowState.INSTALLING,
            PlayStoreMatcher.resolveFlowState(installDecision, confirmedInstall = true),
        )
        assertEquals(
            StoreFlowState.COMPLETED,
            PlayStoreMatcher.resolveFlowState(installDecision, verifiedInstalledAfterAction = true),
        )

        val blockedDecision = PlayStoreMatcher.analyzeScreen(
            "Nova Launcher Prime",
            listOf(ScreenElement(1, "button", "₹149.00 Buy", "")),
        )
        assertEquals(
            StoreFlowState.FAILED,
            PlayStoreMatcher.resolveFlowState(blockedDecision),
        )
        assertEquals(
            StoreFlowState.COMPLETED,
            PlayStoreMatcher.resolveFlowState(blockedDecision, alreadyInstalled = true),
        )
    }

    @Test
    fun rejectsSponsoredAdsCloneGuidesAndWrongMultiTokenVariantsLikeFreeFireMaxForFreeFireIndia() {
        val searchResults = listOf(
            ScreenElement(1, "button", "Sponsored · Battlegrounds Mobile India", ""),
            ScreenElement(2, "button", "Install", ""),
            ScreenElement(3, "button", "Free Fire MAX · Garena International I", ""),
            ScreenElement(4, "button", "Install", ""),
            ScreenElement(5, "button", "Free Fire India Guide & Tips", ""),
            ScreenElement(6, "button", "Free Fire India · Garena International I", ""),
        )
        val decision = PlayStoreMatcher.analyzeScreen("Free Fire India", searchResults)
        // Multiple Install buttons on search list must NOT click an unrelated card's Install button;
        // instead it should select the exact official Free Fire India card to open its listing page!
        assertEquals(PlayStoreMatcher.ButtonState.None, decision.buttonState)
        assertNotNull(decision.matchedListingElement)
        assertEquals(6, decision.matchedListingElement?.number)
        assertEquals("Free Fire India", decision.listingTitle)

        // When only Free Fire MAX and Sponsored exist (no Free Fire India), must not mis-pick Free Fire MAX
        val noIndiaMatch = listOf(
            ScreenElement(1, "button", "Sponsored · Free Fire India", ""),
            ScreenElement(2, "button", "Free Fire MAX", ""),
            ScreenElement(3, "button", "Free Fire India Wallpaper", ""),
        )
        val miss = PlayStoreMatcher.analyzeScreen("Free Fire India", noIndiaMatch)
        assertEquals(PlayStoreMatcher.ButtonState.None, miss.buttonState)
        assertEquals(null, miss.matchedListingElement)
    }

    @Test
    fun detectsPreRegisterNetworkErrorAndDoesNotFalseMatchCancelBodyText() {
        val preReg = listOf(
            ScreenElement(1, "text", "Free Fire India", ""),
            ScreenElement(2, "button", "Pre-register", ""),
        )
        val preRegDecision = PlayStoreMatcher.analyzeScreen("Free Fire India", preReg)
        assertEquals(PlayStoreMatcher.ButtonState.PreRegisterOrUnavailable, preRegDecision.buttonState)
        assertEquals(StoreFlowState.FAILED, PlayStoreMatcher.resolveFlowState(preRegDecision))

        val offline = listOf(
            ScreenElement(1, "text", "You're offline. Check your connection.", ""),
            ScreenElement(2, "button", "Try again", ""),
        )
        val offlineDecision = PlayStoreMatcher.analyzeScreen("WhatsApp", offline)
        assertEquals(PlayStoreMatcher.ButtonState.NetworkError, offlineDecision.buttonState)
        assertEquals(2, offlineDecision.actionElement?.number)

        val descriptionWithCancel = listOf(
            ScreenElement(1, "text", "Spotify", ""),
            ScreenElement(2, "text", "Cancel anytime in settings.", ""),
            ScreenElement(3, "button", "Install", ""),
        )
        val d = PlayStoreMatcher.analyzeScreen("Spotify", descriptionWithCancel)
        assertEquals(PlayStoreMatcher.ButtonState.Install, d.buttonState)
        assertEquals(3, d.actionElement?.number)
    }
}
