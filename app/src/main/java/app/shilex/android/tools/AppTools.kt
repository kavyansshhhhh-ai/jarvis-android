package app.shilex.android.tools

import android.content.Intent
import app.shilex.android.Branding
import app.shilex.android.control.ControlRules
import app.shilex.android.control.ControlService
import app.shilex.android.control.ScreenText
import kotlinx.coroutines.delay
import org.json.JSONObject

data class InstalledApp(val label: String, val packageName: String)

/** App-name matching, pure so it is unit-tested: exact, starts with, then contains. */
object AppMatch {
    fun find(query: String, apps: List<InstalledApp>): List<InstalledApp> {
        val infoApps = apps.map { InstalledAppInfo(label = it.label, packageName = it.packageName) }
        return AppCatalog.find(query, infoApps, includeNonLaunchable = true)
            .map { InstalledApp(it.label, it.packageName) }
    }
}

/** Opening, listing (A-Z), and searching installed apps by name. */
object AppTools {
    fun all(): List<PhoneTool> = listOf(OpenApp, ListInstalledApps)

    private object OpenApp : PhoneTool {
        override val name = "open_app"

        override fun declaration() = Schema.function(
            name,
            "Opens an installed app by name (checks all launchable installed apps accurately). With phone control on it opens at once and returns " +
                "the app's screen, so you can carry on with ui_click / ui_type.",
            Schema.string("name", "App name as the user said it, e.g. WhatsApp, Instagram, Camera.", required = true),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val query = args.text("name")
            val pm = host.context.packageManager
            val catalog = InstalledAppsRegistry.apps(host.context, forceRefresh = true)
            val launchMatches = AppCatalog.find(query, catalog, includeNonLaunchable = false)
            val app = when {
                launchMatches.isEmpty() -> {
                    val nonLaunchable = AppCatalog.find(query, catalog, includeNonLaunchable = true)
                    return if (nonLaunchable.isNotEmpty()) {
                        val first = nonLaunchable.first()
                        Results.failed(
                            "no_launch",
                            "${first.label} (${first.packageName}) is installed on the phone as a background/system component and has no launcher screen to open.",
                        )
                    } else {
                        Results.failed("not_found", "No app called $query is installed on this phone.")
                    }
                }
                launchMatches.size > 1 -> return Results.ask(
                    "Several installed apps match \"$query\". Ask which one.",
                    launchMatches.take(6).map { "${it.label} (${it.packageName})" },
                )
                else -> launchMatches.single()
            }
            val intent = pm.getLaunchIntentForPackage(app.packageName)
                ?: return Results.failed("no_launch", "${app.label} can't be opened from outside.")
            val basic = InstalledApp(app.label, app.packageName)
            return openAndRead(host, intent, basic, "Opened ${app.label}.")
                ?: host.openApp(intent, app.label, "${app.label} will open after you speak.")
        }
    }

    private object ListInstalledApps : PhoneTool {
        override val name = "list_installed_apps"

        override fun declaration() = Schema.function(
            name,
            "Lists installed apps sorted alphabetically (A-Z) or searches installed apps by keyword/Hindi/Hinglish query.",
            Schema.string("mode", "list_az to list A-Z, search to search by name, info to inspect launchability.", choices = listOf("list_az", "search", "info")),
            Schema.string("query", "Search keyword (required when mode is search or info)."),
            Schema.string("include_system", "true or false (default false for shorter user-app lists).", choices = listOf("false", "true")),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val mode = args.text("mode").ifEmpty { "list_az" }
            val query = args.text("query")
            val includeSystem = args.text("include_system") == "true"
            val catalog = InstalledAppsRegistry.apps(host.context, forceRefresh = true)
            return when (mode) {
                "search", "info" -> {
                    if (query.isEmpty()) {
                        Results.done(AppCatalog.summarizeAlphabetical(catalog, includeSystem = includeSystem))
                    } else {
                        val hits = AppCatalog.search(query, catalog, includeNonLaunchable = mode == "info")
                        if (hits.isEmpty()) {
                            Results.done("No installed app matching \"$query\" was found on this phone.")
                        } else {
                            val lines = hits.take(25).joinToString("\n") { app ->
                                val tag = if (app.launchable) "openable" else "no-launcher/service"
                                "- ${app.label} [$tag] (${app.packageName})"
                            }
                            Results.done("Matching installed apps (${hits.size}):\n$lines")
                        }
                    }
                }
                else -> Results.done(AppCatalog.summarizeAlphabetical(catalog, includeSystem = includeSystem))
            }
        }
    }

    /**
     * Opens [app] at once and returns its screen so the model can carry on with ui_* tools;
     * null when phone control is off (the caller opens it after the reply instead).
     */
    suspend fun openAndRead(host: ToolHost, intent: Intent, app: InstalledApp, done: String): JSONObject? {
        val control = ControlService.instance?.takeIf { !it.overlay.stopped } ?: return null
        val started = host.startNow(intent, app.label, done)
        if (started.optString("status") != "done") return started
        if (!control.waitFor(app.packageName, OPEN_WAIT_MS)) {
            return Results.done("$done ${app.label} is still loading. Call ui_read in a moment.")
        }
        delay(SETTLE_MS)
        control.overlay.showActive()
        if (ControlRules.blocked(app.packageName)) {
            return Results.done("Opened ${app.label}. ${Branding.ASSISTANT_NAME} doesn't read or touch this app; the user takes it from here.")
        }
        val snap = control.snapshot() ?: return if (ControlRules.isFinanceApp(app.packageName)) {
            Results.done("$done ${app.label} restricts Accessibility inspection here for security; the user takes it from here.")
        } else {
            Results.done(done)
        }
        if (snap.sensitiveAuth) {
            return Results.done("$done Now on a PIN, OTP, or password screen. Stop here and let the user handle it directly.")
        }
        if (snap.financeApp && !host.authorizeFinance(app.packageName, app.label)) {
            return Results.done("Opened ${app.label}. Limited assistance wasn't authorized, so the user takes it from here.")
        }
        if (snap.financeApp && (snap.elements.isEmpty() || snap.restrictedBySystem)) {
            return Results.done("$done ${app.label} or Android restricts Accessibility inspection here for security; the user takes it from here.")
        }
        val summary = if (snap.financeApp) ControlRules.extractTransactionSummary(snap.elements) else ""
        val prefix = if (summary.isNotEmpty()) "$done Summary on screen: $summary\n" else "$done "
        return Results.done(prefix + "Screen now:\n" + ScreenText.describe(snap.appLabel, snap.elements, snap.more))
    }

    private const val OPEN_WAIT_MS = 6_000L
    private const val SETTLE_MS = 800L
}
