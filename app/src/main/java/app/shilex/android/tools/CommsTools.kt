package app.shilex.android.tools

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import app.shilex.android.Branding
import app.shilex.android.control.ControlService
import app.shilex.android.control.WhatsAppCallMatcher
import app.shilex.android.control.WhatsAppChatVerifier
import app.shilex.android.control.WhatsAppDeleteMatcher
import app.shilex.android.control.WhatsAppWorkflow
import app.shilex.android.data.AutomationCategory
import app.shilex.android.data.Settings as AppSettings
import app.shilex.android.notify.Replies
import app.shilex.android.task.ActiveTaskContext
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.Locale

/**
 * Pure call-route classifier (JVM unit-tested without Android).
 * Strictly separates cellular phone calls from WhatsApp audio calls and WhatsApp video calls.
 */
object CallClassifier {
    enum class Route { NormalPhone, WhatsAppAudio, WhatsAppVideo }

    private val WHATSAPP_CUES = Regex("""\b(whatsapp|what\s*s\s*app|whats\s*app|wa)\b|व्हाट्सएप|व्हाट्सऐप""", RegexOption.IGNORE_CASE)
    private val VIDEO_CUES = Regex("""\b(video\s*call|video|vc|cam)\b|वीडियो""", RegexOption.IGNORE_CASE)
    private val NORMAL_CUES = Regex("""\b(normal|cellular|sim|phone\s*call|carrier|regular|ordinary)\b""", RegexOption.IGNORE_CASE)

    fun classify(spokenText: String, explicitChannel: String = ""): Route {
        val ch = explicitChannel.lowercase().trim()
        when (ch) {
            "whatsapp_video", "wa_video" -> return Route.WhatsAppVideo
            "whatsapp", "whatsapp_audio", "wa_audio" -> return Route.WhatsAppAudio
            "normal", "phone", "cellular" -> return Route.NormalPhone
        }
        val text = spokenText.lowercase()
        val hasWhatsApp = WHATSAPP_CUES.containsMatchIn(text)
        val hasVideo = VIDEO_CUES.containsMatchIn(text)
        if (hasWhatsApp) {
            return if (hasVideo) Route.WhatsAppVideo else Route.WhatsAppAudio
        }
        if (NORMAL_CUES.containsMatchIn(text)) return Route.NormalPhone
        return Route.NormalPhone
    }
}

/**
 * Calls and WhatsApp / SMS communication tools.
 * Honors persistent Settings -> Permissions automation categories to avoid repetitive internal
 * Allow popups once authorized, while strictly preserving Android permissions, recipient
 * disambiguation, recipient verification, and honest outcome reporting.
 */
object CommsTools {
    fun all(): List<PhoneTool> = listOf(
        CallContact,
        WhatsAppSend,
        WhatsAppOpenChat,
        WhatsAppReadChat,
        WhatsAppSearchContact,
        WhatsAppDeleteMessage,
        WhatsAppCall,
        SmsSend,
    )

    private val who = arrayOf(
        Schema.string("name", "Contact name as the user said it. Leave empty if they gave a number."),
        Schema.string("number", "Phone number, only if the user said one."),
        Schema.string("which", "Which of the contact's numbers, e.g. mobile, work, or the last digits."),
    )

    private object CallContact : PhoneTool {
        override val name = "call_contact"

