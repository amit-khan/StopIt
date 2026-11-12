package com.example.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.example.MainActivity
import com.example.data.model.AppInfoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AutomationProgress {
    object Idle : AutomationProgress
    data class Running(
        val currentApp: AppInfoItem,
        val currentIndex: Int,
        val totalCount: Int,
        val isPaused: Boolean = false
    ) : AutomationProgress
    data class Completed(val totalStopped: Int) : AutomationProgress
    object Cancelled : AutomationProgress
}

object AutomationManager {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var automationJob: Job? = null

    private val _currentAppPackage = MutableStateFlow<String?>(null)
    val currentAppPackage: StateFlow<String?> = _currentAppPackage.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _progress = MutableStateFlow<AutomationProgress>(AutomationProgress.Idle)
    val progress: StateFlow<AutomationProgress> = _progress.asStateFlow()

    private var queue: List<AppInfoItem> = emptyList()
    private var currentIndex = 0
    private var stoppedCount = 0

    fun startAutomation(context: Context, appsToStop: List<AppInfoItem>, onComplete: () -> Unit) {
        if (appsToStop.isEmpty()) return
        stopAutomation(context)

        queue = appsToStop
        currentIndex = 0
        stoppedCount = 0
        _isPaused.value = false

        // Wake the AccessibilityService from low-memory sleep mode
        ForceStopAccessibilityService.instance?.wakeForAutomation()

        automationJob = scope.launch(Dispatchers.Default) {
            try {
                for (i in queue.indices) {
                    currentIndex = i
                    val app = queue[i]
                    _currentAppPackage.value = app.packageName

                    _progress.value = AutomationProgress.Running(
                        currentApp = app,
                        currentIndex = i + 1,
                        totalCount = queue.size,
                        isPaused = _isPaused.value
                    )

                    // Wait if paused
                    while (_isPaused.value) {
                        delay(200)
                    }

                    // Fast-path: if already stopped before opening Settings, skip immediately
                    if (isPackageStopped(context, app.packageName)) {
                        stoppedCount++
                        _currentAppPackage.value = null
                        continue
                    }

                    // Open App Info settings for the target app
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        openAppDetailsSettings(context, app.packageName)
                    }

                    // Brief settle so the newly launched App Info activity can start rendering
                    // and we don't accidentally read a previous app's closing Settings screen
                    delay(45)

                    // Allow up to 3 attempts to force-stop the app if it refuses to stop
                    val maxAttempts = 3
                    var currentAttempt = 1
                    var appIsStopped = false

                    while (currentAttempt <= maxAttempts && !appIsStopped) {
                        var waitElapsed = 0L
                        val timeoutMs = 3500L
                        val stepDelay = 50L

                        while (waitElapsed < timeoutMs && !appIsStopped) {
                            while (_isPaused.value) {
                                delay(200)
                            }

                            if (isPackageStopped(context, app.packageName)) {
                                appIsStopped = true
                                break
                            }

                            val service = ForceStopAccessibilityService.instance
                            if (service != null) {
                                val result = service.scanAndExecute(app.packageName)
                                when (result) {
                                    ForceStopActionResult.ALREADY_DISABLED -> {
                                        appIsStopped = true
                                        break
                                    }
                                    ForceStopActionResult.CONFIRMATION_CLICKED -> {
                                        // Poll briefly for FLAG_STOPPED / disabled Force Stop button
                                        // instead of waiting a fixed 250ms every time
                                        var confirmWait = 0L
                                        while (confirmWait < 240L) {
                                            delay(45)
                                            confirmWait += 45
                                            if (isPackageStopped(context, app.packageName)) {
                                                appIsStopped = true
                                                break
                                            }
                                            if (confirmWait >= 90L && service.isForceStopDisabled()) {
                                                appIsStopped = true
                                                break
                                            }
                                        }
                                        break
                                    }
                                    ForceStopActionResult.FORCE_STOP_CLICKED -> {
                                        // Button was clicked, confirmation dialog appears shortly
                                    }
                                    ForceStopActionResult.NONE -> {
                                        // Window or dialog not yet ready
                                    }
                                }
                            }

                            delay(stepDelay)
                            waitElapsed += stepDelay
                        }

                        if (!appIsStopped) {
                            currentAttempt++
                            if (currentAttempt <= maxAttempts) {
                                delay(150) // Small delay before next attempt
                            }
                        }
                    }

                    stoppedCount++
                    _currentAppPackage.value = null
                    if (i < queue.lastIndex) {
                        delay(120) // Brief breathing room between apps
                    }
                }

                // Bring Stop it back to the foreground
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    bringAppToForeground(context)
                    onComplete()
                }

                _progress.value = AutomationProgress.Completed(stoppedCount)
                _currentAppPackage.value = null
            } finally {
                // Guaranteed cleanup: Put the AccessibilityService back to low-memory sleep mode
                ForceStopAccessibilityService.instance?.sleepService()
            }
        }
    }

    fun togglePause() {
        val newPause = !_isPaused.value
        _isPaused.value = newPause
        val currentProg = _progress.value
        if (currentProg is AutomationProgress.Running) {
            _progress.value = currentProg.copy(isPaused = newPause)
        }
    }

    fun stopAutomation(context: Context) {
        automationJob?.cancel()
        automationJob = null
        _currentAppPackage.value = null
        _isPaused.value = false
        _progress.value = AutomationProgress.Cancelled
        ForceStopAccessibilityService.instance?.sleepService()
        bringAppToForeground(context)
    }

    fun resetProgress() {
        _progress.value = AutomationProgress.Idle
    }

    fun notifyForceStopClicked(packageName: String) {}

    fun notifyAppStopped(packageName: String) {}

    private fun isPackageStopped(context: Context, packageName: String): Boolean {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_STOPPED) != 0
        } catch (e: Exception) {
            false
        }
    }

    private fun openAppDetailsSettings(context: Context, packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback to general settings if specific app uri fails
            try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (ignored: Exception) {}
        }
    }

    private fun bringAppToForeground(context: Context) {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            context.startActivity(intent)
        } catch (e: Exception) {}
    }
}
