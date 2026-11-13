package app.shilex.android.tools

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import app.shilex.android.Branding
import app.shilex.android.control.ControlRules
import app.shilex.android.control.ControlService
import app.shilex.android.control.ScreenText
import app.shilex.android.control.SightAim
import app.shilex.android.data.KeyVault
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Phone control through Accessibility, same tool names as desktop. The model reads the
 * screen, then taps / types / scrolls by element number. Risky taps wait for the user's
 * Allow on JARVIS's own card; money, password and settings apps are never touched.
 */
object ScreenTools {
    fun all(): List<PhoneTool> = listOf(UiRead, UiClick, UiTapSeen, UiType, UiScroll, UiKey, HandToUser)

    /** The control service, or the tool result explaining why it can't be used. */
    private fun control(): Pair<ControlService?, JSONObject?> {
        val service = ControlService.instance
            ?: return null to Results.failed(
                "control_off",
                "Phone control is off. Tell the user to turn it on: open ${Branding.ASSISTANT_NAME} and tap Phone control.",
            )
        if (service.overlay.stopped) {
            return null to Results.failed("stopped_by_user", "The user pressed Stop. Don't control the phone until they ask again.")
        }
        return service to null
    }

    /** Reads the screen after an action, so the model sees the result without another call. */
    private suspend fun afterAction(service: ControlService, did: String): JSONObject {
        service.overlay.showActive()
        delay(SETTLE_MS)
        val fg = service.foregroundPackage()
        val snap = service.snapshot() ?: return if (ControlRules.isFinanceApp(fg)) {
            Results.done(
                "$did This banking or UPI screen blocked Accessibility inspection for security. " +
                    "Hand control back to the user with hand_to_user and explain why.",
            )
        } else {
            Results.done("$did The screen couldn't be read.")
        }
        if (snap.blocked) return Results.done("$did Now in ${snap.appLabel}, which ${Branding.ASSISTANT_NAME} doesn't read.")
        if (snap.sensitiveAuth) {
            return Results.done(
                "$did Now on a PIN, MPIN, OTP, CVV, or password screen in ${snap.appLabel}. " +
                    "Stop here, never read or enter credentials, and call hand_to_user so the user finishes this step.",
            )
        }
        if (snap.financeApp && (snap.elements.isEmpty() || snap.restrictedBySystem)) {
            return Results.done(
                "$did ${snap.appLabel} or Android blocked Accessibility inspection on this screen for security. " +
                    "Hand control back to the user with hand_to_user and explain why.",
            )
        }
        val summary = if (snap.financeApp) ControlRules.extractTransactionSummary(snap.elements) else ""
        val prefix = if (summary.isNotEmpty()) "$did Summary on screen: $summary\n" else "$did "
        return Results.done(prefix + "Screen now:\n" + ScreenText.describe(snap.appLabel, snap.elements, snap.more))
    }

