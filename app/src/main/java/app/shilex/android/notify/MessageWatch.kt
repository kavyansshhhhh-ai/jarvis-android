package app.shilex.android.notify

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import app.shilex.android.Diag
import app.shilex.android.assistant.VoiceSession
import app.shilex.android.audio.Speaker

/**
 * The "watch my messages" scene: for a while, JARVIS stays quiet and says out loud only when
 * a person needs the user ([WatchRules]). Runs on the phone via unified [Speaker] so selected
 * voice, rate/pitch, audio focus, and echo suppression apply; nothing goes to Gemini.
 */
object MessageWatch {
    @Volatile var untilMillis = 0L
        private set
    private var rules = WatchRules()
    private val main = Handler(Looper.getMainLooper())

    val watching: Boolean get() = System.currentTimeMillis() < untilMillis

    fun start(minutes: Int) {
        untilMillis = System.currentTimeMillis() + minutes * 60_000L
        rules = WatchRules()
        Diag.i("watch: on for $minutes min")
    }

    fun stop() {
        untilMillis = 0L
        Diag.i("watch: off")
    }

    /** Called by [NotificationWatch] for every new notification. */
    fun posted(context: Context, n: Seen) {
        if (!watching || n.packageName == context.packageName) return
        val audio = context.getSystemService(AudioManager::class.java)
        val silent = audio.ringerMode != AudioManager.RINGER_MODE_NORMAL
        val line = rules.decide(n, System.currentTimeMillis(), silent, VoiceSession.get(context).active) ?: return
        Diag.i("watch: speaking up")
        main.post {
            Speaker.get(context.applicationContext).speak(line, queueMode = TextToSpeech.QUEUE_ADD)
        }
    }
}
