package app.shilex.android.tools

import android.content.Intent
import android.net.Uri
import app.shilex.android.Branding
import app.shilex.android.Diag
import app.shilex.android.control.ControlRules
import app.shilex.android.control.ControlService
import app.shilex.android.control.ScreenElement
import app.shilex.android.data.AutomationCategory
import app.shilex.android.data.Settings as AppSettings
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Pure Google Play Store listing & button state recognizer (unit-tested without Android).
 */
object PlayStoreMatcher {
    enum class ButtonState {
        Install,
        Open,
        Update,
        InstallingOrPending,
        PaidOrBlocked,
        None,
    }

    data class ScreenDecision(
        val buttonState: ButtonState,
        val actionElement: ScreenElement? = null,
        val matchedListingElement: ScreenElement? = null,
        val reason: String = "",
        val ambiguousListings: List<ScreenElement> = emptyList(),
        val isVerifiedInstalledUi: Boolean = false,
    )

    private val PRICE_OR_BUY_RE = Regex("""(?:₹|rs\.?|inr|\$|€|£)\s*\d|\bbuy\b""", RegexOption.IGNORE_CASE)
    private val INSTALLING_RE = Regex("""\b(installing|pending|waiting\b.*|downloading|cancel|uninstalling)\b|%\s*$""", RegexOption.IGNORE_CASE)
    private val INSTALL_BTN_RE = Regex("""^(?:install(?:\s*[·•(-]\s*free\)?)?|free\s+install|get)$""", RegexOption.IGNORE_CASE)
    private val OPEN_BTN_RE = Regex("""^(?:open|play)$""", RegexOption.IGNORE_CASE)
    private val UPDATE_BTN_RE = Regex("""^update$""", RegexOption.IGNORE_CASE)
    private val INSTALLED_BADGE_RE = Regex("""^(?:installed|uninstall)$""", RegexOption.IGNORE_CASE)
    private val AUTH_OR_AGE_RE = Regex("""\b(sign in|verify|age|parental|ask to buy|add a payment method|complete account setup|continue payment|password)\b""", RegexOption.IGNORE_CASE)
    private val SEARCH_BAR_RE = Regex("""\b(search\s+apps(?:\s*(?:&|and)\s*games)?|search\s+google\s+play|search\s+for\s+apps)\b""", RegexOption.IGNORE_CASE)

    fun normalize(text: String): String = AppCatalog.compactKey(text)

    fun isPaidOrRestricted(elements: List<ScreenElement>): String? {
        for (el in elements) {
            val txt = "${el.label} ${el.value}".trim()
            if (AUTH_OR_AGE_RE.containsMatchIn(txt)) {
                return "Google Play asks for account, age, payment, or parental verification (${el.label}). Handing control to the user."
            }
            if (el.role == "button" && PRICE_OR_BUY_RE.containsMatchIn(txt)) {
                return "This app requires payment or purchase (${el.label}); JARVIS will not auto-purchase apps."
            }
        }
        return null
    }

    /** Finds a visible Play Store search input field or search bar button cleanly. */
    fun findSearchInput(elements: List<ScreenElement>): ScreenElement? =
        elements.firstOrNull { el ->
            (el.role == "field" || el.role == "button") &&
                el.isEnabled &&
                (SEARCH_BAR_RE.containsMatchIn(el.label) || SEARCH_BAR_RE.containsMatchIn(el.hint))
        }

    /** Inspects visible Play Store listing or search result elements for [requestedApp]. */
    fun analyzeScreen(requestedApp: String, elements: List<ScreenElement>): ScreenDecision {
        val restrictedReason = isPaidOrRestricted(elements)
        if (restrictedReason != null) {
            return ScreenDecision(ButtonState.PaidOrBlocked, reason = restrictedReason)
        }

        val wantedCompact = normalize(requestedApp)
        val wantedTokens = requestedApp.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }

        val installingBtn = elements.firstOrNull { el ->
            el.isEnabled && (el.role == "button" || el.role == "text") && INSTALLING_RE.containsMatchIn(el.label.trim())
        }
        if (installingBtn != null) {
            return ScreenDecision(ButtonState.InstallingOrPending, actionElement = installingBtn)
        }

