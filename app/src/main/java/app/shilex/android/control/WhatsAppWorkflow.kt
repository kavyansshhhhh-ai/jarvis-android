package app.shilex.android.control

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import app.shilex.android.Diag
import app.shilex.android.task.ActiveTaskContext
import app.shilex.android.task.TaskTracker
import app.shilex.android.tools.SendCheck
import kotlinx.coroutines.delay

/**
 * Pure WhatsApp composer/Send matching & safety rules (unit-tested without Android).
 * Distinguishes the real Send button from voice-note/mic/camera controls, locates the composer
 * field cleanly, and confirms the chat draft matches the intended message text before tapping Send.
 */
object WhatsAppComposerMatcher {
    private val VOICE_OR_MEDIA_BUTTON = Regex(
        "voice|audio|mic|record|camera|attach|emoji|sticker|video\\s*note",
        RegexOption.IGNORE_CASE,
    )
    private val SEND_ID = Regex("(^send$|send_btn|send_button)", RegexOption.IGNORE_CASE)
    private val SEND_LABEL = Regex("^send(\\s+message)?$", RegexOption.IGNORE_CASE)
    private val COMPOSER_ID = Regex("(entry|composer|input|message_text)", RegexOption.IGNORE_CASE)
    private val COMPOSER_LABEL = Regex("^(message|type a message|chat message)$", RegexOption.IGNORE_CASE)

    fun normalize(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

    /** True if the visible chat composer box holds the intended [expectedMessage]. */
    fun hasMatchingDraft(elements: List<ScreenElement>, expectedMessage: String): Boolean {
        val wanted = normalize(expectedMessage).take(40)
        if (wanted.isEmpty()) return false
        return elements.any { el ->
            el.role == "field" && wanted in normalize(el.value)
        }
    }

    /** Finds the WhatsApp chat message composer field number (never mistaking Search fields). */
    fun findComposerNumber(elements: List<ScreenElement>, meta: Map<Int, ControlService.NodeMeta>): Int? {
        val fields = elements.filter { it.role == "field" && it.isEnabled }
        if (fields.isEmpty()) return null
        val byId = fields.filter { el ->
            val vid = meta[el.number]?.viewId.orEmpty()
            "search" !in vid.lowercase() && COMPOSER_ID.containsMatchIn(vid)
        }
        if (byId.isNotEmpty()) return byId.last().number
        val byLabel = fields.filter { el ->
            val lbl = "${el.label} ${el.hint}".trim()
            "search" !in lbl.lowercase() && COMPOSER_LABEL.containsMatchIn(lbl)
        }
        if (byLabel.isNotEmpty()) return byLabel.last().number
        val nonSearch = fields.filterNot {
            val combined = "${it.label} ${it.hint} ${meta[it.number]?.viewId.orEmpty()}".lowercase()
            "search" in combined
        }
        return nonSearch.lastOrNull()?.number
    }

    /** Finds the unambiguous Send button number from a screen snapshot + metadata; null if none/uncertain. */
    fun findSendButtonNumber(elements: List<ScreenElement>, meta: Map<Int, ControlService.NodeMeta>): Int? {
        val candidates = elements.filter { el ->
            if (el.role != "button" || !el.isEnabled) return@filter false
            val m = meta[el.number]
            val viewId = m?.viewId.orEmpty()
            val lbl = el.label.trim()
            val combined = "$lbl $viewId"
            if (VOICE_OR_MEDIA_BUTTON.containsMatchIn(combined)) return@filter false
            SEND_ID.containsMatchIn(viewId) || SEND_LABEL.matches(lbl)
        }
        if (candidates.size == 1) return candidates.single().number
        val explicitId = candidates.filter { SEND_ID.containsMatchIn(meta[it.number]?.viewId.orEmpty()) }
        return explicitId.singleOrNull()?.number
    }
}

/**
 * Pure WhatsApp conversation header & active-chat verifier (JVM-tested).
 * Prevents sending/calling/deleting in the wrong recipient chat or on the Chats list screen.
 */
object WhatsAppChatVerifier {
    private val NON_RECIPIENT_HEADER_TEXT = Regex(
        "^(?:whatsapp|whatsapp business|search|chats|updates|communities|calls|back|navigate up|more options|video call|voice call|call|audio call|today|yesterday|online|typing\\.{0,3}|last seen.*|tap here for contact info)$",
        RegexOption.IGNORE_CASE,
    )

