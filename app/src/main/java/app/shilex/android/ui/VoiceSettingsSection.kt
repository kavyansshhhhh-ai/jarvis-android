package app.shilex.android.ui

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shilex.android.Branding
import app.shilex.android.assistant.AssistantViewModel
import app.shilex.android.audio.HindiVoiceCatalog
import app.shilex.android.audio.TtsVoiceInfo
import java.util.Locale

/**
 * Clean Liquid Glass "JARVIS VOICE" settings folder sub-screen containing ONLY:
 * - Female Voices
 * - Male Voices
 * backed strictly by genuine available Hindi voices without technical jargon.
 */
@Composable
fun JarvisVoiceSubScreen(
    assistant: AssistantViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val speaker = remember { assistant.speaker }
    val hindiCatalog by speaker.hindiCatalog.collectAsStateWithLifecycle()
    val isReady by speaker.isTtsReadyState.collectAsStateWithLifecycle()
    val voiceError by speaker.ttsError.collectAsStateWithLifecycle()
    val previewingVoiceId by speaker.previewingVoiceId.collectAsStateWithLifecycle()

    var speakResponses by remember { mutableStateOf(assistant.speakResponses) }
    var selectedVoiceId by remember { mutableStateOf(assistant.ttsVoiceId) }
    var speechRate by remember { mutableFloatStateOf(assistant.ttsSpeechRate) }
    var pitch by remember { mutableFloatStateOf(assistant.ttsPitch) }

    LifecycleResumeEffect(Unit) {
        assistant.refreshTtsVoices()
        selectedVoiceId = assistant.ttsVoiceId
        onPauseOrDispose {
            speaker.stopPreview()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(OledBlack)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val interaction = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .glassPressScale(interaction)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x1CFFFFFF))
                    .border(0.8.dp, GlassBorderSubtle, RoundedCornerShape(50))
                    .clickable(interactionSource = interaction, indication = null) {
                        speaker.stopPreview()
                        onBack()
                    }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to Settings",
                    tint = PureWhite,
                    modifier = Modifier.size(18.dp),
                )
                Text("Settings", style = MaterialTheme.typography.labelLarge, color = PureWhite)
            }
        }

        Column {
            Text(
                text = "JARVIS VOICE",
                style = MaterialTheme.typography.headlineSmall,
                color = PureWhite,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Choose your preferred Hindi voice for ${Branding.ASSISTANT_NAME}.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (voiceError != null) {
            GlassCard(verticalPadding = 14) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = voiceError ?: "Voice playback is unavailable right now.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CoralError,
                    )
                    OutlinedButton(
                        onClick = { speaker.initTts { assistant.refreshTtsVoices() } },
                        shape = RoundedCornerShape(50),
                    ) {
                        Text("Try Again", color = ElectricCyan, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // 1. Female Voices (only genuine available Hindi female voices, max 5)
        Section("Female Voices") {
            VoiceChoiceList(
                voices = hindiCatalog.femaleVoices,
                accent = SoftPink,
                emptyMessage = if (isReady) {
                    "No female Hindi voices found on this phone."
                } else {
                    "Loading available Hindi voices…"
                },
                selectedVoiceId = selectedVoiceId,
                previewingVoiceId = previewingVoiceId,
                onSelectVoice = { id ->
                    selectedVoiceId = id
                    assistant.ttsVoiceId = id
                },
                onPreviewVoice = { id -> speaker.previewVoice(id) },
                onStopPreview = { speaker.stopPreview() },
            )
        }

        // 2. Male Voices (only genuine available Hindi male voices, max 5)
        Section("Male Voices") {
            VoiceChoiceList(
                voices = hindiCatalog.maleVoices,
                accent = ElectricCyan,
                emptyMessage = if (isReady) {
                    "No male Hindi voices found on this phone."
                } else {
                    "Loading available Hindi voices…"
                },
                selectedVoiceId = selectedVoiceId,
                previewingVoiceId = previewingVoiceId,
                onSelectVoice = { id ->
                    selectedVoiceId = id
                    assistant.ttsVoiceId = id
                },
                onPreviewVoice = { id -> speaker.previewVoice(id) },
                onStopPreview = { speaker.stopPreview() },
            )
        }

        // Install option shown cleanly whenever Hindi voices are missing or fewer than 5
        val needsMoreHindiVoices =
            hindiCatalog.femaleVoices.size < HindiVoiceCatalog.TARGET_COUNT_PER_CATEGORY ||
                hindiCatalog.maleVoices.size < HindiVoiceCatalog.TARGET_COUNT_PER_CATEGORY

        if (needsMoreHindiVoices) {
            GlassCard(verticalPadding = 14) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Need more Hindi voices?",
                        style = MaterialTheme.typography.titleSmall,
                        color = PureWhite,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "You can download or enable additional Hindi voices in your phone's voice settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                    OutlinedButton(
                        onClick = {
                            speaker.stopPreview()
                            if (!speaker.openHindiVoiceDataSettings(context)) {
                                speaker.initTts { assistant.refreshTtsVoices() }
                            }
                        },
                        shape = RoundedCornerShape(50),
                    ) {
                        Text(
                            text = "Install Hindi Voices",
                            color = ElectricCyan,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        // Useful working speech playback controls preserved without extra section headers
        GlassCard(verticalPadding = 12) {
            SwitchRow(
                title = "Speak replies",
                detail = "Speak ${Branding.ASSISTANT_NAME} answers aloud.",
                checked = speakResponses,
                icon = Icons.Filled.Star,
                onChange = { on ->
                    speakResponses = on
                    assistant.speakResponses = on
                },
            )
            GlassDivider()
            VoiceControlSlider(
                title = "Voice speed",
                formattedValue = String.format(Locale.US, "%.2fx", speechRate),
                value = speechRate,
                valueRange = 0.5f..2.0f,
                accent = ElectricCyan,
                onValueChange = { rate ->
                    speechRate = rate
                    assistant.ttsSpeechRate = rate
                },
            )
            GlassDivider()
            VoiceControlSlider(
                title = "Voice pitch",
                formattedValue = String.format(Locale.US, "%.2fx", pitch),
                value = pitch,
                valueRange = 0.5f..1.5f,
                accent = NeonViolet,
                onValueChange = { p ->
                    pitch = p
                    assistant.ttsPitch = p
                },
            )
            GlassDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = {
                        assistant.restoreDefaultTtsVoice()
                        selectedVoiceId = null
                        speechRate = 1.0f
                        pitch = 1.0f
                    },
                ) {
                    Text("Reset voice", color = Muted, fontWeight = FontWeight.Medium)
                }
            }
        }

        FloatingDockBottomSpacer()
    }
}

@Composable
private fun VoiceChoiceList(
    voices: List<TtsVoiceInfo>,
    accent: Color,
    emptyMessage: String,
    selectedVoiceId: String?,
    previewingVoiceId: String?,
    onSelectVoice: (String) -> Unit,
    onPreviewVoice: (String) -> Unit,
    onStopPreview: () -> Unit,
) {
    if (voices.isEmpty()) {
        Text(
            text = emptyMessage,
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        voices.forEach { voice ->
            val isSelected = selectedVoiceId == voice.id
            val isPreviewing = previewingVoiceId == voice.id
            val shape = RoundedCornerShape(16.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(if (isSelected) accent.copy(alpha = 0.14f) else Color(0x0EFFFFFF))
                    .border(
                        0.8.dp,
                        if (isSelected) accent.copy(alpha = 0.6f) else GlassBorderSubtle,
                        shape,
                    )
                    .clickable { onSelectVoice(voice.id) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = voice.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isSelected) PureWhite else PureWhite.copy(alpha = 0.9f),
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                    if (isSelected) {
                        Text(
                            text = "Selected",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }

                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.22f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = accent,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }

                val previewInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .glassPressScale(previewInteraction)
                        .clip(RoundedCornerShape(50))
                        .background(if (isPreviewing) CoralError.copy(alpha = 0.2f) else Color(0x1EFFFFFF))
                        .border(
                            0.8.dp,
                            if (isPreviewing) CoralError.copy(alpha = 0.6f) else Color(0x33FFFFFF),
                            RoundedCornerShape(50),
                        )
                        .clickable(
                            interactionSource = previewInteraction,
                            indication = null,
                        ) {
                            if (isPreviewing) onStopPreview() else onPreviewVoice(voice.id)
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = if (isPreviewing) Icons.Filled.Close else Icons.Filled.PlayArrow,
                            contentDescription = if (isPreviewing) "Stop preview" else "Play preview",
                            tint = if (isPreviewing) CoralError else accent,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = if (isPreviewing) "Stop" else "Play",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isPreviewing) CoralError else PureWhite,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceControlSlider(
    title: String,
    formattedValue: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    accent: Color,
    onValueChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = PureWhite,
            )
            Pill(formattedValue, accent, dot = false)
        }
        Spacer(Modifier.height(4.dp))
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = { raw ->
                onValueChange(raw.coerceIn(valueRange.start, valueRange.endInclusive))
            },
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = PureWhite,
                activeTrackColor = accent,
                inactiveTrackColor = Color(0x2EFFFFFF),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = title
                    stateDescription = formattedValue
                },
        )
    }
}
