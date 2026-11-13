package app.shilex.android.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeIndicatorTest {

    @Test
    fun triggersOnlyOnRealWakeAndFadesOutInOneToTwoSeconds() {
        val controller = WakeIndicatorController()
        assertFalse(controller.state.value.visible)
        assertEquals(0L, controller.state.value.triggerId)

        // Real wake triggers visibility and increments triggerId
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

        // Remains visible before 1.5s expiry window
        assertFalse(controller.expireIfElapsed(10_800L))
        assertTrue(controller.state.value.visible)

        // Automatically hides after ~1.5s (1-2s window)
        assertTrue(controller.expireIfElapsed(10_000L + WakeIndicatorController.DISPLAY_DURATION_MS))
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
    fun systemOverlayRespectsPermissionLockAndSensitiveScreens() {
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

        // Suppressed on banking apps, password managers, settings, NPCI PIN UI, and sensitive auth screens
        listOf(
            "com.sbi.lotusintouch",
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