    fun normalizeTokens(text: String): List<String> = text
        .lowercase()
        .replace(Regex("[^a-z0-9\\u0900-\\u097F\\s]"), " ")
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }

    /** True when the current screen looks like an open individual/group chat rather than the Chats list. */
    fun isChatScreenOpen(elements: List<ScreenElement>, meta: Map<Int, ControlService.NodeMeta>): Boolean {
        if (WhatsAppComposerMatcher.findComposerNumber(elements, meta) != null) return true
        return elements.any { el ->
            val vid = meta[el.number]?.viewId.orEmpty().lowercase()
            "conversation_contact_name" in vid || "conversation_toolbar" in vid
        }
    }

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
                "action_bar_title" in viewId
            val isEarlyTopRow = el.number <= 8 && el.role in setOf("text", "button")
            isExplicitHeaderId || isEarlyTopRow
        }.map { it.label.trim() }
        return topTitle.distinct()
    }

    /**
     * Checks whether the open WhatsApp chat header clearly matches [expectedRecipient]
     * (by contact name tokens OR by trailing phone digits if WhatsApp renders the number).
     */
    fun matchesExpectedRecipient(
        elements: List<ScreenElement>,
        meta: Map<Int, ControlService.NodeMeta>,
        expectedRecipient: String,
        expectedPhoneDigits: String = "",
    ): Boolean {
        if (!isChatScreenOpen(elements, meta)) return false
        val wantedTokens = normalizeTokens(expectedRecipient)
        val cleanPhoneTail = expectedPhoneDigits.filter(Char::isDigit).takeLast(10)
        if (wantedTokens.isEmpty() && cleanPhoneTail.isEmpty()) return true
        val wantedJoined = wantedTokens.joinToString("")
        val headerNamed = elements.filter { el ->
            val viewId = meta[el.number]?.viewId.orEmpty()
            "conversation_contact_name" in viewId || "contact_name" in viewId || "action_bar_title" in viewId
        }.map { it.label.trim() }.filter { it.isNotEmpty() }

        val pool = headerNamed.ifEmpty {
            extractHeaderNames(elements, meta).take(3)
        }
        if (pool.isEmpty()) return false

        return pool.any { candidate ->
            val candDigits = candidate.filter(Char::isDigit)
            if (cleanPhoneTail.length >= 6 && candDigits.endsWith(cleanPhoneTail)) {
                return@any true
            }
            val candTokens = normalizeTokens(candidate)
            val candJoined = candTokens.joinToString("")
            if (candJoined.isEmpty()) return@any false
            (wantedJoined.isNotEmpty() && candJoined == wantedJoined) ||
                (wantedTokens.isNotEmpty() && wantedTokens.all { wt -> candTokens.any { it.startsWith(wt) || wt.startsWith(it) } })
        }
    }
}

/**
 * Pure WhatsApp Audio vs Video call button matcher (JVM-tested).
 * Ensures audio call commands never tap Video call, prefers the top toolbar button even if
 * missed-call cards appear inside the chat history, and never mistakes Mic/Voice-note for Call.
 */
object WhatsAppCallMatcher {
    enum class CallKind { Audio, Video }

