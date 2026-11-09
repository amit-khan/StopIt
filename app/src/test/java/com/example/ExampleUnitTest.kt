package com.example

import com.example.data.model.AppInfoItem
import com.example.data.model.MemoryStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun verify_memory_stats_formatting() {
    val stats = MemoryStats(
        availableBytes = 3L * 1024 * 1024 * 1024,
        totalBytes = 8L * 1024 * 1024 * 1024,
        usedBytes = 5L * 1024 * 1024 * 1024,
        usedPercent = 62
    )
    assertTrue(stats.availableFormatted.contains("GB"))
    assertTrue(stats.totalFormatted.contains("GB"))
    assertEquals(62, stats.usedPercent)
  }

  @Test
  fun verify_app_info_item_defaults() {
    val app = AppInfoItem(
        packageName = "com.android.chrome",
        appName = "Chrome",
        isSystemApp = true,
        isRunning = true,
        isSelected = true,
        isWhitelisted = false
    )
    assertTrue(app.isSelected)
    assertTrue(app.isSystemApp)
    assertFalse(app.isWhitelisted)
    assertEquals("Chrome", app.appName)
    assertEquals(null, app.cacheSizeBytes)
    assertEquals(null, app.cacheSizeFormatted)

    val appWithCache = app.copy(
        cacheSizeBytes = 15_400_000L,
        cacheSizeFormatted = "15.4 MB"
    )
    assertEquals(15_400_000L, appWithCache.cacheSizeBytes)
    assertEquals("15.4 MB", appWithCache.cacheSizeFormatted)

    val appSmallCache = app.copy(
        cacheSizeBytes = 512_000L,
        cacheSizeFormatted = "< 1 MB"
    )
    assertEquals("< 1 MB", appSmallCache.cacheSizeFormatted)
  }

  @Test
  fun verify_clear_cache_action_result_values() {
    val results = com.example.service.ClearCacheActionResult.values()
    assertTrue(results.contains(com.example.service.ClearCacheActionResult.NONE))
    assertTrue(results.contains(com.example.service.ClearCacheActionResult.STORAGE_CLICKED))
    assertTrue(results.contains(com.example.service.ClearCacheActionResult.CLEAR_CACHE_CLICKED))
    assertTrue(results.contains(com.example.service.ClearCacheActionResult.ALREADY_CLEARED))
  }
}
