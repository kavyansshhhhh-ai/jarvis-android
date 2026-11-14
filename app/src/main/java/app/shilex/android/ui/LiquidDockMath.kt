package app.shilex.android.ui

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure layout, hit-testing, and hold-and-drag state machine for [FloatingLiquidGlassDock].
 * Shared directly by the Compose dock and JVM unit tests so tab selection, glass pill
 * positioning, drag tracking, and interruption recovery cannot diverge.
 */
object LiquidDockMath {
    const val DOCK_HEIGHT_DP = 66
    const val DOCK_OUTER_VERTICAL_MARGIN_DP = 10
    const val DOCK_MIN_BREATHING_ROOM_DP = 28
    const val SCROLL_CLEARANCE_DP = DOCK_HEIGHT_DP + DOCK_OUTER_VERTICAL_MARGIN_DP * 2 + DOCK_MIN_BREATHING_ROOM_DP
    const val HOLD_TIMEOUT_MS = 140L
    const val CANCEL_UPWARD_PULL_PX = 140f // ~48dp at 3x density: dragging far up into screen cancels

    /** Minimum bottom spacer/padding (dp) needed so scrolling clears the floating dock + nav bar. */
    fun requiredScrollClearanceDp(navBarBottomDp: Float): Float =
        SCROLL_CLEARANCE_DP + navBarBottomDp.coerceAtLeast(0f)

    /** Resting X offset (px relative to inner tab strip) for [tabIndex]. */
    fun offsetForTab(tabIndex: Int, slotWidthPx: Float, tabCount: Int): Float {
        if (tabCount <= 1 || slotWidthPx <= 0f) return 0f
        return tabIndex.coerceIn(0, tabCount - 1) * slotWidthPx
    }

    /** Clamps any pill X offset within [0f, (tabCount - 1) * slotWidthPx]. */
    fun clampOffset(offsetPx: Float, slotWidthPx: Float, tabCount: Int): Float {
        if (tabCount <= 1 || slotWidthPx <= 0f) return 0f
        return offsetPx.coerceIn(0f, (tabCount - 1) * slotWidthPx)
    }

    /** Resolves which tab index a pointer X coordinate inside the inner dock strip hits. */
    fun tabIndexAtX(localXPx: Float, slotWidthPx: Float, tabCount: Int): Int {
        if (tabCount <= 1 || slotWidthPx <= 0f) return 0
        return (localXPx / slotWidthPx).toInt().coerceIn(0, tabCount - 1)
    }

    /** Records grab offset inside the currently active pill when pressed inside it; else null. */
    fun grabOffsetIfInsideActivePill(
        downLocalXPx: Float,
        activeTabIndex: Int,
        slotWidthPx: Float,
        tabCount: Int,
    ): Float? {
        if (slotWidthPx <= 0f || tabCount <= 0) return null
        val pillStart = offsetForTab(activeTabIndex, slotWidthPx, tabCount)
        val pillEnd = pillStart + slotWidthPx
        return if (downLocalXPx in pillStart..pillEnd) downLocalXPx - pillStart else null
    }

    /** Continuous pill X offset while dragging horizontally across the dock. */
    fun dragOffsetForTouch(
        currentLocalXPx: Float,
        grabOffsetInPillPx: Float?,
        slotWidthPx: Float,
        tabCount: Int,
    ): Float {
        if (tabCount <= 1 || slotWidthPx <= 0f) return 0f
        val raw = if (grabOffsetInPillPx != null) {
            currentLocalXPx - grabOffsetInPillPx
        } else {
            currentLocalXPx - slotWidthPx / 2f
        }
        return clampOffset(raw, slotWidthPx, tabCount)
    }

    /** Nearest valid tab index for a pill X offset. */
    fun nearestTabForOffset(offsetPx: Float, slotWidthPx: Float, tabCount: Int): Int {
        if (tabCount <= 1 || slotWidthPx <= 0f) return 0
        val clamped = clampOffset(offsetPx, slotWidthPx, tabCount)
        return (clamped / slotWidthPx).roundToInt().coerceIn(0, tabCount - 1)
    }
}

/**
 * Pure per-gesture state tracker for hold, tap, horizontal drag, and safe cancellation.
 */
