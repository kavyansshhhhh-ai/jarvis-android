package app.shilex.android.assistant

/**
 * Prevents duplicate clicks on already-reached screens, enforces bounded recovery retries,
 * and resets automatically when the screen, target destination, or user command changes.
 */
class AgentRecoveryController(private val maxRetries: Int = 2) {
    private val lock = Any()
    private val attemptedActions = mutableSetOf<String>()
    private var activeScreenKey: String = ""
    private var activeDestinationKey: String = ""
    private var activeCommandKey: String = ""
    private var retriesUsed: Int = 0

    fun shouldSkipDuplicate(
        actionId: String,
        currentScreen: String,
        expectedDestination: String,
    ): Boolean = synchronized(lock) {
        if (currentScreen.isNotBlank() &&
            expectedDestination.isNotBlank() &&
            currentScreen.equals(expectedDestination, ignoreCase = true)
        ) {
            return@synchronized true
        }
        val key = "${actionId.trim().lowercase()}|${currentScreen.trim().lowercase()}|${expectedDestination.trim().lowercase()}"
        !attemptedActions.add(key)
    }

    fun canRetry(retryCount: Int): Boolean = retryCount < maxRetries

    fun currentRetryCount(): Int = synchronized(lock) { retriesUsed }

    fun nextRetry(): Boolean = synchronized(lock) {
        if (retriesUsed >= maxRetries) {
            false
        } else {
            retriesUsed++
            true
        }
    }

    fun onScreenChanged(screenKey: String) = synchronized(lock) {
        val normalized = screenKey.trim().lowercase()
        if (normalized.isNotEmpty() && normalized != activeScreenKey) {
            activeScreenKey = normalized
            attemptedActions.clear()
            retriesUsed = 0
        }
    }

    fun onDestinationChanged(destinationKey: String) = synchronized(lock) {
        val normalized = destinationKey.trim().lowercase()
        if (normalized != activeDestinationKey) {
            activeDestinationKey = normalized
            attemptedActions.clear()
            retriesUsed = 0
        }
    }

    fun onNewUserCommand(command: String) = synchronized(lock) {
        val normalized = command.trim().lowercase()
        if (normalized != activeCommandKey) {
            activeCommandKey = normalized
            attemptedActions.clear()
            retriesUsed = 0
        }
    }

    fun reset() = synchronized(lock) {
        attemptedActions.clear()
        activeScreenKey = ""
        activeDestinationKey = ""
        activeCommandKey = ""
        retriesUsed = 0
    }
}
