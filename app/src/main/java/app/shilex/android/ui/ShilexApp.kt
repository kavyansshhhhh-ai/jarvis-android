package app.shilex.android.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
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
                            dotSize = assistant.wakeDotSize,
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
        // Full-bleed scrollable content layer (scrolls smoothly behind the floating Liquid Glass capsule dock)
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
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val dir = if (targetState >= initialState) 1 else -1
                    (
                        slideInHorizontally(animationSpec = tween(240)) { width -> dir * (width / 14) } +
                            fadeIn(animationSpec = tween(220)) +
                            scaleIn(initialScale = 0.985f, animationSpec = tween(240))
                        ) togetherWith (
                        slideOutHorizontally(animationSpec = tween(200)) { width -> -dir * (width / 18) } +
                            fadeOut(animationSpec = tween(180)) +
                            scaleOut(targetScale = 0.985f, animationSpec = tween(200))
                        )
                },
                label = "tabTransition",
            ) { currentTab ->
                when (Tab.entries[currentTab]) {
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
        }

        // Interactive Finger-Tracking Floating Liquid Glass Capsule Navigation Dock
        FloatingLiquidGlassDock(
            selectedTab = tab,
            onSelectTab = setTab,
            backdropLayer = contentLayer,
            contentOffset = contentOffset,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
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
    val scope = rememberCoroutineScope()

    var isPressed by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var previewTab by remember { mutableIntStateOf(selectedTab) }
    val dockScale by animateFloatAsState(
        targetValue = if (isPressed) 1.018f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "dockPressScale",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(66.dp)
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

        // Finger-tracking sliding Liquid Glass lens + tab items
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            val count = Tab.entries.size
            val slotWidth = maxWidth / count
            val density = LocalDensity.current
            val slotWidthPx = with(density) { slotWidth.toPx().coerceAtLeast(1f) }
            val padPx = with(density) { 2.dp.toPx() }
            val lensX = remember { Animatable(selectedTab * slotWidthPx) }

            LaunchedEffect(selectedTab, slotWidthPx, isDragging) {
                if (!isDragging) {
                    previewTab = selectedTab
                    lensX.animateTo(
                        targetValue = selectedTab * slotWidthPx,
                        animationSpec = spring(
                            dampingRatio = 0.72f,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    )
                }
            }

            val activeDisplayTab = if (isDragging) previewTab else selectedTab
            val lensScale by animateFloatAsState(
                targetValue = if (isPressed || isDragging) 1.045f else 1f,
                animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium),
                label = "lensScale",
            )
            val lensShape = RoundedCornerShape(28.dp)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(count, slotWidthPx) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            isPressed = true
                            val startPos = down.position
                            var draggingHorizontally = false
                            var cancelledByVerticalScroll = false
                            var lastHoverTab = selectedTab
                            var currentX = down.position.x

                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                currentX = change.position.x
                                val dx = currentX - startPos.x
                                val dy = change.position.y - startPos.y

                                if (!draggingHorizontally && abs(dy) > viewConfiguration.touchSlop * 1.4f && abs(dy) > abs(dx) * 1.3f) {
                                    cancelledByVerticalScroll = true
                                    break
                                }

                                if (!draggingHorizontally && abs(dx) > viewConfiguration.touchSlop * 0.6f && abs(dx) >= abs(dy)) {
                                    draggingHorizontally = true
                                    isDragging = true
                                }

                                if (draggingHorizontally) {
                                    change.consume()
                                    val maxOffset = (count - 1) * slotWidthPx
                                    val targetOffset = (currentX - slotWidthPx / 2f).coerceIn(0f, maxOffset)
                                    val nearest = (targetOffset / slotWidthPx).roundToInt().coerceIn(0, count - 1)
                                    if (nearest != lastHoverTab) {
                                        lastHoverTab = nearest
                                        previewTab = nearest
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    scope.launch {
                                        lensX.snapTo(targetOffset)
                                    }
                                }
                            }

                            isPressed = false
                            val finalTab = when {
                                cancelledByVerticalScroll -> selectedTab
                                draggingHorizontally -> lastHoverTab
                                else -> ((currentX / slotWidthPx).toInt()).coerceIn(0, count - 1)
                            }
                            isDragging = false
                            if (!cancelledByVerticalScroll) {
                                if (finalTab != selectedTab) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSelectTab(finalTab)
                                }
                            }
                            scope.launch {
                                lensX.animateTo(
                                    targetValue = finalTab * slotWidthPx,
                                    animationSpec = spring(
                                        dampingRatio = 0.72f,
                                        stiffness = Spring.StiffnessMediumLow,
                                    ),
                                )
                            }
                        }
                    },
            ) {
                // Luminous frosted Liquid Glass capsule lens behind hovered/active tab
                Box(
                    modifier = Modifier
                        .offset { IntOffset((lensX.value + padPx).roundToInt(), 0) }
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
                    modifier = Modifier.fillMaxSize(),
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
                                .semantics {
                                    role = Role.Tab
                                    this.selected = (selectedTab == item.ordinal)
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label,
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