    private val VIDEO_RE = Regex("\\b(video|vc)\\b|video_call|videocall|menuitem_video", RegexOption.IGNORE_CASE)
    private val AUDIO_RE = Regex("\\b(voice\\s*call|audio\\s*call|call)\\b|voice_call|audio_call|menu_call|menuitem_call", RegexOption.IGNORE_CASE)
    private val NON_CALL_RE = Regex("attach|camera|mic|record|voice\\s*note|message|status|back|missed", RegexOption.IGNORE_CASE)
    private val EXPLICIT_CALL_ID_RE = Regex("(voice_call|audio_call|video_call|videocall|menu_call|menuitem_call|menuitem_video)", RegexOption.IGNORE_CASE)

    fun findCallButtonNumber(
        elements: List<ScreenElement>,
        meta: Map<Int, ControlService.NodeMeta>,
        kind: CallKind,
    ): Int? {
        val candidates = elements.filter { el ->
            if (el.role != "button" || !el.isEnabled) return@filter false
            val lbl = el.label.trim()
            val viewId = meta[el.number]?.viewId.orEmpty()
            val combined = "$lbl $viewId"
            if (NON_CALL_RE.containsMatchIn(combined)) return@filter false
            when (kind) {
                CallKind.Video -> VIDEO_RE.containsMatchIn(combined)
                CallKind.Audio -> AUDIO_RE.containsMatchIn(combined) && !VIDEO_RE.containsMatchIn(combined)
            }
        }
        if (candidates.size <= 1) return candidates.firstOrNull()?.number
        val explicitId = candidates.filter { EXPLICIT_CALL_ID_RE.containsMatchIn(meta[it.number]?.viewId.orEmpty()) }
        if (explicitId.size == 1) return explicitId.single().number
        val toolbarOnly = candidates.filter { el ->
            val top = meta[el.number]?.bounds?.top ?: 0
            el.number <= 8 || (top in 1..280)
        }
        return toolbarOnly.singleOrNull()?.number ?: explicitId.firstOrNull()?.number
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
                ("mute" in viewId && elements.any { "speaker" in it.label.lowercase() })
        }
}

/**
 * Pure WhatsApp message deletion matcher & confirmation rules (JVM-tested).
 * Distinguishes "Delete for me" (default) from "Delete for everyone" (only when explicitly requested
 * AND offered by WhatsApp), filters out non-message chrome/headers/composer, and verifies removal.
 */
object WhatsAppDeleteMatcher {
    enum class DeleteScope { ForMe, ForEveryone }

    sealed interface TargetResolution {
        data class Found(val element: ScreenElement) : TargetResolution
        data class Ambiguous(val candidates: List<ScreenElement>) : TargetResolution
        data object NotFound : TargetResolution
    }

    enum class DialogAction {
        TapDeleteForMe,
        TapDeleteForEveryone,
        TapConfirmDelete,
        ReportEveryoneUnavailable,
        None,
    }

    data class DialogAnalysis(
        val isDeleteDialog: Boolean,
        val deleteForMeElement: ScreenElement? = null,
        val deleteForEveryoneElement: ScreenElement? = null,
        val confirmDeleteElement: ScreenElement? = null,
        val cancelElement: ScreenElement? = null,
    )

    private val NON_MESSAGE_LABELS = Regex(
        "^(?:today|yesterday|end-to-end encrypted|messages and calls are end-to-end encrypted.*|you deleted this message|this message was deleted|unread messages?|missed voice call|missed video call|navigate up|more options|voice call|video call|attach|camera|emoji|send|send voice message|delete|cancel)$",
        RegexOption.IGNORE_CASE,
    )
    private val NON_MESSAGE_IDS = Regex(
        "(conversation_contact_name|contact_name|toolbar|action_bar|entry|composer|send|voice_note|attach|camera|emoji|date_divider|unread|encryption)",
        RegexOption.IGNORE_CASE,
    )
    private val DELETE_FOR_ME_RE = Regex("""\bdelete\s+for\s+me\b|मेरे\s+लिए\s+डिलीट""", RegexOption.IGNORE_CASE)
    private val DELETE_FOR_EVERYONE_RE = Regex("""\bdelete\s+for\s+everyone\b|सभी\s+के\s+लिए\s+डिलीट""", RegexOption.IGNORE_CASE)
    private val DIALOG_TITLE_RE = Regex("""\bdelete\s+(?:\d+\s+)?messages?\??""", RegexOption.IGNORE_CASE)
    private val TOOLBAR_DELETE_ID = Regex("""(menu_delete|delete_btn|action_delete)""", RegexOption.IGNORE_CASE)

