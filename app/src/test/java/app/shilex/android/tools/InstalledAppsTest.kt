package app.shilex.android.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledAppsTest {
    private val sample = listOf(
        InstalledAppInfo("YouTube", "com.google.android.youtube", launchable = true),
        InstalledAppInfo("Amazon Shopping", "in.amazon.mShop.android.shopping", launchable = true),
        InstalledAppInfo("Google Pay", "com.google.android.apps.nbu.paisa.user", launchable = true),
        InstalledAppInfo("Android System WebView", "com.google.android.webview", launchable = false, systemApp = true),
        InstalledAppInfo("Spotify", "com.spotify.music", launchable = true),
        InstalledAppInfo("Spotify", "com.spotify.music", launchable = true),
    )

    @Test
    fun sortsAlphabeticallyAndDeduplicatesPackages() {
        val sorted = AppCatalog.sortAlphabetical(sample)
        assertEquals(
            listOf("Amazon Shopping", "Android System WebView", "Google Pay", "Spotify", "YouTube"),
            sorted.map { it.label },
        )
    }

    @Test
    fun resolvesAliasesRespectsLaunchableFilterAndNeverInventsMissingApps() {
        val gpay = AppCatalog.find("gpay", sample)
        assertEquals(listOf("com.google.android.apps.nbu.paisa.user"), gpay.map { it.packageName })

        assertEquals(emptyList<InstalledAppInfo>(), AppCatalog.find("webview", sample, includeNonLaunchable = false))
        assertEquals(listOf("com.google.android.webview"), AppCatalog.find("webview", sample, includeNonLaunchable = true).map { it.packageName })

        assertTrue(AppCatalog.find("NotInstalledAppXYZ", sample).isEmpty())
        assertTrue(AppCatalog.summarizeAlphabetical(sample).startsWith("4 installed apps (A–Z):"))
    }
}