        val installBtn = elements.firstOrNull { el ->
            el.role == "button" && el.isEnabled && INSTALL_BTN_RE.containsMatchIn(el.label.trim())
        }
        val openBtn = elements.firstOrNull { el ->
            el.role == "button" && el.isEnabled && OPEN_BTN_RE.containsMatchIn(el.label.trim())
        }
        val updateBtn = elements.firstOrNull { el ->
            el.role == "button" && el.isEnabled && UPDATE_BTN_RE.containsMatchIn(el.label.trim())
        }
        val hasInstalledBadge = elements.any { el ->
            INSTALLED_BADGE_RE.containsMatchIn(el.label.trim())
        }

        val scoredListings = rankMatchingListings(wantedCompact, wantedTokens, elements)
        val listingMatch = scoredListings.firstOrNull()?.first

        if (installBtn != null) {
            return if (listingMatch != null || elements.any { normalize(it.label) == wantedCompact }) {
                ScreenDecision(ButtonState.Install, actionElement = installBtn, matchedListingElement = listingMatch)
            } else {
                ScreenDecision(
                    ButtonState.None,
                    matchedListingElement = null,
                    reason = "The visible Play Store page doesn't clearly match \"$requestedApp\".",
                )
            }
        }
        if (openBtn != null && (listingMatch != null || hasInstalledBadge)) {
            return ScreenDecision(
                buttonState = ButtonState.Open,
                actionElement = openBtn,
                matchedListingElement = listingMatch,
                isVerifiedInstalledUi = hasInstalledBadge || listingMatch != null,
            )
        }
        if (updateBtn != null && (listingMatch != null || hasInstalledBadge)) {
            return ScreenDecision(
                buttonState = ButtonState.Update,
                actionElement = updateBtn,
                matchedListingElement = listingMatch,
                isVerifiedInstalledUi = true,
            )
        }

        // On search results list: check if ambiguous top candidates have identical confidence
        if (scoredListings.size > 1 && scoredListings[0].second == scoredListings[1].second) {
            val topScore = scoredListings[0].second
            val tied = scoredListings.filter { it.second == topScore }.map { it.first }
            return ScreenDecision(
                buttonState = ButtonState.None,
                matchedListingElement = listingMatch,
                ambiguousListings = tied,
                reason = "Multiple Play Store results match \"$requestedApp\".",
            )
        }

        if (listingMatch != null) {
            return ScreenDecision(ButtonState.None, matchedListingElement = listingMatch)
        }
        return ScreenDecision(ButtonState.None, reason = "No matching result for \"$requestedApp\" is visible yet.")
    }

    private fun rankMatchingListings(
        wantedCompact: String,
        wantedTokens: List<String>,
        elements: List<ScreenElement>,
    ): List<Pair<ScreenElement, Int>> {
        if (wantedCompact.isEmpty()) return emptyList()
        return elements.mapNotNull { el ->
            val lbl = el.label.trim()
            if (lbl.isEmpty() ||
                INSTALL_BTN_RE.matches(lbl) ||
                OPEN_BTN_RE.matches(lbl) ||
                UPDATE_BTN_RE.matches(lbl) ||
                INSTALLED_BADGE_RE.matches(lbl) ||
                SEARCH_BAR_RE.containsMatchIn(lbl) ||
                lbl.equals("Search", ignoreCase = true) ||
                el.role == "field"
            ) {
                return@mapNotNull null
            }
            val comp = normalize(lbl)
            var score = 0
            when {
                comp == wantedCompact -> score += 100
                comp.startsWith(wantedCompact) -> score += 75
                wantedCompact in comp -> score += 50
                wantedTokens.isNotEmpty() && wantedTokens.all { it in lbl.lowercase() } -> score += 40
            }
            if (score > 0 && el.role == "button") score += 10
            if (score > 0) el to score else null
        }.sortedByDescending { it.second }
    }
}

