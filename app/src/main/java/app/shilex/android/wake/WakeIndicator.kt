package app.shilex.android.wake

import app.shilex.android.control.ControlRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Wake activation dot size (9dp–50dp continuous core diameter) with proportional halo & canvas.
 * Preserves compatibility with legacy preset keys ("small", "standard", "large", "dp22", ...).
 */
@ConsistentCopyVisibility
data class WakeDotSize private constructor(
    val coreDiameterDp: Int,
) {
    val key: String get() = "dp$coreDiameterDp"
    val label: String get() = "${coreDiameterDp}dp"

    /** Soft radial halo scales proportionally with the core dot diameter (9dp->18dp, 12dp->22dp, 50dp->74dp). */
    val glowDiameterDp: Int get() = (coreDiameterDp * 1.38f + 5.4f).roundToInt()

    /** Canvas width/height with headroom for 1.12x radial halo pulse without clipping at any size. */
    val canvasDp: Int get() = glowDiameterDp + max(10, (coreDiameterDp * 0.25f).roundToInt())

    companion object {
        const val MIN_DP = 9
        const val MAX_DP = 50
        const val DEFAULT_DP = 12

        val Small = WakeDotSize(9)
        val Standard = WakeDotSize(12)
        val Large = WakeDotSize(16)
        val MediumLarge = WakeDotSize(22)
        val Prominent = WakeDotSize(30)
        val ExtraLarge = WakeDotSize(40)
        val Max = WakeDotSize(50)

        val entries: List<WakeDotSize> = listOf(
            Small,
            Standard,
            Large,
            MediumLarge,
            Prominent,
            ExtraLarge,
            Max,
        )

        fun clampDp(dp: Int): Int = dp.coerceIn(MIN_DP, MAX_DP)

        fun clampFloat(value: Float): Float = value.coerceIn(MIN_DP.toFloat(), MAX_DP.toFloat())

        fun fromDp(dp: Int): WakeDotSize = WakeDotSize(clampDp(dp))

        fun fromSlider(value: Float): WakeDotSize =
            if (value.isNaN()) Standard else WakeDotSize(clampDp(value.roundToInt()))

        fun fromKey(key: String?): WakeDotSize = when (key?.trim()?.lowercase()) {
            null, "" -> Standard
            "small" -> Small
            "standard" -> Standard
            "large" -> Large
            else -> {
                val numeric = key.trim().lowercase()
                    .removePrefix("dp")
                    .removeSuffix("dp")
                    .trim()
                    .toFloatOrNull()
                if (numeric != null && !numeric.isNaN()) {
                    WakeDotSize(clampDp(numeric.roundToInt()))
                } else {
                    Standard
                }
            }
        }
    }
}

data class WakeDotState(
    val visible: Boolean = false,
    val triggerId: Long = 0L,
    val triggeredAtMs: Long = 0L,
    val durationMs: Long = WakeIndicatorController.DISPLAY_DURATION_MS,
)

/**
 * Pure event/state controller for the subtle top-center wake-activation dot.
 * Only real wake detections activate it; active voice sessions, TTS playback, locked screens,
 * non-interactive displays, and disabled states never trigger duplicate dots.
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
        sessionActive: Boolean = false,
        assistantSpeaking: Boolean = false,
    ): Boolean {
        if (!enabled || keyguardLocked || !screenInteractive || sessionActive || assistantSpeaking) {
            return false
        }
        val current = _state.value
        if (current.visible && nowMs - current.triggeredAtMs < DUPLICATE_DOT_WINDOW_MS) {
            return false
        }
        val nextId = current.triggerId + 1L
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
        const val DUPLICATE_DOT_WINDOW_MS = 1_500L

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
        sessionActive: Boolean = false,
        assistantSpeaking: Boolean = false,
    ): Boolean = controller.onWakeDetected(
        nowMs = nowMs,
        enabled = enabled,
        keyguardLocked = keyguardLocked,
        screenInteractive = screenInteractive,
        durationMs = durationMs,
        sessionActive = sessionActive,
        assistantSpeaking = assistantSpeaking,
    )

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