    fun parseScope(spokenOrParam: String): DeleteScope {
        val text = spokenOrParam.lowercase()
        return if ("everyone" in text || "all" in text || "sabke" in text || "dono" in text || "both" in text || "har kisi" in text) {
            DeleteScope.ForEveryone
        } else {
            DeleteScope.ForMe
        }
    }

    /** Extracts visible chat message elements, excluding headers, composer fields, toolbars, and banners. */
    fun messageCandidates(elements: List<ScreenElement>, meta: Map<Int, ControlService.NodeMeta>): List<ScreenElement> {
        val headerSet = WhatsAppChatVerifier.extractHeaderNames(elements, meta).map { it.lowercase() }.toSet()
        return elements.filter { el ->
            if (el.role == "field") return@filter false
            val lbl = el.label.trim()
            if (lbl.isEmpty()) return@filter false
            if (NON_MESSAGE_LABELS.matches(lbl)) return@filter false
            val vid = meta[el.number]?.viewId.orEmpty()
            if (NON_MESSAGE_IDS.containsMatchIn(vid)) return@filter false
            if (el.number <= 4 && lbl.lowercase() in headerSet) return@filter false
            el.role == "text" || el.role == "button"
        }
    }

    /** Resolves which message bubble to long-press for deletion. */
    fun findTargetMessage(
        elements: List<ScreenElement>,
        meta: Map<Int, ControlService.NodeMeta>,
        which: String = "last",
        messageText: String = "",
    ): TargetResolution {
        val candidates = messageCandidates(elements, meta)
        if (candidates.isEmpty()) return TargetResolution.NotFound
        val query = messageText.trim().lowercase()
        if (query.isNotEmpty()) {
            val matching = candidates.filter { query in it.label.lowercase() }
            if (matching.isEmpty()) return TargetResolution.NotFound
            if (matching.size == 1) return TargetResolution.Found(matching.single())
            return if (which.lowercase() in setOf("last", "latest", "recent", "aakhri")) {
                TargetResolution.Found(matching.last())
            } else {
                TargetResolution.Ambiguous(matching.takeLast(5))
            }
        }
        return TargetResolution.Found(candidates.last())
    }

    /** Finds the contextual action-bar Delete icon button shown after long-pressing a message. */
    fun findToolbarDeleteButton(elements: List<ScreenElement>, meta: Map<Int, ControlService.NodeMeta>): ScreenElement? {
        return elements.firstOrNull { el ->
            if (el.role != "button" || !el.isEnabled) return@firstOrNull false
            val lbl = el.label.trim()
            val vid = meta[el.number]?.viewId.orEmpty()
            if (DELETE_FOR_ME_RE.containsMatchIn(lbl) || DELETE_FOR_EVERYONE_RE.containsMatchIn(lbl)) {
                return@firstOrNull false
            }
            lbl.equals("Delete", ignoreCase = true) ||
                lbl.equals("Delete message", ignoreCase = true) ||
                TOOLBAR_DELETE_ID.containsMatchIn(vid)
        }
    }

