package app.shilex.android.control

/**
 * Pure Instagram screen & control disambiguation helpers (unit-tested without Android).
 * Distinguishes DM Thread, DM Inbox, Reels, Search, Home Feed, Create, Share Sheet, and Profile;
 * matches DM composers ("Message..."), recipients ("Kavyansh", "Message Yourself"),
 * and provides wrong-screen recovery decisions.
 */
object InstagramMatcher {
    enum class TargetIntent {
        Messages,
        Composer,
        DmSend,
        Search,
        HomeFeed,
        Reels,
        Create,
        Share,
        Profile,
        Notifications,
    }

    enum class InstagramScreenState {
        DirectThread,
        DirectInbox,
        Reels,
        Search,
        Create,
        ShareSheet,
        Profile,
        HomeFeed,
        Unknown,
    }

    sealed interface MatchOutcome {
        data class Matched(val element: ScreenElement, val confidenceNote: String) : MatchOutcome
        data class Ambiguous(val candidates: List<ScreenElement>) : MatchOutcome
        data object NotFound : MatchOutcome
    }

    const val INSTAGRAM_PACKAGE = "com.instagram.android"

    private val MESSAGES_ID = Regex("action_bar_inbox_button|inbox_button|direct_inbox", RegexOption.IGNORE_CASE)
    private val MESSAGES_LABEL = Regex("^(?:direct\\s*messages?|messages?|messaging|inbox|chats?)$|\\b\\d+\\s+unread\\s+messages?\\b", RegexOption.IGNORE_CASE)
    private val SHARE_OR_SEND_EXCLUSION = Regex("\\b(share\\s+post|send\\s+post|send\\s+to|repost|comment|like|save|bookmark|audio|video\\s*call|camera|note)\\b", RegexOption.IGNORE_CASE)

    private val COMPOSER_ID = Regex("row_thread_composer_edittext|thread_composer|message_edit_text|composer_edit_text", RegexOption.IGNORE_CASE)
    private val COMPOSER_LABEL = Regex("^(?:message\\.{0,3}|write\\s+a\\s+message\\.{0,3}|type\\s+a\\s+message\\.{0,3}|send\\s+message)$", RegexOption.IGNORE_CASE)
    private val COMPOSER_EXCLUSION = Regex("search|comment|caption|bio|note", RegexOption.IGNORE_CASE)

    private val DM_SEND_ID = Regex("row_thread_composer_button_send|composer_send_button|direct_send_button|send_button", RegexOption.IGNORE_CASE)

    private val SEARCH_ID = Regex("search_tab|action_bar_search_edit_text|search_edit_text|search_bar", RegexOption.IGNORE_CASE)
    private val SEARCH_LABEL = Regex("^(?:search(?:\\s+and\\s+explore|\\s+with\\s+meta.*)?|explore)$", RegexOption.IGNORE_CASE)

    private val FEED_TAB_ID = Regex("feed_tab", RegexOption.IGNORE_CASE)
    private val REELS_TAB_ID = Regex("clips_tab|reels_tab", RegexOption.IGNORE_CASE)
    private val CREATE_TAB_ID = Regex("creation_tab|camera_tab", RegexOption.IGNORE_CASE)
    private val SHARE_BTN_ID = Regex("button_share|row_feed_button_share|share_button", RegexOption.IGNORE_CASE)
    private val PROFILE_TAB_ID = Regex("profile_tab", RegexOption.IGNORE_CASE)
    private val ACTIVITY_TAB_ID = Regex("news_tab|notification", RegexOption.IGNORE_CASE)

