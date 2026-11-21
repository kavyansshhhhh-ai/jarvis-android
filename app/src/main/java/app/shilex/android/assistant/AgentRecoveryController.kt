package app.shilex.android.assistant

/**
 * Prevents duplicate clicks on already-reached screens, enforces bounded recovery retries,
 * and resets automatically when the screen, target destination, or user command changes.
 */
class AgentRecoveryController(private val maxRetries: Int = 2) {
    private val lock = Any()
    private val attemptedActions = mutableSetOf<String>()
    private val cancelledActions = mutableSetOf<String>()
    private var activeScreenKey: String = ""
    private var activePackageKey: String = ""
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

    fun markCancelled(actionId: String, currentScreen: String) = synchronized(lock) {
        val key = "${actionId.trim().lowercase()}|${currentScreen.trim().lowercase()}"
        cancelledActions.add(key)
    }

    fun isCancelledOnScreen(actionId: String, currentScreen: String): Boolean = synchronized(lock) {
        val key = "${actionId.trim().lowercase()}|${currentScreen.trim().lowercase()}"
        key in cancelledActions
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

    /** Updates package context without wiping action memory when staying in the same app. */
    fun onForegroundPackageChanged(packageName: String) = synchronized(lock) {
        val normalizedPkg = packageName.trim().lowercase()
        if (normalizedPkg.isEmpty()) return@synchronized
        if (activePackageKey.isNotEmpty() && activePackageKey != normalizedPkg) {
            activePackageKey = normalizedPkg
            activeScreenKey = ""
            attemptedActions.clear()
            cancelledActions.clear()
            retriesUsed = 0
        } else if (activePackageKey.isEmpty()) {
            activePackageKey = normalizedPkg
        }
    }

    fun onScreenChanged(screenKey: String) = synchronized(lock) {
        val normalized = screenKey.trim().lowercase()
        if (normalized.isNotEmpty() && normalized != activeScreenKey) {
            activeScreenKey = normalized
            val pkgPart = normalized.substringBefore(':')
            if (pkgPart.isNotEmpty()) activePackageKey = pkgPart
            attemptedActions.clear()
            cancelledActions.clear()
            retriesUsed = 0
        }
    }

    fun onDestinationChanged(destinationKey: String) = synchronized(lock) {
        val normalized = destinationKey.trim().lowercase()
        if (normalized != activeDestinationKey) {
            activeDestinationKey = normalized
            attemptedActions.clear()
            cancelledActions.clear()
            retriesUsed = 0
        }
    }

    fun onNewUserCommand(command: String) = synchronized(lock) {
        val normalized = command.trim().lowercase()
        if (normalized != activeCommandKey) {
            activeCommandKey = normalized
            attemptedActions.clear()
            cancelledActions.clear()
            retriesUsed = 0
        }
    }

    fun reset() = synchronized(lock) {
        attemptedActions.clear()
        cancelledActions.clear()
        activeScreenKey = ""
        activePackageKey = ""
        activeDestinationKey = ""
        activeCommandKey = ""
        retriesUsed = 0
    }
}
