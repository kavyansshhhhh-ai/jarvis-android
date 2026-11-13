package app.shilex.android.wake

/** Live status of always-available offline wake-word listening. */
enum class WakeState {
    Off,
    Starting,
    Listening,
    PausedSession,
    PausedMicBusy,
    PausedInCall,
    PermissionNeeded,
    Unavailable,
}

/**
 * Pure policy decisions for wake-word activation, cooldowns, duplicate session suppression,
 * assistant TTS echo suppression, lock-screen safety, phone-call conflicts, and mic backoff.
 */
object WakePolicy {
    const val COOLDOWN_MS = 2_500L
    const val HANDOVER_WAKE_LOCK_MS = 15_000L
    private const val BASE_RETRY_MS = 1_000L
    private const val MAX_RETRY_MS = 5_000L

    enum class Decision {
        Activate,
        SessionAlreadyActive,
        AssistantSpeaking,
        Cooldown,
        Locked,
        NoMicPermission,
    }

    fun evaluateActivation(
        nowMs: Long,
        lastWakeMs: Long,
        keyguardLocked: Boolean,
        hasMicPermission: Boolean,
        sessionActive: Boolean = false,
        assistantSpeaking: Boolean = false,
    ): Decision = when {
        !hasMicPermission -> Decision.NoMicPermission
        sessionActive -> Decision.SessionAlreadyActive
        assistantSpeaking -> Decision.AssistantSpeaking
        keyguardLocked -> Decision.Locked
        lastWakeMs > 0L && nowMs - lastWakeMs < COOLDOWN_MS -> Decision.Cooldown
        else -> Decision.Activate
    }

    /** Backoff delay in ms when another app temporarily owns the microphone. */
    fun micRetryDelayMs(consecutiveFailures: Int): Long {
        val shift = (consecutiveFailures - 1).coerceIn(0, 3)
        return (BASE_RETRY_MS shl shift).coerceAtMost(MAX_RETRY_MS)
    }

    /** True when Android's AudioManager mode indicates a telephony or VoIP call is active. */
    fun isCallAudioMode(mode: Int): Boolean =
        mode == AUDIO_MODE_RINGTONE || mode == AUDIO_MODE_IN_CALL || mode == AUDIO_MODE_IN_COMMUNICATION

    private const val AUDIO_MODE_RINGTONE = 1
    private const val AUDIO_MODE_IN_CALL = 2
    private const val AUDIO_MODE_IN_COMMUNICATION = 3
}
