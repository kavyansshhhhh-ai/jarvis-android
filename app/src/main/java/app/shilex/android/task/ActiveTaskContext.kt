package app.shilex.android.task

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CancellationStatus {
    None,
    Active,
    Interrupted,
    Cancelled,
    Completed,
    ClearedStale,
}

/**
 * Structured, non-sensitive active workflow state for multi-step assistant tasks.
 * Stores step metadata only—strictly never stores OTPs, PINs, MPINs, CVVs, passwords,
 * or full payment card numbers.
 */
data class TaskContextState(
    val currentTask: String = "",
    val currentApplication: String = "",
    val currentAppPackage: String = "",
    val currentScreen: String = "",
    val currentRecipient: String = "",
    val currentSearchQuery: String = "",
    val currentSelectedItem: String = "",
    val currentExecutionStep: String = "",
    val completedSteps: List<String> = emptyList(),
    val pendingSteps: List<String> = emptyList(),
    val lastVerifiedResult: String = "",
    val cancellationState: CancellationStatus = CancellationStatus.None,
    val updatedAtMs: Long = 0L,
) {
    val isActive: Boolean
        get() = cancellationState == CancellationStatus.Active && currentTask.isNotBlank()
}

/**
 * Redacts or rejects secret/credential-like values before they can enter active task state.
 */
object TaskContextSanitizer {
    private val SENSITIVE_LABEL = Regex(
        """\b(otp|one[\s_-]?time[\s_-]?password|pin|m[\s_-]?pin|upi[\s_-]?pin|cvv|cvc|password|passcode|card[\s_-]?number|secret)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val CARD_OR_LONG_SECRET = Regex("""\b(?:\d[ -]*?){13,19}\b""")
    private val OTP_WITH_CONTEXT = Regex(
        """\b(?:otp|pin|mpin|cvv|cvc|passcode|password|code)\s*(?:is|:|=)?\s*[a-zA-Z0-9*#@-]{3,16}\b""",
        RegexOption.IGNORE_CASE,
    )

    fun sanitizeValue(raw: String, maxLen: Int = 120): String {
        val trimmed = raw.trim().replace(Regex("\\s+"), " ")
        if (trimmed.isEmpty()) return ""
        if (OTP_WITH_CONTEXT.containsMatchIn(trimmed) || SENSITIVE_LABEL.containsMatchIn(trimmed)) {
            return "[protected]"
        }
        val withoutCards = CARD_OR_LONG_SECRET.replace(trimmed, "[protected]")
        return withoutCards.take(maxLen)
    }
}

/**
 * Process-wide active task memory manager. Automatically clears stale or interrupted context
 * and prevents unrelated steps from reusing outdated recipients, queries, or selections.
 */
object ActiveTaskContext {
    private val lock = Any()
    private val _state = MutableStateFlow(TaskContextState())
    val state: StateFlow<TaskContextState> = _state.asStateFlow()

    const val DEFAULT_STALE_MS = 180_000L

    fun snapshot(): TaskContextState = _state.value

    fun beginTask(
        task: String,
        application: String = "",
        appPackage: String = "",
        screen: String = "",
        recipient: String = "",
        searchQuery: String = "",
        selectedItem: String = "",
        executionStep: String = "",
        pendingSteps: List<String> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
    ): TaskContextState = synchronized(lock) {
        val next = TaskContextState(
            currentTask = TaskContextSanitizer.sanitizeValue(task, 140),
            currentApplication = TaskContextSanitizer.sanitizeValue(application, 60),
            currentAppPackage = appPackage.trim().take(120),
            currentScreen = TaskContextSanitizer.sanitizeValue(screen, 80),
            currentRecipient = TaskContextSanitizer.sanitizeValue(recipient, 80),
            currentSearchQuery = TaskContextSanitizer.sanitizeValue(searchQuery, 120),
            currentSelectedItem = TaskContextSanitizer.sanitizeValue(selectedItem, 100),
            currentExecutionStep = TaskContextSanitizer.sanitizeValue(executionStep, 100),
            completedSteps = emptyList(),
            pendingSteps = pendingSteps.map { TaskContextSanitizer.sanitizeValue(it, 80) }.filter { it.isNotEmpty() },
            lastVerifiedResult = "",
            cancellationState = CancellationStatus.Active,
            updatedAtMs = nowMs,
        )
        _state.value = next
        next
    }

