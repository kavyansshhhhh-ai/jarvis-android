package app.shilex.android.tools

import android.content.Intent
import android.net.Uri
import app.shilex.android.Diag
import app.shilex.android.assistant.ActionStatus
import app.shilex.android.assistant.AgentActionResult
import app.shilex.android.assistant.InstalledAppChecker
import app.shilex.android.control.ControlRules
import app.shilex.android.control.ControlService
import app.shilex.android.control.ScreenElement
import app.shilex.android.data.AutomationCategory
import app.shilex.android.data.Settings as AppSettings
import app.shilex.android.task.ActiveTaskContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Explicit Google Play Store automation workflow states. */
enum class StoreFlowState {
    IDLE,
    CHECKING_INSTALLED,
    OPENING_STORE,
    SEARCHING,
    WAITING_FOR_RESULTS,
    RESULTS_VISIBLE,
    OPENING_APP_PAGE,
    APP_PAGE_VISIBLE,
    INSTALL_CONFIRMATION,
    INSTALLING,
    VERIFYING_INSTALLATION,
    COMPLETED,
    FAILED
}

/**
 * Pure Google Play Store listing & button state recognizer (unit-tested without Android).
 */
object PlayStoreMatcher {
    enum class ButtonState {
        Install,
        Open,
        Update,
        InstallingOrPending,
        PreRegisterOrUnavailable,
        NetworkError,
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
        val listingTitle: String? = null,
    )

