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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.shilex.android.Branding
import app.shilex.android.assistant.AssistantViewModel
import app.shilex.android.audio.HindiVoiceCatalog
import app.shilex.android.audio.TtsEngineDiagnostics
import app.shilex.android.audio.TtsVoiceInfo
import app.shilex.android.audio.VoiceGender
import app.shilex.android.audio.VoiceGenderSource
import java.util.Locale

private enum class VoiceTypeOption(val key: String, val label: String) {
    FEMALE("FEMALE", "Female Voices"),
    MALE("MALE", "Male Voices"),
}

/**
 * Liquid Glass "Voice & Speech" settings section:
 * - Assistant speech toggle
 * - Strict Hindi (hi-IN) catalog with ONLY two categories: Female Voices (max 5) & Male Voices (max 5)
 * - Active TTS engine / Google Speech Services check + "Install Hindi Voice Data" launcher
 * - Preview-first verification for genuine hi-IN voices whose engine metadata omits gender
 * - Live voice Test preview, speech speed, pitch sliders, and restore-default reset
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
    hindiCatalog: HindiVoiceCatalog,
    engineDiagnostics: TtsEngineDiagnostics,
    isTtsReady: Boolean,
    ttsError: String?,
    previewingVoiceId: String?,
    onPreviewVoice: (String) -> Unit,
    onStopPreview: () -> Unit,
    onClassifyVoice: (String, VoiceGender) -> Unit,
    onInstallHindiVoiceData: () -> Unit,
    onRestoreDefault: () -> Unit,
) {
    val context = LocalContext.current
    val activeFilter = remember(genderFilter) {
        if (genderFilter.equals(VoiceTypeOption.MALE.key, ignoreCase = true)) {
            VoiceTypeOption.MALE
        } else {
            VoiceTypeOption.FEMALE
        }
    }

    var previewedVoiceIds by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(previewingVoiceId) {
        if (!previewingVoiceId.isNullOrBlank()) {
            previewedVoiceIds = previewedVoiceIds + previewingVoiceId
        }
    }

    val activeVoices = if (activeFilter == VoiceTypeOption.MALE) {
        hindiCatalog.maleVoices
    } else {
        hindiCatalog.femaleVoices
    }
    val missingCount = if (activeFilter == VoiceTypeOption.MALE) {
        hindiCatalog.missingMaleCount
    } else {
        hindiCatalog.missingFemaleCount
    }

    val activeSelectedVoice = remember(ttsVoiceId, hindiCatalog) {
        if (ttsVoiceId == null) {
            null
        } else {
            (hindiCatalog.femaleVoices + hindiCatalog.maleVoices + hindiCatalog.unclassifiedVoices)
                .firstOrNull { it.id == ttsVoiceId }
        }
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
                detail = "Checking installed Hindi (hi-IN) Text-to-Speech voices…",
                icon = Icons.Filled.Refresh,
                iconTint = AmberWarning,
            )
        }

        GlassDivider()

        // Active engine + Google Speech Services + Hindi voice catalog status
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
                Pill(
                    text = "hi-IN • F ${hindiCatalog.femaleVoices.size}/5 • M ${hindiCatalog.maleVoices.size}/5",
                    color = ElectricCyan,
                    dot = false,
                )
            }

            // Strictly ONLY Female Voices and Male Voices
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                VoiceTypeOption.entries.forEach { option ->
                    val selected = activeFilter == option
                    val shape = RoundedCornerShape(50)
                    val interaction = remember { MutableInteractionSource() }
                    val accent = if (option == VoiceTypeOption.FEMALE) SoftPink else ElectricCyan
                    val categoryCount = if (option == VoiceTypeOption.FEMALE) {
                        hindiCatalog.femaleVoices.size
                    } else {
                        hindiCatalog.maleVoices.size
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
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "${option.label} ($categoryCount/5)",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) PureWhite else Muted,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
            }

            val engineName = engineDiagnostics.activeEngineLabel ?: "Default Android TTS"
            val engineStatusLine = buildString {
                append("Active voice: ")
                append(activeSelectedVoice?.displayName ?: "Auto Hindi (hi-IN) Default")
                append(" • Engine: ")
                append(engineName)
                if (!engineDiagnostics.isGoogleSpeechServicesActive && engineDiagnostics.isGoogleSpeechServicesInstalled) {
                    append(" (switch to Google Speech Services in Android TTS Settings for more Hindi voices)")
                }
            }
            Text(
                text = engineStatusLine,
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }

        GlassDivider()

        val activeHeader = if (activeFilter == VoiceTypeOption.FEMALE) "FEMALE VOICES (HINDI hi-IN)" else "MALE VOICES (HINDI hi-IN)"
        val activeAccent = if (activeFilter == VoiceTypeOption.FEMALE) SoftPink else ElectricCyan
        val genderWord = if (activeFilter == VoiceTypeOption.FEMALE) "female" else "male"

        HindiVoiceCategoryGroup(
            header = activeHeader,
            accent = activeAccent,
            voices = activeVoices,
            missingCount = missingCount,
            genderWord = genderWord,
            unclassifiedCount = hindiCatalog.unclassifiedVoices.size,
            isTtsReady = isTtsReady,
            totalEngineVoices = engineDiagnostics.totalEngineVoicesCount,
            selectedVoiceId = ttsVoiceId,
            previewingVoiceId = previewingVoiceId,
            onSelectVoice = onVoiceSelected,
            onPreviewVoice = { id ->
                previewedVoiceIds = previewedVoiceIds + id
                onPreviewVoice(id)
            },
            onStopPreview = onStopPreview,
            onClearClassification = { id -> onClassifyVoice(id, VoiceGender.Unspecified) },
        )

        // Honest preview-first classifier only when genuine hi-IN voices lack engine gender metadata
        if (hindiCatalog.unclassifiedVoices.isNotEmpty()) {
            GlassDivider()
            UnclassifiedHindiVoicesGroup(
                voices = hindiCatalog.unclassifiedVoices,
                previewingVoiceId = previewingVoiceId,
                previewedVoiceIds = previewedVoiceIds,
                onPreviewVoice = { id ->
                    previewedVoiceIds = previewedVoiceIds + id
                    onPreviewVoice(id)
                },
                onStopPreview = onStopPreview,
                onClassify = onClassifyVoice,
            )
        }

        // Install Hindi Voice Data action + explanation
        GlassDivider()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Hindi Voice Data",
                style = MaterialTheme.typography.titleMedium,
                color = PureWhite,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Only genuine Hindi (hi-IN) profiles installed on your phone are listed above (up to 5 female + 5 male). Open Android TTS settings to download Hindi voice packs if fewer than 5 appear.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
            OutlinedButton(
                onClick = {
                    onInstallHindiVoiceData()
                    if (!assistant.speaker.openHindiVoiceDataSettings(context)) {
                        assistant.speaker.initTts { assistant.speaker.discoverVoices() }
                    }
                },
                shape = RoundedCornerShape(50),
            ) {
                Text(
                    text = "Install Hindi Voice Data",
                    color = ElectricCyan,
                    fontWeight = FontWeight.SemiBold,
                )
            }
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
            TextButton(onClick = { assistant.refreshTtsVoices() }) {
                Text("Refresh Hindi voices", color = ElectricCyan, fontWeight = FontWeight.Medium)
            }
            TextButton(onClick = onRestoreDefault) {
                Text("Reset voice defaults", color = Muted, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun HindiVoiceCategoryGroup(
    header: String,
    accent: Color,
    voices: List<TtsVoiceInfo>,
    missingCount: Int,
    genderWord: String,
    unclassifiedCount: Int,
    isTtsReady: Boolean,
    totalEngineVoices: Int,
    selectedVoiceId: String?,
    previewingVoiceId: String?,
    onSelectVoice: (String?) -> Unit,
    onPreviewVoice: (String) -> Unit,
    onStopPreview: () -> Unit,
    onClearClassification: (String) -> Unit,
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
                text = "${voices.size} / ${HindiVoiceCatalog.TARGET_COUNT_PER_CATEGORY}",
                style = MaterialTheme.typography.labelMedium,
                color = if (missingCount == 0) accent else Muted,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (voices.isEmpty()) {
            val emptyExplanation = when {
                !isTtsReady -> "Waiting for Android Text-to-Speech engine…"
                unclassifiedCount > 0 ->
                    "0 of 5 Hindi (hi-IN) $genderWord voices confirmed yet ($missingCount missing). $unclassifiedCount genuine Hindi voice(s) on this phone omit gender metadata—preview and classify them below, or tap Install Hindi Voice Data."
                totalEngineVoices == 0 ->
                    "0 of 5 Hindi (hi-IN) $genderWord voices found ($missingCount missing). This TTS engine did not expose downloadable voices; tap Install Hindi Voice Data below."
                else ->
                    "0 of 5 genuine Hindi (hi-IN) $genderWord voices are installed on this phone ($missingCount missing). Tap Install Hindi Voice Data to download Hindi voices in Google Speech Services."
            }
            Text(
                text = emptyExplanation,
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        } else {
            voices.forEach { voice ->
                HindiVoiceCard(
                    voice = voice,
                    accent = accent,
                    isSelected = selectedVoiceId == voice.id,
                    isPreviewing = previewingVoiceId == voice.id,
                    onSelect = { onSelectVoice(voice.id) },
                    onPreview = { onPreviewVoice(voice.id) },
                    onStopPreview = onStopPreview,
                    onClearClassification = if (voice.genderSource == VoiceGenderSource.UserConfirmed) {
                        { onClearClassification(voice.id) }
                    } else {
                        null
                    },
                )
            }

            if (missingCount > 0) {
                Text(
                    text = "Showing ${voices.size} of 5 genuine Hindi (hi-IN) $genderWord voices ($missingCount more unavailable on this phone).",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun HindiVoiceCard(
    voice: TtsVoiceInfo,
    accent: Color,
    isSelected: Boolean,
    isPreviewing: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
    onStopPreview: () -> Unit,
    onClearClassification: (() -> Unit)?,
) {
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
            .clickable(onClick = onSelect)
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
            val installLabel = if (voice.isInstalled) "Installed" else "Download needed"
            Text(
                text = "${voice.gender.label} • ${voice.localeTag} • $modeLabel • $installLabel • ${voice.quality} (${voice.genderSource.label})",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (onClearClassification != null) {
                Text(
                    text = "Unassign label",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted.copy(alpha = 0.85f),
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clickable(onClick = onClearClassification),
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

        VoicePreviewChip(
            isPreviewing = isPreviewing,
            accent = accent,
            onPreview = onPreview,
            onStopPreview = onStopPreview,
        )
    }
}

@Composable
private fun UnclassifiedHindiVoicesGroup(
    voices: List<TtsVoiceInfo>,
    previewingVoiceId: String?,
    previewedVoiceIds: Set<String>,
    onPreviewVoice: (String) -> Unit,
    onStopPreview: () -> Unit,
    onClassify: (String, VoiceGender) -> Unit,
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
                text = "LISTEN & CLASSIFY HINDI VOICES",
                style = MaterialTheme.typography.labelMedium,
                color = AmberWarning,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "${voices.size} pending",
                style = MaterialTheme.typography.labelMedium,
                color = Muted,
            )
        }
        Text(
            text = "Android's TTS engine omitted gender metadata for these genuine hi-IN voices. Tap Test to listen first, then confirm Female or Male.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )

        voices.forEach { voice ->
            val hasPreviewed = voice.id in previewedVoiceIds
            val isPreviewing = previewingVoiceId == voice.id
            val shape = RoundedCornerShape(16.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(Color(0x0EFFFFFF))
                    .border(0.8.dp, GlassBorderSubtle, shape)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = voice.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = PureWhite,
                            fontWeight = FontWeight.Medium,
                        )
                        val modeLabel = if (voice.isNetworkRequired) "Online" else "Offline"
                        val installLabel = if (voice.isInstalled) "Installed" else "Download needed"
                        Text(
                            text = "${voice.localeTag} • $modeLabel • $installLabel • ${voice.quality}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                        )
                    }
                    VoicePreviewChip(
                        isPreviewing = isPreviewing,
                        accent = AmberWarning,
                        onPreview = { onPreviewVoice(voice.id) },
                        onStopPreview = onStopPreview,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!hasPreviewed) {
                        Text(
                            text = "Tap Test to listen first",
                            style = MaterialTheme.typography.labelSmall,
                            color = AmberWarning.copy(alpha = 0.85f),
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Text(
                            text = "Previewed • Confirm:",
                            style = MaterialTheme.typography.labelSmall,
                            color = Muted,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    ClassifyButton(
                        label = "Mark Female",
                        accent = SoftPink,
                        enabled = hasPreviewed,
                        onClick = { onClassify(voice.id, VoiceGender.Female) },
                    )
                    ClassifyButton(
                        label = "Mark Male",
                        accent = ElectricCyan,
                        enabled = hasPreviewed,
                        onClick = { onClassify(voice.id, VoiceGender.Male) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ClassifyButton(
    label: String,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(accent.copy(alpha = if (enabled) 0.2f else 0.08f))
            .border(0.8.dp, accent.copy(alpha = if (enabled) 0.65f else 0.3f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) PureWhite else Muted,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun VoicePreviewChip(
    isPreviewing: Boolean,
    accent: Color,
    onPreview: () -> Unit,
    onStopPreview: () -> Unit,
) {
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
                if (isPreviewing) onStopPreview() else onPreview()
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
                contentDescription = if (isPreviewing) "Stop preview" else "Test voice",
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
