package app.shilex.android.tools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import app.shilex.android.Diag
import java.text.Collator
import java.util.Locale

data class InstalledAppInfo(
    val label: String,
    val packageName: String,
    val launchActivity: String = "",
    val launchable: Boolean = true,
    val systemApp: Boolean = false,
)

/**
 * Pure A-Z sorting, alias lookup, search, and disambiguation logic for installed apps.
 * Never invents apps that are not in the provided list.
 */
object AppCatalog {
    private val COLLATOR: Collator = Collator.getInstance(Locale.US).apply {
        strength = Collator.PRIMARY
    }

    private val ALIASES = mapOf(
        "yt music" to "youtube music",
        "yt" to "youtube",
        "gpay" to "google pay",
        "g pay" to "google pay",
        "wa" to "whatsapp",
        "whats app" to "whatsapp",
        "insta" to "instagram",
        "fb" to "facebook",
        "tg" to "telegram",
        "maps" to "google maps",
        "chrome" to "google chrome",
    )

    fun sortAlphabetical(apps: List<InstalledAppInfo>): List<InstalledAppInfo> =
        apps.distinctBy { it.packageName }
            .sortedWith { a, b ->
                val cmp = COLLATOR.compare(a.label.trim(), b.label.trim())
                if (cmp != 0) cmp else a.packageName.compareTo(b.packageName)
            }

    fun normalizeQuery(raw: String): String {
        val cleaned = raw.lowercase()
            .trim()
            .removePrefix("open ")
            .removePrefix("launch ")
            .removeSuffix(" app")
            .replace(Regex("\\s+"), " ")
            .trim()
        return ALIASES[cleaned] ?: cleaned
    }

    /** Finds best matching apps using exact label, startsWith, word match, then substring / package hint. */
    fun find(query: String, apps: List<InstalledAppInfo>, includeNonLaunchable: Boolean = false): List<InstalledAppInfo> {
        val q = normalizeQuery(query)
        if (q.isEmpty()) return emptyList()
        val pool = if (includeNonLaunchable) apps else apps.filter { it.launchable }
        val sortedPool = sortAlphabetical(pool)

        val exact = sortedPool.filter { it.label.lowercase().trim() == q }
        if (exact.isNotEmpty()) return exact

        val prefix = sortedPool.filter { it.label.lowercase().trim().startsWith(q) }
        if (prefix.isNotEmpty()) return prefix

        val contains = sortedPool.filter { q in it.label.lowercase() }
        if (contains.isNotEmpty()) return contains

        val pkgMatch = sortedPool.filter { q in it.packageName.lowercase() }
        if (pkgMatch.isNotEmpty()) return pkgMatch

        // If user didn't filter non-launchable explicitly, check non-launchable only as an informational fallback when requested.
        return if (!includeNonLaunchable) emptyList() else emptyList()
    }

    fun search(query: String, apps: List<InstalledAppInfo>, includeNonLaunchable: Boolean = false): List<InstalledAppInfo> {
        val q = normalizeQuery(query)
        val sorted = sortAlphabetical(if (includeNonLaunchable) apps else apps.filter { it.launchable })
        if (q.isEmpty()) return sorted
        return sorted.filter { app ->
            val l = app.label.lowercase()
            val p = app.packageName.lowercase()
            q in l || q in p
        }
    }

    fun summarizeAlphabetical(apps: List<InstalledAppInfo>, limit: Int = 80, includeSystem: Boolean = true): String {
        val filtered = sortAlphabetical(apps.filter { it.launchable && (includeSystem || !it.systemApp) })
        if (filtered.isEmpty()) return "No launchable apps were found."
        val shown = filtered.take(limit)
        return buildString {
            append("${filtered.size} installed apps (A–Z):")
            shown.forEach { app ->
                append("\n- ${app.label}")
                if (filtered.count { it.label.equals(app.label, ignoreCase = true) } > 1) {
                    append(" (${app.packageName})")
                }
            }
            if (filtered.size > shown.size) {
                append("\n...and ${filtered.size - shown.size} more apps. Ask to search by name to narrow down.")
            }
        }
    }
}

/**
 * Live installed-app registry backed by [PackageManager] plus a dynamic package change receiver
 * so newly installed/uninstalled apps update automatically without stale caches.
 */
object InstalledAppsRegistry {
    @Volatile private var initialized = false
    @Volatile private var cachedApps: List<InstalledAppInfo> = emptyList()

    fun init(context: Context) {
        val appContext = context.applicationContext
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            refresh(appContext)
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    refresh(appContext)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
            initialized = true
        }
    }

    fun apps(context: Context, forceRefresh: Boolean = false): List<InstalledAppInfo> {
        if (forceRefresh || cachedApps.isEmpty()) refresh(context.applicationContext)
        return cachedApps
    }

    fun refresh(context: Context): List<InstalledAppInfo> {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchActivities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcherIntent, 0)
        }
        val launchMap = LinkedHashMap<String, Pair<String, String>>()
        for (info in launchActivities) {
            val pkg = info.activityInfo.packageName
            val label = info.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { pkg }
            val activity = info.activityInfo.name.orEmpty()
            if (!launchMap.containsKey(pkg)) {
                launchMap[pkg] = label to activity
            }
        }

        val allApps = LinkedHashMap<String, InstalledAppInfo>()
        val installedPackages = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
        }.getOrDefault(emptyList())

        for (appInfo in installedPackages) {
            val pkg = appInfo.packageName ?: continue
            val launch = launchMap[pkg]
            val fallbackLaunch = if (launch == null && pm.getLaunchIntentForPackage(pkg) != null) {
                pm.getLaunchIntentForPackage(pkg)?.component?.className.orEmpty()
            } else {
                ""
            }
            val label = launch?.first ?: runCatching { pm.getApplicationLabel(appInfo).toString().trim() }.getOrDefault(pkg)
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            allApps[pkg] = InstalledAppInfo(
                label = label.ifEmpty { pkg },
                packageName = pkg,
                launchActivity = launch?.second ?: fallbackLaunch,
                launchable = launch != null || fallbackLaunch.isNotEmpty(),
                systemApp = isSystem,
            )
        }

        // Ensure every resolved launcher activity appears even if omitted from getInstalledApplications on restricted devices.
        for ((pkg, pair) in launchMap) {
            if (!allApps.containsKey(pkg)) {
                allApps[pkg] = InstalledAppInfo(
                    label = pair.first,
                    packageName = pkg,
                    launchActivity = pair.second,
                    launchable = true,
                    systemApp = false,
                )
            }
        }

        val sorted = AppCatalog.sortAlphabetical(allApps.values.toList())
        cachedApps = sorted
        Diag.i("apps: indexed ${sorted.size} installed apps (${sorted.count { it.launchable }} launchable)")
        return sorted
    }
}
