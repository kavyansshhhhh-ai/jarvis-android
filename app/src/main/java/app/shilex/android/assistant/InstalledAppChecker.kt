package app.shilex.android.assistant

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Verifies real app installation state via Android [PackageManager].
 * Never guesses unverified package IDs or assumes an app is installed without a PackageManager check.
 */
class InstalledAppChecker(private val context: Context) {

    fun isInstalled(packageName: String): Boolean {
        val cleanPkg = packageName.trim()
        if (cleanPkg.isEmpty()) return false
        val pm = context.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(cleanPkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(cleanPkg, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    fun findFirstInstalled(candidatePackages: Iterable<String>): String? =
        candidatePackages.firstOrNull { isInstalled(it) }

    fun isAnyInstalled(candidatePackages: Iterable<String>): Boolean =
        candidatePackages.any { isInstalled(it) }
}