        override fun declaration() = Schema.function(
            name,
            "Places a normal cellular phone call (default) or routes to WhatsApp call ONLY if requested via channel. Verifies Phone permission and whether the dialer or call screen actually opened.",
            *who,
            Schema.string("channel", "normal (default), whatsapp_audio, or whatsapp_video.", choices = listOf("normal", "whatsapp_audio", "whatsapp_video")),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val route = CallClassifier.classify("${args.text("name")} ${host.lastUserText}", args.text("channel"))
            if (route != CallClassifier.Route.NormalPhone) {
                return WhatsAppCall.executeCall(args, host, route == CallClassifier.Route.WhatsAppVideo)
            }
            if (!AppSettings(host.context).isAutomationEnabled(AutomationCategory.Calling)) {
                return Results.failed("automation_disabled", "Calling automation is turned off in Settings → Permissions.")
            }
            val effectiveArgs = injectRecipientFromContextIfMissing(args)
            val to = when (val r = Recipients.resolve(effectiveArgs, host, "call")) {
                is Recipients.Result.Found -> r
                is Recipients.Result.Answer -> return r.json
            }
            ActiveTaskContext.beginTask(
                task = "Normal Phone Call",
                application = "Phone",
                recipient = to.name,
                executionStep = "Initiate call",
                pendingSteps = listOf("Verify calling screen"),
            )

            val telUri = Uri.fromParts("tel", to.number, null)
            val hasCallPerm = host.hasPermission(Manifest.permission.CALL_PHONE)
            val intent = if (hasCallPerm) {
                Intent(Intent.ACTION_CALL, telUri)
            } else {
                if (!host.isPermissionDeniedInSession(Manifest.permission.CALL_PHONE)) {
                    host.requestPermission(Manifest.permission.CALL_PHONE)
                }
                Intent(Intent.ACTION_DIAL, telUri)
            }

            if (hasCallPerm) {
                val control = ControlService.instance?.takeIf { !it.overlay.stopped }
                return host.openApp(
                    intent,
                    "Phone",
                    "Placing a normal call to ${to.name}.",
                ) { verifyNormalCallScreen(host.context, control, to.name, directCall = true) }
            }
            return host.openApp(
                intent,
                "Phone",
                "Phone call permission isn't granted yet, so the Phone dialer is opening with ${to.name}; the user taps Call.",
            )
        }

        @SuppressLint("MissingPermission")
        private suspend fun verifyNormalCallScreen(
            context: Context,
            control: ControlService?,
            recipient: String,
            directCall: Boolean,
        ): String? {
            if (!directCall) return null
            delay(CALL_VERIFY_WAIT_MS)
            val telecom = runCatching { context.getSystemService(TelecomManager::class.java) }.getOrNull()
            val inCall = runCatching { telecom?.isInCall == true }.getOrDefault(false)
            val fg = control?.foregroundPackage().orEmpty()
            val dialerOpen = "dialer" in fg.lowercase() || "telecom" in fg.lowercase() || "incallui" in fg.lowercase()
            return when {
                inCall -> {
                    ActiveTaskContext.complete("Calling UI active for $recipient.")
                    "Normal phone calling screen is active for $recipient (carrier connection depends on the network)."
                }
                dialerOpen -> {
                    ActiveTaskContext.complete("Phone dialer/call screen opened for $recipient.")
                    "Opened the Phone calling interface for $recipient."
                }
                else -> "Launched the call request for $recipient, though JARVIS couldn't confirm the active call screen."
            }
        }
    }

    private object WhatsAppSend : PhoneTool {
        override val name = "whatsapp_send"

