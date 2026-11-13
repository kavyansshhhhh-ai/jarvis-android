package app.shilex.android.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeIndicatorTest {

    @Test
    fun defaultSizeAndContinuousSliderRangeScaleProportionallyAndPersist() {
        assertEquals(9, WakeDotSize.MIN_DP)
        assertEquals(50, WakeDotSize.MAX_DP)
        assertEquals(12, WakeDotSize.DEFAULT_DP)
        assertEquals(WakeDotSize.Standard, WakeDotSize.fromKey(null))
        assertEquals(WakeDotSize.Standard, WakeDotSize.fromKey("unknown"))
        assertEquals(WakeDotSize.Small, WakeDotSize.fromKey("small"))
        assertEquals(WakeDotSize.Large, WakeDotSize.fromKey("large"))
        assertEquals(9, WakeDotSize.Small.coreDiameterDp)
        assertEquals(18, WakeDotSize.Small.glowDiameterDp)
        assertEquals(12, WakeDotSize.Standard.coreDiameterDp)
        assertEquals(22, WakeDotSize.Standard.glowDiameterDp)
        assertEquals(50, WakeDotSize.Max.coreDiameterDp)
        assertEquals(74, WakeDotSize.Max.glowDiameterDp)

        // Clamps min/max boundaries cleanly
        assertEquals(9, WakeDotSize.fromDp(2).coreDiameterDp)
        assertEquals(50, WakeDotSize.fromDp(90).coreDiameterDp)
        assertEquals(9, WakeDotSize.fromSlider(4.2f).coreDiameterDp)
        assertEquals(50, WakeDotSize.fromSlider(63.8f).coreDiameterDp)

        // Intermediate continuous slider rounding
        assertEquals(24, WakeDotSize.fromSlider(24.3f).coreDiameterDp)
        assertEquals(25, WakeDotSize.fromSlider(24.6f).coreDiameterDp)
        assertEquals(33, WakeDotSize.fromKey("dp33").coreDiameterDp)
        assertEquals(27, WakeDotSize.fromKey("27dp").coreDiameterDp)

        // Round-trip key serialization (persisted across restart)
        (9..50).forEach { dp ->
            val size = WakeDotSize.fromDp(dp)
            assertEquals("${dp}dp", size.label)
            assertEquals("dp$dp", size.key)
            assertEquals(size, WakeDotSize.fromKey(size.key))
        }

        // Glow & canvas scale monotonically with room for 1.12x radial halo pulse (never clips)
        var prevGlow = 0
        var prevCanvas = 0
        (9..50).forEach { dp ->
            val size = WakeDotSize.fromDp(dp)
            assertTrue("Glow for ${dp}dp must exceed core", size.glowDiameterDp > size.coreDiameterDp)
            assertTrue("Glow must not shrink as slider grows at ${dp}dp", size.glowDiameterDp >= prevGlow)
            assertTrue("Canvas must not shrink at ${dp}dp", size.canvasDp >= prevCanvas)
            assertTrue(
                "Canvas ${size.canvasDp}dp must hold 1.12x pulse halo ${size.glowDiameterDp * 1.12f}dp",
                size.canvasDp > size.glowDiameterDp * 1.12f,
            )
            prevGlow = size.glowDiameterDp
            prevCanvas = size.canvasDp
        }

        assertTrue(WakeIndicatorController.DISPLAY_DURATION_MS in 2_500L..3_000L)
        assertEquals(2_000L, WakeIndicatorController.clampDurationMs(500L))
        assertEquals(4_000L, WakeIndicatorController.clampDurationMs(10_000L))
        assertTrue(WakeIndicatorController.cleanupDelayMs(2_800L) > 2_800L)
    }

    @Test
    fun sliderSimulationAndIntermediateRounding() {
        // Continuous float slider values from 9.0f up to 50.0f
        var currentFloat = 9.0f
        while (currentFloat <= 50.0f) {
            val size = WakeDotSize.fromSlider(currentFloat)
            assertTrue("Core diameter ${size.coreDiameterDp} out of bounds", size.coreDiameterDp in 9..50)
            assertEquals("dp${size.coreDiameterDp}", size.key)
            assertEquals("${size.coreDiameterDp}dp", size.label)
            currentFloat += 0.35f
        }

        // Live preview scaling checks
        val minDot = WakeDotSize.fromSlider(9.0f)
        val midDot = WakeDotSize.fromSlider(24.4f)
        val maxDot = WakeDotSize.fromSlider(50.0f)

        assertEquals(9, minDot.coreDiameterDp)
        assertEquals(24, midDot.coreDiameterDp)
        assertEquals(50, maxDot.coreDiameterDp)

        assertTrue("Glow must scale with core", minDot.glowDiameterDp < midDot.glowDiameterDp)
        assertTrue("Glow must scale with core", midDot.glowDiameterDp < maxDot.glowDiameterDp)
        assertTrue("Canvas must scale with glow", minDot.canvasDp < midDot.canvasDp)
        assertTrue("Canvas must scale with glow", midDot.canvasDp < maxDot.canvasDp)

        // Safety fallback on invalid input
        assertEquals(WakeDotSize.Standard, WakeDotSize.fromSlider(Float.NaN))
        assertEquals(WakeDotSize.Small, WakeDotSize.fromSlider(Float.NEGATIVE_INFINITY))
        assertEquals(WakeDotSize.Max, WakeDotSize.fromSlider(Float.POSITIVE_INFINITY))
    }

    @Test
    fun suppressesDotWhenSessionActiveSpeakingOrDuplicateWindow() {
        val controller = WakeIndicatorController()
        assertFalse(
            controller.onWakeDetected(
                nowMs = 5_000L,
                enabled = true,
                keyguardLocked = false,
                screenInteractive = true,
                sessionActive = true,
            ),
        )
        assertFalse(
            controller.onWakeDetected(
                nowMs = 6_000L,
                enabled = true,
                keyguardLocked = false,
                screenInteractive = true,
                assistantSpeaking = true,
            ),
        )
        assertTrue(
            controller.onWakeDetected(
                nowMs = 10_000L,
                enabled = true,
                keyguardLocked = false,
                screenInteractive = true,
            ),
        )
        // Duplicate wake inside visible window is suppressed
        assertFalse(
            controller.onWakeDetected(
                nowMs = 11_000L,
                enabled = true,
                keyguardLocked = false,
                screenInteractive = true,
            ),
        )
        assertEquals(1L, controller.state.value.triggerId)
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