    private fun appLabel(service: ControlService, pkg: String): String =
        if (pkg.isEmpty()) "this app" else runCatching {
            service.packageManager.getApplicationLabel(service.packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

    private fun blocked(app: String) = Results.failed(
        "blocked",
        "${Branding.ASSISTANT_NAME} doesn't read or touch $app (passwords, authenticators, PIN components, app store or settings). The user does this part.",
    )

    private fun financeDeclined(app: String) = Results.failed(
        "not_allowed",
        "The user didn't allow assistance in $app. Stop and let the user handle it directly.",
    )

    private fun sensitiveAuth(what: String) = Results.failed(
        "sensitive_auth",
        "${Branding.ASSISTANT_NAME} stops before PIN, MPIN, OTP, CVV, or password steps ($what) and never reads, stores, or enters credentials. " +
            "Explain this and call hand_to_user so the user completes it directly.",
    )

    private fun accessibilityRestricted(app: String) = Results.failed(
        "accessibility_restricted",
        "$app or Android blocked Accessibility access on this screen for security. Never bypass secure windows—hand control back to the user with hand_to_user and explain why.",
    )

    /** Paying is always the user's: a pay or buy tap is refused, not offered on the Allow card. */
    private fun payment(label: String, summary: String = "") = Results.failed(
        "payment_is_users",
        buildString {
            append("\"${ScreenText.shorten(label.ifEmpty { "This" })}\" authorizes payment. ")
            if (summary.isNotEmpty()) append("Transaction summary: $summary. ")
            append("Stop here: tell the user the summary, ask them to tap Pay and enter their UPI PIN, OTP, or password directly in the official app, and call hand_to_user.")
        },
    )

    private object UiRead : PhoneTool {
        override val name = "ui_read"

        override fun declaration() = Schema.function(
            name,
            "Reads the app on screen as a numbered list of buttons, fields and text. Use before tapping.",
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val (service, problem) = control()
            service ?: return problem!!
            val fg = service.foregroundPackage()
            val snap = service.snapshot() ?: return if (ControlRules.isFinanceApp(fg)) {
                accessibilityRestricted(appLabel(service, fg))
            } else {
                Results.failed("no_screen", "The screen couldn't be read.")
            }
            if (snap.packageName == host.context.packageName) {
                return Results.failed(
                    "own_screen",
                    "The user is looking at ${Branding.ASSISTANT_NAME} itself. Ask them to open the app they mean, or offer open_app.",
                )
            }
            if (snap.blocked) return blocked(snap.appLabel)
            if (snap.sensitiveAuth) return sensitiveAuth(snap.appLabel)
            if (snap.financeApp && !host.authorizeFinance(snap.packageName, snap.appLabel)) {
                return financeDeclined(snap.appLabel)
            }
            if (snap.financeApp && (snap.elements.isEmpty() || snap.restrictedBySystem)) {
                return accessibilityRestricted(snap.appLabel)
            }
            val summary = if (snap.financeApp) ControlRules.extractTransactionSummary(snap.elements) else ""
            val body = ScreenText.describe(snap.appLabel, snap.elements, snap.more)
            return Results.done(if (summary.isNotEmpty()) "Summary on screen: $summary\n$body" else body)
        }
    }

    private object UiClick : PhoneTool {
        override val name = "ui_click"

        override fun declaration() = Schema.function(
            name,
            "Taps an element from the last ui_read, by number (preferred) or exact label. Send, call, " +
                "delete, book and sensitive payment-prep taps show the user an Allow button first; final pay, buy, and PIN/OTP/password taps are refused.",
            Schema.integer("number", "Element number from ui_read."),
            Schema.string("label", "The element's label, if no number."),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val (service, problem) = control()
            service ?: return problem!!
            val node = target(service, args) ?: return Results.failed(
                "not_found",
                "No such element on screen now. Call ui_read and use a number from it.",
            )
            node.second?.let { return it }
            val target = node.first!!
            val pkg = target.packageName?.toString().orEmpty()
            val name = appLabel(service, pkg)
            if (ControlRules.blocked(pkg)) return blocked(name)
            if (service.isSensitiveAuthActive()) return sensitiveAuth("authentication screen")
            if (ControlRules.isFinanceApp(pkg) && !host.authorizeFinance(pkg, name)) {
                return financeDeclined(name)
            }
            val label = service.labelOf(target)
            val viewId = target.viewIdResourceName.orEmpty()
            if (ControlRules.isCredentialAction(label, viewId, target.isPassword)) {
                return sensitiveAuth(ScreenText.shorten(label.ifEmpty { "credential control" }))
            }
            val summary = if (ControlRules.isFinanceApp(pkg)) ControlRules.extractTransactionSummary(service.lastElements()) else ""
            val risk = ControlRules.clickRisk(label, viewId, pkg)
            if (risk == ControlRules.Risk.Money) return payment(label, summary)
            if (risk != ControlRules.Risk.Safe) {
                val shortLabel = ScreenText.shorten(label.ifEmpty { "this button" })
                val what = if (summary.isNotEmpty()) "Tap \"$shortLabel\" ($summary)" else "Tap \"$shortLabel\""
                if (!service.overlay.confirm(what)) {
                    return Results.failed("cancelled", "The user didn't allow it. Don't try again unless they ask.")
                }
            }
            if (!service.click(target)) {
                return if (ControlRules.isFinanceApp(pkg)) {
                    accessibilityRestricted(name)
                } else {
                    Results.failed("tap_failed", "The tap didn't go through.")
                }
            }
            return afterAction(service, "Tapped \"${ScreenText.shorten(label)}\".")
        }

        /** (node, null) when found, (null, question) when several labels match, null when none. */
        private fun target(service: ControlService, args: JSONObject): Pair<AccessibilityNodeInfo?, JSONObject?>? {
            val number = args.optInt("number", 0)
            if (number > 0) return service.node(number)?.let { it to null }
            val label = args.text("label")
            val fg = service.foregroundPackage()
            val snap = service.snapshot() ?: return if (ControlRules.isFinanceApp(fg)) {
                null to accessibilityRestricted(appLabel(service, fg))
            } else {
                null
            }
            if (snap.blocked) return null to blocked(snap.appLabel)
            if (snap.sensitiveAuth) return null to sensitiveAuth(snap.appLabel)
            if (snap.financeApp && (snap.elements.isEmpty() || snap.restrictedBySystem)) {
                return null to accessibilityRestricted(snap.appLabel)
            }
            val matches = ScreenText.find(snap.elements, label)
            return when {
                matches.isEmpty() -> null
                matches.size > 1 -> null to Results.ask(
                    "Several elements match. Ask which, or use a number.",
                    matches.take(6).map { "${it.number}. ${it.label}" },
                )
                else -> service.node(matches.single().number)?.let { it to null }
            }
        }
    }

    private object UiType : PhoneTool {
        override val name = "ui_type"

        override fun declaration() = Schema.function(
            name,
            "Types text into an ordinary non-sensitive field (replacing what's there). Doesn't send; never types PINs, OTPs, CVVs, or passwords.",
            Schema.string("text", "Exactly what to type, in the user's words.", required = true),
            Schema.integer("number", "Field number from ui_read; empty for the focused field."),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val (service, problem) = control()
            service ?: return problem!!
            if (service.isSensitiveAuthActive()) return sensitiveAuth("authentication screen")
            val number = args.optInt("number", 0)
            val field = (if (number > 0) service.node(number) else service.focusedField())
                ?.takeIf { it.isEditable }
                ?: return Results.failed("no_field", "No text field selected. Call ui_read and give the field's number.")
            val pkg = field.packageName?.toString().orEmpty()
            val name = appLabel(service, pkg)
            if (ControlRules.blocked(pkg)) return blocked(name)
            if (ControlRules.isFinanceApp(pkg) && !host.authorizeFinance(pkg, name)) {
                return financeDeclined(name)
            }
            val label = service.labelOf(field)
            val viewId = field.viewIdResourceName.orEmpty()
            if (ControlRules.isCredentialAction(label, viewId, field.isPassword)) {
                return sensitiveAuth(ScreenText.shorten(label.ifEmpty { "credential field" }))
            }
            if (!service.type(field, args.text("text"))) {
                return if (ControlRules.isFinanceApp(pkg)) {
                    accessibilityRestricted(name)
                } else {
                    Results.failed("type_failed", "Typing didn't work in that field.")
                }
            }
            return afterAction(service, "Typed it.")
        }
    }

    /**
     * Click-by-sight: for buttons ui_read can't name (games, image-only buttons, canvases).
     * Never uploads screenshots of banking or UPI screens. The tap's risk is the worst of what
     * the model asked for, what Gemini saw there and the control Android reports at that point.
     */
    private object UiTapSeen : PhoneTool {
        override val name = "ui_tap_seen"

        override fun declaration() = Schema.function(
            name,
            "Taps something by looking at the screen. Only when ui_read doesn't list it (image buttons, " +
                "games). Sends a screenshot to Gemini (disabled on banking/UPI screens for security). Send, call, delete and similar taps show Allow first; pay and PIN/OTP/password taps are refused.",
            Schema.string("target", "What to tap, as seen: e.g. \"the red play button in the middle\".", required = true),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val (service, problem) = control()
            service ?: return problem!!
            val target = args.text("target").ifEmpty { return Results.failed("no_target", "Say what to tap.") }
            val pkg = service.foregroundPackage()
            val fgLabel = appLabel(service, pkg)
            if (pkg == host.context.packageName) return Results.failed("own_screen", "That's ${Branding.ASSISTANT_NAME} itself.")
            if (ControlRules.blocked(pkg)) return blocked(fgLabel)
            if (service.isSensitiveAuthActive()) return sensitiveAuth("authentication screen")
            if (ControlRules.isFinanceApp(pkg)) {
                return Results.failed(
                    "screenshot_restricted_finance",
                    "For security, ${Branding.ASSISTANT_NAME} never captures or uploads screenshots of $fgLabel. Use ui_read / ui_click if available, or call hand_to_user so the user does this step.",
                )
            }
            if (ControlRules.isCredentialAction(target, "")) return sensitiveAuth(target)
            val key = KeyVault(host.context).read(KeyVault.GEMINI_KEY)
                ?: return Results.failed("no_key", "Looking at the screen needs the Gemini key.")
            val shot = service.screenshot() ?: return Results.failed(
                "no_sight",
                "This screen can't be looked at (protected, or Android older than 11). The user taps this part.",
            )
            val seen = when (val found = SightAim(host.http, key).locate(shot, target)) {
                is SightAim.Outcome.Found -> found
                SightAim.Outcome.NotFound -> return Results.failed("not_seen", "Couldn't see \"$target\" on screen. Say what you see instead.")
                is SightAim.Outcome.Failed -> return Results.failed("sight_failed", found.message)
            }
            val node = service.nodeAt(seen.x, seen.y)
            val there = node?.packageName?.toString().orEmpty()
            if (there == host.context.packageName) return Results.failed("own_screen", "That spot is ${Branding.ASSISTANT_NAME}'s own button.")
            if (ControlRules.blocked(there) || ControlRules.isFinanceApp(there)) return blocked(appLabel(service, there))
            val label = node?.let(service::labelOf).orEmpty()
            val nodeId = node?.viewIdResourceName.orEmpty()
            if (ControlRules.isCredentialAction(label, nodeId, node?.isPassword == true) ||
                ControlRules.isCredentialAction(seen.sees, "")
            ) {
                return sensitiveAuth(ScreenText.shorten(label.ifEmpty { seen.sees }.ifEmpty { target }))
            }
            val effectivePkg = there.ifEmpty { pkg }
            val risk = listOf(
                ControlRules.clickRisk(target, "", effectivePkg),
                ControlRules.clickRisk(seen.sees, "", effectivePkg),
                ControlRules.clickRisk(label, nodeId, effectivePkg),
            ).maxBy { it.ordinal }
            val name = ScreenText.shorten(label.ifEmpty { seen.sees }.ifEmpty { target })
            if (risk == ControlRules.Risk.Money) return payment(name)
            if (risk != ControlRules.Risk.Safe && !service.overlay.confirm("Tap \"$name\"")) {
                return Results.failed("cancelled", "The user didn't allow it. Don't try again unless they ask.")
            }
            if (!service.tapAt(seen.x, seen.y)) return Results.failed("tap_failed", "The tap didn't go through.")
            return afterAction(service, "Tapped \"$name\" (by sight).")
        }
    }

    /**
     * Secure-screen handover: the steps JARVIS must not do (final Pay authorization, a PIN,
     * a password, an OTP, or an Accessibility-restricted screen) go to the user with a
     * "Your turn" card; after Done, JARVIS inspects only the visible non-sensitive result screen.
     */
    private object HandToUser : PhoneTool {
        override val name = "hand_to_user"

        override fun declaration() = Schema.function(
            name,
            "Gives one step to the user and waits (up to 2.5 min) until they tap Done: final payment authorization, a UPI PIN, MPIN, " +
                "password, OTP, CVV, or a screen that restricts Accessibility. Then returns the resulting non-sensitive status screen.",
            Schema.string("step", "The step in a few words, e.g. \"Tap Pay and enter your UPI PIN\".", required = true),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val (service, problem) = control()
            service ?: return problem!!
            val step = ScreenText.shorten(args.text("step").ifEmpty { "Finish this step" })
            if (!service.overlay.handover(step)) {
                return Results.failed("stopped", "The user stopped or didn't finish. Ask what they'd like; don't carry on by yourself.")
            }
            val pkg = service.foregroundPackage()
            if (pkg == host.context.packageName || ControlRules.blocked(pkg)) {
                return Results.done("The user tapped Done. The screen now is one ${Branding.ASSISTANT_NAME} doesn't read; ask what's next if unsure.")
            }
            if (service.isSensitiveAuthActive()) {
                return Results.done(
                    "The user tapped Done, though a PIN, OTP, or password screen is still open. " +
                        "Ask them to finish or cancel first before checking the result.",
                )
            }
            return afterAction(service, "The user tapped Done.")
        }
    }

    private object UiScroll : PhoneTool {
        override val name = "ui_scroll"

        override fun declaration() = Schema.function(
            name,
            "Scrolls the screen to show more.",
            Schema.string("direction", "down shows what's further down.", required = true, choices = listOf("down", "up")),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val (service, problem) = control()
            service ?: return problem!!
            val pkg = service.foregroundPackage()
            val name = appLabel(service, pkg)
            if (ControlRules.blocked(pkg)) return blocked(name)
            if (service.isSensitiveAuthActive()) return sensitiveAuth("authentication screen")
            if (ControlRules.isFinanceApp(pkg) && !host.authorizeFinance(pkg, name)) {
                return financeDeclined(name)
            }
            if (!service.scroll(down = args.text("direction") != "up")) return Results.failed("no_scroll", "Nothing to scroll here.")
            return afterAction(service, "Scrolled.")
        }
    }

    private object UiKey : PhoneTool {
        override val name = "ui_key"

        override fun declaration() = Schema.function(
            name,
            "Presses a phone key: back, home, recents, notifications (pull down the shade).",
            Schema.string("key", "Which key.", required = true, choices = KEYS.keys.toList()),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val (service, problem) = control()
            service ?: return problem!!
            val action = KEYS[args.text("key")] ?: return Results.failed("bad_key", "Use back, home, recents or notifications.")
            if (!service.performGlobalAction(action)) return Results.failed("key_failed", "The phone didn't take that key.")
            return afterAction(service, "Pressed ${args.text("key")}.")
        }

        private val KEYS = mapOf(
            "back" to AccessibilityService.GLOBAL_ACTION_BACK,
            "home" to AccessibilityService.GLOBAL_ACTION_HOME,
            "recents" to AccessibilityService.GLOBAL_ACTION_RECENTS,
            "notifications" to AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
        )
    }

    private const val SETTLE_MS = 900L
}