    private val PRICE_OR_BUY_RE = Regex("""(?:₹|rs\.?|inr|\$|€|£)\s*\d|\bbuy\b""", RegexOption.IGNORE_CASE)
    private val CANCEL_BTN_RE = Regex("""^cancel(?:\s+download)?$""", RegexOption.IGNORE_CASE)
    private val ACTIVE_PROGRESS_RE = Regex(
        """^(?:installing|pending|waiting\s+for\b.*|downloading(?:\s+\d+%)?|uninstalling(?:\s+\d+%)?|\d+%)$""",
        RegexOption.IGNORE_CASE,
    )
    private val DOWNLOAD_PCT_RE = Regex("""\b(?:downloading|installing)\b.*\d+\s*%""", RegexOption.IGNORE_CASE)
    private val INSTALL_BTN_RE = Regex("""^(?:install(?:\s*[·•(-]\s*free\)?)?|free\s+install|get)$""", RegexOption.IGNORE_CASE)
    private val OPEN_BTN_RE = Regex("""^(?:open|play)$""", RegexOption.IGNORE_CASE)
    private val UPDATE_BTN_RE = Regex("""^update$""", RegexOption.IGNORE_CASE)
    private val INSTALLED_BADGE_RE = Regex("""^(?:installed|uninstall)$""", RegexOption.IGNORE_CASE)
    private val PRE_REGISTER_RE = Regex(
        """^(?:pre-register|pre-registered|coming soon|register)$|\b(not available in your country|your device isn't compatible|this item isn't available|item not found)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val NETWORK_ERROR_RE = Regex(
        """\b(something went wrong|no internet connection|you're offline|you are offline|couldn't connect|check your connection)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val RETRY_BTN_RE = Regex("""^try again$|^retry$""", RegexOption.IGNORE_CASE)
    private val AUTH_OR_AGE_RE = Regex("""\b(sign in|verify|age|parental|ask to buy|add a payment method|complete account setup|continue payment|password)\b""", RegexOption.IGNORE_CASE)
    private val SEARCH_BAR_RE = Regex("""\b(search\s+apps(?:\s*(?:&|and)\s*games)?|search\s+google\s+play|search\s+for\s+apps)\b""", RegexOption.IGNORE_CASE)
    private val DETAIL_MARKER_RE = Regex("""^(?:about this (?:app|game)|ratings and reviews|data safety|what's new|uninstall)$""", RegexOption.IGNORE_CASE)
    private val SPONSORED_SEGMENT_RE = Regex("""^(?:sponsored|ad|ads)$""", RegexOption.IGNORE_CASE)
    private val CLONE_MODIFIER_TOKENS = setOf(
        "guide", "tips", "tricks", "wallpaper", "wallpapers", "mod", "mods",
        "hack", "cheats", "diamonds", "calculator", "converter", "quiz",
        "companion", "helper", "skin", "skins", "walkthrough", "review",
    )

    fun normalize(text: String): String = AppCatalog.compactKey(text)

    private fun tokenize(text: String): List<String> = text
        .lowercase()
        .replace(Regex("[^a-z0-9\\u0900-\\u097F\\s]"), " ")
        .split(Regex("\\s+"))
        .filter { it.length >= 2 }

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
    fun analyzeScreen(
        requestedApp: String,
        elements: List<ScreenElement>,
        meta: Map<Int, ControlService.NodeMeta> = emptyMap(),
    ): ScreenDecision {
        val restrictedReason = isPaidOrRestricted(elements)
        if (restrictedReason != null) {
            return ScreenDecision(ButtonState.PaidOrBlocked, reason = restrictedReason)
        }

        val networkErrorEl = elements.firstOrNull { el ->
            NETWORK_ERROR_RE.containsMatchIn(el.label.trim())
        }
        if (networkErrorEl != null) {
            val retryBtn = elements.firstOrNull { el ->
                el.role == "button" && el.isEnabled && RETRY_BTN_RE.matches(el.label.trim())
            }
            return ScreenDecision(
                ButtonState.NetworkError,
                actionElement = retryBtn,
                reason = "Google Play Store appears offline or hit a temporary network error (${networkErrorEl.label}).",
            )
        }

        val preRegisterEl = elements.firstOrNull { el ->
            PRE_REGISTER_RE.containsMatchIn(el.label.trim())
        }
        if (preRegisterEl != null) {
            return ScreenDecision(
                ButtonState.PreRegisterOrUnavailable,
                actionElement = preRegisterEl.takeIf { it.role == "button" },
                reason = "Google Play shows \"$requestedApp\" as ${preRegisterEl.label} rather than ready for direct installation.",
            )
        }

        val wantedCompact = normalize(requestedApp)
        val wantedTokens = tokenize(requestedApp)

        val installingBtn = elements.firstOrNull { el ->
            val lbl = el.label.trim()
            el.isEnabled && (
                (el.role == "button" && CANCEL_BTN_RE.matches(lbl)) ||
                    ((el.role == "button" || el.role == "text") &&
                        (ACTIVE_PROGRESS_RE.matches(lbl) || DOWNLOAD_PCT_RE.containsMatchIn(lbl)))
                )
        }
        if (installingBtn != null) {
            return ScreenDecision(ButtonState.InstallingOrPending, actionElement = installingBtn)
        }

        val installBtns = elements.filter { el ->
            el.role == "button" && el.isEnabled && INSTALL_BTN_RE.containsMatchIn(el.label.trim())
        }
        val openBtns = elements.filter { el ->
            el.role == "button" && el.isEnabled && OPEN_BTN_RE.containsMatchIn(el.label.trim())
        }
        val updateBtns = elements.filter { el ->
            el.role == "button" && el.isEnabled && UPDATE_BTN_RE.containsMatchIn(el.label.trim())
        }
        val hasInstalledBadge = elements.any { el ->
            INSTALLED_BADGE_RE.containsMatchIn(el.label.trim())
        }
        val hasDetailMarker = elements.any { el ->
            DETAIL_MARKER_RE.matches(el.label.trim())
        }
        val actionCount = installBtns.size + openBtns.size + updateBtns.size
        val isLikelyDetailPage = hasDetailMarker || actionCount == 1

        val scoredListings = rankMatchingListings(wantedCompact, wantedTokens, elements, meta)
        val listingMatch = scoredListings.firstOrNull()?.first
        val matchedTitle = listingMatch?.let { primaryTitleOf(it.label) }

        if (isLikelyDetailPage) {
            val installBtn = installBtns.singleOrNull()
            val openBtn = openBtns.singleOrNull()
            val updateBtn = updateBtns.singleOrNull()
            if (installBtn != null) {
                return if (listingMatch != null || elements.any { normalize(primaryTitleOf(it.label)) == wantedCompact }) {
                    ScreenDecision(
                        buttonState = ButtonState.Install,
                        actionElement = installBtn,
                        matchedListingElement = listingMatch,
                        listingTitle = matchedTitle,
                    )
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
                    isVerifiedInstalledUi = hasInstalledBadge,
                    listingTitle = matchedTitle,
                )
            }
            if (updateBtn != null && (listingMatch != null || hasInstalledBadge)) {
                return ScreenDecision(
                    buttonState = ButtonState.Update,
                    actionElement = updateBtn,
                    matchedListingElement = listingMatch,
                    isVerifiedInstalledUi = hasInstalledBadge,
                    listingTitle = matchedTitle,
                )
            }
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
                listingTitle = matchedTitle,
            )
        }

        if (listingMatch != null) {
            return ScreenDecision(
                buttonState = ButtonState.None,
                matchedListingElement = listingMatch,
                listingTitle = matchedTitle,
            )
        }
        return ScreenDecision(ButtonState.None, reason = "No matching result for \"$requestedApp\" is visible yet.")
    }