    /** Identifies the current Instagram sub-screen from visible elements and Accessibility metadata. */
    fun detectScreen(
        elements: List<ScreenElement>,
        meta: Map<Int, ControlService.NodeMeta> = emptyMap(),
        screenHeightPx: Int = 0,
    ): InstagramScreenState {
        if (elements.isEmpty()) return InstagramScreenState.Unknown

        val viewIds = elements.map { meta[it.number]?.viewId.orEmpty().lowercase() }
        val labelsLower = elements.map { it.label.trim().lowercase() }

        // 1. DM Thread check (composer box or thread send/header without main feed bottom tabs)
        val hasThreadComposer = elements.any { el ->
            val vid = meta[el.number]?.viewId.orEmpty()
            val lbl = el.label.trim()
            (el.role == "field" || meta[el.number]?.isEditable == true) &&
                !COMPOSER_EXCLUSION.containsMatchIn("$lbl $vid") &&
                (COMPOSER_ID.containsMatchIn(vid) || COMPOSER_LABEL.containsMatchIn(lbl) || COMPOSER_LABEL.containsMatchIn(el.hint))
        }
        val hasThreadHeader = viewIds.any { "thread_title" in it || "row_thread_header" in it || "thread_action_bar" in it }
        if (hasThreadComposer || hasThreadHeader) {
            return InstagramScreenState.DirectThread
        }

        // 2. Share Sheet check
        val hasShareSheet = viewIds.any { "direct_share_sheet" in it || "share_sheet" in it } ||
            (labelsLower.any { it == "send" || it == "send to" || it == "share" } &&
                labelsLower.any { "copy link" in it || "add to story" in it || "whats" in it || "share to" in it })
        if (hasShareSheet) return InstagramScreenState.ShareSheet

        // 3. Create / New post check
        val hasCreate = viewIds.any { "gallery_picker" in it || "cam_dest" in it || "creation_" in it && "tab" !in it } ||
            (labelsLower.any { "new post" in it || "new reel" in it } && labelsLower.any { it == "next" || it == "recents" })
        if (hasCreate) return InstagramScreenState.Create

        // 4. Direct Inbox check
        val hasInboxUi = viewIds.any { "direct_inbox" in it || "inbox_thread" in it } ||
            (labelsLower.any { it == "primary" || it == "general" || it == "requests" || "your note" in it || "new message" in it } &&
                viewIds.none { FEED_TAB_ID.containsMatchIn(it) })
        if (hasInboxUi) return InstagramScreenState.DirectInbox

        // 5. Tab bar selection checks (most reliable when bottom nav is visible)
        val selectedReels = elements.any { el ->
            val vid = meta[el.number]?.viewId.orEmpty()
            (el.isSelected || meta[el.number]?.isSelected == true) &&
                (REELS_TAB_ID.containsMatchIn(vid) || el.label.trim().equals("Reels", ignoreCase = true))
        }
        val hasReelsViewer = viewIds.any { "clips_viewer" in it || "reels_viewer" in it || "clips_video" in it } ||
            (labelsLower.any { it == "reels" } && labelsLower.any { it == "original audio" || "remix" in it || "caption" in it })
        if (selectedReels || hasReelsViewer) return InstagramScreenState.Reels

        val selectedProfile = elements.any { el ->
            val vid = meta[el.number]?.viewId.orEmpty()
            (el.isSelected || meta[el.number]?.isSelected == true) &&
                (PROFILE_TAB_ID.containsMatchIn(vid) || el.label.trim().equals("Profile", ignoreCase = true))
        }
        if (selectedProfile || (labelsLower.any { "edit profile" in it } && labelsLower.any { "share profile" in it })) {
            return InstagramScreenState.Profile
        }

        val selectedSearch = elements.any { el ->
            val vid = meta[el.number]?.viewId.orEmpty()
            (el.isSelected || meta[el.number]?.isSelected == true) &&
                (SEARCH_ID.containsMatchIn(vid) || el.label.trim().equals("Search", ignoreCase = true))
        }
        val hasTopSearchField = elements.any { el ->
            val vid = meta[el.number]?.viewId.orEmpty()
            val y = meta[el.number]?.bounds?.centerY() ?: 0
            (el.role == "field" || meta[el.number]?.isEditable == true) &&
                (SEARCH_ID.containsMatchIn(vid) || SEARCH_LABEL.containsMatchIn(el.label.trim())) &&
                (screenHeightPx <= 0 || y < screenHeightPx * 0.35f)
        }
        if (selectedSearch || hasTopSearchField) return InstagramScreenState.Search

        val hasFeedMarkers = elements.any { el ->
            val vid = meta[el.number]?.viewId.orEmpty()
            (el.isSelected || meta[el.number]?.isSelected == true) && FEED_TAB_ID.containsMatchIn(vid)
        } || viewIds.any { FEED_TAB_ID.containsMatchIn(it) || MESSAGES_ID.containsMatchIn(it) || "stories_recycler" in it }
        if (hasFeedMarkers) return InstagramScreenState.HomeFeed

        return InstagramScreenState.Unknown
    }

