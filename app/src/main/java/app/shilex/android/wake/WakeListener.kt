package app.shilex.android.wake

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.shilex.android.Diag
import app.shilex.android.audio.othersPlaying
import app.shilex.android.local.OfflineSpeech
import org.vosk.Recognizer

/**
 * Listens for the wake phrase ("Jarvis" / "Hey Jarvis") offline on the phone itself.
 * Audio stays in a short in-memory rolling buffer and is fed through the local Vosk
 * grammar recognizer (plus optional openWakeWord ONNX if bundled) only when [SpeechGate]
 * hears speech above room noise. Nothing is recorded, stored, or uploaded.
 */
class WakeListener(
    private val context: Context,
    private val onState: (WakeState) -> Unit = {},
    private val onWake: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val offline = OfflineSpeech.get(context)

    @Volatile private var running = false
    @Volatile private var profile: VoiceProfile? = null
    @Volatile private var lastWake = 0L
    private var thread: Thread? = null

    /** Callers check RECORD_AUDIO first. Returns false if no offline detector is available. */
    fun start(): Boolean {
        if (running) {
            if (WakeWordModel.onnxAvailable(context)) profile = VoiceProfile.load(context)
            return true
        }
        val useVosk = offline.available()
        val useOnnx = WakeWordModel.onnxAvailable(context)
        if (!useVosk && !useOnnx) {
            onState(WakeState.Unavailable)
            return false
        }
        thread?.join(JOIN_MS)
        if (useOnnx) profile = VoiceProfile.load(context)
        running = true
        onState(WakeState.Starting)
        thread = Thread({ runLoop(useVosk, useOnnx) }, "wake").apply { start() }
        Diag.i("wake: starting listener for \"${WakeWordModel.PHRASE}\" (vosk=$useVosk, onnx=$useOnnx)")
        return true
    }

    private fun runLoop(useVosk: Boolean, useOnnx: Boolean) {
        var voskRecognizer: Recognizer? = null
        var onnxDetector: WakeWordModel? = null
        val matcher = WakePhraseMatcher()
        var triggered = false

        try {
            if (useVosk) {
                offline.pinForWake(true)
                val model = runCatching { offline.model() }
                    .onFailure { Diag.w("wake: Vosk model failed to load (${it.javaClass.simpleName})") }
                    .getOrNull()
                if (model != null && running) {
                    voskRecognizer = runCatching {
                        Recognizer(model, SAMPLE_RATE.toFloat(), WakePhraseMatcher.GRAMMAR_JSON).apply {
                            setWords(true)
                            setPartialWords(true)
                            setEndpointerDelays(1.4f, 0.35f, 4.0f)
                        }
                    }.onFailure {
                        Diag.w("wake: recognizer init failed (${it.javaClass.simpleName})")
                    }.getOrNull()
                }
            }
            if (useOnnx && running) {
                onnxDetector = runCatching { WakeWordModel(context) }
                    .onFailure { Diag.w("wake: ONNX model failed to load (${it.javaClass.simpleName})") }
                    .getOrNull()
            }
            if (voskRecognizer == null && onnxDetector == null) {
                if (running) onState(WakeState.Unavailable)
                running = false
                return
            }

            val recent = Array(PREROLL_CHUNKS) { ShortArray(WakeWordModel.CHUNK) }
            var next = 0
            val gate = SpeechGate()
            val stats = GateStats()
            var micFailures = 0

            while (running) {
                if (WakePolicy.isCallAudioMode(audio.mode)) {
                    onState(WakeState.PausedInCall)
                    sleepInterruptibly(CALL_POLL_MS)
                    continue
                }
                val current = openMic(echoCancel = musicPlaying())
                if (current == null) {
                    micFailures++
                    onState(WakeState.PausedMicBusy)
                    sleepInterruptibly(WakePolicy.micRetryDelayMs(micFailures))
                    continue
                }

                micFailures = 0
                onState(WakeState.Listening)
                voskRecognizer?.reset()
                onnxDetector?.reset()
                matcher.reset()

                var awake = false
                var chunks = 0
                var readErrors = 0

                try {
                    while (running) {
                        if (++chunks % CALL_AND_MUSIC_CHECK_CHUNKS == 0) {
                            if (WakePolicy.isCallAudioMode(audio.mode) || musicPlaying() != current.echoCancel) break
                        }
                        val chunk = recent[next]
                        if (!current.read(chunk)) {
                            readErrors++
                            if (readErrors >= MAX_READ_ERRORS) {
                                Diag.w("wake: mic preempted or stopped, reopening")
                                break
                            }
                            continue
                        }
                        readErrors = 0
                        next = (next + 1) % PREROLL_CHUNKS

                        val open = gate.open(chunk) || current.echoCancel
                        stats.add(open, gate.background)

                        if (!open) {
                            if (awake) {
                                if (voskRecognizer != null) {
                                    val tail = matcher.evaluateFinal(voskRecognizer.finalResult)
                                    if (tail.matched && triggerAllowed()) {
                                        Diag.i("wake: heard \"${tail.phrase}\" (tail, conf %.2f)".format(tail.confidence))
                                        triggered = true
                                        running = false
                                        break
                                    }
                                    voskRecognizer.reset()
                                }
                                matcher.reset()
                                awake = false
                            }
                            continue
                        }

                        if (!awake) {
                            awake = true
                            voskRecognizer?.reset()
                            onnxDetector?.reset()
                            matcher.reset()
                            // Replay buffered pre-roll chunks so start of "Jarvis" is never clipped.
                            for (i in 0 until PREROLL_CHUNKS - 1) {
                                val prev = recent[(next + i) % PREROLL_CHUNKS]
                                if (processChunk(prev, voskRecognizer, onnxDetector, matcher, current.echoCancel, isPreroll = true)) {
                                    triggered = true
                                    running = false
                                    break
                                }
                            }
                            if (!running) break
                        }

                        if (processChunk(chunk, voskRecognizer, onnxDetector, matcher, current.echoCancel, isPreroll = false)) {
                            triggered = true
                            running = false
                            break
                        }
                    }
                } finally {
                    current.close()
                }
            }
        } finally {
            runCatching { voskRecognizer?.close() }
            runCatching { onnxDetector?.close() }
            offline.pinForWake(false)
            running = false
            if (triggered) {
                lastWake = SystemClock.elapsedRealtime()
                main.post(onWake)
            }
        }
    }

    private fun processChunk(
        chunk: ShortArray,
        vosk: Recognizer?,
        onnx: WakeWordModel?,
        matcher: WakePhraseMatcher,
        music: Boolean,
        isPreroll: Boolean,
    ): Boolean {
        if (vosk != null) {
            val finalized = vosk.acceptWaveForm(chunk, chunk.size)
            val result = if (finalized) {
                matcher.evaluateFinal(vosk.result)
            } else {
                matcher.evaluatePartial(vosk.partialResult)
            }
            if (result.matched && triggerAllowed()) {
                val kind = if (result.isPartial) "partial" else "final"
                Diag.i("wake: heard \"${result.phrase}\" ($kind, conf %.2f${if (music) ", music" else ""})".format(result.confidence))
                vosk.reset()
                matcher.reset()
                return true
            }
        }
        if (onnx != null && !isPreroll) {
            val score = runCatching { onnx.score(chunk) }.getOrDefault(0f)
            val voice = profile?.takeIf { score >= VOICE_GATE }?.let { it to it.similarity(onnx.window()) }
            val matched = voice != null && voice.second >= voice.first.threshold
            if ((score >= ONNX_THRESHOLD || matched) && triggerAllowed()) {
                Diag.i("wake: heard ONNX (score %.2f${if (music) ", music" else ""})".format(score))
                return true
            }
        }
        return false
    }

    private fun triggerAllowed(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return lastWake == 0L || now - lastWake >= WakePolicy.COOLDOWN_MS
    }

    private fun sleepInterruptibly(ms: Long) {
        if (!running) return
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            // Woken early by stop().
        }
    }

    private fun musicPlaying(): Boolean = othersPlaying(audio)

    @SuppressLint("MissingPermission")
    private fun openMic(echoCancel: Boolean): Mic? {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            if (echoCancel) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, WakeWordModel.CHUNK * 2 * 16),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            Diag.w("wake: mic busy")
            return null
        }
        val canceller = if (echoCancel && AcousticEchoCanceler.isAvailable()) {
            runCatching { AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull()
        } else {
            null
        }
        return runCatching {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                canceller?.release()
                record.release()
                null
            } else {
                if (echoCancel) Diag.i("wake: music playing, echo canceller=${canceller != null}")
                Mic(record, canceller, echoCancel)
            }
        }.getOrElse {
            runCatching { canceller?.release() }
            runCatching { record.release() }
            Diag.w("wake: startRecording failed (${it.javaClass.simpleName})")
            null
        }
    }

    private class Mic(val record: AudioRecord, val canceller: AcousticEchoCanceler?, val echoCancel: Boolean) {
        fun read(chunk: ShortArray): Boolean {
            var filled = 0
            while (filled < chunk.size) {
                val read = record.read(chunk, filled, chunk.size - filled)
                if (read <= 0) return false
                filled += read
            }
            return true
        }

        fun close() {
            runCatching { record.stop() }
            runCatching { canceller?.release() }
            runCatching { record.release() }
        }
    }

    /** How often the recognizer ran, logged every 10 minutes (verifies battery saving). */
    private class GateStats {
        private var total = 0
        private var open = 0

        fun add(isOpen: Boolean, background: Int) {
            total++
            if (isOpen) open++
            if (total == STATS_CHUNKS) {
                Diag.i("wake: decoder active ${open * 100 / total}% of the last 10 min (background $background)")
                total = 0
                open = 0
            }
        }
    }

    fun stop() {
        if (!running && thread?.isAlive != true) {
            onState(WakeState.Off)
            return
        }
        running = false
        val t = thread
        if (t != null && t !== Thread.currentThread()) {
            t.interrupt()
            runCatching { t.join(JOIN_MS) }
        }
        thread = null
        Diag.i("wake: paused")
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val ONNX_THRESHOLD = 0.4f
        const val VOICE_GATE = 0.1f
        const val JOIN_MS = 450L
        const val CALL_POLL_MS = 1_500L
        const val CALL_AND_MUSIC_CHECK_CHUNKS = 12 // ~1 second
        const val MAX_READ_ERRORS = 3
        const val PREROLL_CHUNKS = 22 // ~1.76 s pre-roll before gate opens
        const val STATS_CHUNKS = 7_500 // 10 minutes
    }
}
