package app.shilex.android.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.shilex.android.Branding
import app.shilex.android.assistant.AssistantViewModel
import app.shilex.android.assistant.Phase
import app.shilex.android.wake.WakeWordModel

private enum class Step { Welcome, Key, LocalCloud, Mic, Wake, Control, Done }

/**
 * Premium iOS Liquid Glass Onboarding Flow for JARVIS.
 */
@Composable
fun Onboarding(assistant: AssistantViewModel, hasKey: Boolean, onFinished: () -> Unit) {
    var index by rememberSaveable { mutableIntStateOf(if (assistant.onboarded) Step.Key.ordinal else 0) }
    val step = Step.entries[index]
    val next: () -> Unit = { if (index < Step.entries.lastIndex) index++ else onFinished() }

    Column(
        Modifier
            .fillMaxSize()
            .background(OledBlack)
            .padding(horizontal = 24.dp),
    ) {
        Progress(index, Modifier.padding(top = 24.dp, bottom = 12.dp))
        AnimatedContent(step, label = "onboardingStep", modifier = Modifier.weight(1f)) { shown ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 16.dp, bottom = 24.dp),
            ) {
                when (shown) {
                    Step.Welcome -> Page(Branding.ASSISTANT_NAME, "A private voice assistant for your phone, in English, Hindi and Hinglish.") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) {
                            Orb(Phase.Listening, Modifier.size(200.dp))
                        }
                        Primary("Get started", next)
                    }
                    Step.Key -> Page("Your Gemini key", "${Branding.ASSISTANT_NAME} uses Google Gemini, with your own key, for anything the phone can't do by itself.") {
                        if (hasKey) {
                            GlassCard(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                                Text("Your key is saved and verified.", color = ElectricCyan, style = MaterialTheme.typography.titleMedium)
                            }
                            Primary("Continue", next)
                        } else {
                            KeyForm(onSave = { key -> assistant.saveKey(key).also { if (it == null) next() } })
                        }
                    }
                    Step.LocalCloud -> Page("What runs where", "Simple commands never leave the phone. The rest goes to Gemini.") {
                        Section(null) {
                            InfoRow("On this phone", "Volume, music, flashlight, opening apps, time, battery, timers and alarms. No internet, no Gemini.") {
                                Pill("Local", ElectricCyan)
                            }
                            InfoRow("With Gemini", "Questions, calls and messages, songs by name, your screen. Needs internet and uses your key.") {
                                Pill("Gemini", AmberWarning)
                            }
                        }
                        Spacer(Modifier.height(28.dp))
                        Primary("Continue", next)
                    }
                    Step.Mic -> MicStep(next)
                    Step.Wake -> Page("Hands-free", "Say \"${WakeWordModel.PHRASE}\" (or \"${WakeWordModel.ALT_PHRASE}\") instead of touching the phone. Audio stays offline on this phone until you say it.") {
                        if (assistant.wakeWordAvailable && micAllowed()) {
                            var on by remember { mutableStateOf(assistant.wakeWordOn) }
                            Section(null) {
                                SwitchRow("Wake word (${WakeWordModel.PHRASE})", "Uses minimal battery with a quiet-room gate while awaiting phrase.", on) {
                                    on = it
                                    assistant.setWakeWord(it)
                                }
                                if (assistant.wakeOnnxAvailable) {
                                    VoiceSetupRow(assistant.voiceSaved, assistant::recordVoiceSample, assistant::saveVoice, assistant::forgetVoice)
                                }
                            }
                        } else {
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    if (assistant.wakeWordAvailable) "Allow microphone first in the previous step or later in Settings." else "Offline speech model unavailable.",
                                    color = Muted,
                                )
                            }
                        }
                        Spacer(Modifier.height(28.dp))
                        Primary("Continue", next)
                    }
                    Step.Control -> Page("Phone control (optional)", "Lets ${Branding.ASSISTANT_NAME} read the screen and tap for you. You can turn it on later in Settings.") {
                        ControlCard(showWhenOn = true)
                        Spacer(Modifier.height(24.dp))
                        Primary("Continue", next)
                        TextButton(onClick = next, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text("Skip for now", color = Muted)
                        }
                    }
                    Step.Done -> Page("You're set", "Say \"${WakeWordModel.PHRASE}\" or tap the mic. Settings has everything you configured.") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                            Orb(Phase.Off, Modifier.size(160.dp))
                        }
                        Primary("Start", onFinished)
                    }
                }
            }
        }
    }
}

@Composable
private fun MicStep(next: () -> Unit) {
    var allowed by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    val already = micAllowed()
    Page("Microphone", "Needed to hear you. Audio for the wake word and local commands stays on the phone; Gemini hears you only during active conversation.") {
        if (already || allowed) {
            GlassCard(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Text("Microphone permission granted.", color = ElectricCyan, style = MaterialTheme.typography.titleMedium)
            }
            Primary("Continue", next)
        } else {
            Primary("Allow microphone") { ask.launch(Manifest.permission.RECORD_AUDIO) }
            TextButton(onClick = next, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Not now", color = Muted)
            }
        }
    }
}

@Composable
private fun micAllowed(): Boolean =
    ContextCompat.checkSelfPermission(LocalContext.current, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

@Composable
private fun Page(title: String, detail: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.displaySmall,
        color = PureWhite,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.6).sp,
    )
    Text(
        detail,
        style = MaterialTheme.typography.bodyLarge,
        color = Muted,
        modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
    )
    Column(content = content)
}

@Composable
private fun Primary(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Button(
        onClick = onClick,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = ElectricCyan,
            contentColor = Color(0xFF001E26),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .shadow(
                elevation = 8.dp,
                shape = shape,
                ambientColor = ElectricCyan,
                spotColor = ElectricCyan,
            )
            .border(
                1.dp,
                Brush.verticalGradient(
                    listOf(
                        Color(0x99FFFFFF),
                        Color(0x33FFFFFF),
                    ),
                ),
                shape,
            ),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Progress(index: Int, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Step.entries.forEach {
            val active = it.ordinal <= index
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (active) {
                            Brush.horizontalGradient(listOf(ElectricCyan, NeonViolet))
                        } else {
                            Brush.horizontalGradient(listOf(Color(0x1FFFFFFF), Color(0x1FFFFFFF)))
                        },
                    ),
            )
        }
    }
}
