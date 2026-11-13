package app.shilex.android.orb

import android.app.KeyguardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.shilex.android.control.ControlService
import app.shilex.android.data.Settings
import app.shilex.android.ui.ElectricCyan
import app.shilex.android.ui.NeonViolet
import app.shilex.android.ui.ShilexTheme
import app.shilex.android.wake.WakeIndicator
import app.shilex.android.wake.WakeIndicatorController
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Tiny top-center Liquid Glass activation dot (~1.5s) shown only when the real wake word triggers.
 * Contains no text, labels, pills, or waveforms; never steals focus or touches, never wakes locked
 * screens, and suppresses itself over banking, password, and authentication screens.
 */
object WakeActivationOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var host: OverlayHost? = null
    private val cleanupRunnable = Runnable { removeWindowAndClearState() }
    private var appContext: Context? = null

    fun showBriefly(context: Context) {
        val app = context.applicationContext
        appContext = app
        main.post {
            main.removeCallbacks(cleanupRunnable)
            if (!WakeIndicator.state.value.visible) return@post

            val power = app.getSystemService(PowerManager::class.java)
            val keyguard = app.getSystemService(KeyguardManager::class.java)
            val control = ControlService.instance
            val fgPkg = control?.foregroundPackage().orEmpty()
            val sensitiveAuth = control?.isSensitiveAuthActive() == true
            val showOverlay = WakeIndicator.shouldShowSystemOverlay(
                indicatorEnabled = Settings(app).wakeIndicator,
                hasOverlayPermission = AndroidSettings.canDrawOverlays(app),
                screenInteractive = power?.isInteractive == true,
                keyguardLocked = keyguard?.isKeyguardLocked == true,
                foregroundPackage = fgPkg,
                sensitiveAuthScreen = sensitiveAuth,
                inAppVisible = FloatingOrb.appVisible,
            )

            if (!showOverlay) {
                removeWindowOnly(app)
                main.postDelayed(cleanupRunnable, WakeIndicatorController.CLEANUP_DELAY_MS)
                return@post
            }

            removeWindowOnly(app)
            val windows = app.getSystemService(WindowManager::class.java)
            val sizePx = (DOT_CANVAS_DP * app.resources.displayMetrics.density).roundToInt()
            val topOffsetPx = statusBarHeightPx(app) + (TOP_MARGIN_DP * app.resources.displayMetrics.density).roundToInt()
            val params = WindowManager.LayoutParams(
                sizePx,
                sizePx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = topOffsetPx
            }

            val overlay = OverlayHost(app)
            val triggerId = WakeIndicator.state.value.triggerId
            overlay.open {
                ShilexTheme {
                    TinyWakeDot(triggerId = triggerId)
                }
            }
            runCatching { windows.addView(overlay.view, params) }
                .onSuccess { host = overlay }
                .onFailure { overlay.close() }

            main.postDelayed(cleanupRunnable, WakeIndicatorController.CLEANUP_DELAY_MS)
        }
    }

    fun dismiss(context: Context) {
        val app = context.applicationContext
        appContext = app
        WakeIndicator.dismiss()
        main.post {
            main.removeCallbacks(cleanupRunnable)
            removeWindowOnly(app)
        }
    }

    private fun removeWindowAndClearState() {
        WakeIndicator.dismiss()
        appContext?.let(::removeWindowOnly)
    }

    private fun removeWindowOnly(context: Context) {
        val current = host ?: return
        runCatching { context.getSystemService(WindowManager::class.java).removeView(current.view) }
        current.close()
        host = null
    }

    private fun statusBarHeightPx(context: Context): Int {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) context.resources.getDimensionPixelSize(resId) else 0
    }

    private const val DOT_CANVAS_DP = 28
    private const val TOP_MARGIN_DP = 6
}

/**
 * Tiny glowing cyan/violet Liquid Glass dot (no text, no pill, no card).
 * Pulses softly for ~1.5 seconds and automatically fades out.
 */
@Composable
fun TinyWakeDot(
    triggerId: Long,
    modifier: Modifier = Modifier,
) {
    val alpha = remember(triggerId) { Animatable(0f) }
    LaunchedEffect(triggerId) {
        alpha.snapTo(0f)
        alpha.animateTo(1f, tween(WakeIndicatorController.FADE_IN_MS, easing = FastOutSlowInEasing))
        val holdMs = (WakeIndicatorController.DISPLAY_DURATION_MS -
            WakeIndicatorController.FADE_IN_MS -
            WakeIndicatorController.FADE_OUT_MS).coerceAtLeast(400L)
        delay(holdMs)
        alpha.animateTo(0f, tween(WakeIndicatorController.FADE_OUT_MS, easing = LinearEasing))
    }

    val pulse by rememberInfiniteTransition(label = "wakeDotPulse").animateFloat(
        initialValue = 0.86f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Canvas(modifier = modifier.size(28.dp)) {
        val a = alpha.value
        if (a <= 0.01f) return@Canvas
        val coreRadius = 4.2.dp.toPx() * (0.94f + 0.14f * (pulse - 0.86f) / 0.32f)
        val glowRadius = 13.dp.toPx() * pulse

        // Soft cyan-violet Liquid Glass halo
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    ElectricCyan.copy(alpha = 0.55f * a),
                    NeonViolet.copy(alpha = 0.28f * a),
                    Color.Transparent,
                ),
                center = center,
                radius = glowRadius,
            ),
            radius = glowRadius,
            center = center,
        )

        // Tiny glowing orb core
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.95f * a),
                    ElectricCyan.copy(alpha = 0.95f * a),
                    NeonViolet.copy(alpha = 0.90f * a),
                ),
                center = Offset(center.x - coreRadius * 0.28f, center.y - coreRadius * 0.28f),
                radius = coreRadius * 1.35f,
            ),
            radius = coreRadius,
            center = center,
        )
    }
}
