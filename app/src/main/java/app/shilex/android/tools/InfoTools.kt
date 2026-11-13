package app.shilex.android.tools

import android.content.Intent
import android.net.Uri
import app.shilex.android.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Enforces that external Chrome/browser search ONLY launches when the user explicitly asked for it. */
object BrowserLaunchGuard {
    private val EXPLICIT_BROWSER = Regex(
        """\b(open|launch|in|on|via|use|search\s+(?:in|on))\s+(chrome|browser|google\s+chrome|web\s+browser)\b|\b(chrome|browser)\s+(me|mein|par|pe|kholo|open)\b|\bopen\s+web\s+page\b""",
        RegexOption.IGNORE_CASE,
    )

    fun isExplicitBrowserRequest(userUtterance: String): Boolean {
        val clean = userUtterance.trim()
        if (clean.isEmpty()) return false
        return EXPLICIT_BROWSER.containsMatchIn(clean)
    }
}

/**
 * Date/time, weather, internal factual search (without opening Chrome), and explicit-only
 * browser web search when requested by the user.
 */
object InfoTools {
    fun all(): List<PhoneTool> = listOf(DateTime, GetWeather, WebSearchInternal, OpenWebSearch)

    private object DateTime : PhoneTool {
        override val name = "get_current_datetime"

        override fun declaration() = Schema.function(name, "The phone's current local date, time and time zone.")

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject =
            Results.done(ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm VV")))
    }

    private object GetWeather : PhoneTool {
        override val name = "get_weather"

        override fun declaration() = Schema.function(
            name,
            "Current weather and today's forecast for a city (default: the last city asked about).",
            Schema.string("city", "City; empty for the usual one."),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val settings = Settings(host.context)
            val city = args.text("city").ifEmpty { settings.homeCity }
            if (city.isEmpty()) return Results.failed("needs_city", "Ask which city.")
            return try {
                val line = withContext(Dispatchers.IO) { Weather.fetch(host.http, city) }
                    ?: return Results.failed("not_found", "I couldn't find a place called $city.")
                settings.homeCity = city
                Results.done(line)
            } catch (_: IOException) {
                Results.failed("weather_failed", "The weather service didn't answer.")
            }
        }
    }

    private object WebSearchInternal : PhoneTool {
        override val name = "web_search_internal"

        override fun declaration() = Schema.function(
            name,
            "Looks up factual information internally via Wikipedia / Instant Answer WITHOUT opening Chrome or leaving JARVIS. " +
                "Prefer answering general knowledge questions directly, or use this for factual lookups inside JARVIS.",
            Schema.string("query", "Topic or question to look up inside JARVIS.", required = true),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val query = args.text("query")
            if (query.isEmpty()) return Results.failed("no_query", "Ask what to look up.")
            val summary = withContext(Dispatchers.IO) {
                InternalSearch.lookup(host.http, query)
            }
            return if (summary != null) {
                Results.done(summary)
            } else {
                Results.failed(
                    "no_instant_answer",
                    "No concise summary was returned internally for \"$query\". Answer concisely from your knowledge or ask if they want it opened in Chrome.",
                )
            }
        }
    }

    private object OpenWebSearch : PhoneTool {
        override val name = "open_web_search"

        override fun declaration() = Schema.function(
            name,
            "ONLY call when the user explicitly asked to open Chrome or a browser. NEVER use for normal questions—answer those directly inside JARVIS or use web_search_internal.",
            Schema.string("query", "The search words.", required = true),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val query = args.text("query")
            if (query.isEmpty()) return Results.failed("no_query", "Ask what to search for.")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}"))
            return host.openApp(intent, "Chrome", "Chrome will open with results for \"$query\" after you speak.")
        }
    }
}

/** Lightweight internal web knowledge fetcher (DuckDuckGo Instant Answer + Wikipedia summary). */
object InternalSearch {
    fun lookup(http: OkHttpClient, query: String): String? {
        val ddg = runCatching { fetchDuckDuckGo(http, query) }.getOrNull()
        if (!ddg.isNullOrBlank()) return ddg
        return runCatching { fetchWikipedia(http, query) }.getOrNull()
    }

    fun parseDuckDuckGo(json: JSONObject): String? {
        val abstractText = json.optString("AbstractText").trim()
        if (abstractText.isNotEmpty()) {
            val heading = json.optString("Heading").trim()
            return if (heading.isNotEmpty()) "$heading: $abstractText" else abstractText
        }
        val answer = json.optString("Answer").trim()
        if (answer.isNotEmpty()) return answer
        val definition = json.optString("Definition").trim()
        if (definition.isNotEmpty()) return definition
        val topics = json.optJSONArray("RelatedTopics") ?: return null
        for (i in 0 until minOf(topics.length(), 4)) {
            val firstText = topics.optJSONObject(i)?.optString("Text")?.trim().orEmpty()
            if (firstText.isNotEmpty()) return firstText
        }
        return null
    }