        override fun declaration() = Schema.function(
            name,
            "Opens a WhatsApp chat with the dictated message typed in. When WhatsApp automation is enabled in Settings and Phone control is active on a clean command, automatically taps Send and verifies the message appeared in chat without extra repeated internal Allow popups.",
            *who,
            Schema.string("message", "The exact message in the user's words and language.", required = true),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val message = args.text("message")
            if (message.isEmpty()) return Results.failed("no_message", "Ask what the message should say.")
            val waPkg = WhatsAppWorkflow.resolveInstalledPackage(host.context)
                ?: return Results.failed("whatsapp_missing", "WhatsApp is not installed on this phone.")
            val effectiveArgs = injectRecipientFromContextIfMissing(args)
            handsFree(effectiveArgs, WHATSAPP)?.let { return it }
            val to = when (val r = Recipients.resolve(effectiveArgs, host, "message")) {
                is Recipients.Result.Found -> r
                is Recipients.Result.Answer -> return r.json
            }
            val digits = internationalDigits(host.context, to.number)
                ?: return Results.failed("bad_number", "That number doesn't look complete. Ask for it with the country code.")

            ActiveTaskContext.beginTask(
                task = "WhatsApp Message",
                application = "WhatsApp",
                appPackage = waPkg,
                recipient = to.name,
                searchQuery = message,
                executionStep = "Open WhatsApp chat",
                pendingSteps = listOf("Verify recipient header", "Tap Send", "Verify sent in chat"),
            )

            val link = Uri.parse("https://api.whatsapp.com/send?phone=$digits&text=${Uri.encode(message)}")
            val intent = Intent(Intent.ACTION_VIEW, link).setPackage(waPkg)
            val service = ControlService.instance?.takeIf { !it.overlay.stopped }
            val autoSendAuthorized = AppSettings(host.context).isAutomationEnabled(AutomationCategory.WhatsAppMessages)

            if (service != null) {
                val promptMsg = if (autoSendAuthorized) {
                    "Opening WhatsApp chat with ${to.name} to send \"$message\" and verify delivery."
                } else {
                    "WhatsApp will open with your draft to ${to.name}, then ${Branding.ASSISTANT_NAME} will ask you to tap Allow before pressing Send. " +
                        "Wait until confirmation before saying it was sent."
                }
                return host.openApp(intent, "WhatsApp", promptMsg) {
                    WhatsAppWorkflow.confirmAndSendAfterOpen(
                        service = service,
                        recipient = to.name,
                        message = message,
                        recipientPhoneDigits = digits,
                        skipInternalConfirm = autoSendAuthorized,
                    )
                }
            }
            return host.openApp(
                intent,
                "WhatsApp",
                "WhatsApp will open with the message to ${to.name} after you speak; Phone control is off so the user checks and taps Send.",
            ) { SendCheck.watchWhatsApp(message) }
        }
    }

    private object WhatsAppOpenChat : PhoneTool {
        override val name = "whatsapp_open_chat"

        override fun declaration() = Schema.function(
            name,
            "Opens a specific WhatsApp conversation by contact name or number and remembers the active recipient for follow-up commands like 'ab use hi bhejo'.",
            *who,
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val waPkg = WhatsAppWorkflow.resolveInstalledPackage(host.context)
                ?: return Results.failed("whatsapp_missing", "WhatsApp is not installed on this phone.")
            val to = when (val r = Recipients.resolve(args, host, "open in WhatsApp")) {
                is Recipients.Result.Found -> r
                is Recipients.Result.Answer -> return r.json
            }
            val digits = internationalDigits(host.context, to.number)
                ?: return Results.failed("bad_number", "That number doesn't look complete.")
            ActiveTaskContext.beginTask(
                task = "WhatsApp Open Chat",
                application = "WhatsApp",
                appPackage = waPkg,
                recipient = to.name,
                executionStep = "Open chat",
            )
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$digits")).setPackage(waPkg)
            val service = ControlService.instance?.takeIf { !it.overlay.stopped }
            if (service != null) {
                return host.openApp(intent, "WhatsApp", "Opening WhatsApp chat with ${to.name}.") {
                    WhatsAppWorkflow.verifyChatOpened(service, to.name, recipientPhoneDigits = digits)
                }
            }
            return host.openApp(intent, "WhatsApp", "Opening WhatsApp chat with ${to.name}.")
        }
    }

    private object WhatsAppReadChat : PhoneTool {
        override val name = "whatsapp_read_chat"

