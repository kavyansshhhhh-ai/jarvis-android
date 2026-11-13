package app.shilex.android

import android.app.Application
import app.shilex.android.orb.FloatingOrb
import app.shilex.android.tools.InstalledAppsRegistry

/**
 * Starts the diagnostic log file before any screen, service or receiver runs, brings the
 * floating orb back, and indexes installed apps for instant A-Z discovery.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Diag.start(DiagFile(filesDir))
        FloatingOrb.sync(this)
        runCatching { InstalledAppsRegistry.init(this) }
    }
}
