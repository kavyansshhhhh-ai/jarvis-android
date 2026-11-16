package app.shilex.android.audio

import android.content.Context
import app.shilex.android.Diag
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * In-app offline Hindi neural TTS engine powered by Sherpa-ONNX (Piper VITS int8 + Kokoro multi-lang int8)
 * reading model weights directly from bundled APK assets and espeak-ng-data cached once in internal filesDir.
 */
class OfflineHindiTtsEngine(private val context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()

    private var activeKey: String? = null
    private var activeTts: OfflineTts? = null

    data class SynthesizedAudio(
        val pcm16: ByteArray,
        val sampleRate: Int,
        val peakAmplitude: Float,
    )

    fun isVoiceAvailable(voice: BundledHindiVoice): Boolean =
        BundledHindiVoices.isModelBundled(appContext, voice)

    /**
     * Synthesizes [text] offline using the selected [voice] and streams 16-bit mono PCM chunks
     * to [onChunk] as each sentence completes, supporting instant cooperative cancellation via [isCancelled].
     */
    fun synthesizeStreaming(
        voice: BundledHindiVoice,
        text: String,
        speed: Float = 1.0f,
        isCancelled: () -> Boolean,
        onChunk: (SynthesizedAudio) -> Unit,
    ): Boolean {
        if (text.isBlank() || isCancelled()) return false
        val tts = synchronized(lock) { ensureEngineLocked(voice) } ?: return false
        if (isCancelled()) return false

        val cleanSpeed = speed.coerceIn(0.5f, 2.0f)
        val sr = runCatching { tts.sampleRate() }.getOrDefault(voice.sampleRate)
            .takeIf { it > 0 } ?: voice.sampleRate

        var emittedAny = false
        val sentences = splitSentencesForLowLatency(text)
        for (sentence in sentences) {
            if (isCancelled()) return false
            val generated = runCatching {
                tts.generateWithCallback(
                    text = sentence,
                    sid = voice.speakerId,
                    speed = cleanSpeed,
                ) { samples ->
                    if (isCancelled()) {
                        0
                    } else {
                        if (samples.isNotEmpty()) {
                            emittedAny = true
                            onChunk(floatsToPcm16(samples, sr))
                        }
                        1
                    }
                }
            }.onFailure { err ->
                Diag.w("offline-tts: synthesize failed for ${voice.id}: ${err.message}")
            }.getOrNull() ?: return false

            // Fallback in case generateWithCallback returned full audio without invoking chunk callback
            if (!emittedAny && generated.samples.isNotEmpty() && !isCancelled()) {
                emittedAny = true
                val outRate = generated.sampleRate.takeIf { it > 0 } ?: sr
                onChunk(floatsToPcm16(generated.samples, outRate))
            }
        }
        return emittedAny && !isCancelled()
    }

    fun release() {
        synchronized(lock) {
            runCatching { activeTts?.free() }
            activeTts = null
            activeKey = null
        }
    }

    private fun ensureEngineLocked(voice: BundledHindiVoice): OfflineTts? {
        val key = when (voice.engineKind) {
            BundledEngineKind.PiperVits -> "piper:${voice.id}"
            BundledEngineKind.KokoroMultiLang -> "kokoro"
        }
        if (activeKey == key && activeTts != null) {
            return activeTts
        }

        runCatching { activeTts?.free() }
        activeTts = null
        activeKey = null

        val espeakDir = ensureEspeakDataDir() ?: return null
        val config = buildConfig(voice, espeakDir.absolutePath)
        val created = runCatching {
            OfflineTts(assetManager = appContext.assets, config = config)
        }.onFailure { err ->
            Diag.w("offline-tts: init failed for ${voice.id}: ${err.message}")
        }.getOrNull() ?: return null

        activeTts = created
        activeKey = key
        Diag.i("offline-tts: loaded ${voice.id} (${voice.engineKind})")
        return created
    }

    private fun buildConfig(voice: BundledHindiVoice, espeakDirPath: String): OfflineTtsConfig {
        val modelConfig = when (voice.engineKind) {
            BundledEngineKind.PiperVits -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = voice.modelAssetPath,
                    lexicon = "",
                    tokens = voice.tokensAssetPath,
                    dataDir = espeakDirPath,
                    dictDir = "",
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu",
            )
            BundledEngineKind.KokoroMultiLang -> OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = voice.modelAssetPath,
                    voices = voice.voicesAssetPath,
                    tokens = voice.tokensAssetPath,
                    dataDir = espeakDirPath,
                    lexicon = "",
                    lang = voice.lang,
                    dictDir = "",
                    lengthScale = 1.0f,
                ),
                numThreads = 4,
                debug = false,
                provider = "cpu",
            )
        }
        return OfflineTtsConfig(model = modelConfig)
    }

    private fun ensureEspeakDataDir(): File? {
        val targetDir = File(appContext.filesDir, "tts/espeak-ng-data")
        val stamp = File(targetDir, ".ready-v1")
        val marker = File(targetDir, "hi_dict")
        if (stamp.exists() && marker.exists()) {
            return targetDir
        }
        return runCatching {
            copyAssetDir(BundledHindiVoices.ESPEAK_DATA_ASSET_DIR, targetDir)
            stamp.writeText("ok")
            targetDir
        }.onFailure { err ->
            Diag.w("offline-tts: failed copying espeak-ng-data: ${err.message}")
        }.getOrNull()
    }

    private fun copyAssetDir(assetPath: String, destDir: File) {
        val children = appContext.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            destDir.parentFile?.mkdirs()
            appContext.assets.open(assetPath).use { input ->
                destDir.outputStream().use { output -> input.copyTo(output) }
            }
        } else {
            destDir.mkdirs()
            for (child in children) {
                copyAssetDir("$assetPath/$child", File(destDir, child))
            }
        }
    }

    private fun floatsToPcm16(samples: FloatArray, sampleRate: Int): SynthesizedAudio {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        var peak = 0f
        for (s in samples) {
            val clamped = s.coerceIn(-1f, 1f)
            val a = abs(clamped)
            if (a > peak) peak = a
            val pcmVal = (clamped * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            buf.putShort(pcmVal)
        }
        return SynthesizedAudio(buf.array(), sampleRate, peak.coerceIn(0.12f, 1f))
    }

    private fun splitSentencesForLowLatency(text: String): List<String> {
        val rawParts = text.trim()
            .split(Regex("(?<=[।.!?\\n])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return if (rawParts.isEmpty()) listOf(text.trim()) else rawParts
    }
}