    fun updateScreen(
        application: String = _state.value.currentApplication,
        appPackage: String = _state.value.currentAppPackage,
        screen: String = "",
        nowMs: Long = System.currentTimeMillis(),
    ): TaskContextState = synchronized(lock) {
        val cur = _state.value
        val next = cur.copy(
            currentApplication = TaskContextSanitizer.sanitizeValue(application, 60),
            currentAppPackage = appPackage.trim().take(120),
            currentScreen = TaskContextSanitizer.sanitizeValue(screen, 80),
            updatedAtMs = nowMs,
        )
        _state.value = next
        next
    }

    fun advanceStep(
        completedStep: String? = null,
        nextExecutionStep: String = "",
        selectedItem: String? = null,
        verifiedResult: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): TaskContextState = synchronized(lock) {
        val cur = _state.value
        val completedClean = completedStep?.let { TaskContextSanitizer.sanitizeValue(it, 80) }?.takeIf { it.isNotEmpty() }
        val updatedCompleted = if (completedClean != null) (cur.completedSteps + completedClean).takeLast(12) else cur.completedSteps
        val updatedPending = if (completedClean != null) cur.pendingSteps.filterNot { it.equals(completedClean, ignoreCase = true) } else cur.pendingSteps
        val next = cur.copy(
            currentExecutionStep = TaskContextSanitizer.sanitizeValue(nextExecutionStep, 100),
            currentSelectedItem = selectedItem?.let { TaskContextSanitizer.sanitizeValue(it, 100) } ?: cur.currentSelectedItem,
            completedSteps = updatedCompleted,
            pendingSteps = updatedPending,
            lastVerifiedResult = verifiedResult?.let { TaskContextSanitizer.sanitizeValue(it, 160) } ?: cur.lastVerifiedResult,
            cancellationState = CancellationStatus.Active,
            updatedAtMs = nowMs,
        )
        _state.value = next
        next
    }

    fun complete(verifiedResult: String, nowMs: Long = System.currentTimeMillis()): TaskContextState = synchronized(lock) {
        val cur = _state.value
        val next = TaskContextState(
            lastVerifiedResult = TaskContextSanitizer.sanitizeValue(verifiedResult, 160),
            completedSteps = cur.completedSteps,
            cancellationState = CancellationStatus.Completed,
            updatedAtMs = nowMs,
        )
        _state.value = next
        next
    }

    fun cancel(
        status: CancellationStatus = CancellationStatus.Cancelled,
        reason: String = "",
        nowMs: Long = System.currentTimeMillis(),
    ): TaskContextState = synchronized(lock) {
        val next = TaskContextState(
            lastVerifiedResult = TaskContextSanitizer.sanitizeValue(reason, 160),
            cancellationState = status,
            updatedAtMs = nowMs,
        )
        _state.value = next
        next
    }

    fun onUnexpectedAppChange(actualPackage: String, nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        val cur = _state.value
        if (!cur.isActive || cur.currentAppPackage.isEmpty() || actualPackage.isEmpty()) return@synchronized false
        if (!cur.currentAppPackage.equals(actualPackage, ignoreCase = true)) {
            _state.value = TaskContextState(
                lastVerifiedResult = "Foreground app changed unexpectedly.",
                cancellationState = CancellationStatus.Interrupted,
                updatedAtMs = nowMs,
            )
            true
        } else {
            false
        }
    }

    fun clearIfStale(nowMs: Long = System.currentTimeMillis(), maxAgeMs: Long = DEFAULT_STALE_MS): Boolean = synchronized(lock) {
        val cur = _state.value
        if (cur.isActive && cur.updatedAtMs > 0L && nowMs - cur.updatedAtMs > maxAgeMs) {
            _state.value = TaskContextState(
                cancellationState = CancellationStatus.ClearedStale,
                updatedAtMs = nowMs,
            )
            true
        } else {
            false
        }
    }

    fun clear() = synchronized(lock) {
        _state.value = TaskContextState()
    }

    fun promptSummary(): String {
        val s = _state.value
        if (!s.isActive) return ""
        return buildString {
            append("Active Task: ${s.currentTask}")
            if (s.currentApplication.isNotEmpty()) append(" | App: ${s.currentApplication}")
            if (s.currentScreen.isNotEmpty()) append(" | Screen: ${s.currentScreen}")
            if (s.currentRecipient.isNotEmpty()) append(" | Recipient: ${s.currentRecipient}")
            if (s.currentSearchQuery.isNotEmpty()) append(" | Query: ${s.currentSearchQuery}")
            if (s.currentSelectedItem.isNotEmpty()) append(" | Selected: ${s.currentSelectedItem}")
            if (s.currentExecutionStep.isNotEmpty()) append(" | Step: ${s.currentExecutionStep}")
        }
    }
}
