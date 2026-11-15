package app.shilex.android.local

import android.accessibilityservice.AccessibilityService
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import app.shilex.android.Diag
import app.shilex.android.audio.AudioStream
import app.shilex.android.audio.VolumeController
import app.shilex.android.control.ControlService
import app.shilex.android.tools.AppCatalog
import app.shilex.android.tools.InstalledAppsRegistry
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** What a local command did: a short reply, or "let Gemini handle it" ([handOff]). */
data class LocalResult(val reply: String, val handOff: Boolean = false)

/** Carries out [LocalCommand]s on the phone using real system APIs and verified readback. */
class LocalActions(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val volume = VolumeController(context)

    fun run(command: LocalCommand): LocalResult = when (command) {
        is LocalCommand.SetVolume -> setVolume(command.stream, command.percent)
        is LocalCommand.StepVolume -> stepVolume(command.stream, command.up)
        is LocalCommand.Mute -> mute(command.on, command.mediaOnly)
        is LocalCommand.Media -> media(command.key)
        is LocalCommand.Torch -> torch(command.on)
        LocalCommand.Battery -> battery()
        LocalCommand.Time -> LocalResult("It's ${SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date())}.")
        LocalCommand.Date -> LocalResult("Today is ${SimpleDateFormat("EEEE, d MMMM", Locale.ENGLISH).format(Date())}.")
        is LocalCommand.Timer -> clock(
            Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, command.seconds),
            "Timer set for ${describe(command.seconds)}.",
        )
        is LocalCommand.Alarm -> alarm(command)
        is LocalCommand.OpenApp -> openApp(command.name)
        is LocalCommand.SystemPanel -> openSystemPanel(command.target)
        LocalCommand.ListApps -> {
            val apps = InstalledAppsRegistry.apps(context, forceRefresh = true)
            LocalResult(AppCatalog.summarizeAlphabetical(apps, limit = 15, includeSystem = false))
        }
        is LocalCommand.SearchApps -> searchApps(command.query)
        LocalCommand.Stop -> LocalResult("Okay.")
        LocalCommand.Shutdown -> LocalResult("Band ho raha hoon.")
    }

    private fun setVolume(stream: AudioStream, percent: Int): LocalResult {
        val outcome = volume.setPercent(requestedPercent = percent, stream = stream, showUi = true)
        return LocalResult(outcome.message)
    }

    private fun stepVolume(stream: AudioStream, up: Boolean): LocalResult {
        val outcome = volume.step(up = up, stream = stream, showUi = true)
        return LocalResult(outcome.message)
    }

    private fun mute(on: Boolean, mediaOnly: Boolean): LocalResult {
        val outcome = volume.setMute(on = on, mediaOnly = mediaOnly)
        return LocalResult(outcome.message)
    }

    private fun media(key: MediaKey): LocalResult {
        val code = when (key) {
            MediaKey.Play -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaKey.Pause -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaKey.Next -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaKey.Previous -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return LocalResult("") // the music itself is the answer
    }

    private fun torch(on: Boolean): LocalResult {
        val camera = context.getSystemService(CameraManager::class.java)
        val id = camera.cameraIdList.firstOrNull {
            camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return LocalResult("This phone has no flashlight.")
        return runCatching { camera.setTorchMode(id, on) }
            .map { LocalResult(if (on) "Flashlight on." else "Flashlight off.") }
            .getOrElse { LocalResult("The camera is busy, so the flashlight can't change.") }
    }

    private fun battery(): LocalResult {
        val battery = context.getSystemService(BatteryManager::class.java)
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return LocalResult("Battery is at $level percent" + if (battery.isCharging) ", charging." else ".")
    }

    private fun alarm(command: LocalCommand.Alarm): LocalResult {
        val hour = resolveHour(command, Calendar.getInstance())
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, command.minute)
        val shown = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, command.minute)
        }
        return clock(intent, "Alarm set for ${SimpleDateFormat("h:mm a", Locale.ENGLISH).format(shown.time)}.")
    }

    private fun clock(intent: Intent, done: String): LocalResult {
        if (!canOpenApps()) return LocalResult("", handOff = true)
        return if (start(intent.putExtra(AlarmClock.EXTRA_SKIP_UI, true))) LocalResult(done) else LocalResult("The Clock app didn't accept it.")
    }

    private fun openApp(name: String): LocalResult {
        val pm = context.packageManager
        val apps = InstalledAppsRegistry.apps(context, forceRefresh = true)
        val matches = AppCatalog.find(name, apps, includeNonLaunchable = false)
        if (matches.size != 1 || !canOpenApps()) {
            return if (matches.isEmpty()) LocalResult("I couldn't find an installed app called $name.") else LocalResult("", handOff = true)
        }
        val app = matches.single()
        val intent = pm.getLaunchIntentForPackage(app.packageName) ?: return LocalResult("${app.label} can't be opened from outside.")
        return if (start(intent)) LocalResult("") else LocalResult("${app.label} didn't open.")
    }

    private fun searchApps(query: String): LocalResult {
        val apps = InstalledAppsRegistry.apps(context, forceRefresh = true)
        val hits = AppCatalog.search(query, apps, includeNonLaunchable = true)
        return if (hits.isEmpty()) {
            LocalResult("No, \"$query\" doesn't appear to be installed on this phone.")
        } else {
            val names = hits.take(4).joinToString(", ") { it.label }
            LocalResult("Yes, found: $names.")
        }
    }

    private fun openSystemPanel(target: SystemPanelTarget): LocalResult {
        val control = ControlService.instance
        return when (target) {
            SystemPanelTarget.Notifications -> {
                if (control != null && control.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)) {
                    LocalResult("Opened the notification panel.")
                } else {
                    LocalResult("Turn on Phone control in Settings so JARVIS can pull down the notification panel.", handOff = true)
                }
            }
            SystemPanelTarget.QuickSettings -> {
                if (control != null && control.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)) {
                    LocalResult("Opened Quick Settings.")
                } else {
                    LocalResult("Turn on Phone control in Settings so JARVIS can open Quick Settings.", handOff = true)
                }
            }
            SystemPanelTarget.Wifi -> {
                val wifiEnabled = runCatching { context.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled }.getOrNull()
                val stateText = when (wifiEnabled) {
                    true -> "Wi-Fi is currently on."
                    false -> "Wi-Fi is currently off."
                    null -> ""
                }
                val panelIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Intent(Settings.Panel.ACTION_WIFI)
                } else {
                    Intent(Settings.ACTION_WIFI_SETTINGS)
                }
                val opened = start(panelIntent) || start(Intent(Settings.ACTION_WIFI_SETTINGS))
                if (opened) LocalResult("Opened Wi-Fi settings. $stateText".trim())
                else LocalResult("Couldn't open Wi-Fi settings right now. $stateText".trim(), handOff = true)
            }
            SystemPanelTarget.Hotspot -> {
                val tetheringIntent = Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")
                val opened = start(tetheringIntent) || start(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                if (opened) {
                    LocalResult("Opened Hotspot and tethering settings so you can toggle it directly; Android doesn't allow apps to switch Hotspot silently.")
                } else {
                    LocalResult("Couldn't open Hotspot settings directly; open Wireless settings in Android Settings.", handOff = true)
                }
            }
            SystemPanelTarget.Bluetooth -> {
                val btEnabled = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled }.getOrNull()
                val stateText = when (btEnabled) {
                    true -> "Bluetooth is currently on."
                    false -> "Bluetooth is currently off."
                    null -> ""
                }
                if (start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))) {
                    LocalResult("Opened Bluetooth settings. $stateText".trim())
                } else {
                    LocalResult("Couldn't open Bluetooth settings. $stateText".trim(), handOff = true)
                }
            }
            SystemPanelTarget.Brightness -> {
                if (start(Intent(Settings.ACTION_DISPLAY_SETTINGS))) {
                    LocalResult("Opened Display & Brightness settings.")
                } else {
                    LocalResult("Couldn't open Display settings.", handOff = true)
                }
            }
            SystemPanelTarget.Battery -> {
                val level = runCatching {
                    context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                }.getOrNull()
                val levelNote = level?.let { "Battery is at $it percent." }.orEmpty()
                if (start(Intent(Intent.ACTION_POWER_USAGE_SUMMARY)) || start(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))) {
                    LocalResult("Opened Battery settings. $levelNote".trim())
                } else {
                    LocalResult("Couldn't open Battery settings. $levelNote".trim(), handOff = true)
                }
            }
        }
    }

    // Android lets apps with a running Accessibility service open other apps from the background.
    private fun canOpenApps() = ControlService.instance != null || Settings.canDrawOverlays(context)

    private fun start(intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Diag.w("local: couldn't start ${intent.action} (${it.javaClass.simpleName})") }.isSuccess

    companion object {
        /** 24-hour clock hour for an alarm; with no am/pm, the next time that hour comes round. */
        fun resolveHour(command: LocalCommand.Alarm, now: Calendar): Int {
            val h = command.hour
            return when (command.period) {
                Period.Am -> if (h == 12) 0 else h
                Period.Pm -> if (h == 12) 12 else h + 12
                null -> if (h > 12 || h == 0) {
                    h
                } else {
                    val minutesNow = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                    val morning = (h % 12) * 60 + command.minute
                    if (morning > minutesNow || morning + 12 * 60 <= minutesNow) h % 12 else h % 12 + 12
                }
            }
        }

        fun describe(seconds: Int): String {
            val h = seconds / 3600
            val m = seconds % 3600 / 60
            val s = seconds % 60
            return listOfNotNull(
                h.takeIf { it > 0 }?.let { "$it hour" + if (it > 1) "s" else "" },
                m.takeIf { it > 0 }?.let { "$it minute" + if (it > 1) "s" else "" },
                s.takeIf { it > 0 }?.let { "$it second" + if (it > 1) "s" else "" },
            ).joinToString(" ")
        }
    }
}