/**
 * Google Play Store search & supported free-app installation workflow.
 * Never installs from third-party APK sites; respects paid/age/auth confirmation dialogs;
 * never claims an app is installed unless Android's PackageManager verifies it.
 */
object PlayStoreTools {
    val SearchInstall: PhoneTool = SearchInstallTool

    private object SearchInstallTool : PhoneTool {
        override val name = "playstore_search_install"

        override fun declaration() = Schema.function(
            name,
            "Searches Google Play Store for an app, verifies if it is already installed, checks the exact listing, and taps free Install when authorized. Stops for payments, age/account/parental verification, and never claims installed until verified.",
            Schema.string("app_name", "Exact app name to find/install on Google Play Store.", required = true),
            Schema.string("action", "install (default) or open_listing.", choices = listOf("install", "open_listing")),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val appName = args.text("app_name")
            if (appName.isEmpty()) return Results.failed("no_app", "Ask which app to look up on Google Play Store.")
            val action = args.text("action").ifEmpty { "install" }

            // 1. Check live installed apps BEFORE touching Play Store
            val beforeApps = InstalledAppsRegistry.apps(host.context, forceRefresh = true)
            val alreadyInstalled = AppCatalog.find(appName, beforeApps, includeNonLaunchable = false)
            if (alreadyInstalled.any { AppCatalog.compactKey(it.label) == AppCatalog.compactKey(appName) }) {
                val matched = alreadyInstalled.first()
                return Results.done("${matched.label} (${matched.packageName}) is already installed on this phone. Ask if the user wants to open it.")
            }

            if (!AppSettings(host.context).isAutomationEnabled(AutomationCategory.PlayStoreNavigation)) {
                return Results.failed(
                    "automation_disabled",
                    "Play Store navigation is turned off in Settings → Permissions. Ask the user to enable it or install manually.",
                )
            }

            val pm = host.context.packageManager
            val marketIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("market://search?q=${Uri.encode(appName)}&c=apps"),
            ).setPackage(ControlRules.PLAY_STORE_PACKAGE)
            val launchIntent: Intent = if (marketIntent.resolveActivity(pm) != null) {
                marketIntent
            } else {
                pm.getLaunchIntentForPackage(ControlRules.PLAY_STORE_PACKAGE)
                    ?: return Results.failed("play_store_missing", "Google Play Store isn't available on this phone.")
            }
            val control = ControlService.instance?.takeIf { !it.overlay.stopped }
                ?: return host.openApp(
                    launchIntent,
                    "Google Play Store",
                    "Google Play Store will open on \"$appName\" after you speak so the user can check and tap Install.",
                )

            val knownPackagesBefore = beforeApps.map { it.packageName }.toSet()
            return host.openApp(
                launchIntent,
                "Google Play Store",
                "Opening Google Play Store to check \"$appName\" and start installation if available.",
            ) { automateInstall(control, host, appName, action == "install", knownPackagesBefore) }
        }

