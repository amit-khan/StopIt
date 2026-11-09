package com.example.data.model

data class AppInfoItem(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean = false,
    val isRunning: Boolean = false,
    val isSelected: Boolean = true,
    val isWhitelisted: Boolean = false,
    val cacheSizeBytes: Long? = null,
    val cacheSizeFormatted: String? = null
)
