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
                        delay(300)
                    }

                    // Open App Info settings for the target app
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        openAppDetailsSettings(context, app.packageName)
                    }

                    // Allow up to 3 attempts to force-stop the app if it refuses to stop
                    val maxAttempts = 3
                    var currentAttempt = 1
                    var appIsStopped = false

                    while (currentAttempt <= maxAttempts && !appIsStopped) {
                        var waitElapsed = 0L
                        val timeoutMs = 3500L
                        val stepDelay = 80L

                        while (waitElapsed < timeoutMs && !appIsStopped) {
                            while (_isPaused.value) {
                                delay(300)
                            }

                            val service = ForceStopAccessibilityService.instance
                            if (service != null) {
                                // Check if button is already disabled or app is already stopped
                                if (service.isForceStopDisabled() || isPackageStopped(context, app.packageName)) {
                                    appIsStopped = true
                                    break
                                }

                                val result = service.scanAndExecute(app.packageName)
                                when (result) {
                                    ForceStopActionResult.ALREADY_DISABLED -> {
                                        appIsStopped = true
                                        break
                                    }
                                    ForceStopActionResult.CONFIRMATION_CLICKED -> {
                                        // Small delay between clicking OK and checking "Force stop" button's state,
                                        // to ensure the new state is properly applied on settings page and read by the service.
                                        delay(250)

                                        // Verify that the Force Stop button is now disabled or app is actually stopped
                                        if (service.isForceStopDisabled() || isPackageStopped(context, app.packageName)) {
                                            appIsStopped = true
                                            break
                                        } else {
                                            // Still active after this attempt! Break out of inner scan loop to trigger attempt 2 or 3
                                            break
                                        }
                                    }
                                    ForceStopActionResult.FORCE_STOP_CLICKED -> {
                                        // Button was clicked, dialog is expected shortly
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
                                delay(200) // Small delay before next attempt
                            }
                        }
                    }

                    stoppedCount++
                    _currentAppPackage.value = null
                    delay(200) // Small breathing room between apps
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
