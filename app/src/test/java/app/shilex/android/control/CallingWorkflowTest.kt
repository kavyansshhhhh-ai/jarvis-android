package app.shilex.android.control

import app.shilex.android.tools.CallClassifier
import org.junit.Assert.assertEquals
import org.junit.Test

class CallingWorkflowTest {

    @Test
    fun classifiesNormalPhoneCalls() {
        assertEquals(CallClassifier.Route.NormalPhone, CallClassifier.classify("call mummy"))
        assertEquals(CallClassifier.Route.NormalPhone, CallClassifier.classify("make a phone call to Rahul"))
        assertEquals(CallClassifier.Route.NormalPhone, CallClassifier.classify("normal call lagao"))
        assertEquals(CallClassifier.Route.NormalPhone, CallClassifier.classify("cellular call to Priya"))
        assertEquals(CallClassifier.Route.NormalPhone, CallClassifier.classify("sim 1 se call karo"))
    }

    @Test
    fun classifiesWhatsAppAudioCalls() {
        assertEquals(CallClassifier.Route.WhatsAppAudio, CallClassifier.classify("whatsapp call mummy"))
        assertEquals(CallClassifier.Route.WhatsAppAudio, CallClassifier.classify("Rahul ko whatsapp par call karo"))
        assertEquals(CallClassifier.Route.WhatsAppAudio, CallClassifier.classify("व्हाट्सएप कॉल लगाओ"))
        assertEquals(CallClassifier.Route.WhatsAppAudio, CallClassifier.classify("call Rohan on wa"))
    }

    @Test
    fun classifiesWhatsAppVideoCalls() {
        assertEquals(CallClassifier.Route.WhatsAppVideo, CallClassifier.classify("whatsapp video call mummy"))
        assertEquals(CallClassifier.Route.WhatsAppVideo, CallClassifier.classify("Rahul ko whatsapp par video call lagao"))
        assertEquals(CallClassifier.Route.WhatsAppVideo, CallClassifier.classify("व्हाट्सएप वीडियो कॉल"))
        assertEquals(CallClassifier.Route.WhatsAppVideo, CallClassifier.classify("start a wa vc with boss"))
    }

    @Test
    fun explicitChannelOverridesSpokenText() {
        assertEquals(CallClassifier.Route.WhatsAppVideo, CallClassifier.classify("call mummy", "whatsapp_video"))
        assertEquals(CallClassifier.Route.WhatsAppAudio, CallClassifier.classify("call mummy", "whatsapp"))
        assertEquals(CallClassifier.Route.NormalPhone, CallClassifier.classify("whatsapp mummy", "normal"))
    }
}
