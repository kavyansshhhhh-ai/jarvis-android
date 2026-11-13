package app.shilex.android.wake

import app.shilex.android.control.ControlRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class WakeDotSize(
    val key: String,
    val label: String,
    val coreDiameterDp: Int,
    val glowDiameterDp: Int,
) {
    Small("small", "Subtle", coreDiameterDp = 9, glowDiameterDp = 18),
    Standard("standard", "Standard", coreDiameterDp = 12, glowDiameterDp = 22),
    Large("large", "Large", coreDiameterDp = 16, glowDiameterDp = 28),
    ;

    /** Canvas width/height with headroom for gentle radial halo pulse without clipping. */
    val canvasDp: Int get() = glowDiameterDp + 8

    companion object {
        fun fromKey(key: String?): WakeDotSize = entries.firstOrNull { it.key == key } ?: Standard
    }
}

data class WakeDotState(
    val visible: Boolean = false,
    val triggerId: Long = 0L,
    val triggeredAtMs: Long = 0L,
    val durationMs: Long = WakeIndicatorController.DISPLAY_DURATION_MS,
)

/**
 * Pure event/state controller for the subtle wake-activation dot.
 * Only real wake detections activate it; ordinary listening, locked screens,
 * non-interactive displays, and disabled states never trigger it.
 */
class WakeIndicatorController {
    private val _state = MutableStateFlow(WakeDotState())
    val state: StateFlow<WakeDotState> = _state.asStateFlow()

    /** Activates the dot strictly when a real wake detection fires. Returns true if shown. */
    fun onWakeDetected(
        nowMs: Long,
        enabled: Boolean,
        keyguardLocked: Boolean,
        screenInteractive: Boolean,
        durationMs: Long = DISPLAY_DURATION_MS,
    ): Boolean {
        if (!enabled || keyguardLocked || !screenInteractive) {
            return false
        }
        val nextId = _state.value.triggerId + 1L
        val effectiveDuration = clampDurationMs(durationMs)
        _state.value = WakeDotState(
            visible = true,
            triggerId = nextId,
            triggeredAtMs = nowMs,
            durationMs = effectiveDuration,
        )
        return true
    }

    /** Hides the dot once its configured display duration has elapsed since activation. */
    fun expireIfElapsed(nowMs: Long): Boolean {
        val current = _state.value
        if (!current.visible) return false
        if (nowMs - current.triggeredAtMs >= current.durationMs) {
            _state.value = current.copy(visible = false)
            return true
        }
        return false
    }

    /** Immediately clears the dot (service stopped, wake word disabled, or user disabled indicator). */
    fun dismiss() {
        val current = _state.value
        if (current.visible) {
            _state.value = current.copy(visible = false)
        }
    }

    companion object {
        const val SHORT_DURATION_MS = 2_000L
        const val DISPLAY_DURATION_MS = 2_800L
        const val LONG_DURATION_MS = 3_500L
        const val MIN_DURATION_MS = 2_000L
        const val MAX_DURATION_MS = 4_000L
        val DURATION_PRESETS_MS = listOf(SHORT_DURATION_MS, DISPLAY_DURATION_MS, LONG_DURATION_MS)

        const val FADE_IN_MS = 220
        const val FADE_OUT_MS = 450
        const val CLEANUP_GRACE_MS = 180L
        const val CLEANUP_DELAY_MS = DISPLAY_DURATION_MS + CLEANUP_GRACE_MS

        fun clampDurationMs(durationMs: Long): Long = durationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)

        fun cleanupDelayMs(durationMs: Long): Long = clampDurationMs(durationMs) + CLEANUP_GRACE_MS

        /**
         * Determines whether the system-wide top-center overlay may be drawn on top of other apps.
         * Never draws without explicit overlay permission, on locked/off screens, inside JARVIS itself,
         * on PIN/OTP/password auth screens, or over banking, UPI, password, or system security packages.
         */
        fun shouldShowSystemOverlay(
            indicatorEnabled: Boolean,
            hasOverlayPermission: Boolean,
            screenInteractive: Boolean,
            keyguardLocked: Boolean,
            foregroundPackage: String,
            sensitiveAuthScreen: Boolean,
            inAppVisible: Boolean,
        ): Boolean {
            if (!indicatorEnabled || !hasOverlayPermission) return false
            if (!screenInteractive || keyguardLocked || sensitiveAuthScreen || inAppVisible) return false
            if (foregroundPackage.isNotEmpty() && ControlRules.isSensitiveOrBlockedPackage(foregroundPackage)) return false
            return true
        }
    }
}

/** Process-wide wake-activation indicator state, driven exclusively by [app.shilex.android.assistant.VoiceService]. */
object WakeIndicator {
    private val controller = WakeIndicatorController()
    val state: StateFlow<WakeDotState> = controller.state

    fun onWakeDetected(
        nowMs: Long,
        enabled: Boolean,
        keyguardLocked: Boolean,
        screenInteractive: Boolean,
        durationMs: Long = WakeIndicatorController.DISPLAY_DURATION_MS,
    ): Boolean = controller.onWakeDetected(nowMs, enabled, keyguardLocked, screenInteractive, durationMs)

    fun expireIfElapsed(nowMs: Long): Boolean = controller.expireIfElapsed(nowMs)

    fun dismiss() = controller.dismiss()

    fun shouldShowSystemOverlay(
        indicatorEnabled: Boolean,
        hasOverlayPermission: Boolean,
        screenInteractive: Boolean,
        keyguardLocked: Boolean,
        foregroundPackage: String,
        sensitiveAuthScreen: Boolean,
        inAppVisible: Boolean,
    ): Boolean = WakeIndicatorController.shouldShowSystemOverlay(
        indicatorEnabled = indicatorEnabled,
        hasOverlayPermission = hasOverlayPermission,
        screenInteractive = screenInteractive,
        keyguardLocked = keyguardLocked,
        foregroundPackage = foregroundPackage,
        sensitiveAuthScreen = sensitiveAuthScreen,
        inAppVisible = inAppVisible,
    )
}
