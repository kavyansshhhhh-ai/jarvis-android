package app.shilex.android.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeControllerTest {
    @Test
    fun convertsPercentToStreamIndexAndBackWithMinSupport() {
        // Zero-based media stream (0..15)
        assertEquals(0, VolumeController.percentToIndex(0, minIndex = 0, maxIndex = 15))
        assertEquals(8, VolumeController.percentToIndex(50, minIndex = 0, maxIndex = 15))
        assertEquals(15, VolumeController.percentToIndex(100, minIndex = 0, maxIndex = 15))
        assertEquals(53, VolumeController.indexToPercent(8, minIndex = 0, maxIndex = 15))

        // Call stream often starts at min = 1 (1..5)
        assertEquals(1, VolumeController.percentToIndex(0, minIndex = 1, maxIndex = 5))
        assertEquals(3, VolumeController.percentToIndex(50, minIndex = 1, maxIndex = 5))
        assertEquals(5, VolumeController.percentToIndex(100, minIndex = 1, maxIndex = 5))
        assertEquals(0, VolumeController.indexToPercent(1, minIndex = 1, maxIndex = 5))
        assertEquals(50, VolumeController.indexToPercent(3, minIndex = 1, maxIndex = 5))
        assertEquals(100, VolumeController.indexToPercent(5, minIndex = 1, maxIndex = 5))
    }

    @Test
    fun clampsPercentsAndMapsStreamKeys() {
        assertEquals(0, VolumeController.clampPercent(-25))
        assertEquals(100, VolumeController.clampPercent(180))
        assertEquals(AudioStream.Ring, AudioStream.fromKey("ring"))
        assertEquals(AudioStream.Alarm, AudioStream.fromKey("alarm"))
        assertEquals(AudioStream.Notification, AudioStream.fromKey("notifications"))
        assertEquals(AudioStream.Call, AudioStream.fromKey("call"))
        assertEquals(AudioStream.Media, AudioStream.fromKey("unknown"))
    }
}
