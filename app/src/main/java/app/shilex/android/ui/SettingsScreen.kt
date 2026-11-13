package app.shilex.android.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shilex.android.Branding
import app.shilex.android.assistant.AssistantViewModel
import app.shilex.android.data.Settings as AppSettings
import app.shilex.android.memory.MemoryStore
import app.shilex.android.notify.NotificationWatch
import app.shilex.android.orb.FloatingOrb
import app.shilex.android.wake.WakeDotSize
import app.shilex.android.wake.WakeIndicatorController
import app.shilex.android.wake.WakeState
import app.shilex.android.wake.WakeWordModel

/**
 * AMOLED Smoked Liquid Glass Settings Screen.
 */
@Composable
fun SettingsScreen(assistant: AssistantViewModel) {
    val context = LocalContext.current
    val wakeOn by assistant.wakeWord.collectAsStateWithLifecycle()
    val wakeState by assistant.wakeState.collectAsStateWithLifecycle()
    val profile by assistant.personalProfile.collectAsStateWithLifecycle()
    val installedApps by assistant.installedApps.collectAsStateWithLifecycle()
    var showProfileEditor by remember { mutableStateOf(false) }
    var showAppsBrowser by remember { mutableStateOf(false) }
    var localOn by remember { mutableStateOf(assistant.localCommands) }
    var networkOn by remember { mutableStateOf(assistant.networkSpeech) }
    var engine by remember { mutableStateOf(assistant.speechEngine) }
    var batteryExempt by remember { mutableStateOf(assistant.batteryExempt) }
    var dotOn by remember { mutableStateOf(assistant.wakeIndicator) }
    var dotSize by remember { mutableStateOf(assistant.wakeDotSize) }
    var dotDurationMs by remember { mutableLongStateOf(assistant.wakeDotDurationMs) }
    var overlayAllowed by remember { mutableStateOf(FloatingOrb.allowed(context)) }
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var micDeniedOnce by remember { mutableStateOf(false) }

    if (showProfileEditor) {
        PersonalProfileDialog(
            initial = profile,
            onSave = assistant::savePersonalProfile,
            onClear = assistant::clearPersonalProfile,
            onDismiss = { showProfileEditor = false },
        )
    }
    if (showAppsBrowser) {
        InstalledAppsDialog(
            apps = installedApps,
            onRefresh = assistant::refreshInstalledApps,
            onLaunch = assistant::launchInstalledApp,
            onDismiss = { showAppsBrowser = false },
        )
    }

    val askWakePermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val micNow = results[Manifest.permission.RECORD_AUDIO]
            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        micGranted = micNow
        if (micNow) {
            micDeniedOnce = false
            assistant.setWakeWord(true)
        } else {
            micDeniedOnce = true
            assistant.setWakeWord(false)
        }
    }

    LifecycleResumeEffect(Unit) {
        batteryExempt = assistant.batteryExempt
        engine = assistant.speechEngine
        overlayAllowed = FloatingOrb.allowed(context)
        micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        assistant.refreshWakeState()
        onPauseOrDispose { }
    }
    val open = { action: String -> context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    val openAppSettings = {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
    val requestWakePermissions = {
        val needed = buildList {
            if (!micGranted) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isEmpty()) {
            assistant.setWakeWord(true)
        } else {
            askWakePermissions.launch(needed.toTypedArray())
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(OledBlack)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(Modifier.padding(top = 14.dp)) {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineSmall,
                color = PureWhite,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Customize intelligence, audio response, and privacy boundaries.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // Top Assistant Identity Hero Card
        GlassCard(shape = RoundedCornerShape(26.dp), verticalPadding = 14) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .shadow(10.dp, CircleShape, ambientColor = ElectricCyan, spotColor = NeonViolet)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    ElectricCyan.copy(alpha = 0.85f),
                                    NeonViolet.copy(alpha = 0.85f),
                                ),
                            ),
                        )
                        .border(1.dp, Color(0x80FFFFFF), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MicIcon,
                        contentDescription = null,
                        tint = Color(0xFF001A22),
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        Branding.ASSISTANT_NAME,
                        style = MaterialTheme.typography.titleLarge,
                        color = PureWhite,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (wakeOn && micGranted) "Hands-free Wake Word • On-Phone & Gemini" else "Tap to Talk • On-Phone & Gemini",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Pill(
                    text = if (wakeOn && micGranted) "Active" else "Standby",
                    color = if (wakeOn && micGranted) ElectricCyan else SlateMuted,
                )
            }
        }

        Section("Personal Profile") {
            val songSummary = if (profile.favoriteSongs.isEmpty()) "No favourite songs saved" else profile.favoriteSongs.joinToString(", ")
            val musicAppText = profile.preferredMusicApp.ifBlank { "Default (YouTube Music / Spotify)" }
            val identityLine = listOfNotNull(
                profile.preferredName.takeIf { it.isNotBlank() },
                profile.addressAs.takeIf { it.isNotBlank() }?.let { "call you \"$it\"" },
            ).joinToString(" • ").ifEmpty { "Set preferred name, music taste, and response style" }
            InfoRow(
                "Your preferences",
                "$identityLine\nSongs: $songSummary • Music app: $musicAppText",
                icon = Icons.Filled.Star,
                iconTint = ElectricCyan,
            ) {
                TextButton(onClick = { showProfileEditor = true }) {
                    Text(if (profile.isEmpty) "Set up" else "Edit", color = ElectricCyan, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Section("Gemini Intelligence") {
            InfoRow(
                "API key",
                "Your own key, encrypted with Android Keystore on this phone. Used only for requests the phone can't handle.",
                icon = Icons.Filled.Lock,
                iconTint = ElectricCyan,
            ) {
                TextButton(onClick = assistant::forgetKey) {
                    Text("Replace", color = ElectricCyan, fontWeight = FontWeight.SemiBold)
                }
            }
            GlassDivider()
            InfoRow(
                "Default assistant",
                "Long-press Home or swipe from a corner to talk.",
                icon = Icons.Filled.Phone,
                iconTint = PureWhite,
            ) {
                TextButton(onClick = { open(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS) }) {
                    Text("Open", color = ElectricCyan, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Section("Conversation & Feedback") {
            var talkOn by remember { mutableStateOf(assistant.talkOver) }
            SwitchRow(
                "Talk over ${Branding.ASSISTANT_NAME}",
                "Speak while it answers to cut it off. An echo canceller on this phone removes its own voice first.",
                talkOn,
                icon = Icons.Filled.Phone,
            ) {
                talkOn = it
                assistant.talkOver = it
            }
            GlassDivider()
            var waveOn by remember { mutableStateOf(assistant.waveToStop) }
            SwitchRow(
                "Wave to interrupt",
                "Wave a hand over the phone while ${Branding.ASSISTANT_NAME} speaks to cut it off instantly.",
                waveOn,
                icon = Icons.Filled.Star,
            ) {
                waveOn = it
                assistant.waveToStop = it
            }
        }

        Section("Floating Presence") {
            val prefs = remember { AppSettings(context) }
            var orbOn by remember { mutableStateOf(prefs.floatingOrb && FloatingOrb.allowed(context)) }
            LifecycleResumeEffect(Unit) {
                orbOn = prefs.floatingOrb && FloatingOrb.allowed(context)
                onPauseOrDispose { }
            }
            SwitchRow(
                "Use ${Branding.ASSISTANT_NAME} over any app",
                "A small orb floats over your apps. Tap to talk, double-tap to type, drag to move.",
                orbOn,
                icon = Icons.Filled.Star,
            ) { on ->
                prefs.floatingOrb = on
                orbOn = on && FloatingOrb.allowed(context)
                if (on && !FloatingOrb.allowed(context)) {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                FloatingOrb.sync(context)
            }
        }

        Section("Notifications") {
            var notifyOn by remember { mutableStateOf(NotificationWatch.allowed(context)) }
            LifecycleResumeEffect(Unit) {
                notifyOn = NotificationWatch.allowed(context)
                onPauseOrDispose { }
            }
            InfoRow(
                "What did I miss?",
                (if (notifyOn) "On. " else "Off. ") +
                    "Ask \"what did I miss?\" and hear messages from people first. Notifications stay in memory on this " +
                    "phone; Gemini gets a short summary only when you ask, with codes hidden.",
                icon = Icons.Filled.Notifications,
                iconTint = if (notifyOn) ElectricCyan else SlateMuted,
            ) {
                TextButton(onClick = { open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }) {
                    Text(if (notifyOn) "Change" else "Turn on", color = ElectricCyan, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Section("Wake Word") {
            SwitchRow(
                "Enable wake word",
                "Hands-free voice activation without opening ${Branding.ASSISTANT_NAME} or tapping the mic.",
                wakeOn && micGranted,
                enabled = assistant.wakeWordAvailable,
                icon = MicIcon,
            ) { on ->
                if (on) {
                    requestWakePermissions()
                } else {
                    assistant.setWakeWord(false)
                }
            }
            GlassDivider()
            InfoRow(
                "Active phrase",
                "Primary: \"${WakeWordModel.PHRASE}\" (also recognizes \"${WakeWordModel.ALT_PHRASE}\").",
            ) {
                Pill(WakeWordModel.PHRASE, ElectricCyan, dot = false)
            }
            GlassDivider()
            InfoRow(
                "Microphone permission",
                when {
                    micGranted -> "Granted. Required for offline wake-word detection and voice commands."
                    micDeniedOnce -> "Denied. Allow Microphone in App Settings to use hands-free wake word."
                    else -> "Not granted yet. Tap Allow to enable hands-free wake word."
                },
            ) {
                if (micGranted) {
                    Pill("Granted", ElectricCyan)
                } else {
                    TextButton(onClick = { if (micDeniedOnce) openAppSettings() else requestWakePermissions() }) {
                        Text(if (micDeniedOnce) "App Info" else "Allow", color = ElectricCyan, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            GlassDivider()
            val (statusLabel, statusDetail, statusColor) = wakeStatusUi(wakeOn, micGranted, wakeState)
            InfoRow("Background listening", statusDetail) {
                Pill(statusLabel, statusColor)
            }
            GlassDivider()
            SwitchRow(
                "Wake activation dot",
                "Shows a subtle ~${dotSize.coreDiameterDp}dp top-center Liquid Glass dot for ${formatSeconds(dotDurationMs)} only when \"${WakeWordModel.PHRASE}\" triggers.",
                dotOn,
            ) { on ->
                dotOn = on
                assistant.wakeIndicator = on
            }

            if (dotOn) {
                GlassDivider()
                OptionChoiceRow(
                    title = "Dot size",
                    detail = "Core dot & Liquid Glass glow (default Standard 12dp).",
                    options = WakeDotSize.entries.map { it to "${it.label} (${it.coreDiameterDp}dp)" },
                    selected = dotSize,
                    onSelect = { chosen ->
                        dotSize = chosen
                        assistant.wakeDotSize = chosen
                    },
                )
                GlassDivider()
                OptionChoiceRow(
                    title = "Display duration",
                    detail = "How long the dot fades in, pulses, and fades out (default 2.8s).",
                    options = WakeIndicatorController.DURATION_PRESETS_MS.map { it to formatSeconds(it) },
                    selected = dotDurationMs,
                    onSelect = { chosenMs ->
                        dotDurationMs = chosenMs
                        assistant.wakeDotDurationMs = chosenMs
                    },
                )
                GlassDivider()
                InfoRow(
                    "Dot over other apps",
                    if (overlayAllowed) {
                        "Allowed. Appears on your home screen and ordinary apps (suppressed on banking, UPI, lock, and PIN screens)."
                    } else {
                        "Optional. Allow Display over other apps so the subtle dot appears on your home screen and other apps."
                    },
                ) {
                    if (overlayAllowed) {
                        Pill("Allowed", ElectricCyan)
                    } else {
                        TextButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            },
                        ) {
                            Text("Allow", color = ElectricCyan, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            GlassDivider()
            InfoRow(
                "Battery & microphone usage",
                "Processed 100% offline on this phone. A quiet-room sound gate sleeps the speech decoder when idle to minimize CPU and battery. No continuous audio is saved or sent to Gemini until \"${WakeWordModel.PHRASE}\" is detected, and a visible notification remains active.",
            )

            if (assistant.wakeOnnxAvailable && micGranted) {
                GlassDivider()
                VoiceSetupRow(
                    saved = assistant.voiceSaved,
                    record = assistant::recordVoiceSample,
                    onSave = assistant::saveVoice,
                    onForget = assistant::forgetVoice,
                )
            }
        }

        Section("Local Commands") {
            SwitchRow(
                "Handle simple commands on phone",
                "Volume, music, flashlight, apps, time, timers and alarms run here, without Gemini.",
                localOn,
                icon = Icons.Filled.Build,
            ) {
                localOn = it
                assistant.localCommands = it
            }
            GlassDivider()
            SwitchRow(
                "Use phone speech service",
                "Better Hinglish, with offline fallbacks.",
                networkOn,
                enabled = localOn,
            ) {
                networkOn = it
                assistant.networkSpeech = it
                engine = assistant.speechEngine
            }
            GlassDivider()
            InfoRow("Speech heard by", engineText(engine))
        }

        Section("Adaptive Learning") {
            var learnOn by remember { mutableStateOf(assistant.learning) }
            var learned by remember { mutableStateOf(assistant.learnedSummary()) }
            SwitchRow(
                "Learn from how I use ${Branding.ASSISTANT_NAME}",
                "Kept only on this phone: which commands you use and when, and your phrases once understood.",
                learnOn,
                icon = Icons.Filled.Settings,
            ) {
                learnOn = it
                assistant.learning = it
            }
            GlassDivider()
            InfoRow("What it knows", learned) {
                TextButton(onClick = {
                    assistant.forgetLearned()
                    learned = assistant.learnedSummary()
                }) { Text("Forget", color = CoralError) }
            }
        }

        Section("Installed Apps Discovery") {
            val launchCount = installedApps.count { it.launchable }
            InfoRow(
                "All installed apps (A–Z)",
                "$launchCount launchable apps indexed (${installedApps.size} total including system components). Refreshes automatically when apps are installed or removed.",
                icon = Icons.Filled.Build,
                iconTint = ElectricCyan,
            ) {
                TextButton(
                    onClick = {
                        assistant.refreshInstalledApps()
                        showAppsBrowser = true
                    },
                ) {
                    Text("Browse A–Z", color = ElectricCyan, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Section("Permissions & Phone Control") {
            ControlCard(Modifier.padding(vertical = 6.dp), showWhenOn = true)
            GlassDivider()
            InfoRow(
                "Background running",
                if (batteryExempt) "Allowed. Battery saver will not interrupt wake word." else "Allow under Battery → Unrestricted so the wake word runs smoothly in background.",
            ) {
                TextButton(onClick = { open(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }) {
                    Text("Open", color = ElectricCyan, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "PERMISSION CENTER",
                style = MaterialTheme.typography.labelMedium,
                color = SlateMuted,
                modifier = Modifier.padding(start = 6.dp),
            )
            PermissionCenterPanel()
        }

        Section("Privacy Boundaries") {
            InfoRow(
                "What leaves the phone",
                "The wake word and local commands stay on this phone. Gemini receives only requests the phone cannot resolve locally. Diagnostic logs never store audio.",
                icon = Icons.Filled.Lock,
                iconTint = ElectricCyan,
            )
            GlassDivider()
            InfoRow("Standby", "Between requests only the wake word runs; Gemini is completely disconnected.")
            GlassDivider()
            val memory = remember { MemoryStore(context) }
            var notes by remember { mutableStateOf(memory.notes().size) }
            LifecycleResumeEffect(Unit) {
                notes = memory.notes().size
                onPauseOrDispose { }
            }
            InfoRow(
                "What ${Branding.ASSISTANT_NAME} remembers",
                "$notes note(s) stored locally on this phone only.",
            ) {
                if (notes > 0) {
                    TextButton(onClick = { memory.clearNotes().also { notes = 0 } }) {
                        Text("Forget all", color = CoralError)
                    }
                }
            }
        }

        Section("About") {
            InfoRow(
                Branding.ASSISTANT_NAME,
                "Version ${versionName(context)} • GNU GPL v3.0",
                icon = Icons.Filled.Info,
                iconTint = Muted,
            )
        }
        FloatingDockBottomSpacer()
    }
}

private fun wakeStatusUi(wakeOn: Boolean, micGranted: Boolean, state: WakeState): Triple<String, String, Color> = when {
    !micGranted && wakeOn -> Triple("Blocked", "Waiting for microphone permission.", CoralError)
    !wakeOn -> Triple("Off", "Turn on wake word above to listen hands-free.", SlateMuted)
    state == WakeState.Listening -> Triple("Active", "Listening offline in background for \"${WakeWordModel.PHRASE}\".", ElectricCyan)
    state == WakeState.Starting -> Triple("Starting", "Preparing offline wake-word detector…", ElectricCyan)
    state == WakeState.PausedSession -> Triple("In session", "Paused while your voice conversation runs; resumes automatically.", ElectricCyan)
    state == WakeState.PausedMicBusy -> Triple("Mic busy", "Another app is using the microphone; resumes when free.", AmberWarning)
    state == WakeState.PausedInCall -> Triple("In call", "Paused during a call; resumes automatically.", AmberWarning)
    state == WakeState.PermissionNeeded -> Triple("Blocked", "Microphone permission required.", CoralError)
    state == WakeState.Unavailable -> Triple("Unavailable", "Offline wake-word model could not load.", CoralError)
    else -> Triple("Standby", "Enabled. Open or unlock phone to start background service.", SlateMuted)
}

private fun engineText(engine: String?) = when (engine) {
    "on-device" -> "The phone's on-device recogniser. No internet needed."
    "offline" -> "Offline model on this phone (Vosk). No internet needed; best with English words."
    "network" -> "The phone's speech service (usually Google). Needs internet."
    else -> "Nothing local is available, so Gemini hears every request."
}

private fun formatSeconds(ms: Long): String =
    String.format(java.util.Locale.US, "%.1fs", ms / 1000f)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> OptionChoiceRow(
    title: String,
    detail: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = PureWhite)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = Muted)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                val shape = RoundedCornerShape(50)
                val interaction = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .glassPressScale(interaction)
                        .clip(shape)
                        .background(
                            if (isSelected) {
                                Brush.horizontalGradient(listOf(ElectricCyan.copy(alpha = 0.22f), NeonViolet.copy(alpha = 0.16f)))
                            } else {
                                Brush.horizontalGradient(listOf(Color(0x16FFFFFF), Color(0x0CFFFFFF)))
                            },
                        )
                        .border(
                            0.9.dp,
                            if (isSelected) ElectricCyan.copy(alpha = 0.65f) else Color(0x26FFFFFF),
                            shape,
                        )
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                        ) { onSelect(value) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) ElectricCyan else Muted,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun versionName(context: android.content.Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0.1.0"
