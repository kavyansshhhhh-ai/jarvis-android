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
        "voice|audio|mic|record|camera|attach|emoji|sticker|video\\s*note",
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
 * Pure WhatsApp conversation header & search-result verifier (JVM-tested).
 * Prevents sending/calling the wrong recipient if a stale chat is open.
 */
object WhatsAppChatVerifier {
    private val NON_RECIPIENT_HEADER_TEXT = Regex(
        "^(?:whatsapp|search|chats|updates|calls|back|navigate up|more options|video call|voice call|call|audio call|today|yesterday|online|typing\\.{0,3}|last seen.*)$",
        RegexOption.IGNORE_CASE,
    )

    fun normalizeTokens(text: String): List<String> = text
        .lowercase()
        .replace(Regex("[^a-z0-9\\u0900-\\u097F\\s]"), " ")
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }

    /**
     * Extracts plausible conversation title strings shown in the top area of an active chat screen.
     */
    fun extractHeaderNames(elements: List<ScreenElement>, meta: Map<Int, ControlService.NodeMeta>): List<String> {
        val topTitle = elements.filter { el ->
            if (el.role == "field") return@filter false
            val lbl = el.label.trim()
            if (lbl.isEmpty() || NON_RECIPIENT_HEADER_TEXT.matches(lbl)) return@filter false
            val viewId = meta[el.number]?.viewId.orEmpty()
            val isExplicitHeaderId = "conversation_contact_name" in viewId ||
                "contact_name" in viewId ||
                "toolbar" in viewId ||
                "action_bar_title" in viewId
            val isEarlyTopRow = el.number <= 8 && el.role in setOf("text", "button")
            isExplicitHeaderId || isEarlyTopRow
        }.map { it.label.trim() }
        return topTitle.distinct()
    }

    /**
     * Checks whether the open WhatsApp chat header clearly matches [expectedRecipient].
     * Returns true if no identifiable header was exposed or if at least one header token group matches.
     * Returns false only if an explicit different contact name is visible in the header.
     */
    fun matchesExpectedRecipient(
        elements: List<ScreenElement>,
        meta: Map<Int, ControlService.NodeMeta>,
        expectedRecipient: String,
    ): Boolean {
        val wantedTokens = normalizeTokens(expectedRecipient)
        if (wantedTokens.isEmpty()) return true
        val wantedJoined = wantedTokens.joinToString("")
        val headerNamed = elements.filter { el ->
            val viewId = meta[el.number]?.viewId.orEmpty()
            "conversation_contact_name" in viewId || "contact_name" in viewId
        }.map { it.label.trim() }.filter { it.isNotEmpty() }

        val pool = headerNamed.ifEmpty {
            extractHeaderNames(elements, meta).take(3)
        }
        if (pool.isEmpty()) return true

        val matched = pool.any { candidate ->
            val candTokens = normalizeTokens(candidate)
            val candJoined = candTokens.joinToString("")
            if (candJoined.isEmpty()) return@any false
            candJoined == wantedJoined ||
                wantedTokens.all { wt -> candTokens.any { it.startsWith(wt) || wt.startsWith(it) } }
        }
        // Only reject strictly when an explicit contact-name viewId disagrees, or none of top header items look non-UI chrome and none match.
        return matched || (headerNamed.isEmpty() && !elements.any { it.role == "field" })
    }
}

/**
 * Pure WhatsApp Audio vs Video call button matcher (JVM-tested).
 * Ensures audio call commands never tap Video call, and vice versa.
 */
object WhatsAppCallMatcher {
    enum class CallKind { Audio, Video }

    private val VIDEO_RE = Regex("\\b(video|vc)\\b|video_call|videocall", RegexOption.IGNORE_CASE)
    private val AUDIO_RE = Regex("\\b(voice\\s*call|audio\\s*call|call)\\b|voice_call|audio_call|menu_call", RegexOption.IGNORE_CASE)
    private val NON_CALL_RE = Regex("attach|camera|mic|record|voice\\s*note|message|status|back", RegexOption.IGNORE_CASE)