        override fun declaration() = Schema.function(
            name,
            "Reads the currently visible WhatsApp conversation header and recent visible messages when authorized.",
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val service = ControlService.instance?.takeIf { !it.overlay.stopped }
                ?: return Results.failed("control_off", "Phone control is off. Turn on Accessibility control to read WhatsApp.")
            val snap = service.snapshot()
                ?: return Results.failed("no_screen", "Couldn't read the visible WhatsApp screen.")
            if (snap.packageName !in WHATSAPP) {
                return Results.failed("not_whatsapp", "WhatsApp isn't currently on screen (visible app: ${snap.appLabel}).")
            }
            if (snap.sensitiveAuth) {
                return Results.failed("sensitive_auth", "WhatsApp is locked; ask the user to unlock it first.")
            }
            val headers = WhatsAppChatVerifier.extractHeaderNames(snap.elements, snap.meta)
            val chatTitle = headers.firstOrNull().orEmpty()
            if (chatTitle.isNotEmpty()) {
                val ctx = ActiveTaskContext.snapshot()
                if (!ctx.isActive || ctx.currentRecipient.isEmpty()) {
                    ActiveTaskContext.beginTask(
                        task = "WhatsApp Chat",
                        application = "WhatsApp",
                        appPackage = snap.packageName,
                        screen = "chat:$chatTitle",
                        recipient = chatTitle,
                    )
                } else {
                    ActiveTaskContext.updateScreen(
                        application = "WhatsApp",
                        appPackage = snap.packageName,
                        screen = "chat:$chatTitle",
                    )
                }
            }
            val visibleLines = snap.elements
                .filter { it.role != "field" && it.label.isNotBlank() }
                .take(18)
                .joinToString("\n") { "- ${it.label}" }
            return Results.done(
                buildString {
                    if (chatTitle.isNotEmpty()) append("WhatsApp chat: $chatTitle\n")
                    append(visibleLines.ifEmpty { "No readable messages on screen." })
                },
            )
        }
    }

    private object WhatsAppSearchContact : PhoneTool {
        override val name = "whatsapp_search_contact"

        override fun declaration() = Schema.function(
            name,
            "Searches contacts for a WhatsApp match by name, handling duplicate names honestly.",
            Schema.string("name", "Contact name to search.", required = true),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val query = args.text("name")
            if (query.isEmpty()) return Results.failed("no_name", "Ask which contact name to search for.")
            if (!host.hasPermission(ContactsReader.PERMISSION)) {
                if (host.isPermissionDeniedInSession(ContactsReader.PERMISSION)) {
                    return Results.failed("permission_denied", "Contacts permission was denied. Ask the user to enable Contacts in Settings or say the phone number.")
                }
                host.requestPermission(ContactsReader.PERMISSION)
                return Results.needsPermission("Contacts")
            }
            val matches = ContactsReader(host.context).search(query)
            if (matches.isEmpty()) {
                return Results.failed("not_found", "No contact matching \"$query\" was found.")
            }
            if (matches.size == 1) {
                val c = matches.single()
                ActiveTaskContext.beginTask("WhatsApp Contact Search", "WhatsApp", recipient = c.name)
                return Results.done("Found contact: ${c.name} (${c.numbers.joinToString { it.number }}).")
            }
            return Results.ask(
                "Multiple contacts match \"$query\". Ask which one.",
                matches.take(6).map { "${it.name} (${it.numbers.firstOrNull()?.number.orEmpty()})" },
            )
        }
    }

    private object WhatsAppDeleteMessage : PhoneTool {
        override val name = "whatsapp_delete_message"