    fun resolveControl(
        elements: List<ScreenElement>,
        meta: Map<Int, ControlService.NodeMeta>,
        intent: TargetIntent,
        screenHeightPx: Int = 0,
    ): MatchOutcome {
        val candidates = when (intent) {
            TargetIntent.Composer -> elements.filter { it.role == "field" || meta[it.number]?.isEditable == true }
            else -> elements.filter { it.role == "button" || it.role == "field" }
        }
        val scored = candidates.mapNotNull { el ->
            val nodeMeta = meta[el.number]
            val viewId = nodeMeta?.viewId.orEmpty()
            val lbl = el.label.trim()
            val score = scoreElement(el, nodeMeta, viewId, lbl, intent, screenHeightPx)
            if (score > 0) el to score else null
        }.sortedByDescending { it.second }

        if (scored.isEmpty()) return MatchOutcome.NotFound
        val topScore = scored.first().second
        val topGroup = scored.filter { it.second == topScore }.map { it.first }
        return if (topGroup.size == 1) {
            MatchOutcome.Matched(topGroup.single(), "Matched Instagram ${intent.name} control.")
        } else {
            MatchOutcome.Ambiguous(topGroup)
        }
    }

    /**
     * Matches Instagram DM / Search user rows cleanly, supporting exact handles/names (e.g. "Kavyansh")
     * and self-chat targets ("Message Yourself" / "You"). Never selects an input field.
     */
    fun findRecipientMatch(
        elements: List<ScreenElement>,
        meta: Map<Int, ControlService.NodeMeta>,
        query: String,
    ): MatchOutcome {
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.isEmpty()) return MatchOutcome.NotFound

        val isSelfQuery = cleanQuery == "you" ||
            cleanQuery == "me" ||
            cleanQuery == "self" ||
            cleanQuery == "myself" ||
            "message yourself" in cleanQuery ||
            "note to self" in cleanQuery

        val scored = elements.mapNotNull { el ->
            val nodeMeta = meta[el.number]
            val viewId = nodeMeta?.viewId.orEmpty().lowercase()
            if (el.role == "field" || nodeMeta?.isEditable == true || "search_edit" in viewId) {
                return@mapNotNull null
            }
            val rawLbl = el.label.trim()
            if (rawLbl.isEmpty()) return@mapNotNull null
            val lblLower = rawLbl.lowercase()
            // Ignore top chrome or action buttons
            if (lblLower in setOf("search", "cancel", "back", "messages", "chats", "requests", "notes", "new message")) {
                return@mapNotNull null
            }

            var s = 0
            if (isSelfQuery) {
                when {
                    lblLower == "message yourself" || lblLower == "you" -> s += 100
                    "(you)" in lblLower || "message yourself" in lblLower -> s += 90
                }
            } else {
                val strippedSelf = lblLower.replace("(you)", "").trim()
                val tokens = cleanQuery.split(Regex("\\s+")).filter { it.length >= 2 }
                when {
                    lblLower == cleanQuery || strippedSelf == cleanQuery -> s += 100
                    lblLower.startsWith(cleanQuery) || strippedSelf.startsWith(cleanQuery) -> s += 75
                    cleanQuery in lblLower -> s += 55
                    tokens.isNotEmpty() && tokens.all { it in lblLower } -> s += 45
                }
            }
            if (s > 0 && el.role == "button") s += 5
            if (s > 0 && !el.isEnabled) s -= 20
            if (s > 0) el to s else null
        }.sortedByDescending { it.second }

