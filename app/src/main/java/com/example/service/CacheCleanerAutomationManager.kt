package com.example.service

import android.content.Context
import android.content.Intent
import android.net.Uri
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var activeJob: Job? = null

    fun clearCacheForApp(activityContext: Context, packageName: String, onFinished: (() -> Unit)? = null) {
        val context = activityContext.applicationContext
        activeJob?.cancel()

        activeJob = scope.launch(Dispatchers.Default) {
            // 1. Wake accessibility service for automation
            ForceStopAccessibilityService.instance?.wakeForAutomation()

            // 2. Open App Info settings for the target app
            withContext(Dispatchers.Main) {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    context.startActivity(intent)
                } catch (ignored: Exception) {}
            }

            // 3. Scan & execute:
            // Step A: Find and click "Storage and cache" (Pixel) / "Storage" (Samsung, Xiaomi) / "Storage usage" (OnePlus)
            // Step B: Find and click "Clear cache" button on storage screen
            val maxDurationMs = 4000L
            val stepDelay = 80L
            var elapsed = 0L
            var cacheCleared = false

            while (elapsed < maxDurationMs && !cacheCleared) {
                val service = ForceStopAccessibilityService.instance
                if (service != null) {
                    val result = service.scanAndExecuteClearCache()
                    when (result) {
                        ClearCacheActionResult.CLEAR_CACHE_CLICKED,
                        ClearCacheActionResult.ALREADY_CLEARED -> {
                            cacheCleared = true
                            break
                        }
                        ClearCacheActionResult.STORAGE_CLICKED -> {
                            // Sub-page is opening; short delay before querying Clear Cache
                            delay(250)
                        }
                        ClearCacheActionResult.NONE -> {
                            // Waiting for layout
                        }
                    }
                }
                delay(stepDelay)
                elapsed += stepDelay
            }

            // 4. Put accessibility service back into low-power sleep mode
            delay(150)
            ForceStopAccessibilityService.instance?.sleepService()

            // 5. Bring Stop it back to the foreground
            withContext(Dispatchers.Main) {
                bringAppToForeground(context)
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
