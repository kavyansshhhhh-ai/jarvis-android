package app.shilex.android.wake

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhraseMatcherTest {

    @Test
    fun matchesPrimaryWakePhraseAndVariations() {
        val matcher = WakePhraseMatcher()
        listOf(
            """{"text": "jarvis"}""",
            """{"text": "hey jarvis"}""",
            """{"text": "hi jarvis"}""",
            """{"text": "ok jarvis"}""",
            """{"text": "okay jarvis"}""",
            """{"text": "[unk] jarvis"}""",
            """{"text": "jarvis", "result": [{"word": "jarvis", "conf": 0.91}]}""",
            """{"text": "hey jarvis", "result": [{"word": "hey", "conf": 0.85}, {"word": "jarvis", "conf": 0.88}]}""",
        ).forEach { json ->
            val result = matcher.evaluateFinal(json)
            assertTrue("Expected wake match for $json", result.matched)
        }
    }

    @Test
    fun partialRequiresConsistentOrHighConfidenceMatches() {
        val matcher = WakePhraseMatcher()
        // First single low-confidence partial shouldn't trigger yet (prevents blip false positives)
        val first = matcher.evaluatePartial(
            """{"partial": "jarvis", "partial_result": [{"word": "jarvis", "conf": 0.62}]}""",
        )
        assertFalse(first.matched)

        // Second consecutive consistent partial confirms activation
        val second = matcher.evaluatePartial(
            """{"partial": "jarvis", "partial_result": [{"word": "jarvis", "conf": 0.64}]}""",
        )
        assertTrue(second.matched)
        assertEquals("jarvis", second.phrase)

        // Single partial with very high confidence triggers promptly
        matcher.reset()
        val highConf = matcher.evaluatePartial(
            """{"partial": "hey jarvis", "partial_result": [{"word": "hey", "conf": 0.80}, {"word": "jarvis", "conf": 0.85}]}""",
        )
        assertTrue(highConf.matched)
        assertEquals("hey jarvis", highConf.phrase)
    }

    @Test
    fun rejectsFalseActivationsAndDistractors() {
        val matcher = WakePhraseMatcher()
        listOf(
            """{"text": ""}""",
            """{"text": "[unk]"}""",
            """{"text": "service"}""",
            """{"text": "harvest"}""",
            """{"text": "travis"}""",
            """{"text": "nervous"}""",
            """{"text": "office"}""",
            """{"text": "paris"}""",
            """{"text": "jarvison"}""",
            """{"text": "service jarvis"}""",
            """{"text": "jarvis", "result": [{"word": "jarvis", "conf": 0.28}]}""",
        ).forEach { json ->
            val result = matcher.evaluateFinal(json)
            assertFalse("Expected rejection for $json", result.matched)
        }

        // Distractor in partial stream resets partial counter
        matcher.evaluatePartial("""{"partial": "jarvis", "partial_result": [{"word": "jarvis", "conf": 0.60}]}""")
        assertFalse(matcher.evaluatePartial("""{"partial": "service"}""").matched)
        assertFalse(
            matcher.evaluatePartial("""{"partial": "jarvis", "partial_result": [{"word": "jarvis", "conf": 0.60}]}""").matched,
        )
    }

    @Test
    fun grammarIncludesWakePhrasesDistractorsAndUnknownSink() {
        val entries = JSONArray(WakePhraseMatcher.GRAMMAR_JSON).let { arr ->
            List(arr.length()) { arr.getString(it) }
        }
        assertTrue("jarvis" in entries)
        assertTrue("hey jarvis" in entries)
        assertTrue("service" in entries)
        assertTrue("[unk]" in entries)
    }
}
