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

    @Test
    fun verifiesChatRecipientHeaderAndFindsCallButtons() {
        val headerElements = listOf(
            ScreenElement(1, "button", "Navigate up", ""),
            ScreenElement(2, "text", "Rahul Sharma", ""),
            ScreenElement(3, "button", "Voice call", ""),
            ScreenElement(4, "button", "Video call", ""),
            ScreenElement(5, "field", "Message", ""),
        )
        val meta = mapOf(
            2 to ControlService.NodeMeta(2, "conversation_contact_name", "Rahul Sharma", "text", "com.whatsapp", Rect(50, 0, 200, 50)),
            3 to ControlService.NodeMeta(3, "voice_call", "Voice call", "button", "com.whatsapp", Rect(250, 0, 300, 50)),
            4 to ControlService.NodeMeta(4, "video_call", "Video call", "button", "com.whatsapp", Rect(310, 0, 360, 50)),
        )

        assertTrue(WhatsAppChatVerifier.matchesExpectedRecipient(headerElements, meta, "Rahul"))
        assertTrue(WhatsAppChatVerifier.matchesExpectedRecipient(headerElements, meta, "rahul sharma"))
        assertFalse(WhatsAppChatVerifier.matchesExpectedRecipient(headerElements, meta, "Priya"))

        assertEquals(3, WhatsAppCallMatcher.findCallButtonNumber(headerElements, meta, WhatsAppCallMatcher.CallKind.Audio))
        assertEquals(4, WhatsAppCallMatcher.findCallButtonNumber(headerElements, meta, WhatsAppCallMatcher.CallKind.Video))
    }

    @Test
    fun rejectsChatsListScreenAndSupportsPhoneDigitHeaderMatchAndComposerFinder() {
        val chatsList = listOf(
            ScreenElement(1, "text", "Chats", ""),
            ScreenElement(2, "button", "Search", ""),
            ScreenElement(3, "text", "Rahul Sharma · Hi", ""),
        )
        assertFalse(WhatsAppChatVerifier.isChatScreenOpen(chatsList, emptyMap()))
        assertFalse(WhatsAppChatVerifier.matchesExpectedRecipient(chatsList, emptyMap(), "Rahul Sharma"))

        val unsavedNumberChat = listOf(
            ScreenElement(1, "button", "Navigate up", ""),
            ScreenElement(2, "text", "+91 98765 43210", ""),
            ScreenElement(3, "field", "Message", ""),
        )
        val meta = mapOf(
            2 to ControlService.NodeMeta(2, "conversation_contact_name", "+91 98765 43210", "text", "com.whatsapp", Rect(50, 0, 260, 50)),
            3 to ControlService.NodeMeta(3, "entry", "Message", "field", "com.whatsapp", Rect(20, 800, 600, 900), isEditable = true),
        )
        assertTrue(WhatsAppChatVerifier.isChatScreenOpen(unsavedNumberChat, meta))
        assertTrue(WhatsAppChatVerifier.matchesExpectedRecipient(unsavedNumberChat, meta, "Krishna", expectedPhoneDigits = "919876543210"))
        assertEquals(3, WhatsAppComposerMatcher.findComposerNumber(unsavedNumberChat, meta))
    }

    @Test
    fun prefersTopToolbarCallButtonEvenWhenMissedCallCardsExistInChat() {
        val elements = listOf(
            ScreenElement(1, "button", "Navigate up", ""),
            ScreenElement(2, "text", "Aarav", ""),
            ScreenElement(3, "button", "Voice call", ""),
            ScreenElement(4, "button", "Video call", ""),
            ScreenElement(5, "button", "Voice call", ""), // in-chat missed/history call card
            ScreenElement(6, "field", "Message", ""),
        )
        val meta = mapOf(
            3 to ControlService.NodeMeta(3, "menuitem_call", "Voice call", "button", "com.whatsapp", Rect(500, 20, 580, 100)),
            4 to ControlService.NodeMeta(4, "menuitem_video", "Video call", "button", "com.whatsapp", Rect(590, 20, 670, 100)),
            5 to ControlService.NodeMeta(5, "call_card_btn", "Voice call", "button", "com.whatsapp", Rect(100, 600, 400, 700)),
        )
        assertEquals(3, WhatsAppCallMatcher.findCallButtonNumber(elements, meta, WhatsAppCallMatcher.CallKind.Audio))
        assertEquals(4, WhatsAppCallMatcher.findCallButtonNumber(elements, meta, WhatsAppCallMatcher.CallKind.Video))
    }

    @Test
    fun deleteMatcherFindsTargetMessageExcludesChromeAndEnforcesScopeSafety() {
        val chatElements = listOf(
            ScreenElement(1, "button", "Navigate up", ""),
            ScreenElement(2, "text", "Krishna", ""),
            ScreenElement(3, "text", "Messages and calls are end-to-end encrypted.", ""),
            ScreenElement(4, "text", "Today", ""),
            ScreenElement(5, "text", "Good morning!", ""),
            ScreenElement(6, "text", "Send me the notes please", ""),
            ScreenElement(7, "field", "Message", ""),
        )
        val chatMeta = mapOf(
            2 to ControlService.NodeMeta(2, "conversation_contact_name", "Krishna", "text", "com.whatsapp", Rect(50, 0, 200, 60)),
            7 to ControlService.NodeMeta(7, "entry", "Message", "field", "com.whatsapp", Rect(20, 800, 600, 900), isEditable = true),
        )

        val latest = WhatsAppDeleteMatcher.findTargetMessage(chatElements, chatMeta, which = "last")
        assertTrue(latest is WhatsAppDeleteMatcher.TargetResolution.Found)
        assertEquals(6, (latest as WhatsAppDeleteMatcher.TargetResolution.Found).element.number)

        val named = WhatsAppDeleteMatcher.findTargetMessage(chatElements, chatMeta, messageText = "good morning")
        assertTrue(named is WhatsAppDeleteMatcher.TargetResolution.Found)
        assertEquals(5, (named as WhatsAppDeleteMatcher.TargetResolution.Found).element.number)

        // Default scope ("Delete for me") must NEVER pick "Delete for everyone" even when both exist
        val dialogBoth = listOf(
            ScreenElement(1, "text", "Delete message?", ""),
            ScreenElement(2, "button", "Delete for everyone", ""),
            ScreenElement(3, "button", "Delete for me", ""),
            ScreenElement(4, "button", "Cancel", ""),
        )
        val analysisBoth = WhatsAppDeleteMatcher.analyzeDeleteDialog(dialogBoth)
        assertEquals(
            WhatsAppDeleteMatcher.DialogAction.TapDeleteForMe,
            WhatsAppDeleteMatcher.resolveDialogAction(analysisBoth, WhatsAppDeleteMatcher.DeleteScope.ForMe),
        )
        assertEquals(
            WhatsAppDeleteMatcher.DialogAction.TapDeleteForEveryone,
            WhatsAppDeleteMatcher.resolveDialogAction(analysisBoth, WhatsAppDeleteMatcher.DeleteScope.ForEveryone),
        )

        // When "Delete for everyone" is requested on an older/incoming message where only "Delete for me" exists,
        // it must report limitation instead of silently clicking "Delete for me".
        val dialogOnlyMe = listOf(
            ScreenElement(1, "text", "Delete message?", ""),
            ScreenElement(2, "button", "Delete for me", ""),
            ScreenElement(3, "button", "Cancel", ""),
        )
        val analysisOnlyMe = WhatsAppDeleteMatcher.analyzeDeleteDialog(dialogOnlyMe)
        assertEquals(
            WhatsAppDeleteMatcher.DialogAction.ReportEveryoneUnavailable,
            WhatsAppDeleteMatcher.resolveDialogAction(analysisOnlyMe, WhatsAppDeleteMatcher.DeleteScope.ForEveryone),
        )

        val afterDelete = chatElements.filterNot { it.number == 6 } + ScreenElement(6, "text", "You deleted this message", "")
        assertTrue(WhatsAppDeleteMatcher.verifyMessageDeleted(chatElements, afterDelete, "Send me the notes please"))
        assertFalse(WhatsAppDeleteMatcher.verifyMessageDeleted(chatElements, chatElements, "Send me the notes please"))
    }
}