        private suspend fun automateInstall(
            service: ControlService,
            host: ToolHost,
            appName: String,
            wantInstall: Boolean,
            knownPackagesBefore: Set<String>,
        ): String {
            if (!service.waitFor(ControlRules.PLAY_STORE_PACKAGE, WAIT_MS)) {
                return "Google Play Store didn't open in time; ask the user to check Play Store."
            }
            var clickedListing = false
            var tappedInstall = false
            var typedSearch = false

            repeat(POLL_STEPS) {
                delay(STEP_DELAY_MS)
                val snap = service.snapshot(allowPlayStore = true) ?: return@repeat
                if (snap.packageName != ControlRules.PLAY_STORE_PACKAGE) return@repeat
                if (snap.sensitiveAuth) {
                    return "Google Play Store asked for password or account verification. Hand control to the user."
                }
                val decision = PlayStoreMatcher.analyzeScreen(appName, snap.elements)
                when (decision.buttonState) {
                    PlayStoreMatcher.ButtonState.PaidOrBlocked -> return decision.reason
                    PlayStoreMatcher.ButtonState.Open -> {
                        val after = InstalledAppsRegistry.refresh(host.context)
                        val found = AppCatalog.find(appName, after, includeNonLaunchable = true).firstOrNull()
                        return if (found != null) {
                            "${found.label} (${found.packageName}) is verified as installed on this phone."
                        } else {
                            "Google Play Store shows an Open button for \"$appName\"."
                        }
                    }
                    PlayStoreMatcher.ButtonState.InstallingOrPending -> {
                        val newlyAdded = findNewlyInstalledApp(host, appName, knownPackagesBefore)
                        if (newlyAdded != null) {
                            return "Verified: ${newlyAdded.label} (${newlyAdded.packageName}) finished installing from Google Play Store."
                        }
                        if (!tappedInstall) {
                            return "Google Play Store is already downloading/installing \"$appName\"."
                        }
                    }
                    PlayStoreMatcher.ButtonState.Install -> {
                        if (!wantInstall) {
                            return "Opened the Google Play Store listing for \"$appName\". Ask the user if they want to tap Install."
                        }
                        if (!tappedInstall) {
                            val node = decision.actionElement?.number?.let(service::node)
                            if (node != null && service.click(node)) {
                                tappedInstall = true
                                service.overlay.showActive()
                                Diag.i("playstore: tapped Install for $appName")
                            }
                        }
                    }
                    PlayStoreMatcher.ButtonState.Update -> {
                        return "\"$appName\" is already on this phone with an Update available in Google Play Store."
                    }
                    PlayStoreMatcher.ButtonState.None -> {
                        if (decision.ambiguousListings.size > 1) {
                            val options = decision.ambiguousListings.take(3).joinToString(", ") { it.label }
                            return "Multiple Play Store listings match \"$appName\" ($options). Ask the user which one to open."
                        }
                        if (!clickedListing && decision.matchedListingElement != null) {
                            val node = service.node(decision.matchedListingElement.number)
                            if (node != null && service.click(node)) {
                                clickedListing = true
                                Diag.i("playstore: opened listing ${decision.matchedListingElement.label}")
                            }
                        } else if (!typedSearch) {
                            val searchEl = PlayStoreMatcher.findSearchInput(snap.elements)
                            val searchNode = searchEl?.number?.let(service::node)
                            if (searchNode != null) {
                                if (searchNode.isEditable) {
                                    if (service.type(searchNode, appName, submitIme = true)) {
                                        typedSearch = true
                                        Diag.i("playstore: typed query $appName")
                                    }
                                } else if (service.click(searchNode)) {
                                    delay(500)
                                    service.focusedField()?.let { field ->
                                        if (service.type(field, appName, submitIme = true)) {
                                            typedSearch = true
                                            Diag.i("playstore: opened search & typed $appName")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val newlyAdded = findNewlyInstalledApp(host, appName, knownPackagesBefore)
            if (newlyAdded != null) {
                return "Verified: ${newlyAdded.label} (${newlyAdded.packageName}) is now installed."
            }
            return if (tappedInstall) {
                "Tapped Install for \"$appName\" in Google Play Store; download/installation is still in progress or awaiting Play Store confirmation."
            } else {
                "Opened Google Play Store search for \"$appName\", though JARVIS couldn't find an unambiguous free Install button automatically. Ask the user to check the listing."
            }
        }

        private fun findNewlyInstalledApp(
            host: ToolHost,
            appName: String,
            knownPackagesBefore: Set<String>,
        ): InstalledAppInfo? {
            val now = InstalledAppsRegistry.refresh(host.context)
            val newMatches = now.filter { it.packageName !in knownPackagesBefore }
            return AppCatalog.find(appName, newMatches, includeNonLaunchable = true).firstOrNull()
                ?: AppCatalog.find(appName, now, includeNonLaunchable = false)
                    .firstOrNull { AppCatalog.compactKey(it.label) == AppCatalog.compactKey(appName) }
        }

        private const val WAIT_MS = 8_000L
        private const val STEP_DELAY_MS = 900L
        private const val POLL_STEPS = 12
    }
}