        if (scored.isEmpty()) return MatchOutcome.NotFound
        val topScore = scored.first().second
        val topGroup = scored.filter { it.second == topScore }.map { it.first }
        return if (topGroup.size == 1) {
            MatchOutcome.Matched(topGroup.single(), "Matched Instagram recipient \"${topGroup.single().label}\".")
        } else {
            MatchOutcome.Ambiguous(topGroup)
        }
    }

    /** Matches natural target queries like "messages", "dm", "composer", "reels", "search" to [TargetIntent]. */
    fun inferIntent(query: String): TargetIntent? {
        val q = query.lowercase().trim()
        return when {
            Regex("\\b(message\\s+box|composer|write\\s+a?\\s*message|message\\.\\.\\.|type\\s+message|dm\\s+box|reply\\s+box)\\b").containsMatchIn(q) -> TargetIntent.Composer
            Regex("\\b(send\\s+message|send\\s+dm|dm\\s+send)\\b").containsMatchIn(q) -> TargetIntent.DmSend
            Regex("\\b(messages?|dm|dms|inbox|chat|chats)\\b").containsMatchIn(q) -> TargetIntent.Messages
            Regex("\\b(search|explore)\\b").containsMatchIn(q) -> TargetIntent.Search
            Regex("\\b(reels?|clips?)\\b").containsMatchIn(q) -> TargetIntent.Reels
            Regex("\\b(create|new\\s+post|upload)\\b").containsMatchIn(q) -> TargetIntent.Create
            Regex("\\b(share\\s+post|share\\s+reel|share)\\b").containsMatchIn(q) -> TargetIntent.Share
            Regex("\\b(profile|account)\\b").containsMatchIn(q) -> TargetIntent.Profile
            Regex("\\b(home|feed)\\b").containsMatchIn(q) -> TargetIntent.HomeFeed
            Regex("\\b(notifications?|activity)\\b").containsMatchIn(q) -> TargetIntent.Notifications
            else -> null
        }
    }

    private fun scoreElement(
        el: ScreenElement,
        nodeMeta: ControlService.NodeMeta?,
        viewId: String,
        label: String,
        intent: TargetIntent,
        screenHeightPx: Int,
    ): Int {
        val centerY = nodeMeta?.bounds?.centerY() ?: 0
        val inBottomHalf = screenHeightPx > 0 && centerY > screenHeightPx * 0.55f
        val inBottomNav = screenHeightPx > 0 && centerY > screenHeightPx * 0.85f
        return when (intent) {
            TargetIntent.Messages -> {
                if (SHARE_OR_SEND_EXCLUSION.containsMatchIn(label)) return 0
                if (el.role != "button") return 0
                var s = 0
                if (MESSAGES_ID.containsMatchIn(viewId)) s += 10
                if (MESSAGES_LABEL.containsMatchIn(label)) s += 8
                if (s > 0 && el.number <= 6 && !inBottomHalf) s += 3
                s
            }
            TargetIntent.Composer -> {
                val hint = el.hint.ifEmpty { nodeMeta?.hint.orEmpty() }
                if (COMPOSER_EXCLUSION.containsMatchIn("$label $hint $viewId")) return 0
                if (el.role != "field" && nodeMeta?.isEditable != true) return 0
                var s = 0
                if (COMPOSER_ID.containsMatchIn(viewId)) s += 12
                if (COMPOSER_LABEL.containsMatchIn(label) || COMPOSER_LABEL.containsMatchIn(hint)) s += 10
                if (s > 0 && inBottomHalf) s += 4
                if (!el.isEnabled) s = 0
                s
            }
            TargetIntent.DmSend -> {
                if (SHARE_OR_SEND_EXCLUSION.containsMatchIn(label)) return 0
                if (el.role != "button" || !el.isEnabled) return 0
                var s = 0
                if (DM_SEND_ID.containsMatchIn(viewId)) s += 12
                if (label.equals("Send", ignoreCase = true) || label.equals("Send message", ignoreCase = true)) s += 9
                if (s > 0 && inBottomHalf) s += 3
                s
            }
            TargetIntent.Search -> {
                var s = 0
                if (SEARCH_ID.containsMatchIn(viewId)) s += 10
                if (SEARCH_LABEL.containsMatchIn(label)) s += 7
                if (el.role == "field" && "search" in label.lowercase()) s += 9
                s
            }
            TargetIntent.HomeFeed -> {
                var s = 0
                if (FEED_TAB_ID.containsMatchIn(viewId)) s += 10
                if (label.equals("Home", ignoreCase = true)) s += 7
                if (s > 0 && inBottomNav) s += 2
                s
            }
            TargetIntent.Reels -> {
                var s = 0
                if (REELS_TAB_ID.containsMatchIn(viewId)) s += 10
                if (label.equals("Reels", ignoreCase = true) && !SHARE_OR_SEND_EXCLUSION.containsMatchIn(label)) s += 7
                if (s > 0 && inBottomNav) s += 2
                s
            }
            TargetIntent.Create -> {
                var s = 0
                if (CREATE_TAB_ID.containsMatchIn(viewId)) s += 10
                if (label.equals("Create", ignoreCase = true) || label.equals("New post", ignoreCase = true)) s += 7
                s
            }
            TargetIntent.Share -> {
                var s = 0
                if (SHARE_BTN_ID.containsMatchIn(viewId)) s += 10
                if (label.equals("Share", ignoreCase = true) || label.equals("Share post", ignoreCase = true) || "share" in label.lowercase()) {
                    if ("profile" !in label.lowercase()) s += 6
                }
                s
            }
            TargetIntent.Profile -> {
                var s = 0
                if (PROFILE_TAB_ID.containsMatchIn(viewId)) s += 10
                if (label.equals("Profile", ignoreCase = true)) s += 7
                if (s > 0 && inBottomNav) s += 2
                s
            }
            TargetIntent.Notifications -> {
                var s = 0
                if (ACTIVITY_TAB_ID.containsMatchIn(viewId)) s += 10
                if (label.equals("Notifications", ignoreCase = true) || label.equals("Activity", ignoreCase = true)) s += 7
                s
            }
        }
    }

    /** Pure geometry selector choosing the main vertical scrollable container (excluding narrow story strips). */
    data class ScrollableBox(
        val id: Int,
        val widthPx: Int,
        val heightPx: Int,
        val viewId: String = "",
    )

    fun pickMainVerticalScrollContainer(boxes: List<ScrollableBox>): Int? {
        if (boxes.isEmpty()) return null
        return boxes
            .filter { it.widthPx > 0 && it.heightPx > 0 }
            .maxByOrNull { box ->
                val isStoryStrip = "reel" in box.viewId.lowercase() ||
                    "story" in box.viewId.lowercase() ||
                    "tray" in box.viewId.lowercase() ||
                    box.heightPx < box.widthPx * 0.6f
                val area = box.widthPx.toLong() * box.heightPx.toLong()
                if (isStoryStrip) area / 8 else area
            }?.id
    }
}

