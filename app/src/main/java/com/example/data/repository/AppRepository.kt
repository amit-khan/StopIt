package com.example.data.repository

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import com.example.data.db.WhitelistDao
import com.example.data.db.WhitelistedAppEntity
import com.example.data.model.AppInfoItem
import com.example.data.model.MemoryStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class AppRepository(
    private val context: Context,
    private val whitelistDao: WhitelistDao
) {
    private val packageManager: PackageManager = context.packageManager
    private val activityManager: ActivityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    val whitelistedApps: Flow<List<WhitelistedAppEntity>> = whitelistDao.getAllWhitelisted()
    val whitelistedPackageNames: Flow<List<String>> = whitelistDao.getAllWhitelistedPackageNames()

    fun getMemoryStats(): MemoryStats {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val total = memoryInfo.totalMem
        val avail = memoryInfo.availMem
        val used = (total - avail).coerceAtLeast(0L)
        val percent = if (total > 0) ((used.toDouble() / total.toDouble()) * 100).toInt() else 0
        return MemoryStats(
            availableBytes = avail,
            totalBytes = total,
            usedBytes = used,
            usedPercent = percent
        )
    }

    suspend fun getRunningApps(whitelistedPackages: Set<String>): List<AppInfoItem> =
        withContext(Dispatchers.IO) {
            val ownPackage = context.packageName
            val runningProcesses = activityManager.runningAppProcesses.orEmpty()
            val runningPackageSet = mutableSetOf<String>()

            for (proc in runningProcesses) {
                proc.pkgList?.forEach { pkg ->
                    runningPackageSet.add(pkg)
                }
            }

            // Also inspect installed launchable user apps and third-party apps
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val launchableApps = packageManager.queryIntentActivities(mainIntent, 0)
            val launchablePackages = launchableApps.mapNotNull { it.activityInfo?.packageName }.toSet()

            val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            val result = mutableListOf<AppInfoItem>()

            for (appInfo in installedApps) {
                val pkg = appInfo.packageName

                // Skip own app
                if (pkg == ownPackage) continue

                // Skip whitelisted apps
                if (whitelistedPackages.contains(pkg)) continue

                // Skip core OS & critical components
                if (isCriticalSystemApp(appInfo)) continue

                // Query fresh ApplicationInfo to detect real-time FLAG_STOPPED state
                val freshAppInfo = try {
                    packageManager.getApplicationInfo(pkg, 0)
                } catch (e: Exception) {
                    appInfo
                }

                // In Android, FLAG_STOPPED indicates the app has been force-stopped
                val isStopped = (freshAppInfo.flags and ApplicationInfo.FLAG_STOPPED) != 0

                val isExplicitlyRunning = runningPackageSet.contains(pkg)
                val isLaunchable = launchablePackages.contains(pkg)

                // An app is considered running if it has NOT been force-stopped
                // and is either a launchable app or active in running processes
                val isRunningApp = !isStopped && (isLaunchable || isExplicitlyRunning)

                if (isRunningApp) {
                    val appName = packageManager.getApplicationLabel(freshAppInfo).toString()
                    result.add(
                        AppInfoItem(
                            packageName = pkg,
                            appName = appName,
                            isSystemApp = (freshAppInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                            isRunning = isExplicitlyRunning,
                            isSelected = true,
                            isWhitelisted = false
                        )
                    )
                }
            }

            result.sortedBy { it.appName.lowercase() }
        }

    suspend fun getWhitelistedAppsOnly(): List<AppInfoItem> =
        withContext(Dispatchers.IO) {
            val entities = whitelistDao.getAllWhitelistedList()
            entities.map { entity ->
                AppInfoItem(
                    packageName = entity.packageName,
                    appName = entity.appName,
                    isSystemApp = false,
                    isRunning = false,
                    isSelected = false,
                    isWhitelisted = true
                )
            }.sortedBy { it.appName.lowercase() }
        }

    suspend fun getNonWhitelistedApps(whitelistedPackages: Set<String>): List<AppInfoItem> =
        withContext(Dispatchers.IO) {
            val ownPackage = context.packageName
            val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            val nonWhitelisted = mutableListOf<AppInfoItem>()

            for (appInfo in installedApps) {
                val pkg = appInfo.packageName
                if (pkg == ownPackage) continue
                if (whitelistedPackages.contains(pkg)) continue
                if (isCriticalSystemApp(appInfo)) continue

                val appName = packageManager.getApplicationLabel(appInfo).toString()
                nonWhitelisted.add(
                    AppInfoItem(
                        packageName = pkg,
                        appName = appName,
                        isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        isRunning = false,
                        isSelected = false,
                        isWhitelisted = false
                    )
                )
            }

            nonWhitelisted.sortedBy { it.appName.lowercase() }
        }

    suspend fun addToWhitelist(packageName: String, appName: String) = withContext(Dispatchers.IO) {
        whitelistDao.insert(WhitelistedAppEntity(packageName = packageName, appName = appName))
    }

    suspend fun removeFromWhitelist(packageName: String) = withContext(Dispatchers.IO) {
        whitelistDao.delete(packageName)
    }

    private fun isCriticalSystemApp(appInfo: ApplicationInfo): Boolean {
        val pkg = appInfo.packageName
        if (pkg == "android" ||
            pkg.startsWith("com.android.systemui") ||
            pkg.startsWith("com.android.providers.") ||
            pkg.startsWith("com.android.server.") ||
            pkg.startsWith("com.google.android.gms") ||
            pkg.startsWith("com.google.android.gsf") ||
            pkg.startsWith("com.google.android.inputmethod") ||
            pkg.startsWith("com.samsung.android.honeyboard") ||
            pkg.startsWith("com.samsung.android.app.cocktailbarservice") ||
            pkg.startsWith("com.sec.android.app.launcher")
        ) {
            return true
        }

        // Protect active default home launcher
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val defaultLauncher = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        if (defaultLauncher?.activityInfo?.packageName == pkg) {
            return true
        }

        // Exclude system services that cannot be launched or stopped
        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val isUpdatedSystem = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        if (isSystem && !isUpdatedSystem) {
            // Check if it has a launcher intent
            val hasLauncher = packageManager.getLaunchIntentForPackage(pkg) != null
            if (!hasLauncher) {
                return true
            }
        }
        return false
    }

    /**
     * Checks if PACKAGE_USAGE_STATS permission has been granted in system settings.
     */
    fun isUsageAccessGranted(): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Queries accurate cache size for a package using StorageStatsManager.
     */
    fun getAppCacheBytes(packageName: String): Long? {
        if (!isUsageAccessGranted()) return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val storageStatsManager = context.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager
                    ?: return null
                val stats = storageStatsManager.queryStatsForPackage(
                    StorageManager.UUID_DEFAULT,
                    packageName,
                    Process.myUserHandle()
                )
                stats.cacheBytes
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun formatCacheSize(bytes: Long): String {
        val oneMegabyte = 1024L * 1024L
        return if (bytes < oneMegabyte) {
            "< 1 MB"
        } else {
            android.text.format.Formatter.formatFileSize(context, bytes)
        }
    }

    suspend fun populateCacheSizes(apps: List<AppInfoItem>): List<AppInfoItem> =
        withContext(Dispatchers.IO) {
            if (!isUsageAccessGranted()) {
                return@withContext apps
            }
            apps.map { app ->
                val cacheBytes = getAppCacheBytes(app.packageName)
                if (cacheBytes != null) {
                    app.copy(
                        cacheSizeBytes = cacheBytes,
                        cacheSizeFormatted = formatCacheSize(cacheBytes)
                    )
                } else {
                    app
                }
            }
        }
}
