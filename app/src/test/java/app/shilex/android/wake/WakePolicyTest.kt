package app.shilex.android.wake

import app.shilex.android.wake.WakePolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePolicyTest {

    @Test
    fun cooldownPreventsDuplicateTriggersFromSameUtterance() {
        assertEquals(
            Decision.Activate,
            WakePolicy.evaluateActivation(nowMs = 10_000L, lastWakeMs = 0L, keyguardLocked = false, hasMicPermission = true),
        )
        assertEquals(
            Decision.Cooldown,
            WakePolicy.evaluateActivation(nowMs = 11_000L, lastWakeMs = 10_000L, keyguardLocked = false, hasMicPermission = true),
        )
        assertEquals(
            Decision.Activate,
            WakePolicy.evaluateActivation(
                nowMs = 10_000L + WakePolicy.COOLDOWN_MS,
                lastWakeMs = 10_000L,
                keyguardLocked = false,
                hasMicPermission = true,
            ),
        )
    }

    @Test
    fun respectsMicPermissionAndLockScreenSafety() {
        assertEquals(
            Decision.NoMicPermission,
            WakePolicy.evaluateActivation(nowMs = 20_000L, lastWakeMs = 0L, keyguardLocked = false, hasMicPermission = false),
        )
        assertEquals(
            Decision.Locked,
            WakePolicy.evaluateActivation(nowMs = 20_000L, lastWakeMs = 0L, keyguardLocked = true, hasMicPermission = true),
        )
    }

    @Test
    fun micRetryBackoffAndCallDetectionHandleConflictsGracefully() {
        assertEquals(1_000L, WakePolicy.micRetryDelayMs(1))
        assertEquals(2_000L, WakePolicy.micRetryDelayMs(2))
        assertEquals(4_000L, WakePolicy.micRetryDelayMs(3))
        assertEquals(5_000L, WakePolicy.micRetryDelayMs(4))
        assertEquals(5_000L, WakePolicy.micRetryDelayMs(10))

        assertFalse(WakePolicy.isCallAudioMode(0)) // MODE_NORMAL
        assertTrue(WakePolicy.isCallAudioMode(1))  // MODE_RINGTONE
        assertTrue(WakePolicy.isCallAudioMode(2))  // MODE_IN_CALL
        assertTrue(WakePolicy.isCallAudioMode(3))  // MODE_IN_COMMUNICATION
    }
}
