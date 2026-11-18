package app.shilex.android.assistant

enum class AppDestination {
    MESSAGES,
    REELS,
    PROFILE,
    HOME,
    SEARCH,
    NOTIFICATIONS
}

data class AppNavigationIntent(
    val packageName: String,
    val destination: AppDestination,
    val rawCommand: String = ""
) {
    companion object {
        private val MESSAGES_RE = Regex(
            """\b(messages?|dm|dms|inbox|chat|chats|direct\s*messages?|msg|sandesh)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val REELS_RE = Regex("""\b(reels?|clips?|shorts?)\b""", RegexOption.IGNORE_CASE)
        private val PROFILE_RE = Regex("""\b(profile|account|my\s+page)\b""", RegexOption.IGNORE_CASE)
        private val HOME_RE = Regex("""\b(home|feed|main\s+page)\b""", RegexOption.IGNORE_CASE)
        private val SEARCH_RE = Regex("""\b(search|explore|khoj|dhundo)\b""", RegexOption.IGNORE_CASE)
        private val NOTIFICATIONS_RE = Regex("""\b(notifications?|activity|alerts?)\b""", RegexOption.IGNORE_CASE)

        fun inferDestination(command: String): AppDestination? {
            val clean = command.trim()
            if (clean.isEmpty()) return null
            return when {
                MESSAGES_RE.containsMatchIn(clean) -> AppDestination.MESSAGES
                REELS_RE.containsMatchIn(clean) -> AppDestination.REELS
                PROFILE_RE.containsMatchIn(clean) -> AppDestination.PROFILE
                NOTIFICATIONS_RE.containsMatchIn(clean) -> AppDestination.NOTIFICATIONS
                SEARCH_RE.containsMatchIn(clean) -> AppDestination.SEARCH
                HOME_RE.containsMatchIn(clean) -> AppDestination.HOME
                else -> null
            }
        }

        fun fromCommand(
            command: String,
            packageName: String = "com.instagram.android",
        ): AppNavigationIntent? {
            val dest = inferDestination(command) ?: return null
            return AppNavigationIntent(
                packageName = packageName,
                destination = dest,
                rawCommand = command,
            )
        }
    }
}
