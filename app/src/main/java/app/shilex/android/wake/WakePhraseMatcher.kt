package app.shilex.android.wake

import org.json.JSONArray
import org.json.JSONObject

/**
 * Matches offline recognizer output against the wake phrase ("Jarvis") and natural variations
 * ("Hey Jarvis", "Hi Jarvis", "Ok Jarvis", "Okay Jarvis"), rejecting common false-positive
 * sound-alikes and unstable transient partial noise. Pure so it is unit-tested without Android.
 */
class WakePhraseMatcher {
    data class Result(
        val matched: Boolean,
        val phrase: String? = null,
        val confidence: Float = 0f,
        val isPartial: Boolean = false,
    )

    private var partialHits = 0
    private var lastPartialText = ""

    fun reset() {
        partialHits = 0
        lastPartialText = ""
    }

    /** Evaluates a finalized Vosk recognizer JSON result (`text`, `result` words with `conf`). */
    fun evaluateFinal(json: String): Result {
        reset()
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return Result(false)
        val text = root.optString("text", "")
        val direct = matchWords(text, root.optJSONArray("result"), isPartial = false)
        if (direct.matched) return direct
        val alts = root.optJSONArray("alternatives") ?: return Result(false)
        for (i in 0 until alts.length()) {
            val alt = alts.optJSONObject(i) ?: continue
            val candidate = matchWords(alt.optString("text", ""), alt.optJSONArray("result"), isPartial = false)
            if (candidate.matched) return candidate
        }
        return Result(false)
    }

    /**
     * Evaluates a streaming partial Vosk JSON result (`partial`, `partial_result` words).
     * Requires either strong per-word confidence or 2 consecutive consistent partial detections
     * (~160 ms) so fleeting background noise doesn't false-trigger.
     */
    fun evaluatePartial(json: String): Result {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return Result(false)
        val text = normalize(root.optString("partial", ""))
        if (text.isEmpty()) {
            reset()
            return Result(false)
        }
        val candidate = matchWords(text, root.optJSONArray("partial_result"), isPartial = true)
        if (!candidate.matched) {
            partialHits = 0
            lastPartialText = ""
            return Result(false)
        }
        if (text == lastPartialText || lastPartialText.endsWith("jarvis")) {
            partialHits++
        } else {
            partialHits = 1
        }
        lastPartialText = text
        val strongConf = candidate.confidence >= HIGH_PARTIAL_CONF
        val confirmed = partialHits >= REQUIRED_PARTIAL_HITS || (strongConf && candidate.phrase != null)
        return if (confirmed) candidate else Result(false, candidate.phrase, candidate.confidence, isPartial = true)
    }

    private fun matchWords(rawText: String, wordArray: JSONArray?, isPartial: Boolean): Result {
        val clean = normalize(rawText)
        if (clean.isEmpty()) return Result(false)
        val tokens = clean.split(' ').filter { it.isNotEmpty() && it != UNK_TOKEN }
        if (tokens.isEmpty()) return Result(false)

        // Reject if any phonetic distractor word won the utterance cleanly alongside/instead.
        if (tokens.any { it in DISTRACTORS }) return Result(false)

        // Find an exact wake phrase at the end of tokens (e.g., "hey jarvis" or "jarvis").
        val matchedPhrase = findWakePhrase(tokens) ?: return Result(false)

        val conf = jarvisConfidence(wordArray)
        val minConf = if (isPartial) MIN_PARTIAL_CONF else MIN_FINAL_CONF
        if (conf < minConf) return Result(false, matchedPhrase, conf, isPartial)

        return Result(matched = true, phrase = matchedPhrase, confidence = conf, isPartial = isPartial)
    }

    private fun findWakePhrase(tokens: List<String>): String? {
        if (tokens.size >= 2) {
            val pair = "${tokens[tokens.size - 2]} ${tokens.last()}"
            if (pair in PREFIX_PHRASES) return pair
        }
        if (tokens.last() == TARGET_WORD) return TARGET_WORD
        // Also match if "jarvis" appeared clearly in a short 2-word fragment without distractors.
        if (tokens.size <= 3 && TARGET_WORD in tokens) return TARGET_WORD
        return null
    }

    private fun jarvisConfidence(words: JSONArray?): Float {
        if (words == null || words.length() == 0) return 1.0f // Grammar mode without word scores
        var best = -1f
        for (i in 0 until words.length()) {
            val item = words.optJSONObject(i) ?: continue
            val word = normalize(item.optString("word", ""))
            if (word == TARGET_WORD) {
                val c = if (item.has("conf")) item.optDouble("conf", 1.0).toFloat() else 1.0f
                if (c > best) best = c
            }
        }
        return if (best >= 0f) best else 1.0f
    }

    companion object {
        const val TARGET_WORD = "jarvis"
        const val UNK_TOKEN = "unk"
        const val MIN_FINAL_CONF = 0.50f
        const val MIN_PARTIAL_CONF = 0.55f
        const val HIGH_PARTIAL_CONF = 0.78f
        const val REQUIRED_PARTIAL_HITS = 2

        val ACCEPTED_PHRASES = listOf("jarvis", "hey jarvis", "hi jarvis", "ok jarvis", "okay jarvis")
        private val PREFIX_PHRASES = setOf("hey jarvis", "hi jarvis", "ok jarvis", "okay jarvis")

        /** Common phonetic lookalikes so background speech maps to these or [unk] instead of Jarvis. */
        val DISTRACTORS = setOf(
            "service", "harvest", "travis", "nervous", "office",
            "paris", "alice", "garbage", "charles", "marcus",
            "james", "notice", "device", "various",
        )

        /** Compact Vosk grammar containing wake phrases, acoustic distractors, and unknown sink. */
        val GRAMMAR_JSON: String = JSONArray(ACCEPTED_PHRASES + DISTRACTORS.toList() + "[unk]").toString()

        fun normalize(text: String): String =
            text.lowercase()
                .replace("[unk]", " ")
                .replace(Regex("[^a-z\\s]"), " ")
                .trim()
                .replace(Regex("\\s+"), " ")
    }
}
