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
}
