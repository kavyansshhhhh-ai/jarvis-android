package app.shilex.android.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shilex.android.assistant.AssistantViewModel
import app.shilex.android.assistant.UiState
import app.shilex.android.assistant.VoiceService
import app.shilex.android.local.ActivityLog
import app.shilex.android.local.LocalPhase
import app.shilex.android.orb.TinyWakeDot
import app.shilex.android.task.Task
import app.shilex.android.wake.WakeIndicator
import app.shilex.android.wake.WakeIndicatorController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home),
    Automations("Automations", Icons.Filled.Star),
    Activity("Activity", Icons.AutoMirrored.Filled.List),
    Settings("Settings", Icons.Filled.Settings),
}

@Composable
fun ShilexApp(assistant: AssistantViewModel) {
    val state by assistant.state.collectAsStateWithLifecycle()
    val localPhase by assistant.localPhase.collectAsStateWithLifecycle()
    val activity by assistant.activity.collectAsStateWithLifecycle()
    val wakeWord by assistant.wakeWord.collectAsStateWithLifecycle()
    val wakeDot by assistant.wakeDot.collectAsStateWithLifecycle()
    val wakeDotSize by assistant.wakeDotSizeState.collectAsStateWithLifecycle()
    val level by assistant.level.collectAsStateWithLifecycle()
    val task by assistant.task.collectAsStateWithLifecycle()
    var onboarded by rememberSaveable { mutableStateOf(assistant.onboarded) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var modalOverlayActive by remember { mutableStateOf(false) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        assistant.onPhotoPicked(uri)
    }

    LaunchedEffect(state.photoPickRequestId) {
        if (state.photoPickRequestId > 0L) {
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    LaunchedEffect(wakeDot.visible, wakeDot.triggerId, wakeDot.durationMs) {
        if (wakeDot.visible) {
            delay(WakeIndicatorController.cleanupDelayMs(wakeDot.durationMs))
            WakeIndicator.dismiss()
        }
    }

    ShilexTheme {
        CompositionLocalProvider(LocalModalOverlayActive provides { modalOverlayActive = it }) {
            Surface(
                color = OledBlack,
                contentColor = PureWhite,
                modifier = Modifier.fillMaxSize(),
            ) {
                val modalBlur by animateDpAsState(
                    targetValue = if (modalOverlayActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 16.dp else 0.dp,
                    animationSpec = tween(durationMillis = 220),
                    label = "modalBlur",
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(if (modalBlur > 0.dp) Modifier.blur(modalBlur) else Modifier),
                ) {
                    Content(
                        assistant = assistant,
                        state = state,
                        localPhase = localPhase,
                        level = level,
                        task = task,
                        activity = activity,
                        wakeWord = wakeWord,
                        onboarded = onboarded,
                        onOnboarded = { onboarded = true },
                        tab = tab,
                        setTab = { tab = it },
                    )
                    if (wakeDot.visible && assistant.wakeIndicator) {
                        TinyWakeDot(
                            triggerId = wakeDot.triggerId,
                            durationMs = wakeDot.durationMs,
                            dotSize = wakeDotSize,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .statusBarsPadding()
                                .padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Content(
    assistant: AssistantViewModel,
    state: UiState,
    localPhase: LocalPhase,
    level: Float,
    task: Task?,
    activity: List<ActivityLog.Entry>,
    wakeWord: Boolean,
    onboarded: Boolean,
    onOnboarded: () -> Unit,
    tab: Int,
    setTab: (Int) -> Unit,
) {
    if (!onboarded || !state.hasKey) {
        Box(Modifier.fillMaxSize().background(OledBlack).safeDrawingPadding()) {
            Onboarding(assistant, state.hasKey) {
                assistant.onboarded = true
                onOnboarded()
            }
        }
        return
    }

    val context = LocalContext.current
    val supportsBackdropBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val contentLayer = if (supportsBackdropBlur) rememberBackdropLayer() else null
    var contentOffset by remember { mutableStateOf(Offset.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .background(OledBlack),
    ) {
        val safeTab = tab.coerceIn(0, Tab.entries.lastIndex)

        // Full-bleed scrollable content layer (instant zero-delay swap when tab changes)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { contentOffset = it.positionInRoot() }
                .then(
                    if (contentLayer != null) {
                        Modifier.drawWithContent {
                            contentLayer.record {
                                this@drawWithContent.drawContent()
                            }
                            drawLayer(contentLayer)
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            when (Tab.entries[safeTab]) {
                Tab.Home -> HomeScreen(
                    state = state,
                    localPhase = localPhase,
                    level = level,
                    task = task,
                    activity = activity,
                    wakeOn = assistant.wakeWordAvailable && wakeWord && VoiceService.hasMicPermission(context),
                    onStart = assistant::start,
                    onStop = assistant::stop,
                    onInterrupt = assistant::interrupt,
                    onMicDenied = assistant::micUnavailable,
                    onPermissionAnswered = assistant::permissionAnswered,
                    onQuick = assistant::quick,
                    onSeeActivity = { setTab(Tab.Activity.ordinal) },
                    favourites = remember(activity.size) { assistant.favourites() },
                )
                Tab.Automations -> AutomationsScreen()
                Tab.Activity -> ActivityScreen(activity, assistant::clearActivity)
                Tab.Settings -> SettingsScreen(assistant)
            }
        }

        // Interactive Finger-Tracking Floating Liquid Glass Capsule Navigation Dock
        FloatingLiquidGlassDock(
            selectedTab = safeTab,
            onSelectTab = { next ->
                val clamped = next.coerceIn(0, Tab.entries.lastIndex)
                if (clamped != safeTab) setTab(clamped)
            },
            backdropLayer = contentLayer,
            contentOffset = contentOffset,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = FloatingDockOuterVerticalMargin),
        )
    }
}

@Composable
private fun FloatingLiquidGlassDock(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    backdropLayer: GraphicsLayer?,
    contentOffset: Offset,
    modifier: Modifier = Modifier,
) {
    val dockShape = RoundedCornerShape(34.dp)
    var dockOffset by remember { mutableStateOf(Offset.Zero) }
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    var isPressed by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var hoverTabState by remember { mutableStateOf<Int?>(null) }

    val dockScale by animateFloatAsState(
        targetValue = if (isPressed) 1.018f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "dockPressScale",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(FloatingDockHeight)
            .graphicsLayer {
                scaleX = dockScale
                scaleY = dockScale
            }
            .onGloballyPositioned { dockOffset = it.positionInRoot() }
            .shadow(
                elevation = 20.dp,
                shape = dockShape,
                ambientColor = Color.Black,
                spotColor = Color.Black,
            )
            .clip(dockShape),
    ) {
        // Real GPU backdrop blur on Android 12+ sampling live scrolling content underneath
        if (backdropLayer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .blur(24.dp)
                    .drawBehind {
                        if (backdropLayer.size.width > 0 && backdropLayer.size.height > 0) {
                            translate(
                                left = contentOffset.x - dockOffset.x,
                                top = contentOffset.y - dockOffset.y,
                            ) {
                                drawLayer(backdropLayer)
                            }
                        }
                    },
            )
        }

        // Smoked obsidian glass tint + top specular rim
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(GlassDockSurfaceGradient)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0x1CFFFFFF),
                            Color.Transparent,
                            Color(0x0AFFFFFF),
                        ),
                    ),
                )
                .border(1.dp, GlassDockBorderGradient, dockShape),
        )

        // Finger-tracking sliding Liquid Glass lens + tab items (single source of truth = selectedTab)
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.CenterStart,
        ) {
            val count = Tab.entries.size
            val innerInsetDp = 6.dp
            val stripWidth = (maxWidth - innerInsetDp * 2).coerceAtLeast(1.dp)
            val slotWidth = stripWidth / count
            val density = LocalDensity.current
            val innerInsetPx = with(density) { innerInsetDp.toPx() }
            val slotWidthPx = with(density) { slotWidth.toPx().coerceAtLeast(1f) }
            val padPx = with(density) { 2.dp.toPx() }
            val cancelUpwardPx = with(density) { 48.dp.toPx() }

            val currentSelectedTab by rememberUpdatedState(selectedTab)
            val currentOnSelectTab by rememberUpdatedState(onSelectTab)
            val currentSlotWidthPx by rememberUpdatedState(slotWidthPx)
            val currentInnerInsetPx by rememberUpdatedState(innerInsetPx)
            val currentCancelUpwardPx by rememberUpdatedState(cancelUpwardPx)

            val lensX = remember(count) {
                Animatable(LiquidDockMath.offsetForTab(selectedTab, slotWidthPx, count))
            }
            var dragOffsetPx by remember {
                mutableFloatStateOf(LiquidDockMath.offsetForTab(selectedTab, slotWidthPx, count))
            }
            var pendingTargetTab by remember { mutableStateOf<Int?>(null) }
            var settlingTargetTab by remember { mutableStateOf<Int?>(null) }
            var layoutSnapped by remember { mutableStateOf(false) }
            var lensJob by remember { mutableStateOf<Job?>(null) }

            LaunchedEffect(selectedTab, slotWidthPx) {
                if (pendingTargetTab == selectedTab) {
                    pendingTargetTab = null
                }
                if (!layoutSnapped && slotWidthPx > 1f) {
                    lensJob?.cancel()
                    val snapOffset = LiquidDockMath.offsetForTab(currentSelectedTab, slotWidthPx, count)
                    lensX.snapTo(snapOffset)
                    dragOffsetPx = snapOffset
                    layoutSnapped = true
                } else if (!isDragging && !isPressed && slotWidthPx > 1f) {
                    if (settlingTargetTab == selectedTab) {
                        // Already animating to this exact tab from finger release
                        settlingTargetTab = null
                    } else {
                        settlingTargetTab = null
                        lensJob?.cancel()
                        lensJob = coroutineScope.launch {
                            lensX.animateTo(
                                targetValue = LiquidDockMath.offsetForTab(selectedTab, slotWidthPx, count),
                                animationSpec = spring(
                                    dampingRatio = 0.82f,
                                    stiffness = Spring.StiffnessMedium,
                                ),
                            )
                        }
                    }
                }
            }

            val activeDisplayTab = when {
                isDragging -> hoverTabState ?: selectedTab
                pendingTargetTab != null -> pendingTargetTab ?: selectedTab
                else -> selectedTab
            }

            val lensScale by animateFloatAsState(
                targetValue = if (isPressed || isDragging) 1.045f else 1f,
                animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium),
                label = "lensScale",
            )
            val lensShape = RoundedCornerShape(28.dp)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(count) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val slotW = currentSlotWidthPx
                            val insetPx = currentInnerInsetPx
                            val session = DockGestureSession(
                                downX = down.position.x,
                                downY = down.position.y,
                                initialSelectedTab = currentSelectedTab,
                                slotWidthPx = slotW,
                                innerInsetPx = insetPx,
                                tabCount = count,
                                touchSlopPx = viewConfiguration.touchSlop,
                                cancelUpwardDistancePx = currentCancelUpwardPx,
                            )

                            isPressed = true
                            pendingTargetTab = null
                            var releaseX = down.position.x
                            var releaseY = down.position.y
                            var releaseConsumed = false

                            var holdJob: Job? = coroutineScope.launch {
                                delay(LiquidDockMath.HOLD_TIMEOUT_MS)
                                if (session.cancelled || !isPressed) return@launch
                                val heldTab = session.onHoldActivated()
                                hoverTabState = heldTab
                                dragOffsetPx = session.latestDragOffsetPx
                                isDragging = true
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                lensJob?.cancel()
                            }

                            try {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null) {
                                        session.onCancel()
                                        break
                                    }
                                    releaseX = change.position.x
                                    releaseY = change.position.y
                                    if (!change.pressed) {
                                        releaseConsumed = change.isConsumed
                                        if (!releaseConsumed) change.consume()
                                        break
                                    }

                                    when (
                                        val outcome = session.onMove(
                                            x = change.position.x,
                                            y = change.position.y,
                                            consumedByOther = change.isConsumed,
                                        )
                                    ) {
                                        DockGestureSession.MoveOutcome.Pending -> Unit
                                        DockGestureSession.MoveOutcome.Cancelled -> {
                                            holdJob?.cancel()
                                            holdJob = null
                                            break
                                        }
                                        is DockGestureSession.MoveOutcome.Dragging -> {
                                            change.consume()
                                            holdJob?.cancel()
                                            holdJob = null
                                            // Update offset synchronously with zero coroutine allocation per frame
                                            dragOffsetPx = outcome.offsetPx
                                            isDragging = true
                                            if (outcome.crossedTabBoundary || hoverTabState != outcome.hoverTab) {
                                                hoverTabState = outcome.hoverTab
                                                if (outcome.crossedTabBoundary) {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                            }
                                        }
                                    }
                                }

                                holdJob?.cancel()
                                holdJob = null

                                val wasDragging = isDragging
                                val startOffsetForSettle = if (wasDragging) dragOffsetPx else lensX.value

                                val release = session.onUp(
                                    upX = releaseX,
                                    upY = releaseY,
                                    consumedByOther = releaseConsumed,
                                    latestSelectedTab = currentSelectedTab,
                                )

                                val targetTab = when (release) {
                                    is DockGestureSession.ReleaseOutcome.Select -> release.tabIndex
                                    is DockGestureSession.ReleaseOutcome.Cancelled -> release.restoreTab
                                }

                                // Lock highlight & trigger destination switch IMMEDIATELY on release
                                if (release is DockGestureSession.ReleaseOutcome.Select) {
                                    settlingTargetTab = release.tabIndex
                                    if (release.changed) {
                                        pendingTargetTab = release.tabIndex
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        currentOnSelectTab(release.tabIndex)
                                    }
                                } else {
                                    settlingTargetTab = null
                                    pendingTargetTab = null
                                }

                                isPressed = false
                                isDragging = false
                                hoverTabState = null

                                // Settle lens indicator smoothly in background without blocking navigation
                                val destinationOffset = LiquidDockMath.offsetForTab(targetTab, slotW, count)
                                lensJob?.cancel()
                                lensJob = coroutineScope.launch {
                                    if (wasDragging) {
                                        lensX.snapTo(startOffsetForSettle)
                                    }
                                    lensX.animateTo(
                                        targetValue = destinationOffset,
                                        animationSpec = spring(
                                            dampingRatio = 0.82f,
                                            stiffness = Spring.StiffnessMedium,
                                        ),
                                    )
                                }
                            } finally {
                                holdJob?.cancel()
                                holdJob = null
                                isPressed = false
                                isDragging = false
                                hoverTabState = null
                            }
                        }
                    },
            ) {
                // Luminous frosted Liquid Glass capsule lens behind hovered/active tab
                Box(
                    modifier = Modifier
                        .offset {
                            val xPx = if (isDragging) dragOffsetPx else lensX.value
                            IntOffset((xPx + innerInsetPx + padPx).roundToInt(), 0)
                        }
                        .align(Alignment.CenterStart)
                        .width((slotWidth - 4.dp).coerceAtLeast(1.dp))
                        .height(54.dp)
                        .graphicsLayer {
                            scaleX = lensScale
                            scaleY = lensScale
                        }
                        .clip(lensShape)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    if (isPressed || isDragging) Color(0x40FFFFFF) else Color(0x30FFFFFF),
                                    Color(0x14FFFFFF),
                                ),
                            ),
                        )
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    ElectricCyan.copy(alpha = if (isPressed || isDragging) 0.22f else 0.14f),
                                    NeonViolet.copy(alpha = 0.08f),
                                    Color.Transparent,
                                ),
                            ),
                        )
                        .border(
                            0.9.dp,
                            Brush.verticalGradient(
                                listOf(
                                    Color(0x7AFFFFFF),
                                    Color(0x22FFFFFF),
                                    Color(0x3BFFFFFF),
                                ),
                            ),
                            lensShape,
                        ),
                )

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = innerInsetDp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Tab.entries.forEach { item ->
                        val selected = activeDisplayTab == item.ordinal
                        val contentScale by animateFloatAsState(
                            targetValue = if (selected) 1.05f else 0.96f,
                            animationSpec = spring(
                                dampingRatio = 0.75f,
                                stiffness = Spring.StiffnessMedium,
                            ),
                            label = "tabScale",
                        )
                        val tint by animateColorAsState(
                            targetValue = if (selected) PureWhite else SlateMuted,
                            animationSpec = tween(160),
                            label = "tabTint",
                        )

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .scale(contentScale)
                                .semantics(mergeDescendants = true) {
                                    role = Role.Tab
                                    this.selected = (selectedTab == item.ordinal)
                                    contentDescription = item.label
                                    onClick {
                                        if (item.ordinal != currentSelectedTab) {
                                            currentOnSelectTab(item.ordinal)
                                        }
                                        true
                                    }
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(21.dp),
                            )
                            Text(
                                text = item.label,
                                fontSize = 11.sp,
                                lineHeight = 13.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                color = tint,
                                maxLines = 1,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberBackdropLayer(): GraphicsLayer {
    val graphicsContext = LocalGraphicsContext.current
    val layer = remember(graphicsContext) { graphicsContext.createGraphicsLayer() }
    DisposableEffect(graphicsContext, layer) {
        onDispose { graphicsContext.releaseGraphicsLayer(layer) }
    }
    return layer
}
