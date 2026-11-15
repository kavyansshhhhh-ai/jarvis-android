package app.shilex.android.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ActiveTaskContextTest {

    @Before
    fun setUp() {
        ActiveTaskContext.clear()
    }

    @Test
    fun tracksTaskProgressionAcrossSteps() {
        assertFalse(ActiveTaskContext.snapshot().isActive)

        ActiveTaskContext.beginTask(
            task = "Send WhatsApp message to Rahul",
            application = "WhatsApp",
            appPackage = "com.whatsapp",
            recipient = "Rahul",
            executionStep = "Opening chat",
            pendingSteps = listOf("Opening chat", "Send"),
            nowMs = 1000L,
        )

        assertTrue(ActiveTaskContext.snapshot().isActive)
        assertEquals("WhatsApp", ActiveTaskContext.snapshot().currentApplication)
        assertEquals("Rahul", ActiveTaskContext.snapshot().currentRecipient)
        assertEquals(2, ActiveTaskContext.snapshot().pendingSteps.size)

        // Advance step
        ActiveTaskContext.advanceStep(
            completedStep = "Opening chat",
            nextExecutionStep = "Compose message",
            verifiedResult = "Chat opened with Rahul",
            nowMs = 2000L,
        )

        val advanced = ActiveTaskContext.snapshot()
        assertEquals("Compose message", advanced.currentExecutionStep)
        assertEquals(listOf("Opening chat"), advanced.completedSteps)
        assertEquals(listOf("Send"), advanced.pendingSteps)
        assertEquals("Chat opened with Rahul", advanced.lastVerifiedResult)

        // Complete
        ActiveTaskContext.complete("Message sent to Rahul", nowMs = 3000L)
        assertFalse(ActiveTaskContext.snapshot().isActive)
        assertEquals(CancellationStatus.Completed, ActiveTaskContext.snapshot().cancellationState)
    }

    @Test
    fun clearsOnUnexpectedAppChange() {
        ActiveTaskContext.beginTask(
            task = "Install LastWave",
            application = "Play Store",
            appPackage = "com.android.vending",
            nowMs = 1000L,
        )
        assertTrue(ActiveTaskContext.snapshot().isActive)

        val switched = ActiveTaskContext.onUnexpectedAppChange("com.spotify.music", nowMs = 2000L)
        assertTrue("Must detect unexpected app change", switched)
        assertFalse(ActiveTaskContext.snapshot().isActive)
        assertEquals(CancellationStatus.Interrupted, ActiveTaskContext.snapshot().cancellationState)
    }

    @Test
    fun clearsIfStale() {
        ActiveTaskContext.beginTask(
            task = "Old abandoned task",
            application = "Settings",
            nowMs = 1000L,
        )
        assertTrue(ActiveTaskContext.snapshot().isActive)

        // Not stale after 60s
        assertFalse(ActiveTaskContext.clearIfStale(nowMs = 61_000L, maxAgeMs = 180_000L))
        assertTrue(ActiveTaskContext.snapshot().isActive)

        // Stale after 181s
        assertTrue(ActiveTaskContext.clearIfStale(nowMs = 182_000L, maxAgeMs = 180_000L))
        assertFalse(ActiveTaskContext.snapshot().isActive)
        assertEquals(CancellationStatus.ClearedStale, ActiveTaskContext.snapshot().cancellationState)
    }

    @Test
    fun sanitizesSensitiveTokensFromTaskState() {
        val sanitized = TaskContextSanitizer.sanitizeValue("Your OTP is 482910")
        assertEquals("[protected]", sanitized)

        val sanitizedPin = TaskContextSanitizer.sanitizeValue("enter pin 1234")
        assertEquals("[protected]", sanitizedPin)

        val normal = TaskContextSanitizer.sanitizeValue("Play Kesariya by Arijit Singh")
        assertEquals("Play Kesariya by Arijit Singh", normal)
    }
}
