package app.shilex.android.audio

import android.content.Context
import android.media.AudioManager
import app.shilex.android.Diag
import kotlin.math.roundToInt

/** Supported Android audio streams with explicit user-friendly names. */
enum class AudioStream(
    val key: String,
    val label: String,
    val streamType: Int,
) {
    Media("media", "Media", AudioManager.STREAM_MUSIC),
    Ring("ring", "Ringtone", AudioManager.STREAM_RING),
    Notification("notification", "Notification", AudioManager.STREAM_NOTIFICATION),
    Alarm("alarm", "Alarm", AudioManager.STREAM_ALARM),
    Call("call", "Call", AudioManager.STREAM_VOICE_CALL),
    ;

    companion object {
        fun fromKey(raw: String?): AudioStream = when (raw?.trim()?.lowercase()) {
            "ring", "ringtone", "ringer" -> Ring
            "notification", "notif", "notifications" -> Notification
            "alarm", "alarms" -> Alarm
            "call", "voice", "voice_call" -> Call
            else -> Media
        }
    }
}

sealed interface VolumeOutcome {
    val message: String

    data class Changed(
        val stream: AudioStream,
        val actualPercent: Int,
        val index: Int,
        val minIndex: Int,
        val maxIndex: Int,
        override val message: String = "${stream.label} volume at $actualPercent%.",
    ) : VolumeOutcome

    data class Blocked(
        val stream: AudioStream,
        val currentPercent: Int,
        val reason: String,
    ) : VolumeOutcome {
        override val message: String get() = reason
    }
}

/**
 * Pure percentage/index conversion and real [AudioManager] verification so JARVIS never
 * claims volume changed when Android ignored or clamped the call.
 */