        override fun declaration() = Schema.function(
            name,
            "Safely deletes a specific or latest message inside a WhatsApp chat via Accessibility: long-presses ONLY the target message, taps Delete, and selects 'Delete for me' by default or 'Delete for everyone' ONLY if explicitly requested and available. Never uses ui_click blindly.",
            *who,
            Schema.string("message_text", "Optional exact text or snippet of the message to delete. Leave empty for the latest visible message."),
            Schema.string("scope", "for_me (default) or for_everyone (only if explicitly asked by the user).", choices = listOf("for_me", "for_everyone")),
            Schema.string("confirmed", "true ONLY if the user already explicitly confirmed deleting this specific message.", choices = listOf("false", "true")),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val service = ControlService.instance?.takeIf { !it.overlay.stopped }
                ?: return Results.failed("control_off", "Phone control is off. Turn on Accessibility control to delete a WhatsApp message.")
            val waPkg = WhatsAppWorkflow.resolveInstalledPackage(host.context)
                ?: return Results.failed("whatsapp_missing", "WhatsApp is not installed on this phone.")

            val scopeArg = args.text("scope")
            val scope = if (scopeArg.isNotEmpty()) {
                WhatsAppDeleteMatcher.parseScope(scopeArg)
            } else {
                WhatsAppDeleteMatcher.parseScope(host.lastUserText)
            }
            val messageText = args.text("message_text")
            val which = args.text("which").ifEmpty { "last" }
            val confirmed = args.text("confirmed").equals("true", ignoreCase = true)

            val explicitName = args.text("name")
            val explicitNumber = args.text("number")
            val currentSnap = service.snapshot()
            val alreadyInWaChat = currentSnap != null &&
                currentSnap.packageName in WHATSAPP &&
                WhatsAppChatVerifier.isChatScreenOpen(currentSnap.elements, currentSnap.meta)

            // If user is already inside the right WhatsApp chat, delete directly without reloading WhatsApp.
            if (alreadyInWaChat && currentSnap != null) {
                val targetName = explicitName.ifEmpty {
                    ActiveTaskContext.snapshot().currentRecipient.takeIf {
                        ActiveTaskContext.snapshot().isActive &&
                            ActiveTaskContext.snapshot().currentApplication.equals("WhatsApp", ignoreCase = true)
                    }.orEmpty()
                }
                if (targetName.isEmpty() && explicitNumber.isEmpty()) {
                    val resultMsg = WhatsAppWorkflow.deleteMessage(
                        service = service,
                        recipient = "",
                        which = which,
                        messageText = messageText,
                        scope = scope,
                        alreadyConfirmed = confirmed,
                    )
                    return Results.done(resultMsg)
                }
                if (targetName.isNotEmpty() &&
                    WhatsAppChatVerifier.matchesExpectedRecipient(currentSnap.elements, currentSnap.meta, targetName)
                ) {
                    val resultMsg = WhatsAppWorkflow.deleteMessage(
                        service = service,
                        recipient = targetName,
                        which = which,
                        messageText = messageText,
                        scope = scope,
                        alreadyConfirmed = confirmed,
                    )
                    return Results.done(resultMsg)
                }
            }

            // Otherwise resolve contact, open that WhatsApp chat first, and then delete safely.
            val effectiveArgs = injectRecipientFromContextIfMissing(args)
            val to = when (val r = Recipients.resolve(effectiveArgs, host, "delete a message in")) {
                is Recipients.Result.Found -> r
                is Recipients.Result.Answer -> return r.json
            }
            val digits = internationalDigits(host.context, to.number)
                ?: return Results.failed("bad_number", "That number doesn't look complete.")

            ActiveTaskContext.beginTask(
                task = "WhatsApp Delete Message",
                application = "WhatsApp",
                appPackage = waPkg,
                recipient = to.name,
                executionStep = "Open chat to delete message",
            )
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$digits")).setPackage(waPkg)
            return host.openApp(
                intent,
                "WhatsApp",
                "Opening WhatsApp chat with ${to.name} to delete the message.",
            ) {
                WhatsAppWorkflow.deleteMessage(
                    service = service,
                    recipient = to.name,
                    recipientPhoneDigits = digits,
                    which = which,
                    messageText = messageText,
                    scope = scope,
                    alreadyConfirmed = confirmed,
                )
            }
        }
    }

    private object WhatsAppCall : PhoneTool {
        override val name = "whatsapp_call"

        override fun declaration() = Schema.function(
            name,
            "Starts an audio or video WhatsApp call with a resolved contact. Never triggers cellular phone calling; separates WhatsApp audio calls from WhatsApp video calls.",
            *who,
            Schema.string("call_type", "audio (default) or video.", choices = listOf("audio", "video")),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val video = args.text("call_type").equals("video", ignoreCase = true)
            return executeCall(args, host, isVideo = video)
        }