    fun findCallButtonNumber(
        elements: List<ScreenElement>,
        meta: Map<Int, ControlService.NodeMeta>,
        kind: CallKind,
    ): Int? {
        val candidates = elements.filter { el ->
            if (el.role != "button") return@filter false
            val lbl = el.label.trim()
            val viewId = meta[el.number]?.viewId.orEmpty()
            val combined = "$lbl $viewId"
            if (NON_CALL_RE.containsMatchIn(combined)) return@filter false
            when (kind) {
                CallKind.Video -> VIDEO_RE.containsMatchIn(combined)
                CallKind.Audio -> AUDIO_RE.containsMatchIn(combined) && !VIDEO_RE.containsMatchIn(combined)
            }
        }
        return candidates.singleOrNull()?.number
    }

    /** Detects whether visible elements show an active WhatsApp call/ringing screen. */
    fun isCallScreenVisible(elements: List<ScreenElement>, meta: Map<Int, ControlService.NodeMeta>): Boolean =
        elements.any { el ->
            val lbl = el.label.lowercase()
            val viewId = meta[el.number]?.viewId.orEmpty().lowercase()
            "calling" in lbl ||
                "ringing" in lbl ||
                "end call" in lbl ||
                "end_call" in viewId ||
                "voip" in viewId ||
                "mute" in viewId && "speaker" in elements.joinToString(" ") { it.label.lowercase() }
        }
}

/**
 * Verifies the WhatsApp draft, conversation identity, calling buttons, and executes
 * authorized WhatsApp automation cleanly via Accessibility, checking outcomes honestly.
 */
object WhatsAppWorkflow {
    private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