    /** Computes the explicit [StoreFlowState] for the current Play Store UI decision and progress. */
    fun resolveFlowState(
        decision: ScreenDecision,
        alreadyInstalled: Boolean = false,
        inPlayStore: Boolean = true,
        hasSearchInput: Boolean = false,
        searchSubmitted: Boolean = false,
        tappedInstall: Boolean = false,
        confirmedInstall: Boolean = false,
        verifiedInstalledAfterAction: Boolean = false,
        timedOut: Boolean = false,
    ): StoreFlowState = when {
        alreadyInstalled || verifiedInstalledAfterAction -> StoreFlowState.COMPLETED
        decision.buttonState == ButtonState.PaidOrBlocked ||
            decision.buttonState == ButtonState.PreRegisterOrUnavailable ||
            decision.buttonState == ButtonState.NetworkError -> StoreFlowState.FAILED
        timedOut -> StoreFlowState.FAILED
        !inPlayStore -> StoreFlowState.OPENING_STORE
        decision.buttonState == ButtonState.Open || decision.buttonState == ButtonState.Update -> StoreFlowState.COMPLETED
        decision.buttonState == ButtonState.InstallingOrPending -> StoreFlowState.VERIFYING_INSTALLATION
        decision.buttonState == ButtonState.Install -> when {
            tappedInstall -> StoreFlowState.INSTALLING
            confirmedInstall -> StoreFlowState.INSTALLING
            else -> StoreFlowState.APP_PAGE_VISIBLE
        }
        decision.ambiguousListings.size > 1 -> StoreFlowState.RESULTS_VISIBLE
        decision.matchedListingElement != null -> StoreFlowState.RESULTS_VISIBLE
        searchSubmitted -> StoreFlowState.WAITING_FOR_RESULTS
        hasSearchInput -> StoreFlowState.SEARCHING
        else -> StoreFlowState.IDLE
    }

    private fun primaryTitleOf(rawLabel: String): String {
        val segments = rawLabel.split(" · ", " • ").map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.isEmpty()) return rawLabel.trim()
        return segments.firstOrNull { !SPONSORED_SEGMENT_RE.matches(it) } ?: segments.first()
    }

    private fun isSponsoredCard(rawLabel: String): Boolean =
        rawLabel.split(" · ", " • ")
            .map { it.trim() }
            .any { SPONSORED_SEGMENT_RE.matches(it) }

    private fun rankMatchingListings(
        wantedCompact: String,
        wantedTokens: List<String>,
        elements: List<ScreenElement>,
        meta: Map<Int, ControlService.NodeMeta>,
    ): List<Pair<ScreenElement, Int>> {
        if (wantedCompact.isEmpty()) return emptyList()
        val wantedTokenSet = wantedTokens.toSet()
        return elements.mapNotNull { el ->
            val rawLbl = el.label.trim()
            if (rawLbl.isEmpty() ||
                INSTALL_BTN_RE.matches(rawLbl) ||
                OPEN_BTN_RE.matches(rawLbl) ||
                UPDATE_BTN_RE.matches(rawLbl) ||
                INSTALLED_BADGE_RE.matches(rawLbl) ||
                CANCEL_BTN_RE.matches(rawLbl) ||
                SEARCH_BAR_RE.containsMatchIn(rawLbl) ||
                rawLbl.equals("Search", ignoreCase = true) ||
                el.role == "field"
            ) {
                return@mapNotNull null
            }
            if (isSponsoredCard(rawLbl)) return@mapNotNull null

            val title = primaryTitleOf(rawLbl)
            val titleTokens = tokenize(title)
            if (titleTokens.isEmpty()) return@mapNotNull null
            // Reject unofficial clone/guide/mod/calculator listings unless explicitly asked for in query.
            if (titleTokens.any { it in CLONE_MODIFIER_TOKENS && it !in wantedTokenSet }) {
                return@mapNotNull null
            }
            val titleCompact = normalize(title)
            val titleTokenSet = titleTokens.toSet()

            // For multi-word app names (e.g. "Free Fire India"), require every requested token to match!
            val allTokensPresent = wantedTokens.isNotEmpty() && wantedTokens.all { wt ->
                titleTokenSet.any { tt -> tt == wt || (wt.length >= 4 && tt.startsWith(wt)) }
            }
            if (wantedTokens.size >= 2 && !allTokensPresent && titleCompact != wantedCompact) {
                return@mapNotNull null
            }

            var score = 0
            when {
                titleCompact == wantedCompact -> score += 120
                titleCompact.startsWith(wantedCompact) -> score += 90
                wantedCompact in titleCompact -> score += 65
                allTokensPresent -> score += 55
            }
            if (score > 0 && el.role == "button") score += 10
            if (score > 0 && meta[el.number]?.viewId.orEmpty().contains("title", ignoreCase = true)) score += 10
            if (score > 0) el to score else null
        }.sortedByDescending { it.second }
    }
}

