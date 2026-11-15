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
}
