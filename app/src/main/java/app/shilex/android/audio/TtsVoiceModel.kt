package app.shilex.android.audio

import java.util.Locale

/**
 * Automatic gender classification for genuine Hindi (India) voices.
 * Uses ONLY reliable engine metadata; never guesses from opaque voice names or pitch.
 */
enum class VoiceGender(val label: String) {
    Female("Female"),
    Male("Male"),
    Unspecified("Unspecified"),
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
 * Clean representation of a genuine Hindi (India) voice ready for the JARVIS VOICE UI.
 */
data class TtsVoiceInfo(
    val id: String,
    val displayName: String,
    val gender: VoiceGender,
    val locale: Locale,
    val isNetworkRequired: Boolean,
    val qualityScore: Int = 300,
    val isInstalled: Boolean = true,
) {
    val isOfflineCapable: Boolean
        get() = isInstalled && !isNetworkRequired
}

/**
 * Structured catalog of genuine Hindi (India) voices separated into
 * Female Voices (max 5) and Male Voices (max 5).
 */
data class HindiVoiceCatalog(
    val femaleVoices: List<TtsVoiceInfo> = emptyList(),
    val maleVoices: List<TtsVoiceInfo> = emptyList(),
    val totalFemaleFound: Int = 0,
    val totalMaleFound: Int = 0,
    val totalHindiVoicesCount: Int = 0,
    val installedHindiVoicesCount: Int = 0,
) {
    companion object {
        const val TARGET_COUNT_PER_CATEGORY = 5
    }
}

/** Internal diagnostics about device Hindi voice availability. */
data class TtsEngineDiagnostics(
    val activeEnginePackage: String? = null,
    val isGoogleSpeechServicesActive: Boolean = false,
    val isGoogleSpeechServicesInstalled: Boolean = false,
    val hindiLanguageAvailability: Int? = null,
    val totalEngineVoicesCount: Int = 0,
    val totalHindiVoicesCount: Int = 0,
    val installedHindiVoicesCount: Int = 0,
)

/**
 * Pure parsing, strict Hindi (India) filtering, ranking, and automatic gender organization.
 * Deterministic and unit-testable without requiring an Android device.
 */
object VoiceClassifier {
    const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
    private const val FEATURE_NOT_INSTALLED = "notInstalled"

    /**
     * Strictly accepts Hindi (India), locale `hi-IN`.
     * Rejects English (`en-IN`, `en-US`, etc.) and any other locales.
     */
    fun isHindiIndiaLocale(locale: Locale?): Boolean {
        if (locale == null) return false
        val lang = locale.language.lowercase(Locale.ROOT)
        val country = locale.country.uppercase(Locale.ROOT)
        if (lang != "hi" || country != "IN") return false
        val tag = locale.toLanguageTag().lowercase(Locale.ROOT)
        return tag == "hi-in" || tag.startsWith("hi-in-")
    }

    /** Alias locked strictly to Hindi (India). */
    fun isSupportedLanguage(locale: Locale): Boolean = isHindiIndiaLocale(locale)

    /**
     * Automatically identifies gender from reliable voice feature metadata only.
     * Never guesses from voice names or pitch; returns [VoiceGender.Unspecified] if absent.
     */
    fun classifyGender(
        voiceName: String,
        features: Set<String>?,
    ): VoiceGender {
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
        if (isFemale) return VoiceGender.Female

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
        if (isMale) return VoiceGender.Male

        return VoiceGender.Unspecified
    }

    /**
     * Builds the deduplicated, automatically organized Hindi (India) catalog capped at
     * [maxPerCategory] Female voices and [maxPerCategory] Male voices.
     * Voices without reliable gender metadata are excluded rather than guessed or faked.
     */
    fun buildHindiCatalog(
        rawVoices: Collection<RawTtsVoice>,
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
                val gender = classifyGender(cleanId, raw.features)
                val isInstalled = raw.features?.any { it.equals(FEATURE_NOT_INSTALLED, ignoreCase = true) } != true
                TtsVoiceInfo(
                    id = cleanId,
                    displayName = formatDisplayName(gender),
                    gender = gender,
                    locale = raw.locale,
                    isNetworkRequired = raw.isNetworkRequired,
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
                info.copy(displayName = formatDisplayName(VoiceGender.Female, idx + 1))
            }

        val allMale = deduplicatedHindi
            .filter { it.gender == VoiceGender.Male }
            .mapIndexed { idx, info ->
                info.copy(displayName = formatDisplayName(VoiceGender.Male, idx + 1))
            }

        return HindiVoiceCatalog(
            femaleVoices = allFemale.take(maxPerCategory.coerceAtLeast(0)),
            maleVoices = allMale.take(maxPerCategory.coerceAtLeast(0)),
            totalFemaleFound = allFemale.size,
            totalMaleFound = allMale.size,
            totalHindiVoicesCount = deduplicatedHindi.size,
            installedHindiVoicesCount = deduplicatedHindi.count { it.isInstalled },
        )
    }

    /** Clean, non-technical display name for the user-facing list. */
    fun formatDisplayName(
        gender: VoiceGender,
        indexWithinGroup: Int? = null,
    ): String {
        val base = when (gender) {
            VoiceGender.Female -> "Hindi Female"
            VoiceGender.Male -> "Hindi Male"
            VoiceGender.Unspecified -> "Hindi Voice"
        }
        return if (indexWithinGroup != null && indexWithinGroup > 0) {
            "$base $indexWithinGroup"
        } else {
            base
        }
    }

    /** Localized Hindi preview sentence. */
    fun sampleTextForLocale(locale: Locale): String {
        return when (locale.language.lowercase(Locale.ROOT)) {
            "hi" -> "नमस्ते! मैं जार्विस हूँ। बताइए, मैं आपकी क्या मदद कर सकता हूँ?"
            else -> "Hello! I am JARVIS. How can I help you today?"
        }
    }
}