class DockGestureSession(
    downX: Float,
    downY: Float,
    private val initialSelectedTab: Int,
    private val slotWidthPx: Float,
    private val innerInsetPx: Float,
    private val tabCount: Int,
    private val touchSlopPx: Float,
    private val cancelUpwardDistancePx: Float = LiquidDockMath.CANCEL_UPWARD_PULL_PX,
) {
    sealed interface MoveOutcome {
        data object Pending : MoveOutcome
        data object Cancelled : MoveOutcome
        data class Dragging(
            val offsetPx: Float,
            val hoverTab: Int,
            val crossedTabBoundary: Boolean,
        ) : MoveOutcome
    }

    sealed interface ReleaseOutcome {
        data class Cancelled(val restoreTab: Int) : ReleaseOutcome
        data class Select(val tabIndex: Int, val changed: Boolean) : ReleaseOutcome
    }

    private val startX = downX
    private val startY = downY
    private val downLocalX = toLocalX(downX)

    val downTab: Int = LiquidDockMath.tabIndexAtX(downLocalX, slotWidthPx, tabCount)

    private val grabOffsetPx = LiquidDockMath.grabOffsetIfInsideActivePill(
        downLocalXPx = downLocalX,
        activeTabIndex = initialSelectedTab,
        slotWidthPx = slotWidthPx,
        tabCount = tabCount,
    )

    var draggingHorizontally: Boolean = false
        private set

    var isHoldActivated: Boolean = false
        private set

    var cancelled: Boolean = false
        private set

    var lastHoverTab: Int = initialSelectedTab.coerceIn(0, (tabCount - 1).coerceAtLeast(0))
        private set

    var latestDragOffsetPx: Float = LiquidDockMath.offsetForTab(initialSelectedTab, slotWidthPx, tabCount)
        private set

    private var lastLocalX: Float = downLocalX
    private var lastY: Float = downY
    private var holdGrabOffsetPx: Float? = null

    private fun toLocalX(x: Float): Float =
        (x - innerInsetPx).coerceIn(0f, (tabCount * slotWidthPx).coerceAtLeast(0f))

    /**
     * Activates drag mode when the user touches and holds stationary for [LiquidDockMath.HOLD_TIMEOUT_MS].
     * Returns the held destination tab so the lens can smoothly glide to it.
     */
    fun onHoldActivated(): Int {
        if (cancelled) return initialSelectedTab
        isHoldActivated = true
        draggingHorizontally = true
        lastHoverTab = downTab
        val targetOffset = LiquidDockMath.offsetForTab(downTab, slotWidthPx, tabCount)
        latestDragOffsetPx = targetOffset
        holdGrabOffsetPx = (lastLocalX - targetOffset).coerceIn(0f, slotWidthPx.coerceAtLeast(0f))
        return downTab
    }

    fun onMove(x: Float, y: Float, consumedByOther: Boolean = false): MoveOutcome {
        if (cancelled) return MoveOutcome.Cancelled

        val localX = toLocalX(x)
        lastLocalX = localX
        lastY = y
        val dx = x - startX
        val dy = y - startY

        // Pulling far up into screen content (> cancelUpwardDistancePx above dock) cancels gesture safely
        if (dy < -cancelUpwardDistancePx) {
            cancelled = true
            return MoveOutcome.Cancelled
        }

        if (!draggingHorizontally) {
            if (consumedByOther) {
                cancelled = true
                return MoveOutcome.Cancelled
            }
            // Vertical swipe before horizontal drag starts cancels to let screen scroll
            if (abs(dy) > touchSlopPx * 1.6f && abs(dy) > abs(dx) * 1.5f) {
                cancelled = true
                return MoveOutcome.Cancelled
            }
            // Responsive horizontal threshold to start continuous tracking cleanly
            if (abs(dx) > touchSlopPx * 0.35f) {
                draggingHorizontally = true
            } else {
                return MoveOutcome.Pending
            }
        }

        val targetOffset = LiquidDockMath.dragOffsetForTouch(
            currentLocalXPx = localX,
            grabOffsetInPillPx = if (isHoldActivated) (holdGrabOffsetPx ?: (slotWidthPx / 2f)) else grabOffsetPx,
            slotWidthPx = slotWidthPx,
            tabCount = tabCount,
        )
        latestDragOffsetPx = targetOffset
        val nearest = LiquidDockMath.nearestTabForOffset(targetOffset, slotWidthPx, tabCount)
        val crossed = nearest != lastHoverTab
        if (crossed) lastHoverTab = nearest
        return MoveOutcome.Dragging(
            offsetPx = targetOffset,
            hoverTab = nearest,
            crossedTabBoundary = crossed,
        )
    }

    fun onCancel(): ReleaseOutcome.Cancelled {
        cancelled = true
        return ReleaseOutcome.Cancelled(initialSelectedTab)
    }

    fun onUp(
        upX: Float? = null,
        upY: Float? = null,
        consumedByOther: Boolean = false,
        latestSelectedTab: Int = initialSelectedTab,
    ): ReleaseOutcome {
        if (cancelled || consumedByOther) {
            cancelled = true
            return ReleaseOutcome.Cancelled(initialSelectedTab)
        }
        val effectiveY = upY ?: lastY
        if (effectiveY - startY < -cancelUpwardDistancePx) {
            cancelled = true
            return ReleaseOutcome.Cancelled(initialSelectedTab)
        }

        val effectiveLocalX = upX?.let(::toLocalX) ?: lastLocalX
        val resolvedTab = if (draggingHorizontally) {
            if (upX != null) {
                latestDragOffsetPx = LiquidDockMath.dragOffsetForTouch(
                    currentLocalXPx = effectiveLocalX,
                    grabOffsetInPillPx = if (isHoldActivated) (holdGrabOffsetPx ?: (slotWidthPx / 2f)) else grabOffsetPx,
                    slotWidthPx = slotWidthPx,
                    tabCount = tabCount,
                )
            }
            LiquidDockMath.nearestTabForOffset(latestDragOffsetPx, slotWidthPx, tabCount)
        } else {
            LiquidDockMath.tabIndexAtX(effectiveLocalX, slotWidthPx, tabCount)
        }
        return ReleaseOutcome.Select(
            tabIndex = resolvedTab,
            changed = resolvedTab != latestSelectedTab,
        )
    }
}
