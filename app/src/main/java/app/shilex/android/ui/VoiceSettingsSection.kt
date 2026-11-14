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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.shilex.android.Branding
import app.shilex.android.assistant.AssistantViewModel
import app.shilex.android.audio.TtsVoiceInfo
import app.shilex.android.audio.VoiceGender
import java.util.Locale

private enum class VoiceTypeOption(val key: String, val label: String) {
    ALL("ALL", "All"),
    FEMALE("FEMALE", "Female Voices"),
    MALE("MALE", "Male Voices"),
}

/**
 * Liquid Glass "Voice & Speech" settings section:
 * - Assistant speech toggle
 * - TTS engine readiness/error feedback with retry
 * - Male / Female Voice Type selector backed strictly by genuine available Android TTS profiles
 * - Live voice preview, speech rate, pitch sliders, and restore-default reset
 */
@Composable
fun VoiceSettingsSection(
    assistant: AssistantViewModel,
    speakResponses: Boolean,
    onSpeakResponsesChanged: (Boolean) -> Unit,
    ttsVoiceId: String?,
    onVoiceSelected: (String?) -> Unit,
    speechRate: Float,
    onSpeechRateChanged: (Float) -> Unit,
    pitch: Float,
    onPitchChanged: (Float) -> Unit,
    genderFilter: String,
    onGenderFilterChanged: (String) -> Unit,
    availableVoices: List<TtsVoiceInfo>,
    isTtsReady: Boolean,
    ttsError: String?,
    previewingVoiceId: String?,
    onPreviewVoice: (String) -> Unit,
    onStopPreview: () -> Unit,
    onRestoreDefault: () -> Unit,
) {
    val femaleVoices = remember(availableVoices) {
        availableVoices.filter { it.gender == VoiceGender.Female }
    }
    val maleVoices = remember(availableVoices) {
        availableVoices.filter { it.gender == VoiceGender.Male }
    }
    val unspecifiedVoices = remember(availableVoices) {
        availableVoices.filter { it.gender == VoiceGender.Unspecified }
    }

    val activeFilter = remember(genderFilter) {
        VoiceTypeOption.entries.firstOrNull { it.key.equals(genderFilter, ignoreCase = true) }
            ?: VoiceTypeOption.ALL
    }

    Section("Voice & Speech") {
        SwitchRow(
            title = "Speak assistant replies",
            detail = "Speak ${Branding.ASSISTANT_NAME} responses and completed actions aloud on this phone.",
            checked = speakResponses,
            icon = Icons.Filled.Star,
            onChange = onSpeakResponsesChanged,
        )

        if (ttsError != null) {
            GlassDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = CoralError,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Speech engine error",
                        style = MaterialTheme.typography.titleSmall,
                        color = CoralError,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = ttsError,
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                OutlinedButton(
                    onClick = { assistant.speaker.initTts { assistant.speaker.discoverVoices() } },
                    shape = RoundedCornerShape(50),
                ) {
                    Text("Retry TTS Engine", color = ElectricCyan, fontWeight = FontWeight.SemiBold)
                }
            }
        } else if (!isTtsReady) {
            GlassDivider()
            InfoRow(
                title = "Preparing speech engine",
                detail = "Loading available on-device Text-to-Speech voices…",
                icon = Icons.Filled.Refresh,
                iconTint = AmberWarning,
            )
        }

        GlassDivider()

        // Voice Type selector (Female Voices / Male Voices / All)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Voice Type",
                    style = MaterialTheme.typography.titleMedium,
                    color = PureWhite,
                    fontWeight = FontWeight.SemiBold,
                )
                val selectedCountLabel = when (activeFilter) {
                    VoiceTypeOption.FEMALE -> "${femaleVoices.size} female"
                    VoiceTypeOption.MALE -> "${maleVoices.size} male"
                    VoiceTypeOption.ALL -> "${availableVoices.size} available"
                }
                Pill(selectedCountLabel, ElectricCyan, dot = false)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VoiceTypeOption.entries.forEach { option ->
                    val selected = activeFilter == option
                    val shape = RoundedCornerShape(50)
                    val interaction = remember { MutableInteractionSource() }
                    val accent = when (option) {
                        VoiceTypeOption.FEMALE -> SoftPink
                        VoiceTypeOption.MALE -> ElectricCyan
                        VoiceTypeOption.ALL -> NeonViolet
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .glassPressScale(interaction)
                            .clip(shape)
                            .background(
                                if (selected) {
                                    Brush.horizontalGradient(listOf(accent.copy(alpha = 0.24f), accent.copy(alpha = 0.10f)))
                                } else {
                                    Brush.horizontalGradient(listOf(Color(0x16FFFFFF), Color(0x0BFFFFFF)))
                                },
                            )
                            .border(
                                0.9.dp,
                                if (selected) accent.copy(alpha = 0.65f) else Color(0x26FFFFFF),
                                shape,
                            )
                            .clickable(
                                interactionSource = interaction,
                                indication = null,
                            ) { onGenderFilterChanged(option.key) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) PureWhite else Muted,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        // System default / automatic choice row
        GlassDivider()
        val isSystemDefault = ttsVoiceId == null
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onVoiceSelected(null) }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "System Default Voice",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isSystemDefault) ElectricCyan else PureWhite,
                    fontWeight = if (isSystemDefault) FontWeight.SemiBold else FontWeight.Medium,
                )
                Text(
                    text = "Uses your phone's default English/Indian TTS voice.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
            if (isSystemDefault) {
                Pill("Active", ElectricCyan)
            }
        }

        if (activeFilter == VoiceTypeOption.ALL || activeFilter == VoiceTypeOption.FEMALE) {
            GlassDivider()
            VoiceCategoryGroup(
                header = "FEMALE VOICES",
                accent = SoftPink,
                voices = femaleVoices,
                emptyMessage = if (isTtsReady) {
                    "No genuine female voices were reported by this device's TTS engine. Install Google Speech Services voice data in Android Settings to add more."
                } else {
                    "Waiting for device TTS engine…"
                },
                selectedVoiceId = ttsVoiceId,
                previewingVoiceId = previewingVoiceId,
                onSelectVoice = onVoiceSelected,
                onPreviewVoice = onPreviewVoice,
                onStopPreview = onStopPreview,
            )
        }

        if (activeFilter == VoiceTypeOption.ALL || activeFilter == VoiceTypeOption.MALE) {
            GlassDivider()
            VoiceCategoryGroup(
                header = "MALE VOICES",
                accent = ElectricCyan,
                voices = maleVoices,
                emptyMessage = if (isTtsReady) {
                    "No genuine male voices were reported by this device's TTS engine. Install Google Speech Services voice data in Android Settings to add more."
                } else {
                    "Waiting for device TTS engine…"
                },
                selectedVoiceId = ttsVoiceId,
                previewingVoiceId = previewingVoiceId,
                onSelectVoice = onVoiceSelected,
                onPreviewVoice = onPreviewVoice,
                onStopPreview = onStopPreview,
            )
        }

        if (activeFilter == VoiceTypeOption.ALL && unspecifiedVoices.isNotEmpty()) {
            GlassDivider()
            VoiceCategoryGroup(
                header = "ADDITIONAL SYSTEM VOICES",
                accent = NeonViolet,
                voices = unspecifiedVoices,
                emptyMessage = "",
                selectedVoiceId = ttsVoiceId,
                previewingVoiceId = previewingVoiceId,
                onSelectVoice = onVoiceSelected,
                onPreviewVoice = onPreviewVoice,
                onStopPreview = onStopPreview,
            )
        }

        GlassDivider()

        // Speech Rate Slider
        VoiceFloatSliderRow(
            title = "Speech speed",
            formattedValue = String.format(Locale.US, "%.2fx", speechRate),
            value = speechRate,
            valueRange = 0.5f..2.0f,
            accent = ElectricCyan,
            onValueChange = onSpeechRateChanged,
        )

        GlassDivider()

        // Pitch Slider
        VoiceFloatSliderRow(
            title = "Voice pitch",
            formattedValue = String.format(Locale.US, "%.2fx", pitch),
            value = pitch,
            valueRange = 0.5f..1.5f,
            accent = NeonViolet,
            onValueChange = onPitchChanged,
        )

        GlassDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { assistant.speaker.discoverVoices() }) {
                Text("Refresh voices", color = ElectricCyan, fontWeight = FontWeight.Medium)
            }
            TextButton(onClick = onRestoreDefault) {
                Text("Reset voice defaults", color = Muted, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun VoiceCategoryGroup(
    header: String,
    accent: Color,
    voices: List<TtsVoiceInfo>,
    emptyMessage: String,
    selectedVoiceId: String?,
    previewingVoiceId: String?,
    onSelectVoice: (String?) -> Unit,
    onPreviewVoice: (String) -> Unit,
    onStopPreview: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = header,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "${voices.size}",
                style = MaterialTheme.typography.labelMedium,
                color = Muted,
            )
        }

        if (voices.isEmpty()) {
            Text(
                text = emptyMessage,
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        } else {
            voices.forEach { voice ->
                val isSelected = selectedVoiceId == voice.id
                val isPreviewing = previewingVoiceId == voice.id
                val shape = RoundedCornerShape(16.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(
                            if (isSelected) accent.copy(alpha = 0.14f) else Color(0x0EFFFFFF),
                        )
                        .border(
                            0.8.dp,
                            if (isSelected) accent.copy(alpha = 0.55f) else GlassBorderSubtle,
                            shape,
                        )
                        .clickable { onSelectVoice(voice.id) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = voice.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isSelected) PureWhite else PureWhite.copy(alpha = 0.9f),
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        )
                        val modeLabel = if (voice.isNetworkRequired) "Online" else "Offline"
                        val installLabel = if (voice.isInstalled) "" else " • Download needed"
                        Text(
                            text = "$modeLabel • ${voice.quality} • ${voice.localeTag}$installLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                            modifier = Modifier.padding(top = 2.dp),
                        )
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
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = if (isPreviewing) Icons.Filled.Close else Icons.Filled.PlayArrow,
                                contentDescription = if (isPreviewing) "Stop preview" else "Preview voice",
                                tint = if (isPreviewing) CoralError else accent,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = if (isPreviewing) "Stop" else "Test",
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
}

@Composable
private fun VoiceFloatSliderRow(
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
            .padding(vertical = 10.dp),
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
