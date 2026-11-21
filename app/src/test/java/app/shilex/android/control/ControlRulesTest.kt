package app.shilex.android.control

import app.shilex.android.control.ControlRules.Risk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlRulesTest {
    @Test
    fun financeAppsAreLimitedWhilePasswordVaultsNpciAndSystemRemainBlocked() {
        listOf(
            "com.phonepe.app",
            "net.one97.paytm",
            "com.google.android.apps.nbu.paisa.user",
            "in.org.npci.upiapp",
            "in.amazon.mShop.android.shopping.pay",
            "com.mobikwik_new",
        ).forEach {
            assertTrue(it, ControlRules.isSupportedUpiApp(it))
            assertTrue(it, ControlRules.isFinanceApp(it))
            assertFalse(it, ControlRules.blocked(it))
            assertTrue(it, ControlRules.isSensitiveOrBlockedPackage(it))
        }
        listOf(
            "com.sbi.lotusintouch",
            "com.snapwork.hdfc",
            "com.csam.icici.bank.imobile",
            "com.axis.mobile",
            "com.msf.kbank.mobile",
            "com.example.mybank",
            "com.unknown.upiwallet",
        ).forEach {
            assertEquals(it, ControlRules.AppAccess.LimitedFinance, ControlRules.appAccess(it))
            assertTrue(it, ControlRules.isFinanceApp(it))
            assertFalse(it, ControlRules.blocked(it))
            assertTrue(it, ControlRules.isSensitiveOrBlockedPackage(it))
        }
        listOf(
            "com.x8bit.bitwarden",
            "com.onepassword.android",
            "com.google.android.apps.authenticator2",
            "org.npci.upi.security.pinactivitycomponent",
            "app.shilex.android",
            "com.android.settings",
            "com.android.vending",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
        ).forEach {
            assertEquals(it, ControlRules.AppAccess.Blocked, ControlRules.appAccess(it))
            assertTrue(it, ControlRules.blocked(it))
            assertTrue(it, ControlRules.isSensitiveOrBlockedPackage(it))
        }
        listOf("com.whatsapp", "com.google.android.apps.youtube.music", "com.instagram.android")
            .forEach {
                assertEquals(it, ControlRules.AppAccess.Allowed, ControlRules.appAccess(it))
                assertFalse(it, ControlRules.blocked(it))
                assertFalse(it, ControlRules.isSensitiveOrBlockedPackage(it))
            }
    }

    @Test
    fun riskyTapsAndUpiStagesAreRecognised() {
        val gpay = "com.google.android.apps.nbu.paisa.user"
        val phonepe = "com.phonepe.app"
        val hdfc = "com.snapwork.hdfc"
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
        assertEquals(Risk.Money, ControlRules.clickRisk("Send ₹500", "", phonepe))
        assertEquals(Risk.Money, ControlRules.clickRisk("Transfer ₹1,000", "", hdfc))
        assertEquals(Risk.Money, ControlRules.clickRisk("Confirm & Pay", "", phonepe))
        assertEquals(Risk.Money, ControlRules.clickRisk("Pay", "", gpay))
        assertEquals(Risk.Outgoing, ControlRules.clickRisk("Proceed", "", gpay))
        assertEquals(Risk.Outgoing, ControlRules.clickRisk("Confirm", "", hdfc))
        assertEquals(Risk.Safe, ControlRules.clickRisk("Pay contacts", "", gpay))
        assertEquals(Risk.Safe, ControlRules.clickRisk("Pay phone number", "", gpay))
        assertEquals(Risk.Safe, ControlRules.clickRisk("Pay UPI ID or number", "", gpay))
        assertEquals(Risk.Safe, ControlRules.clickRisk("To Bank / UPI ID", "", phonepe))
        assertEquals(Risk.Safe, ControlRules.clickRisk("To Self Account", "", phonepe))
        assertEquals(Risk.Safe, ControlRules.clickRisk("Check Balance", "", phonepe))
        assertEquals(Risk.Safe, ControlRules.clickRisk("Enter amount", "", gpay))
        assertEquals(Risk.Safe, ControlRules.clickRisk("Rahul Sharma", "", gpay))
        assertEquals(Risk.Outgoing, ControlRules.clickRisk("Book tickets", ""))
        assertEquals(Risk.Safe, ControlRules.clickRisk("Select Seats", ""))
    }

    @Test
    fun credentialFieldsAndAuthScreensAreStrictlyDetectedWithoutFalseNpciBlock() {
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
        // Ordinary UPI screens frequently include "Powered by NPCI" footer/logo; must stay readable!
        assertFalse(
            ControlRules.isSensitiveAuthScreen(
                listOf("Pay contacts", "Pay UPI ID", "Rahul Sharma", "rahul@okaxis", "Powered by NPCI"),
            ),
        )
    }

    @Test
    fun transactionSummaryExtractsNonSensitiveDetailsAndExcludesCredentials() {
        val summary = ControlRules.extractTransactionSummary(
            listOf(
                ScreenElement(1, "text", "Paying Rahul Sharma"),
                ScreenElement(2, "text", "rahul@okaxis"),
                ScreenElement(3, "field", "Enter amount", "500"),
                ScreenElement(4, "field", "Enter UPI PIN", "1234"),
            ),
        )
        assertTrue(summary, "Amount: ₹500" in summary)
        assertTrue(summary, "Recipient: Rahul Sharma" in summary)
        assertTrue(summary, "UPI ID: rahul@okaxis" in summary)
        assertFalse(summary, "1234" in summary)
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

    @Test
    fun playStoreAutomationScopeUnblocksOnlyPlayStoreWhileKeepingVaultsAndSettingsBlocked() {
        assertTrue(ControlRules.blocked(ControlRules.PLAY_STORE_PACKAGE))
        assertFalse(ControlRules.blocked(ControlRules.PLAY_STORE_PACKAGE, allowPlayStoreAutomation = true))

        listOf(
            "com.android.settings",
            "app.shilex.android",
            "com.x8bit.bitwarden",
            "org.npci.upi.security.pinactivitycomponent",
        ).forEach { pkg ->
            assertTrue(pkg, ControlRules.blocked(pkg, allowPlayStoreAutomation = true))
        }
    }

    @Test
    fun screenTextIncludesSelectedAndDisabledStatesOnlyWhenNonDefault() {
        val text = ScreenText.describe(
            "Instagram",
            listOf(
                ScreenElement(1, "button", "Home", isEnabled = true, isSelected = false),
                ScreenElement(2, "button", "Reels", isEnabled = true, isSelected = true),
                ScreenElement(3, "button", "Install", isEnabled = false, isSelected = false),
            ),
            more = false,
        )
        assertTrue("1. [button] Home" in text)
        assertTrue("2. [button] [selected] Reels" in text)
        assertTrue("3. [button] [disabled] Install" in text)
    }
}