/**
 * Pure wrong-screen detection & step-by-step recovery engine.
 * Detects screen/intent mismatches before unsafe actions run and recommends safe recovery steps.
 */
object ScreenRecovery {
    sealed interface RecoveryDecision {
        data object Proceed : RecoveryDecision
        data class RecoverBackAndRecheck(val reason: String) : RecoveryDecision
        data class NavigateToControl(val elementNumber: Int, val label: String, val reason: String) : RecoveryDecision
        data class AskUser(val reason: String) : RecoveryDecision
    }

    const val MAX_RECOVERY_ATTEMPTS = 2

    fun evaluateInstagramAction(
        intent: InstagramMatcher.TargetIntent,
        elements: List<ScreenElement>,
        meta: Map<Int, ControlService.NodeMeta>,
        screenHeightPx: Int = 0,
        recoveryAttempts: Int = 0,
    ): RecoveryDecision {
        val currentScreen = InstagramMatcher.detectScreen(elements, meta, screenHeightPx)
        val expectedScreens = expectedScreensFor(intent)
        if (currentScreen in expectedScreens || currentScreen == InstagramMatcher.InstagramScreenState.Unknown) {
            return RecoveryDecision.Proceed
        }
        // Check if requested tab/control is directly reachable on the current screen (e.g. Feed -> Messages or Reels)
        val direct = InstagramMatcher.resolveControl(elements, meta, intent, screenHeightPx)
        if (direct is InstagramMatcher.MatchOutcome.Matched) {
            return RecoveryDecision.NavigateToControl(
                elementNumber = direct.element.number,
                label = direct.element.label,
                reason = "Currently on ${currentScreen.name}; tap \"${direct.element.label}\" (#${direct.element.number}) first.",
            )
        }
        return if (recoveryAttempts < MAX_RECOVERY_ATTEMPTS) {
            RecoveryDecision.RecoverBackAndRecheck(
                reason = "Currently on ${currentScreen.name} instead of ${expectedScreens.joinToString("/") { it.name }}. Navigate back and re-read.",
            )
        } else {
            RecoveryDecision.AskUser(
                reason = "Instagram is currently on ${currentScreen.name} rather than ${expectedScreens.first().name}. Ask the user where to go.",
            )
        }
    }

