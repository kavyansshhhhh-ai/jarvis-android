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
    fun classifiesGenderAutomaticallyFromReliableMetadataNeverFromNames() {
        assertEquals(
            VoiceGender.Female,
            VoiceClassifier.classifyGender("hi-in-x-hia-local", setOf("gender=female")),
        )
        assertEquals(
            VoiceGender.Male,
            VoiceClassifier.classifyGender("hi-in-x-hid-local", setOf("gender=male")),
        )
        assertEquals(
            VoiceGender.Female,
            VoiceClassifier.classifyGender("hi-in-x-hif-network", setOf("female")),
        )

        // Never guesses from opaque or suggestive voice names when metadata is absent
        assertEquals(
            VoiceGender.Unspecified,
            VoiceClassifier.classifyGender("hi-in-x-hia-local", setOf("networkTts")),
        )
        assertEquals(
            VoiceGender.Unspecified,
            VoiceClassifier.classifyGender("hi-in-standard-female-name-only", emptySet()),
        )
    }

    @Test
    fun buildsDeduplicatedFivePlusFiveHindiCatalogWithCleanNonTechnicalNames() {
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

        // Only 2 male hi-IN voices -> shows 2 honestly
        raw += RawTtsVoice("hi-in-m-1", Locale("hi", "IN"), isNetworkRequired = false, qualityScore = 400, features = setOf("gender=male"))
        raw += RawTtsVoice("hi-in-m-2", Locale("hi", "IN"), isNetworkRequired = true, qualityScore = 300, features = setOf("gender=male"))

        // Unclassified hi-IN voice (no gender metadata) -> excluded cleanly without guessing or inventing
        raw += RawTtsVoice("hi-in-x-hia-local", Locale("hi", "IN"), isNetworkRequired = false, qualityScore = 400, features = emptySet())

        val catalog = VoiceClassifier.buildHindiCatalog(raw)

        // All voices strictly hi-IN
        assertTrue((catalog.femaleVoices + catalog.maleVoices).all { it.locale == Locale("hi", "IN") })
        assertFalse((catalog.femaleVoices + catalog.maleVoices).any { it.id.startsWith("en-") })

        // Capped at 5 Female, exact 2 Male
        assertEquals(5, catalog.femaleVoices.size)
        assertEquals(7, catalog.totalFemaleFound)
        assertEquals(2, catalog.maleVoices.size)
        assertEquals(2, catalog.totalMaleFound)

        // Display names are clean and free of voice IDs or technical jargon
        assertEquals("Hindi Female 1", catalog.femaleVoices[0].displayName)
        assertEquals("Hindi Female 5", catalog.femaleVoices[4].displayName)
        assertEquals("Hindi Male 1", catalog.maleVoices[0].displayName)
        assertEquals("Hindi Male 2", catalog.maleVoices[1].displayName)

        // Ranking: installed + offline + highest quality first
        assertEquals("hi-in-f-1", catalog.femaleVoices[0].id)
        assertEquals("hi-in-f-2", catalog.femaleVoices[1].id)
        assertEquals("hi-in-f-3", catalog.femaleVoices[2].id)
        assertEquals("hi-in-f-4", catalog.femaleVoices[3].id)
        assertEquals("hi-in-f-low", catalog.femaleVoices[4].id)
    }

    @Test
    fun showsEmptyListsWithoutFabricatingWhenEngineHasNoGenderedHindiVoices() {
        val raw = listOf(
            RawTtsVoice("en-in-x-end-local", Locale("en", "IN"), isNetworkRequired = false, qualityScore = 400, features = setOf("gender=female")),
            RawTtsVoice("hi-in-x-hia-local", Locale("hi", "IN"), isNetworkRequired = false, qualityScore = 400, features = emptySet()),
        )
        val catalog = VoiceClassifier.buildHindiCatalog(raw)
        assertEquals(0, catalog.femaleVoices.size)
        assertEquals(0, catalog.maleVoices.size)
        assertEquals(1, catalog.totalHindiVoicesCount)
    }

    @Test
    fun settingsFilterAndHindiPreviewSampleWorkCorrectly() {
        assertEquals(Settings.GENDER_FEMALE, Settings.normalizeGenderFilter("ALL"))
        assertEquals(Settings.GENDER_FEMALE, Settings.normalizeGenderFilter(null))
        assertEquals(Settings.GENDER_FEMALE, Settings.normalizeGenderFilter("FEMALE"))
        assertEquals(Settings.GENDER_MALE, Settings.normalizeGenderFilter("male"))

        val hiSample = VoiceClassifier.sampleTextForLocale(Locale("hi", "IN"))
        assertTrue(hiSample.contains("जार्विस"))
    }

    @Test
    fun bundledHindiVoicesAreGenuineDistinctAndOfflineCapable() {
        val voices = BundledHindiVoices.ALL
        assertEquals(3, voices.size)

        // Priyamvada (Female)
        val female = voices.filter { it.gender == VoiceGender.Female }
        assertEquals(1, female.size)
        assertEquals("bundled_hi_female_priyamvada", female[0].id)
        assertEquals("Hindi Female 1", female[0].displayName)
        assertTrue(female[0].modelAssetPath.endsWith(".onnx"))
        assertTrue(female[0].configAssetPath.endsWith(".json"))
        assertTrue(female[0].sampleAssetPath.endsWith(".mp3"))
        assertEquals(22050, female[0].sampleRate)
        assertTrue(female[0].licenseNotice.isNotBlank())
        assertTrue(female[0].datasetSource.contains("AI4Bharat"))

        // Pratham & Rohan (Male)
        val male = voices.filter { it.gender == VoiceGender.Male }
        assertEquals(2, male.size)
        assertEquals("bundled_hi_male_pratham", male[0].id)
        assertEquals("Hindi Male 1", male[0].displayName)
        assertEquals("bundled_hi_male_rohan", male[1].id)
        assertEquals("Hindi Male 2", male[1].displayName)
        assertTrue(male.all { it.locale == Locale("hi", "IN") })
        assertTrue(male.all { it.toTtsVoiceInfo().isOfflineCapable })
    }

    @Test
    fun buildCatalogPrioritizesBundledVoicesAndHonorsFiveVoiceCap() {
        val bundled = BundledHindiVoices.ALL.map { it.toTtsVoiceInfo() }
        val catalog = VoiceClassifier.buildHindiCatalog(emptyList(), bundled)

        assertEquals(1, catalog.femaleVoices.size)
        assertEquals("Hindi Female 1", catalog.femaleVoices[0].displayName)
        assertEquals("bundled_hi_female_priyamvada", catalog.femaleVoices[0].id)
        assertTrue(catalog.femaleVoices[0].isBundled)
        assertTrue(catalog.femaleVoices[0].isOfflineCapable)

        assertEquals(2, catalog.maleVoices.size)
        assertEquals("Hindi Male 1", catalog.maleVoices[0].displayName)
        assertEquals("bundled_hi_male_pratham", catalog.maleVoices[0].id)
        assertEquals("Hindi Male 2", catalog.maleVoices[1].displayName)
        assertEquals("bundled_hi_male_rohan", catalog.maleVoices[1].id)
        assertTrue(catalog.maleVoices.all { it.isBundled && it.isOfflineCapable })
    }
}

