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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import app.shilex.android.wake.WakeState
import app.shilex.android.wake.WakeWordModel

/**
 * Premium iOS-style Liquid Glass Settings Screen.
 */
@Composable
fun SettingsScreen(assistant: AssistantViewModel) {
    val context = LocalContext.current
    val wakeOn by assistant.wakeWord.collectAsStateWithLifecycle()
    val wakeState by assistant.wakeState.collectAsStateWithLifecycle()
    var localOn by remember { mutableStateOf(assistant.localCommands) }
    var networkOn by remember { mutableStateOf(assistant.networkSpeech) }
    var engine by remember { mutableStateOf(assistant.speechEngine) }
    var batteryExempt by remember { mutableStateOf(assistant.batteryExempt) }
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var micDeniedOnce by remember { mutableStateOf(false) }

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
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(Modifier.padding(top = 16.dp)) {
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

        Section("Gemini Intelligence") {
            InfoRow("API key", "Your own key, encrypted with Android Keystore on this phone. Used only for requests the phone can't handle.") {
                TextButton(onClick = assistant::forgetKey) {
                    Text("Replace", color = ElectricCyan, fontWeight = FontWeight.SemiBold)
                }
            }
            InfoRow("Default assistant", "Long-press Home or swipe from a corner to talk.") {
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
            ) {
                talkOn = it
                assistant.talkOver = it
            }
            var waveOn by remember { mutableStateOf(assistant.waveToStop) }
            SwitchRow(
                "Wave to interrupt",
                "Wave a hand over the phone while ${Branding.ASSISTANT_NAME} speaks to cut it off instantly.",
                waveOn,
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
            ) { on ->
                if (on) {
                    requestWakePermissions()
                } else {
                    assistant.setWakeWord(false)
                }
            }

            InfoRow(
                "Active phrase",
                "Primary: \"${WakeWordModel.PHRASE}\" (also recognizes \"${WakeWordModel.ALT_PHRASE}\").",
            ) {
                Pill(WakeWordModel.PHRASE, ElectricCyan, dot = false)
            }

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

            val (statusLabel, statusDetail, statusColor) = wakeStatusUi(wakeOn, micGranted, wakeState)
            InfoRow("Background listening", statusDetail) {
                Pill(statusLabel, statusColor)
            }

            InfoRow(
                "Battery & microphone usage",
                "Processed 100% offline on this phone. A quiet-room sound gate sleeps the speech decoder when idle to minimize CPU and battery. No continuous audio is saved or sent to Gemini until \"${WakeWordModel.PHRASE}\" is detected, and a visible notification remains active.",
            )

            if (assistant.wakeOnnxAvailable && micGranted) {
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
            ) {
                localOn = it
                assistant.localCommands = it
            }
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
            InfoRow("Speech heard by", engineText(engine))
        }

        Section("Adaptive Learning") {
            var learnOn by remember { mutableStateOf(assistant.learning) }
            var learned by remember { mutableStateOf(assistant.learnedSummary()) }
            SwitchRow(
                "Learn from how I use ${Branding.ASSISTANT_NAME}",
                "Kept only on this phone: which commands you use and when, and your phrases once understood.",
                learnOn,
            ) {
                learnOn = it
                assistant.learning = it
            }
            InfoRow("What it knows", learned) {
                TextButton(onClick = {
                    assistant.forgetLearned()
                    learned = assistant.learnedSummary()
                }) { Text("Forget", color = CoralError) }
            }
        }

        Section("Permissions & Safety") {
            ControlCard(Modifier.padding(vertical = 4.dp), showWhenOn = true)
            InfoRow(
                "Background running",
                if (batteryExempt) "Allowed. Battery saver will not interrupt wake word." else "Allow under Battery → Unrestricted so the wake word runs smoothly in background.",
            ) {
                TextButton(onClick = { open(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }) {
                    Text("Open", color = ElectricCyan, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Section("Privacy Boundaries") {
            InfoRow(
                "What leaves the phone",
                "The wake word and local commands stay on this phone. Gemini receives only requests the phone cannot resolve locally. Diagnostic logs never store audio.",
            )
            InfoRow("Standby", "Between requests only the wake word runs; Gemini is completely disconnected.")
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
            InfoRow(Branding.ASSISTANT_NAME, "Version ${versionName(context)} • GNU GPL v3.0")
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun wakeStatusUi(wakeOn: Boolean, micGranted: Boolean, state: WakeState): Triple<String, String, androidx.compose.ui.graphics.Color> = when {
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

private fun versionName(context: android.content.Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0.1.0"
