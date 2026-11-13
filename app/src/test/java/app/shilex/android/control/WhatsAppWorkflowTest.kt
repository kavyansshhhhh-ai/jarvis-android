package app.shilex.android.control

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppWorkflowTest {
    @Test
    fun matchesComposerDraftAndDistinguishesSendButtonFromMicButton() {
        val elements = listOf(
            ScreenElement(1, "field", "Message", "On my way home"),
            ScreenElement(2, "button", "Attach", ""),
            ScreenElement(3, "button", "Send", ""),
        )
        val meta = mapOf(
            3 to ControlService.NodeMeta(3, "send", "Send", "button", "com.whatsapp", Rect(0, 0, 10, 10)),
        )
        assertTrue(WhatsAppComposerMatcher.hasMatchingDraft(elements, "On my way home!"))
        assertFalse(WhatsAppComposerMatcher.hasMatchingDraft(elements, "Different text"))
        assertEquals(3, WhatsAppComposerMatcher.findSendButtonNumber(elements, meta))

        // Must never confuse voice note / mic button with Send
        val micOnly = listOf(
            ScreenElement(1, "field", "Message", "On my way home"),
            ScreenElement(2, "button", "Send voice message", ""),
        )
        val micMeta = mapOf(
            2 to ControlService.NodeMeta(2, "voice_note_btn", "Send voice message", "button", "com.whatsapp", Rect(0, 0, 10, 10)),
        )
        assertNull(WhatsAppComposerMatcher.findSendButtonNumber(micOnly, micMeta))
    }
}
