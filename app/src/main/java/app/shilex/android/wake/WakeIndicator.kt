package app.shilex.android.wake

import app.shilex.android.control.ControlRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class WakeDotState(
    val visible: Boolean = false,
    val triggerId: Long = 0L,
    val triggeredAtMs: Long = 0L,
)

/**
 * Pure event/state controller for the tiny wake-activation dot.
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
    ): Boolean {
        if (!enabled || keyguardLocked || !screenInteractive) {
            return false
        }
        val nextId = _state.value.triggerId + 1L
        _state.value = WakeDotState(
            visible = true,
            triggerId = nextId,
            triggeredAtMs = nowMs,
        )
        return true
    }

    /** Hides the dot once [DISPLAY_DURATION_MS] has elapsed since activation. */
    fun expireIfElapsed(nowMs: Long): Boolean {
        val current = _state.value
        if (!current.visible) return false
        if (nowMs - current.triggeredAtMs >= DISPLAY_DURATION_MS) {
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
        const val DISPLAY_DURATION_MS = 1_500L
        const val FADE_IN_MS = 140
        const val FADE_OUT_MS = 280
        const val CLEANUP_DELAY_MS = 1_650L

        /**
         * Determines whether the system-wide top-center overlay may be drawn on top of other apps.
         * Never draws without explicit overlay permission, on locked/off screens, inside JARVIS itself,
         * on PIN/OTP/password auth screens, or over blocked banking/password/system packages.
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
            if (foregroundPackage.isNotEmpty() && ControlRules.blocked(foregroundPackage)) return false
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
    ): Boolean = controller.onWakeDetected(nowMs, enabled, keyguardLocked, screenInteractive)

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