    /** Inspects WhatsApp's delete confirmation modal dialog. */
    fun analyzeDeleteDialog(elements: List<ScreenElement>): DialogAnalysis {
        val forEveryone = elements.firstOrNull { el ->
            el.isEnabled && DELETE_FOR_EVERYONE_RE.containsMatchIn(el.label.trim())
        }
        val forMe = elements.firstOrNull { el ->
            el.isEnabled && DELETE_FOR_ME_RE.containsMatchIn(el.label.trim())
        }
        val confirmDelete = elements.firstOrNull { el ->
            el.role == "button" && el.isEnabled && el.label.trim().equals("Delete", ignoreCase = true)
        }
        val cancelBtn = elements.firstOrNull { el ->
            el.role == "button" && el.isEnabled && el.label.trim().equals("Cancel", ignoreCase = true)
        }
        val hasTitle = elements.any { DIALOG_TITLE_RE.containsMatchIn(it.label.trim()) }
        val isDialog = forEveryone != null || forMe != null || (hasTitle && confirmDelete != null)
        return DialogAnalysis(
            isDeleteDialog = isDialog,
            deleteForMeElement = forMe,
            deleteForEveryoneElement = forEveryone,
            confirmDeleteElement = confirmDelete,
            cancelElement = cancelBtn,
        )
    }

    /**
     * Resolves the exact confirmation action respecting scope safety:
     * - [DeleteScope.ForMe] NEVER chooses "Delete for everyone".
     * - [DeleteScope.ForEveryone] NEVER silently downgrades to "Delete for me" when unavailable.
     */
    fun resolveDialogAction(analysis: DialogAnalysis, requestedScope: DeleteScope): DialogAction {
        if (!analysis.isDeleteDialog) return DialogAction.None
        return when (requestedScope) {
            DeleteScope.ForEveryone -> when {
                analysis.deleteForEveryoneElement != null -> DialogAction.TapDeleteForEveryone
                analysis.deleteForMeElement != null -> DialogAction.ReportEveryoneUnavailable
                analysis.confirmDeleteElement != null -> DialogAction.TapConfirmDelete
                else -> DialogAction.None
            }
            DeleteScope.ForMe -> when {
                analysis.deleteForMeElement != null -> DialogAction.TapDeleteForMe
                analysis.confirmDeleteElement != null -> DialogAction.TapConfirmDelete
                else -> DialogAction.None
            }
        }
    }

    /** Checks post-action chat elements to confirm the target message disappeared or became deleted placeholder. */
    fun verifyMessageDeleted(
        beforeElements: List<ScreenElement>,
        afterElements: List<ScreenElement>,
        targetLabel: String,
    ): Boolean {
        val key = targetLabel.trim().lowercase()
        if (key.isEmpty()) return false
        val beforeCount = beforeElements.count { it.label.trim().lowercase() == key }
        val afterCount = afterElements.count { it.label.trim().lowercase() == key }
        if (afterCount < beforeCount) return true
        val beforeDeletedMarkers = beforeElements.count {
            "you deleted this message" in it.label.lowercase() || "this message was deleted" in it.label.lowercase()
        }
        val afterDeletedMarkers = afterElements.count {
            "you deleted this message" in it.label.lowercase() || "this message was deleted" in it.label.lowercase()
        }
        return afterDeletedMarkers > beforeDeletedMarkers
    }
}

/**
 * Verifies WhatsApp package availability, draft/typing, conversation identity, calling buttons,
 * and safe message deletion via Accessibility, reporting outcomes honestly.
 */
object WhatsAppWorkflow {
    val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