/**
 * Google Play Store search & supported free-app installation workflow.
 * Never installs from third-party APK sites; skips Sponsored/clone apps; respects paid/age/auth
 * confirmation dialogs; reuses active Play Store screen when already showing target; and never
 * claims an app is installed unless Android's PackageManager verifies it.
 */
object PlayStoreTools {
    val SearchInstall: PhoneTool = SearchInstallTool
    private val installMutex = Mutex()

    private object SearchInstallTool : PhoneTool {
        override val name = "playstore_search_install"

        override fun declaration() = Schema.function(
            name,
            "Searches Google Play Store for an app, verifies via PackageManager if it is already installed, avoids Sponsored/unofficial clone apps, requests user confirmation before free Install, and verifies installation afterward via PackageManager.",
            Schema.string("app_name", "Exact app name to find/install on Google Play Store.", required = true),
            Schema.string("action", "install (default) or open_listing.", choices = listOf("install", "open_listing")),
            Schema.string("package_name", "Optional known verified package ID to check before opening Play Store. Never guess package IDs."),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val appName = args.text("app_name")
            if (appName.isEmpty()) return Results.failed("no_app", "Ask which app to look up on Google Play Store.")
            if (!installMutex.tryLock()) {
                return Results.failed("install_in_progress", "Another Play Store lookup/installation is already running. Wait for it to finish.")
            }
            try {
                return executeSearchInstall(appName, args, host)
            } finally {
                installMutex.unlock()
            }
        }

