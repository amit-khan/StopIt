package com.example.service

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated automation manager for clearing a single app's cache.
 * Keeps single-app cache clearing DRY, modular, and isolated from bulk force-stop queue logic.
 */
object CacheCleanerAutomationManager {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var activeJob: Job? = null

    fun clearCacheForApp(context: Context, packageName: String, onFinished: (() -> Unit)? = null) {
        activeJob?.cancel()

        activeJob = scope.launch(Dispatchers.Default) {
            try {
                // 1. Wake accessibility service for automation
                ForceStopAccessibilityService.instance?.wakeForAutomation()

                // 2. Open App Info settings for the target app
                withContext(Dispatchers.Main) {
                    AutomationManager.openAppDetailsSettings(context, packageName)
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
                        when (service.scanAndExecuteClearCache()) {
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

                delay(150)
            } finally {
                // Guaranteed cleanup: Put accessibility service back into low-power sleep mode
                ForceStopAccessibilityService.instance?.sleepService()

                // Bring Stop it back to the foreground
                withContext(Dispatchers.Main) {
                    AutomationManager.bringAppToForeground(context)
                    onFinished?.invoke()
                }
            }
        }
    }
}
