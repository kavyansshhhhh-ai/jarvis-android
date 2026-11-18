package app.shilex.android.tools

import app.shilex.android.audio.AudioStream
import app.shilex.android.audio.VolumeController
import app.shilex.android.audio.VolumeOutcome
import app.shilex.android.local.LocalActions
import app.shilex.android.local.LocalCommand
import app.shilex.android.local.SystemPanelTarget
import org.json.JSONObject

/**
 * Phone hardware & system controls: multi-stream volume (Media, Ring, Notification, Alarm, Call),
 * mute/unmute media, flashlight, battery, plus Quick Settings, Notification shade, and Android settings panels.
 */
object DeviceTools {
    fun all(): List<PhoneTool> = listOf(DeviceControl, OpenSystemPanel, ShutdownAssistant)

    private val ACTIONS = listOf(
        "volume_set", "volume_up", "volume_down", "mute", "mute_media", "unmute", "unmute_media", "torch_on", "torch_off", "battery",
    )

    private val STREAMS = listOf("media", "ring", "notification", "alarm", "call")

    private object DeviceControl : PhoneTool {
        override val name = "device_control"

        override fun declaration() = Schema.function(
            name,
            "Phone controls: real verified volume across Media, Ring, Notification, Alarm, or Call streams (set percent, up, down, mute_media, mute/unmute), " +
                "flashlight on/off, and battery level and charging.",
            Schema.string("action", "What to do.", required = true, choices = ACTIONS),
            Schema.integer("percent", "For volume_set: 0 to 100."),
            Schema.string("stream", "Optional audio stream (default media).", choices = STREAMS),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val stream = AudioStream.fromKey(args.text("stream"))
            val command = command(args.text("action"), args.optInt("percent", -1), stream)
                ?: return Results.failed("bad_action", "Ask what to change: volume (0-100%), mute media, flashlight or battery.")
            val volumeOutcome: VolumeOutcome? = when (command) {
                is LocalCommand.SetVolume -> VolumeController(host.context).setPercent(command.percent, command.stream, showUi = true)
                is LocalCommand.StepVolume -> VolumeController(host.context).step(command.up, command.stream, showUi = true)
                is LocalCommand.Mute -> VolumeController(host.context).setMute(command.on, command.mediaOnly, showUi = true)
                else -> null
            }
            if (volumeOutcome != null) {
                return when (volumeOutcome) {
                    is VolumeOutcome.Changed -> Results.done(volumeOutcome.message)
                    is VolumeOutcome.Blocked -> Results.failed("volume_blocked", volumeOutcome.reason)
                }
            }
            val result = LocalActions(host.context).run(command)
            return Results.done(result.reply.ifEmpty { "Done." })
        }
    }

    private object OpenSystemPanel : PhoneTool {
        override val name = "open_system_panel"

        override fun declaration() = Schema.function(
            name,
            "Opens Android Notification shade, Quick Settings panel, Wi-Fi panel, Hotspot/Tethering settings, " +
                "Bluetooth settings, Display/Brightness settings, or Battery settings, and reports verified state honestly.",
            Schema.string(
                "panel",
                "Which panel or setting to open.",
                required = true,
                choices = listOf("notifications", "quick_settings", "wifi", "hotspot", "bluetooth", "brightness", "battery"),
            ),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val target = panelTarget(args.text("panel"))
                ?: return Results.failed("bad_panel", "Specify notifications, quick_settings, wifi, hotspot, bluetooth, brightness, or battery.")
            val result = LocalActions(host.context).run(LocalCommand.SystemPanel(target))
            return if (result.handOff) {
                Results.failed("panel_unavailable", result.reply.ifEmpty { "Turn on Phone control or open Android Settings directly." })
            } else {
                Results.done(result.reply.ifEmpty { "Opened ${target.label}." })
            }
        }
    }

    private object ShutdownAssistant : PhoneTool {
        override val name = "shutdown_assistant"

        override fun declaration() = Schema.function(
            name,
            "Completely stops JARVIS speaking, active voice listening, background wake-word standby, and overlays when the user says they don't need JARVIS or tells JARVIS to turn off (e.g. 'abhi band ho jao', 'khud ko band kar lo').",
            Schema.string("reason", "Short reason from the user's request."),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val reason = args.text("reason").ifEmpty { "user requested shutdown" }
            host.shutdownAssistant(reason)
            return Results.done("Shut down JARVIS voice listening and standby completely.")
        }
    }

    /** The tool's action -> the shared command; null when invalid. */
    fun command(action: String, percent: Int, stream: AudioStream = AudioStream.Media): LocalCommand? = when (action) {
        "volume_set" -> if (percent in 0..100) LocalCommand.SetVolume(percent, stream) else null
        "volume_up" -> LocalCommand.StepVolume(up = true, stream = stream)
        "volume_down" -> LocalCommand.StepVolume(up = false, stream = stream)
        "mute" -> LocalCommand.Mute(on = true, mediaOnly = false)
        "mute_media" -> LocalCommand.Mute(on = true, mediaOnly = true)
        "unmute" -> LocalCommand.Mute(on = false, mediaOnly = false)
        "unmute_media" -> LocalCommand.Mute(on = false, mediaOnly = true)
        "torch_on" -> LocalCommand.Torch(true)
        "torch_off" -> LocalCommand.Torch(false)
        "battery" -> LocalCommand.Battery
        else -> null
    }

    fun panelTarget(raw: String): SystemPanelTarget? = when (raw.lowercase().trim()) {
        "notifications", "notification", "shade" -> SystemPanelTarget.Notifications
        "quick_settings", "quicksettings", "tiles" -> SystemPanelTarget.QuickSettings
        "wifi", "wi-fi" -> SystemPanelTarget.Wifi
        "hotspot", "tethering" -> SystemPanelTarget.Hotspot
        "bluetooth", "bt" -> SystemPanelTarget.Bluetooth
        "brightness", "display" -> SystemPanelTarget.Brightness
        "battery", "power" -> SystemPanelTarget.Battery
        else -> null
    }
}