    /** Returns the installed WhatsApp package name ("com.whatsapp" or "com.whatsapp.w4b"), or null if neither exists. */
    fun resolveInstalledPackage(context: Context): String? {
        val pm = context.packageManager
        return WHATSAPP_PACKAGES.firstOrNull { pkg ->
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0)
                }
                true
            }.getOrDefault(false)
        }
    }

    /**
     * Completes sending after WhatsApp opens to the chat draft.
     * Types into the composer if the deep link didn't pre-fill it, asks at most ONE Allow card
     * if [skipInternalConfirm] is false, taps Send once, and verifies chat delivery.
     */
    suspend fun confirmAndSendAfterOpen(
        service: ControlService,
        recipient: String,
        message: String,
        recipientPhoneDigits: String = "",
        skipInternalConfirm: Boolean = false,
    ): String {
        var internalConfirmed = skipInternalConfirm
        var attemptedType = false
        var tappedSend = false
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
                    if (!WhatsAppChatVerifier.isChatScreenOpen(snap.elements, snap.meta)) {
                        delay(POLL_MS)
                        continue
                    }
                    if (!WhatsAppChatVerifier.matchesExpectedRecipient(snap.elements, snap.meta, recipient, recipientPhoneDigits)) {
                        TaskTracker.verified(false, "Open chat didn't match $recipient; stopped before typing/sending.")
                        return "WhatsApp opened, but the visible chat name did not clearly match $recipient. Ask the user to check the chat before sending."
                    }

                    // If deep link opened the chat without filling the draft, type into composer explicitly once.
                    if (!WhatsAppComposerMatcher.hasMatchingDraft(snap.elements, message)) {
                        if (!attemptedType) {
                            attemptedType = true
                            val composerNum = WhatsAppComposerMatcher.findComposerNumber(snap.elements, snap.meta)
                            val composerNode = composerNum?.let(service::node)
                            if (composerNode != null && service.type(composerNode, message, submitIme = false)) {
                                delay(350L)
                                continue
                            }
                        }
                        delay(POLL_MS)
                        continue
                    }

                    if (!internalConfirmed) {
                        val preview = ScreenText.shorten(message)
                        val approved = service.overlay.confirm("Send WhatsApp message to $recipient: \"$preview\"")
                        if (!approved) {
                            TaskTracker.verified(false, "Ready in WhatsApp. You can edit and tap Send yourself.")
                            return "WhatsApp opened with your draft to $recipient. Sending wasn't confirmed, so tell the user to check and tap Send themselves."
                        }
                        internalConfirmed = true
                    }

                    if (!tappedSend) {
                        val sendNum = WhatsAppComposerMatcher.findSendButtonNumber(snap.elements, snap.meta)
                        val sendNode = sendNum?.let(service::node)
                        if (sendNode != null) {
                            service.overlay.showActive()
                            tappedSend = true
                            if (service.click(sendNode)) {
                                Diag.i("whatsapp: tapped Send (#$sendNum)")
                                delay(POST_SEND_SETTLE_MS)
                                repeat(VERIFY_TRIES) {
                                    val after = service.snapshot()
                                    if (after != null && SendCheck.sent(after.elements, message)) {
                                        ActiveTaskContext.advanceStep(
                                            completedStep = "Sent message to $recipient",
                                            verifiedResult = "Verified in chat",
                                        )
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
        return "WhatsApp is open for $recipient, though JARVIS couldn't reliably find or tap the Send button. Ask the user to check and tap Send."
    }

    /** Waits for WhatsApp to open a chat with [recipient] and verifies the header identity. */
    suspend fun verifyChatOpened(
        service: ControlService,
        recipient: String,
        recipientPhoneDigits: String = "",
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
                    if (WhatsAppChatVerifier.isChatScreenOpen(snap.elements, snap.meta) &&
                        WhatsAppChatVerifier.matchesExpectedRecipient(snap.elements, snap.meta, recipient, recipientPhoneDigits)
                    ) {
                        ActiveTaskContext.updateScreen("WhatsApp", snap.packageName, "chat:$recipient")
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
     * the recipient header, exact Audio vs Video toolbar button, and whether the WhatsApp call UI opened.
     */
    suspend fun initiateCallAfterOpen(
        service: ControlService,
        recipient: String,
        kind: WhatsAppCallMatcher.CallKind,
        recipientPhoneDigits: String = "",
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
                    if (!WhatsAppChatVerifier.isChatScreenOpen(snap.elements, snap.meta)) {
                        delay(POLL_MS)
                        continue
                    }
                    if (!WhatsAppChatVerifier.matchesExpectedRecipient(snap.elements, snap.meta, recipient, recipientPhoneDigits)) {
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
                                    ActiveTaskContext.complete("Started $kindLabel with $recipient.")
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

    /**
     * Safely deletes a targeted message inside an open WhatsApp chat:
     * 1. Verifies WhatsApp chat screen & recipient header (if [recipient] provided).
     * 2. Finds the exact message bubble (never headers, composer, or unrelated chats).
     * 3. Asks confirmation ONCE if [alreadyConfirmed] is false.
     * 4. Long-clicks the message -> taps toolbar Delete -> inspects WhatsApp's delete dialog.
     * 5. Selects "Delete for me" by default, or "Delete for everyone" ONLY when requested & available.
     * 6. Bounded attempts — never loops or re-triggers repeated Allow popups.
     */
    suspend fun deleteMessage(
        service: ControlService,
        recipient: String,
        recipientPhoneDigits: String = "",
        which: String = "last",
        messageText: String = "",
        scope: WhatsAppDeleteMatcher.DeleteScope = WhatsAppDeleteMatcher.DeleteScope.ForMe,
        alreadyConfirmed: Boolean = false,
    ): String {
        val until = System.currentTimeMillis() + OPEN_WAIT_MS
        var chatSnap: ControlService.Snapshot? = null
        while (System.currentTimeMillis() < until) {
            val fg = service.foregroundPackage()
            if (fg in WHATSAPP_PACKAGES) {
                val snap = service.snapshot()
                if (snap != null && snap.packageName in WHATSAPP_PACKAGES) {
                    if (snap.sensitiveAuth) {
                        return "WhatsApp shows a lock or authentication screen. Ask the user to unlock WhatsApp first."
                    }
                    if (WhatsAppChatVerifier.isChatScreenOpen(snap.elements, snap.meta)) {
                        chatSnap = snap
                        break
                    }
                }
            }
            delay(POLL_MS)
        }
        val snap = chatSnap ?: return "WhatsApp chat isn't open right now. Open the conversation first and try again."

        if (recipient.isNotBlank() &&
            !WhatsAppChatVerifier.matchesExpectedRecipient(snap.elements, snap.meta, recipient, recipientPhoneDigits)
        ) {
            TaskTracker.verified(false, "Open chat didn't match $recipient; stopped before deleting.")
            return "The open WhatsApp chat doesn't match $recipient, so JARVIS stopped without deleting anything."
        }

        val target = when (val res = WhatsAppDeleteMatcher.findTargetMessage(snap.elements, snap.meta, which, messageText)) {
            is WhatsAppDeleteMatcher.TargetResolution.Found -> res.element
            is WhatsAppDeleteMatcher.TargetResolution.Ambiguous -> {
                val opts = res.candidates.joinToString("; ") { "\"${ScreenText.shorten(it.label)}\"" }
                return "Multiple messages match ($opts). Ask the user which exact message to delete."
            }
            WhatsAppDeleteMatcher.TargetResolution.NotFound -> {
                return if (messageText.isNotBlank()) {
                    "Couldn't find a visible message matching \"$messageText\" in this WhatsApp chat."
                } else {
                    "Couldn't identify the message to delete on this WhatsApp screen."
                }
            }
        }

        val scopeLabel = if (scope == WhatsAppDeleteMatcher.DeleteScope.ForEveryone) {
            "Delete for everyone"
        } else {
            "Delete for me"
        }
        if (!alreadyConfirmed) {
            val snippet = ScreenText.shorten(target.label)
            if (!service.overlay.confirm("$scopeLabel: \"$snippet\"?")) {
                TaskTracker.verified(false, "Message deletion cancelled.")
                return "Message deletion was cancelled; nothing was deleted."
            }
        }

        val targetNode = service.node(target.number)
            ?: return "That message is no longer visible on screen. Scroll to it and try again."

        service.overlay.showActive()
        if (!service.longClick(targetNode)) {
            return "Couldn't select that message in WhatsApp. Ask the user to long-press it directly."
        }
        delay(DELETE_UI_STEP_MS)

        // Step 2: Find and tap Delete icon in WhatsApp contextual action bar (max 2 tries)
        var tappedToolbarDelete = false
        repeat(2) {
            val barSnap = service.snapshot()
            val deleteBtn = barSnap?.let { WhatsAppDeleteMatcher.findToolbarDeleteButton(it.elements, it.meta) }
            val deleteNode = deleteBtn?.let { service.node(it.number) }
            if (deleteNode != null && service.click(deleteNode)) {
                tappedToolbarDelete = true
                return@repeat
            }
            delay(350L)
        }
        if (!tappedToolbarDelete) {
            return "Selected the message, but couldn't find WhatsApp's Delete button in the top bar. Ask the user to tap Delete."
        }
        delay(DELETE_UI_STEP_MS)

        // Step 3: Inspect WhatsApp's delete confirmation dialog and choose ONLY the requested valid option
        val dialogSnap = service.snapshot()
            ?: return "WhatsApp opened a confirmation step, though JARVIS couldn't read it. Ask the user to confirm directly."
        val analysis = WhatsAppDeleteMatcher.analyzeDeleteDialog(dialogSnap.elements)
        val action = WhatsAppDeleteMatcher.resolveDialogAction(analysis, scope)

        when (action) {
            WhatsAppDeleteMatcher.DialogAction.ReportEveryoneUnavailable -> {
                analysis.cancelElement?.let { service.node(it.number)?.let(service::click) }
                TaskTracker.verified(false, "Delete for everyone isn't available for this message.")
                return "WhatsApp does not permit \"Delete for everyone\" on this message (the time limit may have passed or it isn't your sent message). Ask if the user wants \"Delete for me\" instead."
            }
            WhatsAppDeleteMatcher.DialogAction.TapDeleteForEveryone,
            WhatsAppDeleteMatcher.DialogAction.TapDeleteForMe,
            WhatsAppDeleteMatcher.DialogAction.TapConfirmDelete -> {
                val btn = when (action) {
                    WhatsAppDeleteMatcher.DialogAction.TapDeleteForEveryone -> analysis.deleteForEveryoneElement
                    WhatsAppDeleteMatcher.DialogAction.TapDeleteForMe -> analysis.deleteForMeElement
                    WhatsAppDeleteMatcher.DialogAction.TapConfirmDelete -> analysis.confirmDeleteElement
                    else -> null
                }
                val btnNode = btn?.let { service.node(it.number) }
                if (btnNode == null || !service.click(btnNode)) {
                    return "WhatsApp showed the delete confirmation dialog, but tapping \"$scopeLabel\" didn't go through. Ask the user to tap it."
                }
                delay(POST_SEND_SETTLE_MS)
                repeat(4) {
                    val after = service.snapshot()
                    if (after != null && WhatsAppDeleteMatcher.verifyMessageDeleted(snap.elements, after.elements, target.label)) {
                        ActiveTaskContext.advanceStep("Deleted message ($scopeLabel)", verifiedResult = "Verified deleted")
                        TaskTracker.verified(true, "Message deleted ($scopeLabel).")
                        return "Deleted the WhatsApp message (\"$scopeLabel\") and verified it was removed from the chat."
                    }
                    delay(VERIFY_POLL_MS)
                }
                return "Tapped \"$scopeLabel\" in WhatsApp, though WhatsApp didn't confirm removal cleanly. Ask the user to check the chat."
            }
            WhatsAppDeleteMatcher.DialogAction.None -> {
                return "WhatsApp didn't show a standard delete confirmation dialog. Ask the user to delete the message directly."
            }
        }
    }

    private const val OPEN_WAIT_MS = 8_000L
    private const val POLL_MS = 450L
    private const val DELETE_UI_STEP_MS = 550L
    private const val POST_SEND_SETTLE_MS = 700L
    private const val VERIFY_TRIES = 6
    private const val VERIFY_POLL_MS = 500L
}

