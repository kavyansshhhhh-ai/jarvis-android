package app.shilex.android.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.shilex.android.Branding
import app.shilex.android.assistant.Line
import app.shilex.android.assistant.Phase
import app.shilex.android.assistant.UiState
import app.shilex.android.learn.HabitKeys
import app.shilex.android.local.ActivityLog
import app.shilex.android.local.LocalCommand
import app.shilex.android.local.LocalPhase
import app.shilex.android.local.MediaKey
import app.shilex.android.task.Task
import app.shilex.android.wake.WakeWordModel

private data class Status(val title: String, val hint: String, val badge: String, val orb: Phase, val live: Boolean)

private fun status(state: UiState, local: LocalPhase, wakeOn: Boolean): Status = when {
    local == LocalPhase.Listening -> Status("Listening", "Say a command. Simple ones run directly on this phone.", "On phone", Phase.Listening, true)
    local == LocalPhase.Doing -> Status("Working", "Executing on this phone…", "On phone", Phase.Connecting, true)
    local == LocalPhase.Speaking -> Status("Speaking", "Answered on this phone.", "On phone", Phase.Speaking, true)
    state.phase == Phase.Connecting -> Status("Thinking", "Connecting to Gemini intelligence…", "Gemini", Phase.Connecting, true)
    state.phase == Phase.Listening -> Status("Listening", "Talk any time, in English, Hindi or Hinglish.", "Gemini", Phase.Listening, true)
    state.phase == Phase.Speaking -> Status("Speaking", "Tap the orb to cut in.", "Gemini", Phase.Speaking, true)
    else -> Status(
        "Standby",
        state.problem ?: if (wakeOn) "Say \"${WakeWordModel.PHRASE}\", or tap the mic." else "Tap the mic to talk.",
        if (wakeOn) "Wake word on" else "Wake word off",
        Phase.Off,
        false,
    )
}

/**
 * Premium iOS Liquid Glass Home Screen for JARVIS.
 */
@Composable
fun HomeScreen(
    state: UiState,
    localPhase: LocalPhase,
    level: Float,
    task: Task?,
    activity: List<ActivityLog.Entry>,
    wakeOn: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onInterrupt: () -> Unit,
    onMicDenied: () -> Unit,
    onPermissionAnswered: (Boolean) -> Unit,
    onQuick: (LocalCommand) -> String,
    onSeeActivity: () -> Unit,
    favourites: List<LocalCommand> = emptyList(),
) {
    val context = LocalContext.current
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onStart() else onMicDenied()
    }
    val askForTool = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onPermissionAnswered)
    LaunchedEffect(state.permissionNeeded) {
        state.permissionNeeded?.let(askForTool::launch)
    }
    val startListening = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) onStart() else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }
    val now = status(state, localPhase, wakeOn)
    val problem = state.problem != null && !now.live

    Column(
        Modifier
            .fillMaxSize()
            .background(OledBlack)
            .padding(horizontal = 20.dp),
    ) {
        // Floating Top Glass Bar
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    Branding.ASSISTANT_NAME,
                    style = MaterialTheme.typography.titleLarge,
                    color = PureWhite,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                )
                Box(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(6.dp)
                        .background(if (now.live) ElectricCyan else Color(0x33FFFFFF), CircleShape),
                )
            }
            Pill(now.badge, if (now.live) ElectricCyan else Muted)
        }

        // Central Liquid Glass AI Orb
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Orb(
                phase = now.orb,
                level = level,
                modifier = Modifier
                    .size(240.dp)
                    .semantics { contentDescription = "${Branding.ASSISTANT_NAME}: ${now.title}" }
                    .clickable(remember { MutableInteractionSource() }, indication = null) {
                        when (state.phase) {
                            Phase.Off -> if (localPhase == LocalPhase.Idle) startListening()
                            Phase.Speaking -> onInterrupt()
                            else -> Unit
                        }
                    },
            )
        }

        // Status Title & Subtitle with smooth transitions
        AnimatedContent(now.title, label = "statusTitle") { title ->
            Text(
                title,
                style = MaterialTheme.typography.displaySmall,
                color = PureWhite,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            now.hint,
            style = MaterialTheme.typography.bodyMedium,
            color = if (problem) CoralError else Muted,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 14.dp),
        )

        // Recent Task in Progress
        val recentTask = task?.takeIf { now.live || System.currentTimeMillis() - it.at < TASK_SHOWN_MS }
        recentTask?.let {
            TaskCard(it, Modifier.padding(bottom = 12.dp))
        }

        // ChatGPT-Style Conversation or Quick Actions
        if (state.phase != Phase.Off && state.lines.isNotEmpty()) {
            ChatConversation(state.lines, Modifier.fillMaxWidth().weight(1f))
        } else {
            QuickActions(onQuick, favourites)
            Spacer(Modifier.height(14.dp))
            RecentActivitySnippet(activity.take(2), onSeeActivity)
        }

        // Floating Liquid Glass Action Button (Mic / Stop)
        Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val live = now.live
            val buttonShape = CircleShape

            Box(
                modifier = Modifier
                    .size(76.dp)
                    .shadow(
                        elevation = if (live) 18.dp else 10.dp,
                        shape = buttonShape,
                        ambientColor = if (live) CoralError else ElectricCyan,
                        spotColor = if (live) CoralError else ElectricCyan,
                    )
                    .clip(buttonShape)
                    .background(
                        if (live) {
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xFFE54D4D),
                                    Color(0xFF9E1F1F),
                                ),
                            )
                        } else {
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xFF45EEFF),
                                    Color(0xFF0C94A8),
                                ),
                            )
                        },
                    )
                    .border(
                        1.5.dp,
                        Brush.verticalGradient(
                            listOf(
                                Color(0x99FFFFFF),
                                Color(0x33FFFFFF),
                            ),
                        ),
                        buttonShape,
                    )
                    .clickable { if (live) onStop() else startListening() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (live) Icons.Filled.Close else MicIcon,
                    contentDescription = if (live) "Stop" else "Talk",
                    tint = if (live) PureWhite else Color(0xFF001E26),
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}

