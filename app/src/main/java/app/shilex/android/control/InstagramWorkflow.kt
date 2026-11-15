package app.shilex.android.control

/**
 * Pure Instagram screen & control disambiguation helpers (unit-tested without Android).
 * Distinguishes DM / Messages Inbox cleanly from Share/Post, Comments, Reels, Search, and Video Call,
 * selects the proper scrollable feed/list container, and flags ambiguous candidates cleanly.
 */
object InstagramMatcher {
    enum class TargetIntent {
        Messages,
        Search,
        HomeFeed,
        Reels,
        Profile,
        Notifications,
    }

    sealed interface MatchOutcome {
        data class Matched(val element: ScreenElement, val confidenceNote: String) : MatchOutcome
        data class Ambiguous(val candidates: List<ScreenElement>) : MatchOutcome
        data object NotFound : MatchOutcome
    }

    const val INSTAGRAM_PACKAGE = "com.instagram.android"

    private val MESSAGES_ID = Regex("action_bar_inbox_button|inbox_button|direct_inbox", RegexOption.IGNORE_CASE)
    private val MESSAGES_LABEL = Regex("^(?:direct\\s*messages?|messages?|messaging|inbox|chats?)$|\\b\\d+\\s+unread\\s+messages?\\b", RegexOption.IGNORE_CASE)
    private val SHARE_OR_SEND_EXCLUSION = Regex("\\b(share|send\\s+post|send\\s+to|repost|story|comment|like|save|bookmark|audio|video\\s*call|camera|note)\\b", RegexOption.IGNORE_CASE)

    private val SEARCH_ID = Regex("search_tab|action_bar_search_edit_text|search_edit_text", RegexOption.IGNORE_CASE)
    private val SEARCH_LABEL = Regex("^(?:search|search and explore|explore)$", RegexOption.IGNORE_CASE)

    private val FEED_TAB_ID = Regex("feed_tab", RegexOption.IGNORE_CASE)
    private val REELS_TAB_ID = Regex("clips_tab|reels_tab", RegexOption.IGNORE_CASE)
    private val PROFILE_TAB_ID = Regex("profile_tab", RegexOption.IGNORE_CASE)
    private val ACTIVITY_TAB_ID = Regex("news_tab|notification", RegexOption.IGNORE_CASE)

    fun resolveControl(
        elements: List<ScreenElement>,
        meta: Map<Int, ControlService.NodeMeta>,
        intent: TargetIntent,
    ): MatchOutcome {
        val interactive = elements.filter { it.role == "button" || it.role == "field" }
        val scored = interactive.mapNotNull { el ->
            val viewId = meta[el.number]?.viewId.orEmpty()
            val lbl = el.label.trim()
            val score = scoreElement(el, viewId, lbl, intent)
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

    /** Matches natural target queries like "messages", "dm", "reels", "search" to an [InstagramMatcher.TargetIntent]. */
    fun inferIntent(query: String): TargetIntent? {
        val q = query.lowercase().trim()
        return when {
            Regex("\\b(messages?|dm|dms|inbox|chat|chats)\\b").containsMatchIn(q) -> TargetIntent.Messages
            Regex("\\b(search|explore)\\b").containsMatchIn(q) -> TargetIntent.Search
            Regex("\\b(reels?|clips?)\\b").containsMatchIn(q) -> TargetIntent.Reels
            Regex("\\b(profile|account)\\b").containsMatchIn(q) -> TargetIntent.Profile
            Regex("\\b(home|feed)\\b").containsMatchIn(q) -> TargetIntent.HomeFeed
            Regex("\\b(notifications?|activity)\\b").containsMatchIn(q) -> TargetIntent.Notifications
            else -> null
        }
    }

    private fun scoreElement(
        el: ScreenElement,
        viewId: String,
        label: String,
        intent: TargetIntent,
    ): Int {
        return when (intent) {
            TargetIntent.Messages -> {
                if (SHARE_OR_SEND_EXCLUSION.containsMatchIn(label)) return 0
                if (el.role != "button") return 0
                var s = 0
                if (MESSAGES_ID.containsMatchIn(viewId)) s += 10
                if (MESSAGES_LABEL.containsMatchIn(label)) s += 8
                // Top action bar items on Instagram appear in top positions (numbers 1..6)
                if (s > 0 && el.number <= 6) s += 3
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
                s
            }
            TargetIntent.Reels -> {
                var s = 0
                if (REELS_TAB_ID.containsMatchIn(viewId)) s += 10
                if (label.equals("Reels", ignoreCase = true)) s += 7
                s
            }
            TargetIntent.Profile -> {
                var s = 0
                if (PROFILE_TAB_ID.containsMatchIn(viewId)) s += 10
                if (label.equals("Profile", ignoreCase = true)) s += 7
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