    private fun expectedScreensFor(intent: InstagramMatcher.TargetIntent): Set<InstagramMatcher.InstagramScreenState> = when (intent) {
        InstagramMatcher.TargetIntent.Composer,
        InstagramMatcher.TargetIntent.DmSend -> setOf(InstagramMatcher.InstagramScreenState.DirectThread)
        InstagramMatcher.TargetIntent.Messages -> setOf(
            InstagramMatcher.InstagramScreenState.HomeFeed,
            InstagramMatcher.InstagramScreenState.DirectInbox,
        )
        InstagramMatcher.TargetIntent.Search -> setOf(
            InstagramMatcher.InstagramScreenState.Search,
            InstagramMatcher.InstagramScreenState.HomeFeed,
            InstagramMatcher.InstagramScreenState.DirectInbox,
        )
        InstagramMatcher.TargetIntent.Reels -> setOf(
            InstagramMatcher.InstagramScreenState.Reels,
            InstagramMatcher.InstagramScreenState.HomeFeed,
        )
        InstagramMatcher.TargetIntent.Create -> setOf(
            InstagramMatcher.InstagramScreenState.Create,
            InstagramMatcher.InstagramScreenState.HomeFeed,
        )
        InstagramMatcher.TargetIntent.Share -> setOf(
            InstagramMatcher.InstagramScreenState.ShareSheet,
            InstagramMatcher.InstagramScreenState.HomeFeed,
            InstagramMatcher.InstagramScreenState.Reels,
        )
        InstagramMatcher.TargetIntent.Profile -> setOf(
            InstagramMatcher.InstagramScreenState.Profile,
            InstagramMatcher.InstagramScreenState.HomeFeed,
        )
        InstagramMatcher.TargetIntent.HomeFeed,
        InstagramMatcher.TargetIntent.Notifications -> setOf(InstagramMatcher.InstagramScreenState.HomeFeed)
    }
}
