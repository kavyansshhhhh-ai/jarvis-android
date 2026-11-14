package app.shilex.android.audio

import java.util.Locale

/**
 * Standard classification for TTS voices.
 * Gender is derived ONLY from explicit engine metadata or user confirmation after preview.
 * We never guess gender from opaque voice IDs/names.
 */
enum class VoiceGender(val label: String) {
    Female("Female"),
    Male("Male"),
    Unspecified("Unclassified"),
}

/** Indicates where a voice's Female/Male classification originated. */
enum class VoiceGenderSource(val label: String) {
    Metadata("Engine metadata"),
    UserConfirmed("Verified by you"),
    Unavailable("No engine gender metadata"),
}

/** Lightweight, JVM-testable representation of an Android TextToSpeech Voice. */
data class RawTtsVoice(
    val id: String,
    val locale: Locale,
    val isNetworkRequired: Boolean,
    val qualityScore: Int,
    val features: Set<String>? = null,
)

/**
 * Metadata for a genuine Hindi (India) [hi-IN] TextToSpeech voice discovered on the device.
 */
data class TtsVoiceInfo(
    val id: String,
    val displayName: String,
    val gender: VoiceGender,
    val genderSource: VoiceGenderSource = VoiceGenderSource.Unavailable,
    val locale: Locale,
    val isNetworkRequired: Boolean,
    val quality: String,
    val qualityScore: Int = 300,
    val isInstalled: Boolean = true,
) {
    val isOfflineCapable: Boolean
        get() = isInstalled && !isNetworkRequired

    val languageDisplayName: String
        get() = locale.getDisplayName(Locale.getDefault()).ifBlank { locale.toLanguageTag() }

    val localeTag: String
        get() = locale.toLanguageTag()
}

/**
 * Structured catalog of genuine Hindi (India) [hi-IN] voices separated strictly into
 * Female (max 5), Male (max 5), and Unclassified (awaiting user listen + confirm).
 */
data class HindiVoiceCatalog(
    val femaleVoices: List<TtsVoiceInfo> = emptyList(),
    val maleVoices: List<TtsVoiceInfo> = emptyList(),
    val unclassifiedVoices: List<TtsVoiceInfo> = emptyList(),
    val totalFemaleFound: Int = 0,
    val totalMaleFound: Int = 0,
    val totalHindiVoicesCount: Int = 0,
    val installedHindiVoicesCount: Int = 0,
    val offlineHindiVoicesCount: Int = 0,
) {
    val missingFemaleCount: Int
        get() = (TARGET_COUNT_PER_CATEGORY - femaleVoices.size).coerceAtLeast(0)

    val missingMaleCount: Int
        get() = (TARGET_COUNT_PER_CATEGORY - maleVoices.size).coerceAtLeast(0)

    companion object {
        const val TARGET_COUNT_PER_CATEGORY = 5
    }
}

/** Snapshot of the device's active TTS engine & Google Speech Services availability for Hindi (hi-IN). */
data class TtsEngineDiagnostics(
    val activeEnginePackage: String? = null,
    val activeEngineLabel: String? = null,
    val isGoogleSpeechServicesActive: Boolean = false,
    val isGoogleSpeechServicesInstalled: Boolean = false,
    val hindiLanguageAvailability: Int? = null,
    val totalEngineVoicesCount: Int = 0,
    val totalHindiVoicesCount: Int = 0,
    val installedHindiVoicesCount: Int = 0,
)

/**
 * Pure parsing, strict hi-IN filtering, ranking, and metadata-only classification for TTS voices.
 * Completely deterministic and unit-testable without requiring an Android device.
 */
object VoiceClassifier {
    const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
    private const val FEATURE_NOT_INSTALLED = "notInstalled"

    /**
     * Strictly accepts Hindi (India), locale `hi-IN`.
     * Rejects English (`en-IN`, `en-US`, etc.) and any other non-`hi-IN` locales.
     */
    fun isHindiIndiaLocale(locale: Locale?): Boolean {
        if (locale == null) return false
        val lang = locale.language.lowercase(Locale.ROOT)
        val country = locale.country.uppercase(Locale.ROOT)
        if (lang != "hi" || country != "IN") return false
        val tag = locale.toLanguageTag().lowercase(Locale.ROOT)
        return tag == "hi-in" || tag.startsWith("hi-in-")
    }

    /** Backwards-compatible alias locked strictly to Hindi (India) `hi-IN`. */
    fun isSupportedLanguage(locale: Locale): Boolean = isHindiIndiaLocale(locale)

    /**
     * Classifies a voice's gender using ONLY genuine engine metadata (`features`) or
     * explicit user confirmation ([manualOverrides]).
     * Never guesses from voice names; returns [VoiceGender.Unspecified] when absent.
     */
    fun classifyGenderWithSource(
        voiceName: String,
        features: Set<String>?,
        manualOverrides: Map<String, VoiceGender> = emptyMap(),
    ): Pair<VoiceGender, VoiceGenderSource> {
        val manual = manualOverrides[voiceName]
        if (manual == VoiceGender.Female || manual == VoiceGender.Male) {
            return manual to VoiceGenderSource.UserConfirmed
        }

        val normalizedFeatures = features
            ?.asSequence()
            ?.map { it.trim().lowercase(Locale.ROOT) }
            ?.filter { it.isNotEmpty() }
            ?.toList()
            ?: emptyList()

        val isFemale = normalizedFeatures.any { token ->
            token == "female" ||
                token.contains("gender=female") ||
                token.contains("gender:female") ||
                token.contains("voicegender=female") ||
                token.contains("voice_gender=female") ||
                token.contains("sex=female")
        }
        if (isFemale) {
            return VoiceGender.Female to VoiceGenderSource.Metadata
        }

        val isMale = normalizedFeatures.any { token ->
            !token.contains("female") && (
                token == "male" ||
                    token.contains("gender=male") ||
                    token.contains("gender:male") ||
                    token.contains("voicegender=male") ||
                    token.contains("voice_gender=male") ||
                    token.contains("sex=male")
                )
        }
        if (isMale) {
            return VoiceGender.Male to VoiceGenderSource.Metadata
        }

        return VoiceGender.Unspecified to VoiceGenderSource.Unavailable
    }

