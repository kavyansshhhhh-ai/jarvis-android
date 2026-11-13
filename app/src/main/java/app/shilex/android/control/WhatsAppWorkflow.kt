package app.shilex.android.control

import app.shilex.android.Diag
import app.shilex.android.task.TaskTracker
import app.shilex.android.tools.SendCheck
import kotlinx.coroutines.delay

/**
 * Pure WhatsApp composer/Send matching & safety rules (unit-tested without Android).
 * Distinguishes the real Send button from voice-note/mic/camera controls and confirms the
 * chat draft matches the intended message text before tapping Send.
 */
object WhatsAppComposerMatcher {
    private val VOICE_OR_MEDIA_BUTTON = Regex(
        "voice|audio|mic|record|camera|attach|emoji|sticker",
        RegexOption.IGNORE_CASE,
    )
    private val SEND_ID = Regex("(^send$|send_btn|send_button)", RegexOption.IGNORE_CASE)
    private val SEND_LABEL = Regex("^send(\\s+message)?$", RegexOption.IGNORE_CASE)

    fun normalize(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

    /** True if the visible chat composer box holds the intended [expectedMessage]. */
    fun hasMatchingDraft(elements: List<ScreenElement>, expectedMessage: String): Boolean {
        val wanted = normalize(expectedMessage).take(40)
        if (wanted.isEmpty()) return false
        return elements.any { el ->
            el.role == "field" && wanted in normalize(el.value)
        }
    }

    /** Finds the unambiguous Send button number from a screen snapshot + metadata; null if none/uncertain. */
    fun findSendButtonNumber(elements: List<ScreenElement>, meta: Map<Int, ControlService.NodeMeta>): Int? {
        val candidates = elements.filter { el ->
            if (el.role != "button") return@filter false
            val m = meta[el.number]
            val viewId = m?.viewId.orEmpty()
            val lbl = el.label.trim()
            val combined = "$lbl $viewId"
            if (VOICE_OR_MEDIA_BUTTON.containsMatchIn(combined)) return@filter false
            SEND_ID.containsMatchIn(viewId) || SEND_LABEL.matches(lbl)
        }
        return candidates.singleOrNull()?.number
    }
}

/**
 * Verifies the WhatsApp draft and executes an explicitly user-authorized Send tap via Accessibility,
 * then confirms the message actually moved from composer into chat before claiming it was sent.
 */
object WhatsAppWorkflow {
    private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

    /**
     * Asks the user via [ControlOverlay.confirm] to authorize tapping Send for this exact
     * recipient & message, waits for the WhatsApp composer, taps Send, and verifies the post-send chat.
     */
    suspend fun confirmAndSendAfterOpen(
        service: ControlService,
        recipient: String,
        message: String,
    ): String {
        val preview = ScreenText.shorten(message)
        val approved = service.overlay.confirm("Send WhatsApp message to $recipient: \"$preview\"")
        if (!approved) {
            TaskTracker.verified(false, "Ready in WhatsApp. You can edit and tap Send yourself.")
            return "WhatsApp opened with your draft to $recipient. Sending wasn't confirmed, so tell the user to check and tap Send themselves."
        }

        // Wait briefly for WhatsApp to bring the pre-filled chat window forward.
        val until = System.currentTimeMillis() + OPEN_WAIT_MS
        while (System.currentTimeMillis() < until) {
            val fg = service.foregroundPackage()
            if (fg in WHATSAPP_PACKAGES) {
                val snap = service.snapshot()
                if (snap != null && snap.packageName in WHATSAPP_PACKAGES) {
                    if (snap.sensitiveAuth) {
                        TaskTracker.verified(false, "WhatsApp asked for lock/authentication; finish directly.")
                        return "WhatsApp shows an authentication screen. Ask the user to unlock WhatsApp and tap Send themselves."
                    }
                    if (WhatsAppComposerMatcher.hasMatchingDraft(snap.elements, message)) {
                        val sendNum = WhatsAppComposerMatcher.findSendButtonNumber(snap.elements, snap.meta)
                        val sendNode = sendNum?.let(service::node)
                        if (sendNode != null) {
                            service.overlay.showActive()
                            if (service.click(sendNode)) {
                                Diag.i("whatsapp: tapped Send (#$sendNum)")
                                delay(POST_SEND_SETTLE_MS)
                                repeat(VERIFY_TRIES) {
                                    val after = service.snapshot()
                                    if (after != null && SendCheck.sent(after.elements, message)) {
                                        TaskTracker.verified(true, "Sent to $recipient.")
                                        return "Sent the WhatsApp message to $recipient and verified it appeared in the chat."
                                    }
                                    delay(VERIFY_POLL_MS)
                                }
                                TaskTracker.verified(false, "Tapped Send, but couldn't verify it in the chat.")
                                return "WhatsApp is open and Send was tapped, though the chat couldn't verify delivery yet. Ask the user to glance at WhatsApp."
                            }
                        }
                    }
                }
            }
            delay(POLL_MS)
        }

        TaskTracker.verified(false, "Ready in WhatsApp. Tap Send to finish.")
        return "WhatsApp is open with the message to $recipient, but JARVIS couldn't reliably find the Send button. Ask the user to tap Send."
    }

    private const val OPEN_WAIT_MS = 8_000L
    private const val POLL_MS = 450L
    private const val POST_SEND_SETTLE_MS = 700L
    private const val VERIFY_TRIES = 6
    private const val VERIFY_POLL_MS = 500L
}
