package app.shilex.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VoiceInterruptionTest {

    @Test
    fun stopTtsAndFlushClearSpeakingAndSpeechAmplitude() {
        val speaker = Speaker()
        assertEquals(0f, speaker.speechAmplitude.value, 0.01f)
        assertFalse(speaker.isSpeaking.value)

        // Stop/flush resets state safely even without an active AudioTrack or TTS engine
        speaker.stopTts()
        assertEquals(0f, speaker.speechAmplitude.value, 0.01f)
        assertFalse(speaker.isSpeaking.value)

        speaker.flush()
        assertEquals(0f, speaker.speechAmplitude.value, 0.01f)
        assertFalse(speaker.isSpeaking.value)
    }

    @Test
    fun pcmLevelDetectionIsAccurate() {
        assertEquals(0, pcmLevel(ByteArray(0)))
        val highEnergy = ByteArray(4).also {
            it[0] = 0x00
            it[1] = 0x40 // 16384
            it[2] = 0x00
            it[3] = 0x40
        }
        assertEquals(16384, pcmLevel(highEnergy))
    }
}