    fun parseWikipediaSummary(json: JSONObject): String? {
        val extract = json.optString("extract").trim()
        if (extract.isEmpty()) return null
        val title = json.optString("title").trim()
        val short = extract.take(420).trim()
        return if (title.isNotEmpty() && !short.startsWith(title, ignoreCase = true)) {
            "$title: $short"
        } else {
            short
        }
    }

    private fun fetchDuckDuckGo(http: OkHttpClient, query: String): String? {
        val url = "https://api.duckduckgo.com/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("format", "json")
            .addQueryParameter("no_html", "1")
            .addQueryParameter("skip_disambig", "1")
            .build()
        val req = Request.Builder().url(url).header("User-Agent", "JARVIS-Android/1.0").build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null
            val body = res.body?.string().orEmpty()
            if (body.isBlank()) return null
            return parseDuckDuckGo(JSONObject(body))
        }
    }

    private fun fetchWikipedia(http: OkHttpClient, query: String): String? {
        val searchUrl = "https://en.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("list", "search")
            .addQueryParameter("srsearch", query)
            .addQueryParameter("srlimit", "1")
            .addQueryParameter("format", "json")
            .build()
        val searchReq = Request.Builder().url(searchUrl).header("User-Agent", "JARVIS-Android/1.0").build()
        val title = http.newCall(searchReq).execute().use { res ->
            if (!res.isSuccessful) return null
            val obj = JSONObject(res.body?.string().orEmpty())
            obj.optJSONObject("query")?.optJSONArray("search")?.optJSONObject(0)?.optString("title")?.trim().orEmpty()
        }
        if (title.isEmpty()) return null
        val slug = title.replace(' ', '_')
        val summaryUrl = "https://en.wikipedia.org/api/rest_v1/page/summary/${Uri.encode(slug)}"
        val summaryReq = Request.Builder().url(summaryUrl).header("User-Agent", "JARVIS-Android/1.0").build()
        http.newCall(summaryReq).execute().use { res ->
            if (!res.isSuccessful) return null
            return parseWikipediaSummary(JSONObject(res.body?.string().orEmpty()))
        }
    }
}

/** Open-Meteo, the same free source as desktop (non-commercial; see docs/PROVENANCE.md). */
object Weather {
    private val CODES = mapOf(
        0 to "clear", 1 to "mostly clear", 2 to "partly cloudy", 3 to "cloudy", 45 to "foggy", 48 to "foggy",
        51 to "light drizzle", 53 to "drizzle", 55 to "heavy drizzle", 61 to "light rain", 63 to "rain",
        65 to "heavy rain", 71 to "light snow", 73 to "snow", 75 to "heavy snow", 80 to "rain showers",
        81 to "rain showers", 82 to "heavy showers", 95 to "thunderstorm", 96 to "thunderstorm with hail",
        99 to "thunderstorm with hail",
    )

    /** One line, or null if the place isn't found. Only the city name is sent. */
    fun fetch(http: OkHttpClient, city: String): String? {
        val geo = get(
            http,
            "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
                .addQueryParameter("name", city).addQueryParameter("count", "1").build().toString(),
        )
        val place = geo.optJSONArray("results")?.optJSONObject(0) ?: return null
        val forecast = get(
            http,
            "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
                .addQueryParameter("latitude", place.optDouble("latitude").toString())
                .addQueryParameter("longitude", place.optDouble("longitude").toString())
                .addQueryParameter("current", "temperature_2m,weather_code")
                .addQueryParameter("daily", "temperature_2m_max,temperature_2m_min,precipitation_probability_max")
                .addQueryParameter("forecast_days", "1")
                .addQueryParameter("timezone", "auto")
                .build().toString(),
        )
        return summary(place.optString("name", city), forecast)
    }

    fun summary(name: String, forecast: JSONObject): String {
        val current = forecast.optJSONObject("current") ?: JSONObject()
        val daily = forecast.optJSONObject("daily") ?: JSONObject()
        fun first(key: String) = daily.optJSONArray(key)?.opt(0)?.takeUnless { it == JSONObject.NULL }
        val sky = CODES[current.optInt("weather_code", -1)].orEmpty()
        val rain = first("precipitation_probability_max")
        val now = current.opt("temperature_2m")
        val low = first("temperature_2m_min")
        val high = first("temperature_2m_max")
        val today = if (low != null && high != null) "; today $low–$high°C" else ""
        return "$name: $now°C now, $sky$today" + if (rain != null) ", $rain% chance of rain." else "."
    }

    private fun get(http: OkHttpClient, url: String): JSONObject =
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrElse { throw IOException("bad JSON") }
        }
}
