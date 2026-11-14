package app.shilex.android.audio

import app.shilex.android.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class VoiceClassifierTest {

    @Test
    fun filtersStrictlyToHindiIndiaLocaleAndRejectsEnglish() {
        assertTrue(VoiceClassifier.isHindiIndiaLocale(Locale("hi", "IN")))
        assertTrue(VoiceClassifier.isSupportedLanguage(Locale("hi", "IN")))

        assertFalse(VoiceClassifier.isHindiIndiaLocale(Locale("en", "IN")))
        assertFalse(VoiceClassifier.isHindiIndiaLocale(Locale.US))
        assertFalse(VoiceClassifier.isHindiIndiaLocale(Locale.UK))
        assertFalse(VoiceClassifier.isHindiIndiaLocale(Locale("hi", "US")))
        assertFalse(VoiceClassifier.isHindiIndiaLocale(Locale("hi")))
        assertFalse(VoiceClassifier.isHindiIndiaLocale(Locale.JAPANESE))
    }

    @Test
    fun classifiesGenderStrictlyFromMetadataOrUserConfirmationNeverFromNames() {
        // Explicit metadata works
        assertEquals(
            VoiceGender.Female to VoiceGenderSource.Metadata,
            VoiceClassifier.classifyGenderWithSource("hi-in-x-hia-local", setOf("gender=female")),
        )
        assertEquals(
            VoiceGender.Male to VoiceGenderSource.Metadata,
            VoiceClassifier.classifyGenderWithSource("hi-in-x-hid-local", setOf("gender=male")),
        )
        // Must not mistake "female" substring for "male"
        assertEquals(
            VoiceGender.Female,
            VoiceClassifier.classifyGender("hi-in-x-hif-network", setOf("female")),
        )

        // Never guesses from opaque or suggestive voice names when metadata is absent
        assertEquals(
            VoiceGender.Unspecified to VoiceGenderSource.Unavailable,
            VoiceClassifier.classifyGenderWithSource("hi-in-x-hia-local", setOf("networkTts")),
        )
        assertEquals(
            VoiceGender.Unspecified,
            VoiceClassifier.classifyGender("hi-in-standard-female-name-only", emptySet()),
        )

        // Manual user confirmation overrides cleanly after preview
        val manual = mapOf("hi-in-x-hia-local" to VoiceGender.Female)
        assertEquals(
            VoiceGender.Female to VoiceGenderSource.UserConfirmed,
            VoiceClassifier.classifyGenderWithSource("hi-in-x-hia-local", setOf("networkTts"), manual),
        )
    }

    @Test
    fun buildsDeduplicatedFivePlusFiveHindiCatalogAndReportsMissingCounts() {
        val raw = mutableListOf<RawTtsVoice>()
        // English voices must never appear
        raw += RawTtsVoice("en-in-x-end-local", Locale("en", "IN"), isNetworkRequired = false, qualityScore = 500, features = setOf("gender=female"))
        raw += RawTtsVoice("en-us-x-sfg-local", Locale.US, isNetworkRequired = false, qualityScore = 500, features = setOf("gender=male"))

        // 7 female hi-IN voices (with 1 duplicate ID, 1 online, 1 not installed) -> capped at top 5
        raw += RawTtsVoice("hi-in-f-low", Locale("hi", "IN"), isNetworkRequired = false, qualityScore = 200, features = setOf("gender=female"))
        raw += RawTtsVoice("hi-in-f-not-installed", Locale("hi", "IN"), isNetworkRequired = false, qualityScore = 500, features = setOf("gender=female", "notInstalled"))
        raw += RawTtsVoice("hi-in-f-online", Locale("hi", "IN"), isNetworkRequired = true, qualityScore = 500, features = setOf("gender=female"))
        raw += RawTtsVoice("hi-in-f-1", Locale("hi", "IN"), isNetworkRequired = false, qualityScore = 500, features = setOf("gender=female"))
        raw += RawTtsVoice("hi-in-f-1", Locale("hi", "IN"), isNetworkRequired = false, qualityScore = 400, features = setOf("gender=female")) // duplicate ID
        raw += RawTtsVoice("hi-in-f-2", Locale("hi", "IN"), isNetworkRequired = false, qualityScore = 400, features = setOf("gender=female"))
        raw += RawTtsVoice("hi-in-f-3", Locale("hi", "IN"), isNetworkRequired = false, qualityScore = 350, features = setOf("gender=female"))
        raw += RawTtsVoice("hi-in-f-4", Locale("hi", "IN"), isNetworkRequired = false, qualityScore = 300, features = setOf("gender=female"))

        // Only 2 male hi-IN voices -> shows 2, reports missing = 3
        raw += RawTtsVoice("hi-in-m-1", Locale("hi", "IN"), isNetworkRequired = false, qualityScore = 400, features = setOf("gender=male"))
        raw += RawTtsVoice("hi-in-m-2", Locale("hi", "IN"), isNetworkRequired = true, qualityScore = 300, features = setOf("gender=male"))

        // 1 unclassified hi-IN voice (omits gender metadata) -> stays unclassified
        raw += RawTtsVoice("hi-in-x-hia-local", Locale("hi", "IN"), isNetworkRequired = false, qualityScore = 400, features = emptySet())

        val catalog = VoiceClassifier.buildHindiCatalog(raw)

        // All voices strictly hi-IN
        assertTrue((catalog.femaleVoices + catalog.maleVoices + catalog.unclassifiedVoices).all {
            it.locale == Locale("hi", "IN")
        })
        assertFalse((catalog.femaleVoices + catalog.maleVoices).any { it.id.startsWith("en-") })

        // Capped at 5 Female, exact 2 Male + missing count = 3
        assertEquals(5, catalog.femaleVoices.size)
        assertEquals(0, catalog.missingFemaleCount)
        assertEquals(7, catalog.totalFemaleFound) // deduplicated 7 female voices total
        assertEquals(2, catalog.maleVoices.size)
        assertEquals(3, catalog.missingMaleCount)

        // Ranking: installed + offline + highest quality first
        assertEquals("hi-in-f-1", catalog.femaleVoices[0].id)
        assertEquals("hi-in-f-2", catalog.femaleVoices[1].id)
        assertEquals("hi-in-f-3", catalog.femaleVoices[2].id)
        assertEquals("hi-in-f-4", catalog.femaleVoices[3].id)
        assertEquals("hi-in-f-low", catalog.femaleVoices[4].id) // installed+offline before online/not-installed

        // Unclassified stays separate and is NOT auto-labeled Female or Male
        assertEquals(1, catalog.unclassifiedVoices.size)
        assertEquals("hi-in-x-hia-local", catalog.unclassifiedVoices[0].id)
        assertEquals(VoiceGender.Unspecified, catalog.unclassifiedVoices[0].gender)

        // After user listens & manually classifies hi-in-x-hia-local as Male -> moves into Male Voices
        val updatedCatalog = VoiceClassifier.buildHindiCatalog(raw, mapOf("hi-in-x-hia-local" to VoiceGender.Male))
        assertEquals(0, updatedCatalog.unclassifiedVoices.size)
        assertEquals(3, updatedCatalog.maleVoices.size)
        assertEquals(2, updatedCatalog.missingMaleCount)
        assertTrue(updatedCatalog.maleVoices.any {
            it.id == "hi-in-x-hia-local" && it.genderSource == VoiceGenderSource.UserConfirmed
        })
    }

    @Test
    fun reportsFiveMissingWhenEngineHasZeroHindiVoices() {
        val onlyEnglish = listOf(
            RawTtsVoice("en-in-x-end-local", Locale("en", "IN"), isNetworkRequired = false, qualityScore = 400, features = setOf("gender=female")),
            RawTtsVoice("en-us-x-sfg-local", Locale.US, isNetworkRequired = false, qualityScore = 400, features = setOf("gender=male")),
        )
        val catalog = VoiceClassifier.buildHindiCatalog(onlyEnglish)
        assertEquals(0, catalog.femaleVoices.size)
        assertEquals(0, catalog.maleVoices.size)
        assertEquals(5, catalog.missingFemaleCount)
        assertEquals(5, catalog.missingMaleCount)
        assertEquals(0, catalog.totalHindiVoicesCount)
    }

    @Test
    fun settingsFilterNormalizationAndManualClassificationRoundTrip() {
        assertEquals(Settings.GENDER_FEMALE, Settings.normalizeGenderFilter("ALL"))
        assertEquals(Settings.GENDER_FEMALE, Settings.normalizeGenderFilter(null))
        assertEquals(Settings.GENDER_FEMALE, Settings.normalizeGenderFilter("FEMALE"))
        assertEquals(Settings.GENDER_MALE, Settings.normalizeGenderFilter("male"))

        val map = mapOf(
            "hi-in-x-hia-local" to VoiceGender.Female,
            "hi-in|special%id" to VoiceGender.Male,
        )
        val encoded = Settings.encodeManualVoiceGenders(map)
        val decoded = Settings.decodeManualVoiceGenders(encoded)
        assertEquals(map, decoded)

        val hiSample = VoiceClassifier.sampleTextForLocale(Locale("hi", "IN"))
        assertTrue(hiSample.contains("जार्विस"))
    }
}
