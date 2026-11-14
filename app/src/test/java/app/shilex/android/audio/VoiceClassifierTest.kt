package app.shilex.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class VoiceClassifierTest {

    @Test
    fun classifiesExplicitFemaleAndMaleVoicesAccuratelyWithoutGuessing() {
        assertEquals(
            VoiceGender.Female,
            VoiceClassifier.classifyGender("en-in-x-end-network", setOf("gender=female")),
        )
        assertEquals(
            VoiceGender.Male,
            VoiceClassifier.classifyGender("en-us-x-sfg-local", setOf("gender=male")),
        )
        assertEquals(
            VoiceGender.Female,
            VoiceClassifier.classifyGender("en-US-standard-female", null),
        )
        assertEquals(
            VoiceGender.Male,
            VoiceClassifier.classifyGender("hi_IN_male_1", emptySet()),
        )
        // Must not mistake "female" substring for "male"
        assertEquals(
            VoiceGender.Female,
            VoiceClassifier.classifyGender("voice_female_hq", setOf("female")),
        )
        // Ambiguous Google TTS ID without explicit metadata stays Unspecified
        assertEquals(
            VoiceGender.Unspecified,
            VoiceClassifier.classifyGender("en-in-x-ahp-local", setOf("networkTts")),
        )
    }

    @Test
    fun formatsDisplayNamesAndLocalizedSampleSentencesCleanly() {
        val enInName = VoiceClassifier.formatDisplayName("en-in-x-end-network", VoiceGender.Female, Locale("en", "IN"))
        assertTrue(enInName.contains("English (India)"))
        assertTrue(enInName.contains("Female"))

        val hiSample = VoiceClassifier.sampleTextForLocale(Locale("hi", "IN"))
        assertTrue(hiSample.contains("जार्विस"))

        val enSample = VoiceClassifier.sampleTextForLocale(Locale.US)
        assertTrue(enSample.contains("JARVIS"))
    }

    @Test
    fun filtersSupportedLanguagesToEnglishHindiOrDeviceLocale() {
        assertTrue(VoiceClassifier.isSupportedLanguage(Locale("en", "IN")))
        assertTrue(VoiceClassifier.isSupportedLanguage(Locale("hi", "IN")))
        assertTrue(VoiceClassifier.isSupportedLanguage(Locale.getDefault()))
        if (Locale.getDefault().language != "ja") {
            assertFalse(VoiceClassifier.isSupportedLanguage(Locale.JAPANESE))
        }
    }
}
