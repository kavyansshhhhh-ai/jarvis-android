package app.shilex.android.tools

import app.shilex.android.local.SystemPanelTarget
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserGuardTest {
    @Test
    fun neverLaunchesChromeForKnowledgeQuestionsUnlessExplicitlyAsked() {
        assertFalse(BrowserLaunchGuard.isExplicitBrowserRequest("Koshika ka powerhouse kya hai?"))
        assertFalse(BrowserLaunchGuard.isExplicitBrowserRequest("Photosynthesis kya hota hai?"))
        assertFalse(BrowserLaunchGuard.isExplicitBrowserRequest("Explain gravity simply"))
        assertFalse(BrowserLaunchGuard.isExplicitBrowserRequest("Mitochondria function"))

        assertTrue(BrowserLaunchGuard.isExplicitBrowserRequest("Open this in Chrome"))
        assertTrue(BrowserLaunchGuard.isExplicitBrowserRequest("Search photosynthesis in browser"))
        assertTrue(BrowserLaunchGuard.isExplicitBrowserRequest("Chrome me mitochondria search karo"))
    }

    @Test
    fun parsesInternalSearchAnswersAndSystemPanels() {
        val ddg = JSONObject()
            .put("Heading", "Mitochondria")
            .put("AbstractText", "Mitochondria generate most of the cell's supply of ATP.")
        assertEquals(
            "Mitochondria: Mitochondria generate most of the cell's supply of ATP.",
            InternalSearch.parseDuckDuckGo(ddg),
        )
        val wiki = JSONObject()
            .put("title", "Photosynthesis")
            .put("extract", "Photosynthesis is a system of biological processes by which photosynthetic organisms convert light energy into chemical energy.")
        assertTrue(InternalSearch.parseWikipediaSummary(wiki)!!.contains("biological processes"))
        assertEquals(SystemPanelTarget.QuickSettings, DeviceTools.panelTarget("quick_settings"))
        assertEquals(SystemPanelTarget.Hotspot, DeviceTools.panelTarget("hotspot"))
    }
}
