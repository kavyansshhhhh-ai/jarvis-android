package app.shilex.android.audio

import android.os.SystemClock

/**
 * Shared state tracking whether JARVIS itself is speaking (Gemini AudioTrack [Speaker] or local [TextToSpeech]),
 * plus a short acoustic tail window so wake detection never self-triggers on its own spoken "Jarvis".
 */
object AudioPlaybackState {
    @Volatile private var localTtsActive = false
    @Volatile private var liveSpeakerActive = false
    @Volatile private var lastEndedAtMs = 0L

    const val TTS_ECHO_TAIL_MS = 1_100L

    fun onLocalTtsStarted() {
        localTtsActive = true
    }

    fun onLocalTtsEnded(nowMs: Long = SystemClock.elapsedRealtime()) {
        localTtsActive = false
        lastEndedAtMs = nowMs
    }

    fun onLiveSpeakerState(active: Boolean, nowMs: Long = SystemClock.elapsedRealtime()) {
        val was = liveSpeakerActive
        liveSpeakerActive = active
        if (was && !active) lastEndedAtMs = nowMs
    }

    fun isSpeakingOrTail(nowMs: Long = SystemClock.elapsedRealtime()): Boolean =
        localTtsActive ||
            liveSpeakerActive ||
            (lastEndedAtMs > 0L && nowMs - lastEndedAtMs < TTS_ECHO_TAIL_MS)

    fun resetForTest() {
        localTtsActive = false
        liveSpeakerActive = false
        lastEndedAtMs = 0L
    }
}