    fun classifyGender(
        voiceName: String,
        features: Set<String>?,
        manualOverrides: Map<String, VoiceGender> = emptyMap(),
    ): VoiceGender = classifyGenderWithSource(voiceName, features, manualOverrides).first

    /** Builds the ranked, deduplicated Hindi (India) catalog capped at [maxPerCategory] per gender. */
    fun buildHindiCatalog(
        rawVoices: Collection<RawTtsVoice>,
        manualOverrides: Map<String, VoiceGender> = emptyMap(),
        maxPerCategory: Int = HindiVoiceCatalog.TARGET_COUNT_PER_CATEGORY,
    ): HindiVoiceCatalog {
        val comparator = compareByDescending<TtsVoiceInfo> { it.isInstalled }
            .thenBy { it.isNetworkRequired }
            .thenByDescending { it.qualityScore }
            .thenBy { it.id }

        val deduplicatedHindi = rawVoices
            .asSequence()
            .filter { it.id.isNotBlank() && isHindiIndiaLocale(it.locale) }
            .map { raw ->
                val cleanId = raw.id.trim()
                val (gender, source) = classifyGenderWithSource(cleanId, raw.features, manualOverrides)
                val isInstalled = raw.features?.any { it.equals(FEATURE_NOT_INSTALLED, ignoreCase = true) } != true
                TtsVoiceInfo(
                    id = cleanId,
                    displayName = formatDisplayName(cleanId, gender, raw.locale),
                    gender = gender,
                    genderSource = source,
                    locale = raw.locale,
                    isNetworkRequired = raw.isNetworkRequired,
                    quality = qualityLabel(raw.qualityScore),
                    qualityScore = raw.qualityScore,
                    isInstalled = isInstalled,
                )
            }
            .sortedWith(comparator)
            .distinctBy { it.id }
            .toList()

        val allFemale = deduplicatedHindi
            .filter { it.gender == VoiceGender.Female }
            .mapIndexed { idx, info ->
                info.copy(displayName = formatDisplayName(info.id, info.gender, info.locale, idx + 1))
            }

        val allMale = deduplicatedHindi
            .filter { it.gender == VoiceGender.Male }
            .mapIndexed { idx, info ->
                info.copy(displayName = formatDisplayName(info.id, info.gender, info.locale, idx + 1))
            }

        val unclassified = deduplicatedHindi
            .filter { it.gender == VoiceGender.Unspecified }
            .mapIndexed { idx, info ->
                info.copy(displayName = formatDisplayName(info.id, info.gender, info.locale, idx + 1))
            }

        return HindiVoiceCatalog(
            femaleVoices = allFemale.take(maxPerCategory.coerceAtLeast(0)),
            maleVoices = allMale.take(maxPerCategory.coerceAtLeast(0)),
            unclassifiedVoices = unclassified,
            totalFemaleFound = allFemale.size,
            totalMaleFound = allMale.size,
            totalHindiVoicesCount = deduplicatedHindi.size,
            installedHindiVoicesCount = deduplicatedHindi.count { it.isInstalled },
            offlineHindiVoicesCount = deduplicatedHindi.count { it.isOfflineCapable },
        )
    }

    fun qualityLabel(qualityScore: Int): String = when {
        qualityScore >= 500 -> "Very High"
        qualityScore >= 400 -> "High"
        qualityScore >= 300 -> "Standard"
        qualityScore >= 200 -> "Low"
        else -> "Basic"
    }

    /** Formats an honest, clean human-readable label for a genuine hi-IN voice. */
    fun formatDisplayName(
        voiceName: String,
        gender: VoiceGender,
        locale: Locale,
        indexWithinGroup: Int? = null,
    ): String {
        val langName = if (isHindiIndiaLocale(locale)) {
            "Hindi (India)"
        } else {
            locale.displayLanguage.ifBlank { locale.language }
        }

        val genderPrefix = when (gender) {
            VoiceGender.Female -> "Female Voice"
            VoiceGender.Male -> "Male Voice"
            VoiceGender.Unspecified -> "Hindi Voice"
        }

        val indexSuffix = if (indexWithinGroup != null && indexWithinGroup > 0) " $indexWithinGroup" else ""

        val variant = voiceName.split("-", "_").takeIf { it.size >= 3 }?.let { parts ->
            parts.drop(2)
                .filterNot { it.equals("network", ignoreCase = true) || it.equals("local", ignoreCase = true) || it.equals("x", ignoreCase = true) }
                .joinToString("-")
                .trim()
        }?.takeIf { it.isNotBlank() }

        return if (variant != null) {
            "$langName • $genderPrefix$indexSuffix ($variant)"
        } else {
            "$langName • $genderPrefix$indexSuffix"
        }
    }

    /** Localized preview sample text for this voice's locale. */
    fun sampleTextForLocale(locale: Locale): String {
        return when (locale.language.lowercase(Locale.ROOT)) {
            "hi" -> "नमस्ते! मैं जार्विस हूँ। बताइए, मैं आपकी क्या मदद कर सकता हूँ?"
            else -> "Hello! I am JARVIS. How can I help you today?"
        }
    }
}
