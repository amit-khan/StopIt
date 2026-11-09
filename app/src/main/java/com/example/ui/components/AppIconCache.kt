package com.example.ui.components

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AppIconCache {
    // Retain only 35 icons in memory max (approx 1.5MB total memory)
    private val cache = object : LruCache<String, ImageBitmap>(35) {}

    fun get(packageName: String): ImageBitmap? = cache.get(packageName)

    fun put(packageName: String, bitmap: ImageBitmap) {
        cache.put(packageName, bitmap)
    }

    suspend fun loadIcon(context: Context, packageName: String): ImageBitmap? =
        withContext(Dispatchers.IO) {
            val cached = get(packageName)
            if (cached != null) return@withContext cached

            try {
                val pm = context.packageManager
                val appInfo = pm.getApplicationInfo(packageName, 0)
                val drawable = pm.getApplicationIcon(appInfo)
                val bitmap = renderCompactBitmap(drawable)
                val imageBitmap = bitmap.asImageBitmap()
                put(packageName, imageBitmap)
                imageBitmap
            } catch (e: Exception) {
                null
            }
        }

    private fun renderCompactBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            val src = drawable.bitmap
            // Scale down if larger than 96x96
            if (src.width > 96 || src.height > 96) {
                return Bitmap.createScaledBitmap(src, 96, 96, true)
            }
            return src
        }

        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, 96, 96)
        drawable.draw(canvas)
        return bitmap
    }
}

@Composable
fun rememberAppIcon(packageName: String): ImageBitmap? {
    val context = LocalContext.current
    val cached = remember(packageName) { AppIconCache.get(packageName) }
    val iconState = remember(packageName) { mutableStateOf(cached) }

    if (cached == null) {
        LaunchedEffect(packageName) {
            val loaded = AppIconCache.loadIcon(context, packageName)
            iconState.value = loaded
        }
    }

    return iconState.value
}