    /**
     * Completes sending after WhatsApp opens to the chat draft.
     * When [skipInternalConfirm] is true (user enabled WhatsApp message automation on a clean turn),
     * skips the redundant internal Allow popup, while still verifying recipient header, draft text,
     * unambiguous Send button, and post-send chat appearance.
     */
    suspend fun confirmAndSendAfterOpen(
        service: ControlService,
        recipient: String,
        message: String,
        skipInternalConfirm: Boolean = false,
    ): String {
        if (!skipInternalConfirm) {
            val preview = ScreenText.shorten(message)
            val approved = service.overlay.confirm("Send WhatsApp message to $recipient: \"$preview\"")
            if (!approved) {
                TaskTracker.verified(false, "Ready in WhatsApp. You can edit and tap Send yourself.")
                return "WhatsApp opened with your draft to $recipient. Sending wasn't confirmed, so tell the user to check and tap Send themselves."
            }
        }

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
                    if (!WhatsAppChatVerifier.matchesExpectedRecipient(snap.elements, snap.meta, recipient)) {
                        TaskTracker.verified(false, "Open chat didn't match $recipient; stopped before sending.")
                        return "WhatsApp opened, but the visible chat name did not clearly match $recipient. Ask the user to check the chat before sending."
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
                                return "WhatsApp is open and Send was tapped, though the chat couldn't verify delivery yet. Ask the user to check WhatsApp."
                            }
                        }
                    }
                }
            }
            delay(POLL_MS)
        }

        TaskTracker.verified(false, "Ready in WhatsApp. Tap Send to finish.")
        return "WhatsApp is open with the message to $recipient, but JARVIS couldn't reliably find or verify the Send button. Ask the user to tap Send."
    }

    /** Waits for WhatsApp to open a chat with [recipient] and verifies the header identity + optional message lines. */
    suspend fun verifyChatOpened(
        service: ControlService,
        recipient: String,
    ): String {
        val until = System.currentTimeMillis() + OPEN_WAIT_MS
        while (System.currentTimeMillis() < until) {
            val fg = service.foregroundPackage()
            if (fg in WHATSAPP_PACKAGES) {
                val snap = service.snapshot()
                if (snap != null && snap.packageName in WHATSAPP_PACKAGES) {
                    if (snap.sensitiveAuth) {
                        TaskTracker.verified(false, "WhatsApp asked for lock/authentication.")
                        return "WhatsApp shows an authentication screen. Ask the user to unlock WhatsApp first."
                    }
                    if (WhatsAppChatVerifier.matchesExpectedRecipient(snap.elements, snap.meta, recipient)) {
                        TaskTracker.verified(true, "Opened WhatsApp chat with $recipient.")
                        return "Opened WhatsApp chat with $recipient."
                    }
                }
            }
            delay(POLL_MS)
        }
        return "WhatsApp opened, though the chat for $recipient couldn't be verified yet."
    }

    /**
     * Initiates a WhatsApp Audio or Video call inside an open chat with [recipient], verifying
     * the recipient header, exact Audio vs Video button, and whether the WhatsApp call UI opened.
     */
    suspend fun initiateCallAfterOpen(
        service: ControlService,
        recipient: String,
        kind: WhatsAppCallMatcher.CallKind,
        skipInternalConfirm: Boolean = false,
    ): String {
        val kindLabel = if (kind == WhatsAppCallMatcher.CallKind.Video) "WhatsApp video call" else "WhatsApp call"
        if (!skipInternalConfirm) {
            if (!service.overlay.confirm("Start $kindLabel with $recipient?")) {
                TaskTracker.verified(false, "WhatsApp chat opened; $kindLabel cancelled.")
                return "Opened WhatsApp chat with $recipient. $kindLabel wasn't confirmed, so tell the user they can tap Call themselves."
            }
        }

        val until = System.currentTimeMillis() + OPEN_WAIT_MS
        while (System.currentTimeMillis() < until) {
            val fg = service.foregroundPackage()
            if (fg in WHATSAPP_PACKAGES) {
                val snap = service.snapshot()
                if (snap != null && snap.packageName in WHATSAPP_PACKAGES) {
                    if (snap.sensitiveAuth) {
                        TaskTracker.verified(false, "WhatsApp asked for authentication before calling.")
                        return "WhatsApp shows a lock or authentication screen. Ask the user to unlock WhatsApp first."
                    }
                    if (!WhatsAppChatVerifier.matchesExpectedRecipient(snap.elements, snap.meta, recipient)) {
                        TaskTracker.verified(false, "Chat recipient didn't match $recipient; call aborted.")
                        return "WhatsApp chat didn't clearly match $recipient, so JARVIS stopped before placing the call."
                    }
                    val callBtn = WhatsAppCallMatcher.findCallButtonNumber(snap.elements, snap.meta, kind)
                    val node = callBtn?.let(service::node)
                    if (node != null) {
                        service.overlay.showActive()
                        if (service.click(node)) {
                            Diag.i("whatsapp: tapped $kindLabel button (#$callBtn)")
                            delay(POST_SEND_SETTLE_MS)
                            val postSnap = service.snapshot()
                            // Some WhatsApp versions pop a confirmation sheet ("Start video/voice call?")
                            if (postSnap != null && !WhatsAppCallMatcher.isCallScreenVisible(postSnap.elements, postSnap.meta)) {
                                val confirmCallBtn = postSnap.elements.firstOrNull { el ->
                                    el.role == "button" && el.label.trim().equals("Call", ignoreCase = true)
                                }?.let { service.node(it.number) }
                                if (confirmCallBtn != null) {
                                    service.click(confirmCallBtn)
                                    delay(POST_SEND_SETTLE_MS)
                                }
                            }
                            repeat(VERIFY_TRIES) {
                                val after = service.snapshot()
                                if (after != null && WhatsAppCallMatcher.isCallScreenVisible(after.elements, after.meta)) {
                                    TaskTracker.verified(true, "Opened $kindLabel screen with $recipient.")
                                    return "Started the $kindLabel to $recipient and verified the WhatsApp calling screen is open."
                                }
                                delay(VERIFY_POLL_MS)
                            }
                            TaskTracker.verified(false, "Tapped $kindLabel; check WhatsApp screen.")
                            return "Tapped $kindLabel for $recipient in WhatsApp, though WhatsApp didn't confirm the active calling screen yet."
                        }
                    }
                }
            }
            delay(POLL_MS)
        }

        TaskTracker.verified(false, "Opened chat with $recipient; tap Call in WhatsApp.")
        return "Opened WhatsApp chat with $recipient, but JARVIS couldn't find the exact $kindLabel button. Ask the user to tap it."
    }

    private const val OPEN_WAIT_MS = 8_000L
    private const val POLL_MS = 450L
    private const val POST_SEND_SETTLE_MS = 700L
    private const val VERIFY_TRIES = 6
    private const val VERIFY_POLL_MS = 500L
}
