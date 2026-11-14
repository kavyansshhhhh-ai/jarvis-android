package app.shilex.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidDockTest {
    private val tabCount = 4
    private val slotWidthPx = 200f
    private val innerInsetPx = 12f
    private val touchSlopPx = 16f

    @Test
    fun allFourTabsRespondReliablyToTapsAndAvoidDuplicateSelection() {
        // Centers of the 4 tab slots: 0 (Home), 1 (Automations), 2 (Activity), 3 (Settings)
        val tabCenters = (0 until tabCount).map { idx -> innerInsetPx + idx * slotWidthPx + slotWidthPx / 2f }

        var activeTab = 0
        tabCenters.forEachIndexed { targetIdx, x ->
            val session = DockGestureSession(
                downX = x,
                downY = 60f,
                initialSelectedTab = activeTab,
                slotWidthPx = slotWidthPx,
                innerInsetPx = innerInsetPx,
                tabCount = tabCount,
                touchSlopPx = touchSlopPx,
            )
            val release = session.onUp(upX = x, latestSelectedTab = activeTab)
            assertTrue(release is DockGestureSession.ReleaseOutcome.Select)
            val sel = release as DockGestureSession.ReleaseOutcome.Select
            assertEquals(targetIdx, sel.tabIndex)
            assertEquals(targetIdx != activeTab, sel.changed)
            activeTab = sel.tabIndex
        }

        // Verify tapping Home (0) after navigating through Automations/Activity/Settings still works reliably
        val backHomeSession = DockGestureSession(
            downX = tabCenters[0],
            downY = 60f,
            initialSelectedTab = activeTab,
            slotWidthPx = slotWidthPx,
            innerInsetPx = innerInsetPx,
            tabCount = tabCount,
            touchSlopPx = touchSlopPx,
        )
        val backHome = backHomeSession.onUp(upX = tabCenters[0], latestSelectedTab = activeTab) as DockGestureSession.ReleaseOutcome.Select
        assertEquals(0, backHome.tabIndex)
        assertTrue(backHome.changed)

        // Outer rounded edge caps also resolve cleanly to first/last tabs
        assertEquals(0, LiquidDockMath.tabIndexAtX(0f, slotWidthPx, tabCount))
        assertEquals(3, LiquidDockMath.tabIndexAtX(tabCount * slotWidthPx + 40f, slotWidthPx, tabCount))
    }

    @Test
    fun pillPositionDerivesStrictlyFromSelectedDestinationAndHoldDragFollowsFinger() {
        // Resting pill positions lock strictly to selectedTab * slotWidthPx
        assertEquals(0f, LiquidDockMath.offsetForTab(0, slotWidthPx, tabCount), 0.01f)
        assertEquals(200f, LiquidDockMath.offsetForTab(1, slotWidthPx, tabCount), 0.01f)
        assertEquals(400f, LiquidDockMath.offsetForTab(2, slotWidthPx, tabCount), 0.01f)
        assertEquals(600f, LiquidDockMath.offsetForTab(3, slotWidthPx, tabCount), 0.01f)

        // Grabbing inside active Automations pill (tab 1, pill [200..400]) preserves finger offset without jumping
        val startX = innerInsetPx + 260f
        val session = DockGestureSession(
            downX = startX,
            downY = 60f,
            initialSelectedTab = 1,
            slotWidthPx = slotWidthPx,
            innerInsetPx = innerInsetPx,
            tabCount = tabCount,
            touchSlopPx = touchSlopPx,
        )

        // Small jitter within touch slop stays pending (not yet dragging)
        assertEquals(DockGestureSession.MoveOutcome.Pending, session.onMove(startX + 4f, 62f))
        assertFalse(session.draggingHorizontally)

        // Horizontal slide right by 180px tracks smoothly at 200 + 180 = 380px (still hovering nearest tab 2)
        val drag1 = session.onMove(startX + 180f, 64f) as DockGestureSession.MoveOutcome.Dragging
        assertTrue(session.draggingHorizontally)
        assertEquals(380f, drag1.offsetPx, 0.5f)
        assertEquals(2, drag1.hoverTab)
        assertTrue(drag1.crossedTabBoundary)

        // Drag far past right edge clamps cleanly to 3 * slotWidthPx (600px)
        val dragPastRight = session.onMove(startX + 900f, 65f) as DockGestureSession.MoveOutcome.Dragging
        assertEquals(600f, dragPastRight.offsetPx, 0.5f)
        assertEquals(3, dragPastRight.hoverTab)

        // Slide back left to Activity slot (~410px) and release -> selects Activity (2)
        val dragBack = session.onMove(innerInsetPx + 470f, 62f) as DockGestureSession.MoveOutcome.Dragging
        assertEquals(410f, dragBack.offsetPx, 0.5f)
        assertEquals(2, dragBack.hoverTab)

        val release = session.onUp(upX = innerInsetPx + 470f, latestSelectedTab = 1) as DockGestureSession.ReleaseOutcome.Select
        assertEquals(2, release.tabIndex)
        assertTrue(release.changed)
    }

    @Test
    fun draggingFromNonActiveTabCentersPillAndInterruptedGesturesRestoreSafely() {
        // Resting at Home (0), pressing near Settings (x = 700) does not lock grab inside Home pill
        assertNull(LiquidDockMath.grabOffsetIfInsideActivePill(700f, activeTabIndex = 0, slotWidthPx, tabCount))

        // Vertical swipe cancels gesture cleanly and never changes destination
        val verticalCancelSession = DockGestureSession(
            downX = innerInsetPx + 300f,
            downY = 60f,
            initialSelectedTab = 1,
            slotWidthPx = slotWidthPx,
            innerInsetPx = innerInsetPx,
            tabCount = tabCount,
            touchSlopPx = touchSlopPx,
        )
        assertEquals(
            DockGestureSession.MoveOutcome.Cancelled,
            verticalCancelSession.onMove(innerInsetPx + 304f, 60f - touchSlopPx * 2.5f),
        )
        val afterVerticalCancel = verticalCancelSession.onUp(latestSelectedTab = 1)
        assertTrue(afterVerticalCancel is DockGestureSession.ReleaseOutcome.Cancelled)
        assertEquals(1, (afterVerticalCancel as DockGestureSession.ReleaseOutcome.Cancelled).restoreTab)

        // Mid-drag system cancellation restores the latest active destination cleanly
        val midDragCancel = DockGestureSession(
            downX = innerInsetPx + 100f,
            downY = 60f,
            initialSelectedTab = 0,
            slotWidthPx = slotWidthPx,
            innerInsetPx = innerInsetPx,
            tabCount = tabCount,
            touchSlopPx = touchSlopPx,
        )
        assertTrue(midDragCancel.onMove(innerInsetPx + 280f, 60f) is DockGestureSession.MoveOutcome.Dragging)
        val cancelled = midDragCancel.onCancel()
        assertEquals(0, cancelled.restoreTab)
        val upAfterCancel = midDragCancel.onUp(innerInsetPx + 280f, latestSelectedTab = 0)
        assertTrue(upAfterCancel is DockGestureSession.ReleaseOutcome.Cancelled)

        // Consumed pointer release (parent interception) never triggers tab navigation
        val consumedTap = DockGestureSession(
            downX = innerInsetPx + 500f,
            downY = 60f,
            initialSelectedTab = 0,
            slotWidthPx = slotWidthPx,
            innerInsetPx = innerInsetPx,
            tabCount = tabCount,
            touchSlopPx = touchSlopPx,
        )
        val upConsumed = consumedTap.onUp(upX = innerInsetPx + 500f, consumedByOther = true, latestSelectedTab = 0)
        assertTrue(upConsumed is DockGestureSession.ReleaseOutcome.Cancelled)
    }

    @Test
    fun stationaryHoldActivatesDragAtHeldTabAndGlidesCleanly() {
        // User touches down on Tab 2 (Activity, slot [400..600]) and holds stationary
        val touchX = innerInsetPx + 500f
        val session = DockGestureSession(
            downX = touchX,
            downY = 60f,
            initialSelectedTab = 0,
            slotWidthPx = slotWidthPx,
            innerInsetPx = innerInsetPx,
            tabCount = tabCount,
            touchSlopPx = touchSlopPx,
        )

        assertEquals(2, session.downTab)
        assertFalse(session.isHoldActivated)
        assertFalse(session.draggingHorizontally)

        // Stationary hold timeout expires -> onHoldActivated triggers
        val heldTab = session.onHoldActivated()
        assertEquals(2, heldTab)
        assertTrue(session.isHoldActivated)
        assertTrue(session.draggingHorizontally)
        assertEquals(400f, session.latestDragOffsetPx, 0.01f)
        assertEquals(2, session.lastHoverTab)

        // Any subsequent movement immediately yields Dragging (no touch slop required)
        val move = session.onMove(innerInsetPx + 520f, 60f)
        assertTrue(move is DockGestureSession.MoveOutcome.Dragging)
        val drag = move as DockGestureSession.MoveOutcome.Dragging
        assertEquals(420f, drag.offsetPx, 0.5f)
        assertEquals(2, drag.hoverTab)

        // Releasing selects held tab 2 immediately
        val release = session.onUp(upX = innerInsetPx + 520f, latestSelectedTab = 0)
        assertTrue(release is DockGestureSession.ReleaseOutcome.Select)
        val sel = release as DockGestureSession.ReleaseOutcome.Select
        assertEquals(2, sel.tabIndex)
        assertTrue(sel.changed)
    }

    @Test
    fun continuousDragAcrossAllFourTabsWithBoundaryCrossings() {
        // Touch down at Home (0) and drag all the way to Settings (3)
        val startX = innerInsetPx + 50f
        val session = DockGestureSession(
            downX = startX,
            downY = 60f,
            initialSelectedTab = 0,
            slotWidthPx = slotWidthPx,
            innerInsetPx = innerInsetPx,
            tabCount = tabCount,
            touchSlopPx = touchSlopPx,
        )

        // Start drag right
        val toAutomations = session.onMove(innerInsetPx + 300f, 60f) as DockGestureSession.MoveOutcome.Dragging
        assertEquals(1, toAutomations.hoverTab)
        assertTrue(toAutomations.crossedTabBoundary)

        // Move to Activity
        val toActivity = session.onMove(innerInsetPx + 500f, 60f) as DockGestureSession.MoveOutcome.Dragging
        assertEquals(2, toActivity.hoverTab)
        assertTrue(toActivity.crossedTabBoundary)

        // Move to Settings
        val toSettings = session.onMove(innerInsetPx + 700f, 60f) as DockGestureSession.MoveOutcome.Dragging
        assertEquals(3, toSettings.hoverTab)
        assertTrue(toSettings.crossedTabBoundary)

        // Slight movement within Settings does NOT trigger crossedTabBoundary again
        val withinSettings = session.onMove(innerInsetPx + 720f, 60f) as DockGestureSession.MoveOutcome.Dragging
        assertEquals(3, withinSettings.hoverTab)
        assertFalse(withinSettings.crossedTabBoundary)

        // Release at Settings selects Tab 3
        val release = session.onUp(upX = innerInsetPx + 720f, latestSelectedTab = 0) as DockGestureSession.ReleaseOutcome.Select
        assertEquals(3, release.tabIndex)
        assertTrue(release.changed)
    }

    @Test
    fun dragUpwardIntoScreenCancelsGestureSafelyAndRestoresInitialTab() {
        // Touch down on Automations (1)
        val startX = innerInsetPx + 300f
        val session = DockGestureSession(
            downX = startX,
            downY = 100f,
            initialSelectedTab = 1,
            slotWidthPx = slotWidthPx,
            innerInsetPx = innerInsetPx,
            tabCount = tabCount,
            touchSlopPx = touchSlopPx,
            cancelUpwardDistancePx = 140f,
        )

        // Horizontal drag to Activity (2)
        val drag = session.onMove(innerInsetPx + 500f, 95f) as DockGestureSession.MoveOutcome.Dragging
        assertEquals(2, drag.hoverTab)

        // Dragging upward by 160px (y = -60f, dy = -160f < -140f) cancels the gesture
        val cancelOutcome = session.onMove(innerInsetPx + 500f, -60f)
        assertEquals(DockGestureSession.MoveOutcome.Cancelled, cancelOutcome)
        assertTrue(session.cancelled)

        // OnUp after cancellation restores initialSelectedTab (1) without selecting hovered tab
        val release = session.onUp(upX = innerInsetPx + 500f, upY = -60f, latestSelectedTab = 1)
        assertTrue(release is DockGestureSession.ReleaseOutcome.Cancelled)
        assertEquals(1, (release as DockGestureSession.ReleaseOutcome.Cancelled).restoreTab)
    }

    @Test
    fun naturalThumbArcWithSlightVerticalDeviationDoesNotPrematurelyCancel() {
        // Natural thumb movement has slight vertical tilt (e.g. dx = 40px, dy = -10px)
        val startX = innerInsetPx + 100f
        val session = DockGestureSession(
            downX = startX,
            downY = 60f,
            initialSelectedTab = 0,
            slotWidthPx = slotWidthPx,
            innerInsetPx = innerInsetPx,
            tabCount = tabCount,
            touchSlopPx = touchSlopPx,
        )

        // Diagonal swipe where dx dominates dy
        val move = session.onMove(startX + 50f, 50f)
        assertTrue("Slight vertical deviation during swipe must not cancel horizontal drag", move is DockGestureSession.MoveOutcome.Dragging)
        assertFalse(session.cancelled)
    }

    @Test
    fun bottomScrollClearanceExceedsFloatingDockFootprintAcrossAllNavBarHeights() {
        listOf(0f, 24f, 48f, 64f).forEach { navBarBottomDp ->
            val dockOccupiedFromBottom =
                navBarBottomDp + LiquidDockMath.DOCK_OUTER_VERTICAL_MARGIN_DP * 2 + LiquidDockMath.DOCK_HEIGHT_DP
            val scrollClearance = LiquidDockMath.requiredScrollClearanceDp(navBarBottomDp)
            assertTrue(
                "Scroll clearance ($scrollClearance dp) must exceed dock + navBar ($dockOccupiedFromBottom dp)",
                scrollClearance >= dockOccupiedFromBottom + LiquidDockMath.DOCK_MIN_BREATHING_ROOM_DP,
            )
        }
    }

    @Test
    fun fastDragReleaseUsesFinalUpXAndSelectsImmediatelyWithoutWaitingForHold() {
        // Fast swipe starting at Home (0, center=100) directly to Settings (3) in a single quick move + release
        val startX = innerInsetPx + 100f
        val session = DockGestureSession(
            downX = startX,
            downY = 60f,
            initialSelectedTab = 0,
            slotWidthPx = slotWidthPx,
            innerInsetPx = innerInsetPx,
            tabCount = tabCount,
            touchSlopPx = touchSlopPx,
        )

        // Move past Activity (2) toward Settings (3) without waiting for HOLD_TIMEOUT_MS
        val move = session.onMove(innerInsetPx + 520f, 60f) as DockGestureSession.MoveOutcome.Dragging
        assertFalse(session.isHoldActivated)
        assertTrue(move.hoverTab == 2)

        // Finger flicks to Settings (upX = 690px) at release frame -> immediately resolves Settings (3)
        val release = session.onUp(upX = innerInsetPx + 690f, upY = 60f, latestSelectedTab = 0)
        assertTrue(release is DockGestureSession.ReleaseOutcome.Select)
        val sel = release as DockGestureSession.ReleaseOutcome.Select
        assertEquals(3, sel.tabIndex)
        assertTrue(sel.changed)
    }

    @Test
    fun releaseBetweenTabsResolvesNearestDestinationAndOffCenterHoldHasZeroJump() {
        // 1. Hold near right edge of Tab 1 ([200..400]) at 360px -> pill centers at 200px
        val holdSession = DockGestureSession(
            downX = innerInsetPx + 360f,
            downY = 60f,
            initialSelectedTab = 0,
            slotWidthPx = slotWidthPx,
            innerInsetPx = innerInsetPx,
            tabCount = tabCount,
            touchSlopPx = touchSlopPx,
        )
        assertEquals(1, holdSession.onHoldActivated())
        assertEquals(200f, holdSession.latestDragOffsetPx, 0.01f)

        // Moving 5px right to 365px must move pill smoothly to 205px (NO backward snap!)
        val move1 = holdSession.onMove(innerInsetPx + 365f, 60f) as DockGestureSession.MoveOutcome.Dragging
        assertEquals(205f, move1.offsetPx, 0.5f)

        // 2. Release right between Tab 1 and Tab 2: offset 295 -> nearest 1; offset 305 -> nearest 2
        assertEquals(1, LiquidDockMath.nearestTabForOffset(295f, slotWidthPx, tabCount))
        assertEquals(2, LiquidDockMath.nearestTabForOffset(305f, slotWidthPx, tabCount))

        // Drag to offset 305 (grabOffset = 160px, so localX = 465 -> 465 - 160 = 305) -> selects Activity (2)
        val dragMid = holdSession.onMove(innerInsetPx + 465f, 60f) as DockGestureSession.MoveOutcome.Dragging
        assertEquals(305f, dragMid.offsetPx, 0.5f)
        val selMid = holdSession.onUp(upX = innerInsetPx + 465f, upY = 60f, latestSelectedTab = 0) as DockGestureSession.ReleaseOutcome.Select
        assertEquals(2, selMid.tabIndex)
        assertTrue(selMid.changed)
    }

    @Test
    fun liftBeforeHoldTimeoutAndRapidTabChangesAlwaysSelectImmediately() {
        val sequence = listOf(3, 1, 2, 0, 3, 2, 0)
        var current = 0
        for (target in sequence) {
            val centerX = innerInsetPx + target * slotWidthPx + slotWidthPx / 2f
            val session = DockGestureSession(
                downX = centerX,
                downY = 60f,
                initialSelectedTab = current,
                slotWidthPx = slotWidthPx,
                innerInsetPx = innerInsetPx,
                tabCount = tabCount,
                touchSlopPx = touchSlopPx,
            )
            // Lift immediately before hold timeout with 2px micro-jitter
            session.onMove(centerX + 2f, 61f)
            assertFalse(session.isHoldActivated)
            assertFalse(session.draggingHorizontally)
            val release = session.onUp(upX = centerX + 2f, upY = 61f, latestSelectedTab = current)
            assertTrue(release is DockGestureSession.ReleaseOutcome.Select)
            val sel = release as DockGestureSession.ReleaseOutcome.Select
            assertEquals(target, sel.tabIndex)
            assertEquals(target != current, sel.changed)
            current = sel.tabIndex
        }
    }
}
