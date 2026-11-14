package app.shilex.android.audio

import java.util.Locale

/**
 * Standard classification for TTS voices.
 * As per project policy: we strictly use genuine available TTS profiles.
 * If gender metadata is absent or unverified, it is classified as [Unspecified].
 */
enum class VoiceGender(val label: String) {
    Female("Female"),
    Male("Male"),
    Unspecified("Neutral / Unspecified"),
}

/**
 * Metadata for an installed or available TextToSpeech voice.
 */
data class TtsVoiceInfo(
    val id: String,
    val displayName: String,
    val gender: VoiceGender,
    val locale: Locale,
    val isNetworkRequired: Boolean,
    val quality: String,
    val isInstalled: Boolean = true,
) {
    val languageDisplayName: String
        get() = locale.getDisplayName(Locale.getDefault()).ifBlank { locale.toLanguageTag() }

    val localeTag: String
        get() = locale.toLanguageTag()
}

/**
 * Pure parsing, filtering, and text localization for TTS voices.
 * Completely deterministic and unit-testable without requiring an Android device.
 */
object VoiceClassifier {

    /**
     * Classifies a voice's gender based on its name and features.
     * Does NOT guess: returns [VoiceGender.Unspecified] if metadata is absent or ambiguous.
     */
    fun classifyGender(voiceName: String, features: Set<String>?): VoiceGender {
        val lowerName = voiceName.lowercase()
        val featureList = features?.map { it.lowercase() } ?: emptyList()

        // 1. Explicit features
        if (featureList.any { it.contains("gender=female") || it == "female" }) return VoiceGender.Female
        if (featureList.any { (it.contains("gender=male") || it == "male") && !it.contains("female") }) return VoiceGender.Male

        // 2. Clear name tags used by Google TTS, Samsung TTS, and other standard engines
        if (lowerName.contains("#female") || lowerName.contains("-female") || lowerName.contains("_female")) {
            return VoiceGender.Female
        }
        if (lowerName.contains("#male") || lowerName.contains("-male") || lowerName.contains("_male")) {
            return VoiceGender.Male
        }

        // 3. Known Google TTS voice naming schemes:
        // Google TTS voice IDs often look like: "en-in-x-end-network" or "en-in-x-ene-local"
        // Endings: 'd', 'a', 'c', 'f' are female in specific Google models, but without standard tags
        // we strictly do not guess to avoid mislabeling.
        if (lowerName.endsWith("-female") || lowerName.endsWith("_f") || lowerName.contains("female")) {
            return VoiceGender.Female
        }
        if (lowerName.endsWith("-male") || lowerName.endsWith("_m") || (lowerName.contains("male") && !lowerName.contains("female"))) {
            return VoiceGender.Male
        }

        return VoiceGender.Unspecified
    }

    /**
     * Formats a clean human-readable name for display.
     */
    fun formatDisplayName(
        voiceName: String,
        gender: VoiceGender,
        locale: Locale,
        indexWithinGroup: Int? = null,
    ): String {
        val langName = when (locale.language.lowercase()) {
            "en" -> when (locale.country.uppercase()) {
                "IN" -> "English (India)"
                "US" -> "English (US)"
                "GB", "UK" -> "English (UK)"
                "AU" -> "English (Australia)"
                else -> "English"
            }
            "hi" -> "Hindi (India)"
            else -> locale.displayLanguage.ifBlank { locale.language }
        }

        val genderPrefix = when (gender) {
            VoiceGender.Female -> "Female"
            VoiceGender.Male -> "Male"
            VoiceGender.Unspecified -> "Voice"
        }

        val indexSuffix = if (indexWithinGroup != null && indexWithinGroup > 0) " $indexWithinGroup" else ""

        // Extract any short variant code from the name (e.g. "x-end" from "en-in-x-end-network")
        val variant = voiceName.split("-").takeIf { it.size >= 3 }?.let { parts ->
            parts.drop(2).joinToString(" ").replace("network", "").replace("local", "").trim()
        }?.takeIf { it.isNotBlank() }

        return if (variant != null) {
            "$langName • $genderPrefix$indexSuffix ($variant)"
        } else {
            "$langName • $genderPrefix$indexSuffix"
        }
    }

    /**
     * Localized preview sample text for this voice's locale.
     */
    fun sampleTextForLocale(locale: Locale): String {
        return when (locale.language.lowercase()) {
            "hi" -> "नमस्ते! मैं जार्विस हूँ। मैं आपकी क्या मदद कर सकता हूँ?"
            else -> "Hello! I am JARVIS. How can I help you today?"
        }
    }

    /**
     * Filters voices by target languages (primarily English and Hindi, plus system default).
     */
    fun isSupportedLanguage(locale: Locale): Boolean {
        val lang = locale.language.lowercase()
        return lang == "en" || lang == "hi" || locale == Locale.getDefault()
    }
}
