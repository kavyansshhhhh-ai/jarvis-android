package app.shilex.android.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeIndicatorTest {

    @Test
    fun defaultSizeAndDurationMatchVisibleSubtleRequirements() {
        assertEquals(WakeDotSize.Standard, WakeDotSize.fromKey(null))
        assertEquals(WakeDotSize.Standard, WakeDotSize.fromKey("unknown"))
        assertEquals(12, WakeDotSize.Standard.coreDiameterDp)
        assertEquals(22, WakeDotSize.Standard.glowDiameterDp)
        assertTrue(WakeIndicatorController.DISPLAY_DURATION_MS in 2_500L..3_000L)
        assertEquals(2_000L, WakeIndicatorController.clampDurationMs(500L))
        assertEquals(4_000L, WakeIndicatorController.clampDurationMs(10_000L))
        assertTrue(WakeIndicatorController.cleanupDelayMs(2_800L) > 2_800L)
    }

    @Test
    fun triggersOnlyOnRealWakeAndRespectsConfiguredDuration() {
        val controller = WakeIndicatorController()
        assertFalse(controller.state.value.visible)
        assertEquals(0L, controller.state.value.triggerId)

        // Real wake triggers visibility with configured duration (~2.8s default) and increments triggerId
        assertTrue(
            controller.onWakeDetected(
                nowMs = 10_000L,
                enabled = true,
                keyguardLocked = false,
                screenInteractive = true,
            ),
        )
        assertTrue(controller.state.value.visible)
        assertEquals(1L, controller.state.value.triggerId)
        assertEquals(WakeIndicatorController.DISPLAY_DURATION_MS, controller.state.value.durationMs)

        // Remains visible before configured 2.8s expiry window
        assertFalse(controller.expireIfElapsed(12_000L))
        assertTrue(controller.state.value.visible)

        // Automatically hides once 2.8s window elapses
        assertTrue(controller.expireIfElapsed(10_000L + WakeIndicatorController.DISPLAY_DURATION_MS))
        assertFalse(controller.state.value.visible)

        // Supports custom duration from settings (clamped cleanly)
        assertTrue(
            controller.onWakeDetected(
                nowMs = 20_000L,
                enabled = true,
                keyguardLocked = false,
                screenInteractive = true,
                durationMs = 3_500L,
            ),
        )
        assertFalse(controller.expireIfElapsed(23_000L))
        assertTrue(controller.state.value.visible)
        assertTrue(controller.expireIfElapsed(23_500L))
        assertFalse(controller.state.value.visible)
    }

    @Test
    fun refusesActivationWhenLockedScreenOffOrDisabledAndCleansUpOnDismiss() {
        val controller = WakeIndicatorController()

        assertFalse(controller.onWakeDetected(nowMs = 1_000L, enabled = false, keyguardLocked = false, screenInteractive = true))
        assertFalse(controller.state.value.visible)

        assertFalse(controller.onWakeDetected(nowMs = 2_000L, enabled = true, keyguardLocked = true, screenInteractive = true))
        assertFalse(controller.state.value.visible)

        assertFalse(controller.onWakeDetected(nowMs = 3_000L, enabled = true, keyguardLocked = false, screenInteractive = false))
        assertFalse(controller.state.value.visible)

        assertTrue(controller.onWakeDetected(nowMs = 4_000L, enabled = true, keyguardLocked = false, screenInteractive = true))
        assertTrue(controller.state.value.visible)

        // Service stop / disable clears indicator immediately
        controller.dismiss()
        assertFalse(controller.state.value.visible)
    }

    @Test
    fun systemOverlayRespectsPermissionLockBankingUpiAndSensitiveScreens() {
        // Allowed on Home screen & ordinary apps when overlay permission is granted
        assertTrue(
            WakeIndicatorController.shouldShowSystemOverlay(
                indicatorEnabled = true,
                hasOverlayPermission = true,
                screenInteractive = true,
                keyguardLocked = false,
                foregroundPackage = "com.google.android.apps.nexuslauncher",
                sensitiveAuthScreen = false,
                inAppVisible = false,
            ),
        )
        assertTrue(
            WakeIndicatorController.shouldShowSystemOverlay(
                indicatorEnabled = true,
                hasOverlayPermission = true,
                screenInteractive = true,
                keyguardLocked = false,
                foregroundPackage = "com.whatsapp",
                sensitiveAuthScreen = false,
                inAppVisible = false,
            ),
        )

        // Denied overlay permission -> never tries to show system overlay
        assertFalse(
            WakeIndicatorController.shouldShowSystemOverlay(
                indicatorEnabled = true,
                hasOverlayPermission = false,
                screenInteractive = true,
                keyguardLocked = false,
                foregroundPackage = "com.whatsapp",
                sensitiveAuthScreen = false,
                inAppVisible = false,
            ),
        )

        // Suppressed on banking apps, UPI apps, password managers, settings, NPCI PIN UI, and JARVIS itself
        listOf(
            "com.sbi.lotusintouch",
            "com.snapwork.hdfc",
            "com.phonepe.app",
            "com.google.android.apps.nbu.paisa.user",
            "com.x8bit.bitwarden",
            "org.npci.upi.security.pinactivitycomponent",
            "com.android.settings",
            "app.shilex.android",
        ).forEach { pkg ->
            assertFalse(
                pkg,
                WakeIndicatorController.shouldShowSystemOverlay(
                    indicatorEnabled = true,
                    hasOverlayPermission = true,
                    screenInteractive = true,
                    keyguardLocked = false,
                    foregroundPackage = pkg,
                    sensitiveAuthScreen = false,
                    inAppVisible = false,
                ),
            )
        }

        // Suppressed when locked, screen off, inside JARVIS, or on a sensitive PIN/OTP screen
        assertFalse(
            WakeIndicatorController.shouldShowSystemOverlay(
                indicatorEnabled = true,
                hasOverlayPermission = true,
                screenInteractive = true,
                keyguardLocked = false,
                foregroundPackage = "com.google.android.apps.nbu.paisa.user",
                sensitiveAuthScreen = true,
                inAppVisible = false,
            ),
        )
        assertFalse(
            WakeIndicatorController.shouldShowSystemOverlay(
                indicatorEnabled = true,
                hasOverlayPermission = true,
                screenInteractive = false,
                keyguardLocked = false,
                foregroundPackage = "com.whatsapp",
                sensitiveAuthScreen = false,
                inAppVisible = false,
            ),
        )
        assertFalse(
            WakeIndicatorController.shouldShowSystemOverlay(
                indicatorEnabled = true,
                hasOverlayPermission = true,
                screenInteractive = true,
                keyguardLocked = true,
                foregroundPackage = "com.whatsapp",
                sensitiveAuthScreen = false,
                inAppVisible = false,
            ),
        )
        assertFalse(
            WakeIndicatorController.shouldShowSystemOverlay(
                indicatorEnabled = true,
                hasOverlayPermission = true,
                screenInteractive = true,
                keyguardLocked = false,
                foregroundPackage = "com.whatsapp",
                sensitiveAuthScreen = false,
                inAppVisible = true,
            ),
        )
    }
}
