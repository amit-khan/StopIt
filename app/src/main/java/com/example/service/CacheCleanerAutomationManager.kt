package com.example.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated automation manager for clearing a single app's cache.
 * Keeps single-app cache clearing DRY, modular, and isolated from bulk force-stop queue logic.
 */
object CacheCleanerAutomationManager {
    private const val MAX_DURATION_MS = 4000L
    /** Safety net: re-scan at least this often even if no accessibility event arrives. */
    private const val EVENT_FALLBACK_MS = 300L
    /** Time allowed for the storage sub-page to open after tapping "Storage". */
    private const val STORAGE_OPEN_WAIT_MS = 400L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var activeJob: Job? = null

    fun clearCacheForApp(context: Context, packageName: String, onFinished: (() -> Unit)? = null) {
        val appContext = context.applicationContext
        activeJob?.cancel()

        activeJob = scope.launch(Dispatchers.IO) {
            // 1. Wake accessibility service for automation. Not restricted to the Settings package
            // because some OEMs host the storage page in their own security/safe-center app.
            ForceStopAccessibilityService.instance?.wakeForAutomation()

            // 2. Open App Info settings for the target app
            withContext(Dispatchers.Main) {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    appContext.startActivity(intent)
                } catch (ignored: Exception) {}
            }

            // 3. Scan & execute, waking on UI events rather than polling:
            // Step A: Find and click "Storage and cache" (Pixel) / "Storage" (Samsung, Xiaomi) / "Storage usage" (OnePlus)
            // Step B: Find and click "Clear cache" button on storage screen
            val deadline = SystemClock.elapsedRealtime() + MAX_DURATION_MS
            var cacheCleared = false

            while (!cacheCleared && SystemClock.elapsedRealtime() < deadline) {
                val service = ForceStopAccessibilityService.instance
                var waitMs = EVENT_FALLBACK_MS
                if (service != null) {
                    when (service.scanAndExecuteClearCache()) {
                        ClearCacheActionResult.CLEAR_CACHE_CLICKED,
                        ClearCacheActionResult.ALREADY_CLEARED -> cacheCleared = true
                        // Sub-page is opening; give it a moment before querying Clear Cache
                        ClearCacheActionResult.STORAGE_CLICKED -> waitMs = STORAGE_OPEN_WAIT_MS
                        ClearCacheActionResult.NONE -> Unit
                    }
                }
                if (!cacheCleared) ForceStopAccessibilityService.awaitChange(waitMs)
            }

            // 4. Let the click settle, then put accessibility service back into low-power sleep mode
            delay(150)
            ForceStopAccessibilityService.instance?.sleepService()

            // 5. Bring Stop it back to the foreground
            withContext(Dispatchers.Main) {
                bringAppToForeground(appContext)
                onFinished?.invoke()
            }
        }
    }

    private fun bringAppToForeground(context: Context) {
        try {
            val intent = Intent(context, com.example.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            context.startActivity(intent)
        } catch (e: Exception) {}
    }
}
