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
    const val EXTRA_RETURNED_FROM_AUTOMATION = "extra_returned_from_automation"
    private val scope = CoroutineScope(Dispatchers.Main)
    private var automationJob: Job? = null

    private val _currentAppPackage = MutableStateFlow<String?>(null)
    val currentAppPackage: StateFlow<String?> = _currentAppPackage.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _progress = MutableStateFlow<AutomationProgress>(AutomationProgress.Idle)
    val progress: StateFlow<AutomationProgress> = _progress.asStateFlow()

    private var stoppedCount = 0

    fun startAutomation(context: Context, appsToStop: List<AppInfoItem>, onComplete: () -> Unit) {
        if (appsToStop.isEmpty()) return
        stopAutomation(context)

        stoppedCount = 0
        _isPaused.value = false

        // Wake the AccessibilityService from low-memory sleep mode
        ForceStopAccessibilityService.instance?.wakeForAutomation()

        automationJob = scope.launch(Dispatchers.Default) {
            try {
                for (i in appsToStop.indices) {
                    val app = appsToStop[i]
                    _currentAppPackage.value = null

                    _progress.value = AutomationProgress.Running(
                        currentApp = app,
                        currentIndex = i + 1,
                        totalCount = appsToStop.size,
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

                    // Reset per-app click/cooldown state before opening next Settings page
                    ForceStopAccessibilityService.instance?.resetAutomationState(app.packageName)

                    // Open App Info settings for the target app
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        openAppDetailsSettings(context, app.packageName)
                    }

                    // Allow the newly launched App Info activity to transition onto screen
                    // before activating accessibility event scanning for this package
                    delay(200)
                    _currentAppPackage.value = app.packageName

                    // Allow up to 3 attempts to force-stop the app if Settings lags or drops the launch
                    val maxAttempts = 3
                    var currentAttempt = 1
                    var appIsStopped = false

                    while (currentAttempt <= maxAttempts && !appIsStopped) {
                        var waitElapsed = 0L
                        val timeoutMs = 2800L
                        val stepDelay = 80L
                        var consecutiveDisabledCount = 0
                        var noneStreakMs = 0L
                        var scrollAttemptsInRound = 0
                        var awaitingDialogSinceClick = false

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
                                // Check if onAccessibilityEvent already clicked confirmation between polls
                                if (service.wasConfirmationRecentlyClicked(app.packageName, withinMs = 600L)) {
                                    var confirmWait = 0L
                                    while (confirmWait < 450L) {
                                        delay(50)
                                        confirmWait += 50
                                        if (isPackageStopped(context, app.packageName)) {
                                            appIsStopped = true
                                            break
                                        }
                                        if (confirmWait >= 120L && service.isForceStopDisabled()) {
                                            appIsStopped = true
                                            break
                                        }
                                    }
                                    if (appIsStopped) break
                                }

                                val result = service.scanAndExecute(app.packageName)
                                when (result) {
                                    ForceStopActionResult.ALREADY_DISABLED -> {
                                        noneStreakMs = 0L
                                        awaitingDialogSinceClick = false
                                        consecutiveDisabledCount++
                                        // Guard against transient disabled state while Settings runs
                                        // async checkForceStop() or finishes activity transition
                                        if ((waitElapsed >= 320L && consecutiveDisabledCount >= 2) ||
                                            currentAttempt > 1
                                        ) {
                                            appIsStopped = true
                                            break
                                        }
                                    }
                                    ForceStopActionResult.CONFIRMATION_CLICKED -> {
                                        noneStreakMs = 0L
                                        consecutiveDisabledCount = 0
                                        awaitingDialogSinceClick = false
                                        // Poll briefly for FLAG_STOPPED / disabled Force Stop button
                                        var confirmWait = 0L
                                        while (confirmWait < 500L) {
                                            delay(50)
                                            confirmWait += 50
                                            if (isPackageStopped(context, app.packageName)) {
                                                appIsStopped = true
                                                break
                                            }
                                            if (confirmWait >= 120L && service.isForceStopDisabled()) {
                                                appIsStopped = true
                                                break
                                            }
                                        }
                                        if (!appIsStopped) {
                                            // Confirmation was clicked; wait a bit longer on next attempt if needed
                                            break
                                        }
                                    }
                                    ForceStopActionResult.FORCE_STOP_CLICKED -> {
                                        noneStreakMs = 0L
                                        consecutiveDisabledCount = 0
                                        awaitingDialogSinceClick = true
                                    }
                                    ForceStopActionResult.NONE -> {
                                        consecutiveDisabledCount = 0
                                        if (!awaitingDialogSinceClick) {
                                            noneStreakMs += stepDelay
                                            // If Settings page loaded without exposing Force Stop
                                            // (e.g., button below the fold or unlaid-out RecyclerView),
                                            // scroll forward automatically instead of stalling
                                            if (noneStreakMs >= 700L && scrollAttemptsInRound < 2) {
                                                scrollAttemptsInRound++
                                                noneStreakMs = 0L
                                                service.tryScrollForward()
                                                delay(220)
                                                waitElapsed += 220L
                                            }
                                        }
                                    }
                                }
                            }

                            delay(stepDelay)
                            waitElapsed += stepDelay
                        }

                        if (!appIsStopped) {
                            currentAttempt++
                            if (currentAttempt <= maxAttempts) {
                                ForceStopAccessibilityService.instance?.resetAutomationState(app.packageName)
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    openAppDetailsSettings(context, app.packageName)
                                }
                                delay(280)
                            }
                        }
                    }

                    if (appIsStopped || isPackageStopped(context, app.packageName)) {
                        stoppedCount++
                    }
                    _currentAppPackage.value = null
                    ForceStopAccessibilityService.instance?.resetAutomationState(null)
                    if (i < appsToStop.lastIndex) {
                        delay(100) // Breathing room so Settings finishes tearing down before next launch
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

    private fun isPackageStopped(context: Context, packageName: String): Boolean {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_STOPPED) != 0
        } catch (e: Exception) {
            false
        }
    }

    internal fun openAppDetailsSettings(context: Context, packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_HISTORY or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback to general settings if specific app uri fails
            try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_HISTORY or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
                }
                context.startActivity(intent)
            } catch (ignored: Exception) {}
        }
    }

    internal fun bringAppToForeground(context: Context) {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_RETURNED_FROM_AUTOMATION, true)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            context.startActivity(intent)
        } catch (e: Exception) {}
    }
}