        suspend fun executeCall(args: JSONObject, host: ToolHost, isVideo: Boolean): JSONObject {
            if (!AppSettings(host.context).isAutomationEnabled(AutomationCategory.Calling)) {
                return Results.failed("automation_disabled", "Calling automation is turned off in Settings → Permissions.")
            }
            val waPkg = WhatsAppWorkflow.resolveInstalledPackage(host.context)
                ?: return Results.failed("whatsapp_missing", "WhatsApp is not installed on this phone.")
            val effectiveArgs = injectRecipientFromContextIfMissing(args)
            val to = when (val r = Recipients.resolve(effectiveArgs, host, "call on WhatsApp")) {
                is Recipients.Result.Found -> r
                is Recipients.Result.Answer -> return r.json
            }
            val digits = internationalDigits(host.context, to.number)
                ?: return Results.failed("bad_number", "That number doesn't look complete.")
            val kind = if (isVideo) WhatsAppCallMatcher.CallKind.Video else WhatsAppCallMatcher.CallKind.Audio
            val kindText = if (isVideo) "WhatsApp video call" else "WhatsApp call"

            ActiveTaskContext.beginTask(
                task = kindText,
                application = "WhatsApp",
                appPackage = waPkg,
                recipient = to.name,
                executionStep = "Open chat for $kindText",
            )
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$digits")).setPackage(waPkg)
            val service = ControlService.instance?.takeIf { !it.overlay.stopped }
            if (service == null) {
                return host.openApp(
                    intent,
                    "WhatsApp",
                    "Opening WhatsApp chat with ${to.name}; Phone control is off so the user taps the $kindText button themselves.",
                )
            }
            return host.openApp(
                intent,
                "WhatsApp",
                "Opening WhatsApp chat with ${to.name} to start a $kindText.",
            ) {
                WhatsAppWorkflow.initiateCallAfterOpen(
                    service = service,
                    recipient = to.name,
                    kind = kind,
                    recipientPhoneDigits = digits,
                    skipInternalConfirm = true,
                )
            }
        }
    }

    private object SmsSend : PhoneTool {
        override val name = "sms_send"

        override fun declaration() = Schema.function(
            name,
            "Opens the Messages app with an SMS typed in; the user taps Send.",
            *who,
            Schema.string("message", "The message, in the user's words and language.", required = true),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val message = args.text("message")
            if (message.isEmpty()) return Results.failed("no_message", "Ask what the message should say.")
            val effectiveArgs = injectRecipientFromContextIfMissing(args)
            handsFree(effectiveArgs, SMS_APPS)?.let { return it }
            val to = when (val r = Recipients.resolve(effectiveArgs, host, "message")) {
                is Recipients.Result.Found -> r
                is Recipients.Result.Answer -> return r.json
            }
            val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", to.number, null))
                .putExtra("sms_body", message)
            return host.openApp(intent, "Messages", "Messages will open with the SMS to ${to.name} after you speak; the user taps Send.")
        }
    }

    /** Multi-step continuity ("Ab use hi bhejo"): fill missing recipient ONLY from active WhatsApp/comms task context. */
    private fun injectRecipientFromContextIfMissing(args: JSONObject): JSONObject {
        if (args.text("name").isNotEmpty() || args.text("number").isNotEmpty()) return args
        val ctx = ActiveTaskContext.snapshot()
        if (!ctx.isActive || ctx.currentRecipient.isBlank()) return args
        if (ctx.currentApplication.equals("WhatsApp", ignoreCase = true) || ctx.currentTask.contains("WhatsApp", ignoreCase = true) || ctx.currentTask.contains("Call", ignoreCase = true)) {
            return JSONObject(args.toString()).put("name", ctx.currentRecipient)
        }
        return args
    }