        private suspend fun executeSearchInstall(appName: String, args: JSONObject, host: ToolHost): JSONObject {
            val action = args.text("action").ifEmpty { "install" }
            val explicitPkg = args.text("package_name").takeIf { '.' in it }

            var flowState = StoreFlowState.CHECKING_INSTALLED
            val installerChecker = InstalledAppChecker(host.context)
            host.recoveryController.onDestinationChanged("playstore:$appName")

            // 1. Check live installed apps via PackageManager BEFORE touching Play Store
            val explicitInstalled = withContext(Dispatchers.IO) {
                explicitPkg != null && installerChecker.isInstalled(explicitPkg)
            }
            if (explicitInstalled && explicitPkg != null) {
                flowState = StoreFlowState.COMPLETED
                val verified = AgentActionResult(
                    status = ActionStatus.ALREADY_COMPLETED,
                    message = "$appName ($explicitPkg) is already installed on this phone. Ask if the user wants to open it.",
                    expectedDestination = explicitPkg,
                    actualDestination = explicitPkg,
                )
                Diag.i("playstore: state=$flowState pkg=$explicitPkg")
                return Results.done(verified.message)
            }

            val beforeApps = withContext(Dispatchers.IO) {
                InstalledAppsRegistry.apps(host.context, forceRefresh = true)
            }
            val alreadyInstalled = AppCatalog.find(appName, beforeApps, includeNonLaunchable = false)
            val exactMatched = withContext(Dispatchers.IO) {
                alreadyInstalled.firstOrNull {
                    AppCatalog.compactKey(it.label) == AppCatalog.compactKey(appName) &&
                        installerChecker.isInstalled(it.packageName)
                }
            }
            if (exactMatched != null) {
                flowState = StoreFlowState.COMPLETED
                val verified = AgentActionResult(
                    status = ActionStatus.ALREADY_COMPLETED,
                    message = "${exactMatched.label} (${exactMatched.packageName}) is already installed on this phone. Ask if the user wants to open it.",
                    expectedDestination = exactMatched.packageName,
                    actualDestination = exactMatched.packageName,
                )
                Diag.i("playstore: state=$flowState pkg=${exactMatched.packageName}")
                return Results.done(verified.message)
            }

            if (!AppSettings(host.context).isAutomationEnabled(AutomationCategory.PlayStoreNavigation)) {
                flowState = StoreFlowState.FAILED
                return Results.failed(
                    "automation_disabled",
                    "Play Store navigation is turned off in Settings → Permissions. Ask the user to enable it or install manually.",
                )
            }

            ActiveTaskContext.beginTask(
                task = "Play Store Install",
                application = "Google Play Store",
                appPackage = ControlRules.PLAY_STORE_PACKAGE,
                searchQuery = appName,
                executionStep = "Search Play Store",
            )

            val knownPackagesBefore = beforeApps.map { it.packageName }.toSet()
            val control = ControlService.instance?.takeIf { !it.overlay.stopped }

            // Avoid re-opening the same Play Store listing if it is already visible on screen!
            if (control != null && control.foregroundPackage() == ControlRules.PLAY_STORE_PACKAGE) {
                val currentSnap = control.snapshot(allowPlayStore = true)
                val currentDecision = currentSnap?.let { PlayStoreMatcher.analyzeScreen(appName, it.elements, it.meta) }
                if (currentDecision != null &&
                    (currentDecision.buttonState != PlayStoreMatcher.ButtonState.None || currentDecision.matchedListingElement != null)
                ) {
                    val outcome = automateInstall(
                        service = control,
                        host = host,
                        appName = appName,
                        wantInstall = action == "install",
                        knownPackagesBefore = knownPackagesBefore,
                        explicitPackage = explicitPkg,
                        alreadyForeground = true,
                    )
                    return Results.done(outcome)
                }
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
            if (control == null) {
                return host.openApp(
                    launchIntent,
                    "Google Play Store",
                    "Google Play Store will open on \"$appName\" after you speak so the user can check and tap Install.",
                )
            }

            flowState = StoreFlowState.OPENING_STORE
            return host.openApp(
                launchIntent,
                "Google Play Store",
                "Opening Google Play Store to check \"$appName\" and start installation if available.",
            ) {
                automateInstall(
                    service = control,
                    host = host,
                    appName = appName,
                    wantInstall = action == "install",
                    knownPackagesBefore = knownPackagesBefore,
                    explicitPackage = explicitPkg,
                    alreadyForeground = false,
                )
            }
        }

        private suspend fun automateInstall(
            service: ControlService,
            host: ToolHost,
            appName: String,
            wantInstall: Boolean,
            knownPackagesBefore: Set<String>,
            explicitPackage: String?,
            alreadyForeground: Boolean,
        ): String {
            var flowState = if (alreadyForeground) StoreFlowState.RESULTS_VISIBLE else StoreFlowState.OPENING_STORE
            if (!alreadyForeground && !service.waitFor(ControlRules.PLAY_STORE_PACKAGE, WAIT_MS)) {
                flowState = StoreFlowState.FAILED
                Diag.w("playstore: state=$flowState reason=wait_timeout")
                return "Google Play Store didn't open in time; ask the user to check Play Store."
            }

            val checker = InstalledAppChecker(host.context)
            var tappedInstall = false
            var confirmedInstall = false
            var typedSearch = false
            var tappedListingCount = 0
            var retriedNetwork = false
            var discoveredListingTitle: String? = null

            repeat(POLL_STEPS) {
                delay(STEP_DELAY_MS)
                val snap = service.snapshot(allowPlayStore = true) ?: return@repeat
                if (snap.packageName != ControlRules.PLAY_STORE_PACKAGE) {
                    flowState = StoreFlowState.OPENING_STORE
                    return@repeat
                }
                if (snap.sensitiveAuth) {
                    flowState = StoreFlowState.FAILED
                    return "Google Play Store asked for password or account verification. Hand control to the user."
                }

                val decision = PlayStoreMatcher.analyzeScreen(appName, snap.elements, snap.meta)
                if (!decision.listingTitle.isNullOrBlank()) {
                    discoveredListingTitle = decision.listingTitle
                }
                val searchEl = PlayStoreMatcher.findSearchInput(snap.elements)
                flowState = PlayStoreMatcher.resolveFlowState(
                    decision = decision,
                    alreadyInstalled = false,
                    inPlayStore = true,
                    hasSearchInput = searchEl != null,
                    searchSubmitted = typedSearch,
                    tappedInstall = tappedInstall,
                    confirmedInstall = confirmedInstall,
                    verifiedInstalledAfterAction = false,
                )

                when (decision.buttonState) {
                    PlayStoreMatcher.ButtonState.PaidOrBlocked -> {
                        flowState = StoreFlowState.FAILED
                        return decision.reason
                    }
                    PlayStoreMatcher.ButtonState.PreRegisterOrUnavailable -> {
                        flowState = StoreFlowState.FAILED
                        return decision.reason
                    }
                    PlayStoreMatcher.ButtonState.NetworkError -> {
                        if (!retriedNetwork && decision.actionElement != null) {
                            retriedNetwork = true
                            service.node(decision.actionElement.number)?.let(service::click)
                        } else {
                            flowState = StoreFlowState.FAILED
                            return decision.reason
                        }
                    }
                    PlayStoreMatcher.ButtonState.Open -> {
                        flowState = StoreFlowState.VERIFYING_INSTALLATION
                        val installed = findVerifiedInstalledApp(
                            host = host,
                            checker = checker,
                            appName = appName,
                            discoveredTitle = discoveredListingTitle,
                            knownPackagesBefore = knownPackagesBefore,
                            explicitPackage = explicitPackage,
                        )
                        if (installed != null) {
                            flowState = StoreFlowState.COMPLETED
                            ActiveTaskContext.complete("Verified installed: ${installed.label} (${installed.packageName})")
                            return "Verified: ${installed.label} (${installed.packageName}) is installed on this phone."
                        }
                        return "Google Play Store shows an Open/Play button for \"$appName\", though Android's PackageManager has not confirmed the installed package yet. Ask the user to verify on screen."
                    }
                    PlayStoreMatcher.ButtonState.InstallingOrPending -> {
                        flowState = StoreFlowState.VERIFYING_INSTALLATION
                        val newlyAdded = findVerifiedInstalledApp(
                            host = host,
                            checker = checker,
                            appName = appName,
                            discoveredTitle = discoveredListingTitle,
                            knownPackagesBefore = knownPackagesBefore,
                            explicitPackage = explicitPackage,
                        )
                        if (newlyAdded != null) {
                            flowState = StoreFlowState.COMPLETED
                            ActiveTaskContext.complete("Verified installed: ${newlyAdded.label} (${newlyAdded.packageName})")
                            return "Verified: ${newlyAdded.label} (${newlyAdded.packageName}) finished installing from Google Play Store."
                        }
                    }
                    PlayStoreMatcher.ButtonState.Install -> {
                        flowState = StoreFlowState.APP_PAGE_VISIBLE
                        if (!wantInstall) {
                            flowState = StoreFlowState.COMPLETED
                            return "Opened the Google Play Store listing for \"${discoveredListingTitle ?: appName}\". Ask the user if they want to tap Install."
                        }
                        if (!tappedInstall) {
                            flowState = StoreFlowState.INSTALL_CONFIRMATION
                            if (!confirmedInstall) {
                                confirmedInstall = service.overlay.confirm("Install \"${discoveredListingTitle ?: appName}\" from Google Play Store")
                                if (!confirmedInstall) {
                                    flowState = StoreFlowState.FAILED
                                    return "Installation of \"$appName\" was cancelled before tapping Install."
                                }
                            }
                            val node = decision.actionElement?.number?.let(service::node)
                            if (node != null && service.click(node)) {
                                tappedInstall = true
                                flowState = StoreFlowState.INSTALLING
                                service.overlay.showActive()
                                Diag.i("playstore: state=$flowState tapped Install for $appName")
                            }
                        }
                    }
                    PlayStoreMatcher.ButtonState.Update -> {
                        val existing = findVerifiedInstalledApp(
                            host = host,
                            checker = checker,
                            appName = appName,
                            discoveredTitle = discoveredListingTitle,
                            knownPackagesBefore = knownPackagesBefore,
                            explicitPackage = explicitPackage,
                        )
                        if (existing != null) {
                            flowState = StoreFlowState.COMPLETED
                            return "Verified: ${existing.label} (${existing.packageName}) is already installed on this phone with an Update available in Google Play Store."
                        }
                        return "Google Play Store shows an Update button for \"$appName\"."
                    }
                    PlayStoreMatcher.ButtonState.None -> {
                        if (decision.ambiguousListings.size > 1) {
                            flowState = StoreFlowState.RESULTS_VISIBLE
                            val options = decision.ambiguousListings.take(3).joinToString(", ") { it.label }
                            return "Multiple Play Store listings match \"$appName\" ($options). Ask the user which one to open."
                        }
                        val listingEl = decision.matchedListingElement
                        if (listingEl != null && tappedListingCount < 2) {
                            val node = service.node(listingEl.number)
                            if (node != null && service.click(node)) {
                                tappedListingCount++
                                flowState = StoreFlowState.OPENING_APP_PAGE
                                Diag.i("playstore: state=$flowState opened listing ${listingEl.label}")
                            }
                        } else if (!typedSearch && searchEl != null) {
                            val searchNode = service.node(searchEl.number)
                            if (searchNode != null) {
                                flowState = StoreFlowState.SEARCHING
                                if (searchNode.isEditable) {
                                    if (service.type(searchNode, appName, submitIme = true)) {
                                        typedSearch = true
                                        flowState = StoreFlowState.WAITING_FOR_RESULTS
                                        Diag.i("playstore: state=$flowState typed query $appName")
                                    }
                                } else if (service.click(searchNode)) {
                                    delay(500)
                                    service.focusedField()?.let { field ->
                                        if (service.type(field, appName, submitIme = true)) {
                                            typedSearch = true
                                            flowState = StoreFlowState.WAITING_FOR_RESULTS
                                            Diag.i("playstore: state=$flowState opened search & typed $appName")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            flowState = StoreFlowState.VERIFYING_INSTALLATION
            val newlyAdded = findVerifiedInstalledApp(
                host = host,
                checker = checker,
                appName = appName,
                discoveredTitle = discoveredListingTitle,
                knownPackagesBefore = knownPackagesBefore,
                explicitPackage = explicitPackage,
            )
            if (newlyAdded != null) {
                flowState = StoreFlowState.COMPLETED
                ActiveTaskContext.complete("Verified installed: ${newlyAdded.label} (${newlyAdded.packageName})")
                return "Verified: ${newlyAdded.label} (${newlyAdded.packageName}) is now installed."
            }
            return if (tappedInstall) {
                "Tapped Install for \"${discoveredListingTitle ?: appName}\" in Google Play Store; download/installation is still in progress and not yet confirmed by PackageManager."
            } else {
                flowState = StoreFlowState.FAILED
                "Opened Google Play Store search for \"$appName\", though an exact free Install button for \"$appName\" wasn't found (the app may be unavailable, pre-register only, or named differently). Ask the user to check the listing."
            }
        }

        private suspend fun findVerifiedInstalledApp(
            host: ToolHost,
            checker: InstalledAppChecker,
            appName: String,
            discoveredTitle: String?,
            knownPackagesBefore: Set<String>,
            explicitPackage: String?,
        ): InstalledAppInfo? = withContext(Dispatchers.IO) {
            if (explicitPackage != null && checker.isInstalled(explicitPackage)) {
                val refreshed = InstalledAppsRegistry.refresh(host.context)
                return@withContext refreshed.firstOrNull { it.packageName == explicitPackage }
                    ?: InstalledAppInfo(label = appName, packageName = explicitPackage, launchable = true)
            }
            val now = InstalledAppsRegistry.refresh(host.context)
            val newMatches = now.filter { it.packageName !in knownPackagesBefore }
            val queryNames = listOfNotNull(appName, discoveredTitle?.takeIf { it.isNotBlank() }).distinct()
            val candidate = queryNames.firstNotNullOfOrNull { q ->
                AppCatalog.find(q, newMatches, includeNonLaunchable = true).firstOrNull()
                    ?: AppCatalog.find(q, now, includeNonLaunchable = false)
                        .firstOrNull { AppCatalog.compactKey(it.label) == AppCatalog.compactKey(q) }
            }
            candidate?.takeIf { checker.isInstalled(it.packageName) }
        }

        private const val WAIT_MS = 8_000L
        private const val STEP_DELAY_MS = 900L
        private const val POLL_STEPS = 20
    }
}