class VolumeController(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)

    fun currentPercent(stream: AudioStream = AudioStream.Media): Int {
        val min = minVolume(stream)
        val max = maxVolume(stream)
        val now = audio.getStreamVolume(stream.streamType)
        return indexToPercent(now, min, max)
    }

    fun setPercent(
        requestedPercent: Int,
        stream: AudioStream = AudioStream.Media,
        showUi: Boolean = true,
    ): VolumeOutcome {
        if (audio.isVolumeFixed) {
            return VolumeOutcome.Blocked(
                stream,
                currentPercent(stream),
                "This device uses fixed volume, so ${stream.label.lowercase()} volume cannot be changed in software.",
            )
        }
        val clamped = clampPercent(requestedPercent)
        val min = minVolume(stream)
        val max = maxVolume(stream)
        val targetIndex = percentToIndex(clamped, min, max)
        val before = audio.getStreamVolume(stream.streamType)
        val flags = if (showUi) AudioManager.FLAG_SHOW_UI else 0

        val setError = runCatching {
            audio.setStreamVolume(stream.streamType, targetIndex, flags)
        }.exceptionOrNull()

        if (setError is SecurityException) {
            return VolumeOutcome.Blocked(
                stream,
                currentPercent(stream),
                "Android Do Not Disturb blocked changing ${stream.label.lowercase()} volume. Adjust Do Not Disturb settings or change it manually.",
            )
        }
        if (setError != null) {
            Diag.w("volume: setStreamVolume(${stream.key}) failed (${setError.javaClass.simpleName})")
            return VolumeOutcome.Blocked(
                stream,
                currentPercent(stream),
                "Android didn't allow changing ${stream.label.lowercase()} volume.",
            )
        }

        val after = audio.getStreamVolume(stream.streamType)
        val actualPct = indexToPercent(after, min, max)
        if (after == before && targetIndex != before) {
            return VolumeOutcome.Blocked(
                stream,
                actualPct,
                "${stream.label} volume remained at $actualPct% because the system or connected audio device restricted the change.",
            )
        }
        return VolumeOutcome.Changed(stream, actualPct, after, min, max, "${stream.label} volume set to $actualPct%.")
    }

    fun step(
        up: Boolean,
        stream: AudioStream = AudioStream.Media,
        showUi: Boolean = true,
    ): VolumeOutcome {
        if (audio.isVolumeFixed) {
            return VolumeOutcome.Blocked(
                stream,
                currentPercent(stream),
                "This device uses fixed volume, so ${stream.label.lowercase()} volume cannot be changed in software.",
            )
        }
        val min = minVolume(stream)
        val max = maxVolume(stream)
        val before = audio.getStreamVolume(stream.streamType)
        val direction = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        val uiFlags = if (showUi) AudioManager.FLAG_SHOW_UI else 0
        val err = runCatching {
            repeat(STEP_TICKS) {
                audio.adjustStreamVolume(stream.streamType, direction, 0)
            }
            audio.adjustStreamVolume(stream.streamType, AudioManager.ADJUST_SAME, uiFlags)
        }.exceptionOrNull()

        if (err is SecurityException) {
            return VolumeOutcome.Blocked(
                stream,
                currentPercent(stream),
                "Android Do Not Disturb blocked changing ${stream.label.lowercase()} volume.",
            )
        }
        val after = audio.getStreamVolume(stream.streamType)
        val actualPct = indexToPercent(after, min, max)
        val atBoundary = (up && before >= max) || (!up && before <= min)
        if (after == before && !atBoundary) {
            return VolumeOutcome.Blocked(
                stream,
                actualPct,
                "${stream.label} volume stayed at $actualPct% because Android restricted the change.",
            )
        }
        return VolumeOutcome.Changed(stream, actualPct, after, min, max, "${stream.label} volume is now at $actualPct%.")
    }

    fun setMute(on: Boolean, mediaOnly: Boolean = true, showUi: Boolean = true): VolumeOutcome {
        val mediaOutcome = muteMedia(on, showUi)
        if (mediaOnly || mediaOutcome is VolumeOutcome.Blocked) return mediaOutcome
        val dir = if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        val ringerErr = runCatching {
            audio.adjustStreamVolume(AudioManager.STREAM_RING, dir, 0)
        }.exceptionOrNull()
        val msg = if (ringerErr != null) {
            val stateWord = if (on) "Muted" else "Unmuted"
            "$stateWord media; ringer was left untouched because Android or Do Not Disturb restricted it."
        } else {
            if (on) "Muted media and ringtone." else "Unmuted media and ringtone."
        }
        return (mediaOutcome as VolumeOutcome.Changed).copy(message = msg)
    }

    fun muteMedia(on: Boolean, showUi: Boolean = true): VolumeOutcome {
        if (audio.isVolumeFixed) {
            return VolumeOutcome.Blocked(
                AudioStream.Media,
                currentPercent(AudioStream.Media),
                "Media mute isn't available on fixed-volume devices.",
            )
        }
        val dir = if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        val uiFlags = if (showUi) AudioManager.FLAG_SHOW_UI else 0
        runCatching {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, uiFlags)
        }.onFailure {
            return VolumeOutcome.Blocked(AudioStream.Media, currentPercent(AudioStream.Media), "Android didn't allow changing media mute.")
        }
        val min = minVolume(AudioStream.Media)
        val max = maxVolume(AudioStream.Media)
        val after = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val pct = indexToPercent(after, min, max)
        val label = if (on) "Muted media." else "Unmuted media ($pct%)."
        return VolumeOutcome.Changed(AudioStream.Media, pct, after, min, max, label)
    }

    private fun minVolume(stream: AudioStream): Int =
        runCatching { audio.getStreamMinVolume(stream.streamType) }.getOrDefault(0).coerceAtLeast(0)

    private fun maxVolume(stream: AudioStream): Int =
        audio.getStreamMaxVolume(stream.streamType).coerceAtLeast(1)

    companion object {
        private const val STEP_TICKS = 2

        fun clampPercent(percent: Int): Int = percent.coerceIn(0, 100)

        /** Converts 0..100 into a valid stream index inside [minIndex]..[maxIndex]. */
        fun percentToIndex(percent: Int, minIndex: Int, maxIndex: Int): Int {
            val low = minIndex.coerceAtLeast(0)
            val high = maxIndex.coerceAtLeast(low)
            if (high == low) return low
            val p = clampPercent(percent)
            return (low + (p / 100f) * (high - low)).roundToInt().coerceIn(low, high)
        }

        /** Converts a device stream index inside [minIndex]..[maxIndex] back into rounded 0..100%. */
        fun indexToPercent(index: Int, minIndex: Int, maxIndex: Int): Int {
            val low = minIndex.coerceAtLeast(0)
            val high = maxIndex.coerceAtLeast(low)
            if (high <= low) return 100
            val clamped = index.coerceIn(low, high)
            return ((clamped - low) * 100f / (high - low)).roundToInt().coerceIn(0, 100)
        }
    }
}
