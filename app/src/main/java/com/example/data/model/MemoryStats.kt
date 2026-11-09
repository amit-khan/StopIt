package com.example.data.model

import java.util.Locale

data class MemoryStats(
    val availableBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val usedBytes: Long = 0L,
    val usedPercent: Int = 0
) {
    val availableFormatted: String
        get() = formatBytes(availableBytes)

    val totalFormatted: String
        get() = formatBytes(totalBytes)

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val gb = bytes.toDouble() / (1024 * 1024 * 1024)
        return if (gb >= 1.0) {
            String.format(Locale.US, "%.1f GB", gb)
        } else {
            val mb = bytes.toDouble() / (1024 * 1024)
            String.format(Locale.US, "%.0f MB", mb)
        }
    }
}
