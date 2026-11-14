package app.shilex.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import app.shilex.android.Diag
import app.shilex.android.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Unified Audio Output & Speech Engine for JARVIS.
 * Handles both streaming raw PCM chunks (Gemini Live AudioTrack) and native
 * Android TextToSpeech synthesis with full male/female voice selection,
 * volume & audio focus management, and echo cancellation integration.
 */
class Speaker(private val context: Context? = null) {
    private val appContext = context?.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    // --- PCM AudioTrack Engine (Gemini Live & Local PCM) ---
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "speaker") }
    @Volatile private var track: AudioTrack? = null
    @Volatile private var trackRate = 0

    // A 16 kHz copy of everything written to the track, for the echo canceller (TalkOver).
    private val ring = ShortArray(RING)
    private var ringWritten = 0L // samples since the track was created or flushed
    private var resamplePos = 0.0

    // Bumped by flush(); queued writes from before it are dropped.
    @Volatile private var generation = 0
    @Volatile private var audibleUntil = 0L

    // A reply runs from its first chunk to endReply() or flush().
    @Volatile private var replyOpen = false
    @Volatile private var gaps = 0
    private var underrunsSeen = 0 // speaker thread only

    // --- TextToSpeech Engine ---
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var ttsInitInProgress = false

    private val _availableVoices = MutableStateFlow<List<TtsVoiceInfo>>(emptyList())
    val availableVoices: StateFlow<List<TtsVoiceInfo>> = _availableVoices.asStateFlow()

    private val _ttsError = MutableStateFlow<String?>(null)
    val ttsError: StateFlow<String?> = _ttsError.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _previewingVoiceId = MutableStateFlow<String?>(null)
    val previewingVoiceId: StateFlow<String?> = _previewingVoiceId.asStateFlow()

    private data class QueuedUtterance(
        val text: String,
        val queueMode: Int,
        val utteranceId: String,
        val onComplete: (() -> Unit)?,
    )

    private val speechQueue = mutableListOf<QueuedUtterance>()
    private val initCallbacks = mutableListOf<(Boolean) -> Unit>()
    private val utteranceCallbacks = ConcurrentHashMap<String, () -> Unit>()

    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { /* transient focus change */ }

    // Duplicate utterance guard
    private var lastSpokenText: String = ""
    private var lastSpokenTimeMs: Long = 0L

    init {
        if (appContext != null) {
            initTts()
        }
    }

    // ==========================================
    // 1. TextToSpeech Engine Lifecycle & Control
    // ==========================================

    fun isTtsReady(): Boolean = ttsReady

    /** Initializes the TTS engine asynchronously if not already ready. */
    fun initTts(onReady: ((Boolean) -> Unit)? = null) {
        val ctx = appContext ?: run {
            onReady?.invoke(false)
            return
        }
        if (ttsReady) {
            onReady?.invoke(true)
            return
        }
        synchronized(initCallbacks) {
            if (onReady != null) initCallbacks += onReady
            if (ttsInitInProgress) return
            ttsInitInProgress = true
        }
        mainHandler.post {
            try {
                tts = TextToSpeech(ctx) { status ->
                    val callbacks = synchronized(initCallbacks) {
                        ttsInitInProgress = false
                        initCallbacks.toList().also { initCallbacks.clear() }
                    }
                    if (status == TextToSpeech.SUCCESS) {
                        ttsReady = true
                        _ttsError.value = null
                        configureTtsEngine()
                        drainSpeechQueue()
                        callbacks.forEach { it(true) }
                    } else {
                        ttsReady = false
                        _ttsError.value = "TTS engine initialization failed (status code: $status)"
                        drainSpeechQueueOnError()
                        callbacks.forEach { it(false) }
                    }
                }
            } catch (e: Exception) {
                val callbacks = synchronized(initCallbacks) {
                    ttsInitInProgress = false
                    initCallbacks.toList().also { initCallbacks.clear() }
                }
                ttsReady = false
                _ttsError.value = "Failed to start TextToSpeech: ${e.message}"
                drainSpeechQueueOnError()
                callbacks.forEach { it(false) }
            }
        }
    }

    private fun configureTtsEngine() {
        val engine = tts ?: return
        try {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                    AudioPlaybackState.onLocalTtsStarted()
                }

                override fun onDone(utteranceId: String?) {
                    handleUtteranceFinished(utteranceId)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    _ttsError.value = "Text-to-Speech could not play audio on this device."
                    handleUtteranceFinished(utteranceId)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Diag.w("tts: error $errorCode on utterance $utteranceId")
                    _ttsError.value = "Text-to-Speech playback error (code $errorCode). Check Android TTS settings."
                    handleUtteranceFinished(utteranceId)
                }
            })

            discoverVoices()
            applySavedVoiceAndSettings()
        } catch (e: Exception) {
            Diag.w("tts: error during configuration: ${e.message}")
        }
    }

    private fun handleUtteranceFinished(utteranceId: String?) {
        _isSpeaking.value = false
        if (utteranceId != null && utteranceId.startsWith("preview_")) {
            _previewingVoiceId.value = null
            // Restore chosen assistant voice after preview ends
            applySavedVoiceAndSettings()
        }
        AudioPlaybackState.onLocalTtsEnded()
        abandonAudioFocus()
        if (utteranceId != null) {
            utteranceCallbacks.remove(utteranceId)?.invoke()
        }
    }

    /**
     * Discovers genuine voices installed on the device and classifies them
     * as Female, Male, or Unspecified without guessing.
     */
    fun discoverVoices() {
        val engine = tts ?: return
        val rawVoices = try {
            engine.voices
        } catch (e: Exception) {
            null
        }

        if (rawVoices.isNullOrEmpty()) {
            _availableVoices.value = emptyList()
            return
        }

        val parsed = rawVoices
            .filter { VoiceClassifier.isSupportedLanguage(it.locale) }
            .map { v ->
                val gender = VoiceClassifier.classifyGender(v.name, v.features)
                val quality = when (v.quality) {
                    Voice.QUALITY_VERY_HIGH -> "Very High"
                    Voice.QUALITY_HIGH -> "High"
                    Voice.QUALITY_NORMAL -> "Standard"
                    else -> "Normal"
                }
                TtsVoiceInfo(
                    id = v.name,
                    displayName = VoiceClassifier.formatDisplayName(v.name, gender, v.locale),
                    gender = gender,
                    locale = v.locale,
                    isNetworkRequired = v.isNetworkConnectionRequired,
                    quality = quality,
                    isInstalled = !v.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED),
                )
            }
            // Sort: installed first, local before online, highest numeric quality, then name
            .sortedWith(
                compareByDescending<TtsVoiceInfo> { it.isInstalled }
                    .thenBy { it.isNetworkRequired }
                    .thenByDescending { qualityRank(it.quality) }
                    .thenBy { it.displayName },
            )

        _availableVoices.value = parsed
    }

    /**
     * Applies the saved voice ID, locale, speech rate, and pitch from Settings
     * directly to the active TTS engine.
     */
    fun applySavedVoiceAndSettings() {
        val ctx = appContext ?: return
        val engine = tts ?: return
        if (!ttsReady) return

        val settings = Settings(ctx)
        val targetVoiceId = settings.ttsVoiceId

        // 1. Voice profile selection (prefer installed profiles)
        val allVoices = engine.voices
        if (!allVoices.isNullOrEmpty()) {
            val installedVoices = allVoices.filter {
                !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }.ifEmpty { allVoices.toList() }

            val matching = if (targetVoiceId != null) {
                installedVoices.firstOrNull { it.name == targetVoiceId }
                    ?: allVoices.firstOrNull { it.name == targetVoiceId }
            } else {
                null
            }

            if (matching != null) {
                engine.voice = matching
            } else {
                // Fallback: prefer Indian English, then device default locale
                val defaultFallback = installedVoices.firstOrNull { it.locale.language == "en" && it.locale.country == "IN" }
                    ?: installedVoices.firstOrNull { it.locale == Locale.getDefault() }
                    ?: installedVoices.firstOrNull { it.locale.language == "en" }
                    ?: engine.defaultVoice
                defaultFallback?.let { engine.voice = it }
            }
        } else {
            // Set locale fallback if voices collection is unavailable
            val langResult = engine.setLanguage(Locale("en", "IN"))
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                val fallbackResult = engine.setLanguage(Locale.getDefault())
                if (fallbackResult == TextToSpeech.LANG_MISSING_DATA || fallbackResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    _ttsError.value = "Selected TTS language data is missing. Install English voice data in Android TTS Settings."
                }
            }
        }

        // 2. Speech rate and pitch
        engine.setSpeechRate(settings.ttsSpeechRate)
        engine.setPitch(settings.ttsPitch)
    }

    /**
     * Speaks the given [text] reply aloud using the active TTS voice.
     * Prevents duplicate utterances and ignores internal tool calls.
     */
    fun speak(
        text: String,
        queueMode: Int = TextToSpeech.QUEUE_FLUSH,
        onComplete: (() -> Unit)? = null,
    ) {
        val ctx = appContext
        if (ctx != null) {
            val settings = Settings(ctx)
            if (!settings.speakResponses) {
                onComplete?.invoke()
                return
            }
        }

        val cleaned = cleanSpokenText(text)
        if (cleaned.isBlank()) {
            onComplete?.invoke()
            return
        }

        // Deduplication guard: do not repeat the exact same sentence within 1.2 seconds
        val now = SystemClock.elapsedRealtime()
        if (cleaned == lastSpokenText && now - lastSpokenTimeMs < DUPLICATE_WINDOW_MS) {
            onComplete?.invoke()
            return
        }
        lastSpokenText = cleaned
        lastSpokenTimeMs = now

        val utteranceId = "reply_${UUID.randomUUID()}"
        if (onComplete != null) {
            utteranceCallbacks[utteranceId] = onComplete
        }

        if (!ttsReady) {
            synchronized(speechQueue) {
                if (queueMode == TextToSpeech.QUEUE_FLUSH) {
                    speechQueue.forEach { it.onComplete?.invoke() }
                    speechQueue.clear()
                }
                speechQueue += QueuedUtterance(cleaned, queueMode, utteranceId, onComplete)
            }
            initTts()
            return
        }

        val engine = tts
        if (engine == null) {
            _ttsError.value = "Text-to-Speech engine is unavailable on this device."
            utteranceCallbacks.remove(utteranceId)?.invoke()
            return
        }

        applySavedVoiceAndSettings()
        requestAudioFocus()
        _ttsError.value = null
        _isSpeaking.value = true
        AudioPlaybackState.onLocalTtsStarted()

        val result = engine.speak(cleaned, queueMode, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            Diag.w("tts: speak failed with code $result")
            _ttsError.value = "Text-to-Speech failed to speak (code $result)."
            _isSpeaking.value = false
            AudioPlaybackState.onLocalTtsEnded()
            abandonAudioFocus()
            utteranceCallbacks.remove(utteranceId)?.invoke()
        }
    }

    /**
     * Speaks a short preview sentence in the target voice profile without changing
     * the permanent selected assistant voice setting.
     */
    fun previewVoice(
        voiceId: String,
        sampleText: String? = null,
        onComplete: (() -> Unit)? = null,
    ) {
        val engine = tts
        if (!ttsReady || engine == null) {
            initTts { ok ->
                if (ok) previewVoice(voiceId, sampleText, onComplete) else onComplete?.invoke()
            }
            return
        }

        // Stop any active speech before starting preview
        stopTts()

        val targetVoice = engine.voices?.firstOrNull { it.name == voiceId }
        val previewLocale = targetVoice?.locale ?: Locale.getDefault()
        val textToSpeak = sampleText ?: VoiceClassifier.sampleTextForLocale(previewLocale)

        if (targetVoice != null) {
            engine.voice = targetVoice
        }

        val utteranceId = "preview_$voiceId"
        if (onComplete != null) {
            utteranceCallbacks[utteranceId] = onComplete
        }

        _ttsError.value = null
        _previewingVoiceId.value = voiceId
        _isSpeaking.value = true
        requestAudioFocus()
        AudioPlaybackState.onLocalTtsStarted()

        val res = engine.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (res != TextToSpeech.SUCCESS) {
            _ttsError.value = "Voice preview failed (code $res). Voice data may not be downloaded."
            _previewingVoiceId.value = null
            _isSpeaking.value = false
            AudioPlaybackState.onLocalTtsEnded()
            abandonAudioFocus()
            utteranceCallbacks.remove(utteranceId)?.invoke()
            applySavedVoiceAndSettings()
        }
    }

    fun stopPreview() {
        if (_previewingVoiceId.value != null) {
            stopTts()
            applySavedVoiceAndSettings()
        }
    }

    private fun drainSpeechQueue() {
        val queue = synchronized(speechQueue) {
            val copy = speechQueue.toList()
            speechQueue.clear()
            copy
        }
        for (item in queue) {
            speak(item.text, item.queueMode, item.onComplete)
        }
    }

    private fun drainSpeechQueueOnError() {
        val queue = synchronized(speechQueue) {
            val copy = speechQueue.toList()
            speechQueue.clear()
            copy
        }
        queue.forEach { it.onComplete?.invoke() }
    }

    private fun stopTts() {
        tts?.stop()
        synchronized(speechQueue) {
            speechQueue.forEach { it.onComplete?.invoke() }
            speechQueue.clear()
        }
        val pendingIds = utteranceCallbacks.keys.toList()
        pendingIds.forEach { id -> utteranceCallbacks.remove(id)?.invoke() }
        _isSpeaking.value = false
        _previewingVoiceId.value = null
        AudioPlaybackState.onLocalTtsEnded()
        abandonAudioFocus()
    }

    // ==========================================
    // 2. Audio Focus Management
    // ==========================================

    private fun requestAudioFocus(): Boolean {
        val ctx = appContext ?: return true
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build().also { audioFocusRequest = it }
            am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        val ctx = appContext ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(audioFocusListener)
        }
    }

    // ==========================================
    // 3. Streaming PCM Engine (AudioTrack)
    // ==========================================

    fun play(pcm: ByteArray, sampleRate: Int) {
        val gen = generation
        val first = !replyOpen
        replyOpen = true
        if (first) requestAudioFocus()
        val now = SystemClock.elapsedRealtime()
        val durationMs = pcm.size / 2 * 1000L / sampleRate
        audibleUntil = maxOf(audibleUntil, now) + durationMs + if (first) LEAD_IN_MS else 0
        AudioPlaybackState.onLiveSpeakerState(active = true, nowMs = now)
        writer.execute {
            if (gen != generation) return@execute
            val out = trackFor(sampleRate)
            if (first) {
                // A little silence first gives network hiccups room before the speaker runs dry.
                val lead = ByteArray((sampleRate * LEAD_IN_MS / 1000).toInt() * 2)
                keep(lead, sampleRate)
                out.write(lead, 0, lead.size)
            } else if (out.underrunCount > underrunsSeen) {
                gaps++
            }
            keep(pcm, sampleRate)
            out.write(pcm, 0, pcm.size)
            underrunsSeen = out.underrunCount
        }
    }

    /** The server finished this reply; the speaker running dry after it is expected. */
    fun endReply() {
        replyOpen = false
        AudioPlaybackState.onLiveSpeakerState(active = false)
        if (!_isSpeaking.value) abandonAudioFocus()
    }

    /** Times the speaker ran dry in the middle of a reply since the last call. */
    fun takeGaps(): Int = gaps.also { gaps = 0 }

    /** True while speech is (or is about to be) coming out of the speaker. */
    fun isAudible(tailMs: Long = 300): Boolean =
        SystemClock.elapsedRealtime() < audibleUntil + tailMs || _isSpeaking.value

    fun flush() {
        generation++
        audibleUntil = 0
        replyOpen = false
        AudioPlaybackState.onLiveSpeakerState(active = false)
        stopTts()
        writer.execute {
            track?.run {
                pause()
                flush()
                play()
            }
            clearRing() // the track's position starts again at 0
        }
    }

    fun release() {
        generation++
        audibleUntil = 0
        AudioPlaybackState.onLiveSpeakerState(active = false)
        stopTts()
        tts?.shutdown()
        tts = null
        ttsReady = false
        writer.execute {
            track?.release()
            track = null
            trackRate = 0
            clearRing()
        }
    }

    private fun trackFor(rate: Int): AudioTrack {
        track?.let { if (trackRate == rate) return it else it.release() }
        val minBuffer = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val created = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        created.play()
        clearRing()
        track = created
        trackRate = rate
        underrunsSeen = 0
        return created
    }

    /**
     * What the speaker played, at 16 kHz, ending [backSamples] before the sample playing now
     * (silence where nothing played). For the echo canceller; any thread.
     */
    fun reference(out: ShortArray, backSamples: Int) {
        val t = track
        val rate = trackRate
        if (t == null || rate == 0) {
            out.fill(0)
            return
        }
        val playing = (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) * REF_RATE / rate
        synchronized(ring) {
            val start = playing - backSamples - out.size
            for (i in out.indices) {
                val at = start + i
                out[i] = if (at >= 0 && at < ringWritten && at >= ringWritten - RING) ring[(at % RING).toInt()] else 0
            }
        }
    }

    /** Writer thread: appends [pcm] (16-bit, [rate] Hz) to the ring at 16 kHz. */
    private fun keep(pcm: ByteArray, rate: Int) {
        val n = pcm.size / 2
        if (n == 0) return
        val step = rate.toDouble() / REF_RATE
        synchronized(ring) {
            var pos = resamplePos
            while (pos < n - 1) {
                val i = pos.toInt()
                val a = sample(pcm, i)
                val b = sample(pcm, i + 1)
                ring[(ringWritten % RING).toInt()] = (a + (b - a) * (pos - i)).toInt().toShort()
                ringWritten++
                pos += step
            }
            resamplePos = (pos - n).coerceAtLeast(0.0)
        }
    }

    private fun clearRing() = synchronized(ring) {
        ringWritten = 0
        resamplePos = 0.0
    }

    private fun sample(pcm: ByteArray, i: Int): Int = (pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)

    private fun cleanSpokenText(text: String): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return ""
        if (trimmed.startsWith("[Note from the app") || trimmed.startsWith("[Note:")) return ""
        if (trimmed.contains("\"functionCall\"") || trimmed.contains("\"toolResponse\"")) return ""
        return trimmed
    }

    private fun qualityRank(quality: String): Int = when (quality) {
        "Very High" -> 4
        "High" -> 3
        "Standard" -> 2
        else -> 1
    }

    companion object {
        private const val REF_RATE = 16_000
        private const val RING = REF_RATE * 4 // 4 s
        private const val LEAD_IN_MS = 150L
        private const val DUPLICATE_WINDOW_MS = 1_200L

        @Volatile private var instance: Speaker? = null

        fun get(context: Context): Speaker = instance ?: synchronized(this) {
            instance ?: Speaker(context.applicationContext).also { instance = it }
        }
    }
}