    /** E.164 digits without "+", using the SIM/network country or Locale for local numbers. */
    private fun internationalDigits(context: Context, number: String): String? {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val region = listOfNotNull(
            telephony?.simCountryIso?.takeIf { it.isNotBlank() },
            telephony?.networkCountryIso?.takeIf { it.isNotBlank() },
            Locale.getDefault().country.takeIf { it.isNotBlank() },
        ).firstOrNull()?.uppercase().orEmpty()
        val e164 = if (region.isNotEmpty()) {
            PhoneNumberUtils.formatNumberToE164(number, region)
        } else {
            null
        } ?: number.takeIf { it.trim().startsWith("+") }
        val digits = (e164 ?: number).filter(Char::isDigit)
        return digits.takeIf { it.length >= 10 || (e164 != null && it.length >= 8) }
    }

    private const val CALL_VERIFY_WAIT_MS = 1_400L
}

/**
 * If [args]' person just messaged in one of [packages] and the notification has a Reply box,
 * the answer goes through it hands-free (Replies) instead of opening the app.
 */
private fun handsFree(args: JSONObject, packages: Set<String>): JSONObject? {
    val name = args.text("name")
    if (name.isEmpty()) return null
    val id = Replies.idFor(name, packages, System.currentTimeMillis()) ?: return null
    return Results.failed(
        "use_reply",
        "$name just messaged and can be answered without opening the app: call reply_prepare with reply_id $id " +
            "and this message, read its line back and wait for the user's yes.",
    )
}

private val WHATSAPP = setOf("com.whatsapp", "com.whatsapp.w4b")
private val SMS_APPS = setOf("com.google.android.apps.messaging", "com.samsung.android.messaging")

object Recipients {
    sealed interface Result {
        data class Found(val name: String, val number: String) : Result
        data class Answer(val json: JSONObject) : Result
    }

    fun resolve(args: JSONObject, host: ToolHost, verb: String): Result {
        val spoken = args.text("number")
        if (spoken.count(Char::isDigit) >= 6) return Result.Found(args.text("name").ifEmpty { spoken }, spoken)
        val name = args.text("name")
        if (name.isEmpty()) return Result.Answer(Results.failed("no_recipient", "Ask who to $verb."))
        if (!host.hasPermission(ContactsReader.PERMISSION)) {
            if (host.isPermissionDeniedInSession(ContactsReader.PERMISSION)) {
                return Result.Answer(
                    Results.failed(
                        "permission_denied",
                        "Contacts permission was denied. Ask the user to enable Contacts in Settings or say the phone number.",
                    ),
                )
            }
            host.requestPermission(ContactsReader.PERMISSION)
            return Result.Answer(Results.needsPermission("Contacts"))
        }
        val matches = ContactsReader(host.context).search(name)
        return when {
            matches.isEmpty() -> Result.Answer(
                Results.failed("not_found", "No contact called $name. Ask the user to spell it or say the number."),
            )
            matches.size > 1 -> Result.Answer(
                Results.ask("Several contacts match \"$name\". Ask which one.", matches.take(6).map { it.name }),
            )
            else -> pick(matches.single(), args.text("which"))
        }
    }

    /** One contact: its only number, the one [which] names, or a question. */
    fun pick(contact: Contact, which: String): Result {
        val numbers = contact.numbers
        if (numbers.isEmpty()) return Result.Answer(Results.failed("no_number", "${contact.name} has no phone number saved."))
        if (numbers.size == 1) return Result.Found(contact.name, numbers.single().number)
        val w = which.lowercase().trim()
        val chosen = if (w.isEmpty()) {
            emptyList()
        } else {
            numbers.filter { w in it.label || (w.any(Char::isDigit) && it.number.filter(Char::isDigit).endsWith(w.filter(Char::isDigit))) }
        }
        if (chosen.size == 1) return Result.Found(contact.name, chosen.single().number)
        return Result.Answer(
            Results.ask(
                "${contact.name} has several numbers. Ask which one.",
                numbers.map { "${it.label} ending ${it.number.filter(Char::isDigit).takeLast(4)}" },
            ),
        )
    }
}
