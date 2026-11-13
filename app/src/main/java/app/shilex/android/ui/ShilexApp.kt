package app.shilex.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import app.shilex.android.assistant.AssistantViewModel
import app.shilex.android.assistant.UiState
import app.shilex.android.assistant.VoiceService
import app.shilex.android.local.ActivityLog
import app.shilex.android.local.LocalPhase
import app.shilex.android.task.Task

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home),
    Automations("Automations", Icons.Filled.Star),
    Activity("Activity", Icons.Filled.List),
    Settings("Settings", Icons.Filled.Settings),
}

@Composable
fun ShilexApp(assistant: AssistantViewModel) {
    val state by assistant.state.collectAsStateWithLifecycle()
    val localPhase by assistant.localPhase.collectAsStateWithLifecycle()
    val activity by assistant.activity.collectAsStateWithLifecycle()
    val wakeWord by assistant.wakeWord.collectAsStateWithLifecycle()
    val level by assistant.level.collectAsStateWithLifecycle()
    val task by assistant.task.collectAsStateWithLifecycle()
    var onboarded by rememberSaveable { mutableStateOf(assistant.onboarded) }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    ShilexTheme {
        Surface(
            color = OledBlack,
            contentColor = PureWhite,
            modifier = Modifier.fillMaxSize(),
        ) {
            Content(assistant, state, localPhase, level, task, activity, wakeWord, onboarded, { onboarded = true }, tab) { tab = it }
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

    Scaffold(
        containerColor = OledBlack,
        bottomBar = {
            // iOS Liquid Glass Floating Dock
            val dockShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(dockShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xE610192C), // 90% translucent dark glass
                                Color(0xF2090F1B),
                            ),
                        ),
                    )
                    .border(
                        1.dp,
                        Brush.verticalGradient(
                            listOf(
                                Color(0x4DFFFFFF), // top specular light sheen
                                Color(0x1F38E5FF),
                            ),
                        ),
                        dockShape,
                    )
                    .navigationBarsPadding(),
            ) {
                NavigationBar(
                    containerColor = Color.Transparent,
                    tonalElevation = 0.dp,
                    modifier = Modifier.height(68.dp),
                ) {
                    Tab.entries.forEach {
                        val selected = tab == it.ordinal
                        NavigationBarItem(
                            selected = selected,
                            onClick = { setTab(it.ordinal) },
                            icon = {
                                Icon(
                                    it.icon,
                                    contentDescription = null,
                                    tint = if (selected) ElectricCyan else Muted,
                                )
                            },
                            label = {
                                Text(
                                    it.label,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) ElectricCyan else Muted,
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = Color(0x2638E5FF),
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .background(OledBlack)
                .padding(padding),
        ) {
            when (Tab.entries[tab]) {
                Tab.Home -> HomeScreen(
                    state = state,
                    localPhase = localPhase,
                    level = level,
                    task = task,
                    activity = activity,
                    wakeOn = assistant.wakeWordAvailable && wakeWord && VoiceService.hasMicPermission(LocalContext.current),
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
}