/** One-tap phone actions with frosted glass styling. */
@Composable
private fun QuickActions(onQuick: (LocalCommand) -> String, favourites: List<LocalCommand>) {
    var torchOn by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<String?>(null) }
    val learned = favourites.mapNotNull { command -> HabitKeys.key(command)?.let { HabitKeys.describe(it).replaceFirstChar(Char::uppercase) to { command } } }
    val defaults = listOf(
        (if (torchOn) "Torch off" else "Torch") to {
            torchOn = !torchOn
            LocalCommand.Torch(torchOn)
        },
        "Battery" to { LocalCommand.Battery },
        "Time" to { LocalCommand.Time },
        "Pause" to { LocalCommand.Media(MediaKey.Pause) },
    )
    val actions = (learned + defaults.filter { d -> learned.none { it.first.startsWith(d.first, ignoreCase = true) } }).take(4)

    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            actions.forEach { (label, command) ->
                val shape = RoundedCornerShape(14.dp)
                Box(
                    Modifier
                        .weight(1f)
                        .clip(shape)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color(0x99141E34),
                                    Color(0x660D1424),
                                ),
                            ),
                        )
                        .border(
                            1.dp,
                            Brush.verticalGradient(
                                listOf(
                                    Color(0x33FFFFFF),
                                    Color(0x1A38E5FF),
                                    Color(0x08FFFFFF),
                                ),
                            ),
                            shape,
                        )
                        .clickable { answer = onQuick(command()) }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = PureWhite,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
        answer?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = ElectricCyan,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )
        }
    }
}

/** Recent activity with iOS glass card layout. */
@Composable
private fun RecentActivitySnippet(entries: List<ActivityLog.Entry>, onSeeAll: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "RECENT ACTIVITY",
            style = MaterialTheme.typography.titleSmall,
            color = Muted,
            letterSpacing = 0.8.sp,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        TextButton(onClick = onSeeAll) { Text("See all", color = ElectricCyan, fontWeight = FontWeight.SemiBold) }
    }
    if (entries.isEmpty()) {
        Text(
            "Nothing yet. What you ask shows up here.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )
    } else {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            entries.forEachIndexed { idx, entry ->
                ActivityRow(entry)
                if (idx < entries.lastIndex) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(0.6.dp)
                            .background(Color(0x1FFFFFFF)),
                    )
                }
            }
        }
    }
}

/** Modern ChatGPT-style conversation view with frosted glass message bubbles. */
@Composable
private fun ChatConversation(lines: List<Line>, modifier: Modifier) {
    val list = rememberLazyListState()
    LaunchedEffect(lines.size, lines.lastOrNull()?.text) {
        if (lines.isNotEmpty()) list.animateScrollToItem(lines.lastIndex)
    }
    LazyColumn(
        modifier = modifier.padding(vertical = 4.dp),
        state = list,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(lines, key = { it.id }) { line ->
            Box(
                Modifier.fillMaxWidth(),
                contentAlignment = if (line.fromUser) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                if (line.fromUser) {
                    // User prompt bubble: electric cyan with violet tint
                    val userShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
                    Box(
                        Modifier
                            .clip(userShape)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        Color(0xFF38E5FF),
                                        Color(0xFF7C3AED),
                                    ),
                                ),
                            )
                            .border(
                                1.dp,
                                Color(0x66FFFFFF),
                                userShape,
                            )
                            .padding(horizontal = 15.dp, vertical = 10.dp),
                    ) {
                        Text(
                            line.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                } else {
                    // Assistant response bubble: translucent dark glass with specular rim
                    val aiShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)
                    Box(
                        Modifier
                            .clip(aiShape)
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color(0xD9121D33),
                                        Color(0xBF0B1220),
                                    ),
                                ),
                            )
                            .border(
                                1.dp,
                                Brush.verticalGradient(
                                    listOf(
                                        Color(0x40FFFFFF),
                                        Color(0x2638E5FF),
                                    ),
                                ),
                                aiShape,
                            )
                            .padding(horizontal = 15.dp, vertical = 10.dp),
                    ) {
                        Text(
                            line.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = PureWhite,
                            lineHeight = 21.sp,
                        )
                    }
                }
            }
        }
    }
}

private const val TASK_SHOWN_MS = 2 * 60_000L
