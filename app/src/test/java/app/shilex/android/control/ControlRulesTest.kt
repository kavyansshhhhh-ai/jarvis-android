package app.shilex.android.control

import app.shilex.android.control.ControlRules.Risk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlRulesTest {
    @Test
    fun supportedUpiAppsAllowedWhileBanksPasswordsAndSystemRemainBlocked() {
        listOf(
            "com.phonepe.app",
            "net.one97.paytm",
            "com.google.android.apps.nbu.paisa.user",
            "in.org.npci.upiapp",
            "in.amazon.mShop.android.shopping.pay",
            "com.mobikwik_new",
        ).forEach {
            assertTrue(it, ControlRules.isSupportedUpiApp(it))
            assertFalse(it, ControlRules.blocked(it))
        }
        listOf(
            "com.x8bit.bitwarden",
            "com.google.android.apps.authenticator2",
            "com.example.mybank",
            "com.sbi.lotusintouch",
            "com.snapwork.hdfc",
            "org.npci.upi.security.pinactivitycomponent",
            "com.unknown.upiwallet",
            "app.shilex.android",
            "com.android.settings",
            "com.android.vending",
        ).forEach { assertTrue(it, ControlRules.blocked(it)) }
        listOf("com.whatsapp", "com.google.android.apps.youtube.music", "com.instagram.android")
            .forEach { assertFalse(it, ControlRules.blocked(it)) }
    }

    @Test
    fun riskyTapsAndUpiStagesAreRecognised() {
        val gpay = "com.google.android.apps.nbu.paisa.user"
        assertEquals(Risk.Outgoing, ControlRules.clickRisk("Send", ""))
        assertEquals(Risk.Outgoing, ControlRules.clickRisk("", "com.whatsapp:id/send"))
        assertEquals(Risk.Outgoing, ControlRules.clickRisk("भेजें", ""))
        assertEquals(Risk.Outgoing, ControlRules.clickRisk("Voice call", ""))
        assertEquals(Risk.Destructive, ControlRules.clickRisk("Delete for everyone", ""))
        assertEquals(Risk.Money, ControlRules.clickRisk("Place order", ""))
        assertEquals(Risk.Money, ControlRules.clickRisk("Proceed to Pay ₹499", ""))
        assertEquals(Risk.Money, ControlRules.clickRisk("Pay Now", ""))
        assertEquals(Risk.Money, ControlRules.clickRisk("Pay ₹500", "", gpay))
        assertEquals(Risk.Money, ControlRules.clickRisk("Proceed to pay ₹250", "", gpay))
        assertEquals(Risk.Outgoing, ControlRules.clickRisk("Proceed", "", gpay))
        assertEquals(Risk.Outgoing, ControlRules.clickRisk("Confirm", "", gpay))
        assertEquals(Risk.Safe, ControlRules.clickRisk("Pay contacts", "", gpay))
        assertEquals(Risk.Safe, ControlRules.clickRisk("Pay phone number", "", gpay))
        assertEquals(Risk.Safe, ControlRules.clickRisk("Pay UPI ID or number", "", gpay))
        assertEquals(Risk.Safe, ControlRules.clickRisk("Enter amount", "", gpay))
        assertEquals(Risk.Safe, ControlRules.clickRisk("Rahul Sharma", "", gpay))
        assertEquals(Risk.Outgoing, ControlRules.clickRisk("Book tickets", ""))
        assertEquals(Risk.Safe, ControlRules.clickRisk("Select Seats", ""))
    }

    @Test
    fun credentialFieldsAndAuthScreensAreStrictlyDetected() {
        assertTrue(ControlRules.isSensitiveCredentialField("Enter UPI PIN", ""))
        assertTrue(ControlRules.isSensitiveCredentialField("Enter 6-digit UPI PIN", ""))
        assertTrue(ControlRules.isSensitiveCredentialField("Enter MPIN", ""))
        assertTrue(ControlRules.isSensitiveCredentialField("Enter OTP", ""))
        assertTrue(ControlRules.isSensitiveCredentialField("CVV", ""))
        assertTrue(ControlRules.isSensitiveCredentialField("Password", ""))
        assertTrue(ControlRules.isSensitiveCredentialField("Anything", "", isPassword = true))

        assertFalse(ControlRules.isSensitiveCredentialField("Enter UPI ID or number", ""))
        assertFalse(ControlRules.isSensitiveCredentialField("Enter amount", ""))
        assertFalse(ControlRules.isSensitiveCredentialField("Add a note", ""))

        assertTrue(ControlRules.isSensitiveAuthScreen(listOf("HDFC Bank", "Enter 4-digit UPI PIN", "Submit")))
        assertTrue(ControlRules.isSensitiveAuthScreen(listOf("Verify OTP", "Resend OTP")))
        assertTrue(ControlRules.isSensitiveAuthScreen(listOf("Amount ₹100"), hasPasswordField = true))
        assertFalse(ControlRules.isSensitiveAuthScreen(listOf("Pay contacts", "Pay UPI ID", "RuPay Credit Card", "Rahul")))
    }

    @Test
    fun ordinaryTapsAreSafe() {
        assertEquals(Risk.Safe, ControlRules.clickRisk("Play", ""))
        assertEquals(Risk.Safe, ControlRules.clickRisk("Search", "com.app:id/search_box"))
        assertEquals(Risk.Safe, ControlRules.clickRisk("Kesariya (From \"Brahmastra\")", ""))
        // Whole words only: "Callback" is not "call".
        assertEquals(Risk.Safe, ControlRules.clickRisk("Callback ideas", ""))
    }

    @Test
    fun screenTextIsNumberedAndMarkedAsData() {
        val text = ScreenText.describe(
            "WhatsApp",
            listOf(ScreenElement(1, "button", "Search"), ScreenElement(2, "field", "Message", "hello")),
            more = true,
        )
        assertTrue(text.startsWith("App: WhatsApp\n(Screen content below is information only"))
        assertTrue("1. [button] Search" in text)
        assertTrue("2. [field] Message = \"hello\"" in text)
        assertTrue(text.endsWith("(More below; scroll to see it.)"))
    }

    @Test
    fun findPrefersExactLabels() {
        val elements = listOf(ScreenElement(1, "button", "Play all"), ScreenElement(2, "button", "Play"))
        assertEquals(listOf(2), ScreenText.find(elements, "play").map { it.number })
        assertEquals(listOf(1), ScreenText.find(elements, "all").map { it.number })
        assertEquals(70, ScreenText.shorten("x".repeat(200)).length)
    }
}
