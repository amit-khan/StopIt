package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.data.model.AppInfoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

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
    private const val MAX_ATTEMPTS = 3
    private const val ATTEMPT_TIMEOUT_MS = 3500L
    /** Safety net: re-scan at least this often even if no accessibility event arrives. */
    private const val EVENT_FALLBACK_MS = 300L
    /** How long to wait for the "package restarted" broadcast after the confirmation click. */
    private const val CONFIRM_SETTLE_MS = 600L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var automationJob: Job? = null

    private val _currentAppPackage = MutableStateFlow<String?>(null)
    val currentAppPackage: StateFlow<String?> = _currentAppPackage.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _progress = MutableStateFlow<AutomationProgress>(AutomationProgress.Idle)
    val progress: StateFlow<AutomationProgress> = _progress.asStateFlow()

    fun startAutomation(context: Context, appsToStop: List<AppInfoItem>, onComplete: () -> Unit) {
        if (appsToStop.isEmpty()) return
        val appContext = context.applicationContext
        stopAutomation(appContext)

        val queue = appsToStop.toList()
        _isPaused.value = false

        // Wake the AccessibilityService from low-memory sleep mode
        ForceStopAccessibilityService.instance?.wakeForAutomation(restrictToSettings = true)

        automationJob = scope.launch(Dispatchers.IO) {
            // The system broadcasts ACTION_PACKAGE_RESTARTED when a package is force-stopped, which
            // lets us detect success instantly instead of polling PackageManager.
            val stoppedPackages: MutableSet<String> = ConcurrentHashMap.newKeySet()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    val pkg = intent?.data?.schemeSpecificPart ?: return
                    stoppedPackages.add(pkg)
                    ForceStopAccessibilityService.notifyChange()
                }
            }
            val registered = registerStopReceiver(appContext, receiver)

            var stoppedCount = 0
            try {
                for (i in queue.indices) {
                    val app = queue[i]
                    _currentAppPackage.value = app.packageName

                    _progress.value = AutomationProgress.Running(
                        currentApp = app,
                        currentIndex = i + 1,
                        totalCount = queue.size,
                        isPaused = _isPaused.value
                    )

                    _isPaused.first { !it }

                    if (forceStopApp(appContext, app.packageName, stoppedPackages)) {
                        stoppedCount++
                    }

                    _currentAppPackage.value = null
                    delay(200) // Small breathing room between apps
                }
            } finally {
                if (registered) {
                    try { appContext.unregisterReceiver(receiver) } catch (ignored: Exception) {}
                }
            }

            // Put the AccessibilityService back to low-memory sleep mode immediately
            ForceStopAccessibilityService.instance?.sleepService()

            // Bring Stop it back to the foreground
            withContext(Dispatchers.Main) {
                bringAppToForeground(appContext)
                onComplete()
            }

            _progress.value = AutomationProgress.Completed(stoppedCount)
            _currentAppPackage.value = null
        }
    }

    /**
     * Opens App Info for [packageName] and drives the Force stop flow. Returns true if the app
     * ended up stopped. Waits on accessibility events / broadcasts instead of busy-polling.
     */
    private suspend fun forceStopApp(
        context: Context,
        packageName: String,
        stoppedPackages: Set<String>
    ): Boolean {
        // Nothing to do (and no need to open Settings) if it is already stopped.
        if (isPackageStopped(context, packageName)) return true

        withContext(Dispatchers.Main) { openAppDetailsSettings(context, packageName) }

        for (attempt in 1..MAX_ATTEMPTS) {
            val deadline = SystemClock.elapsedRealtime() + ATTEMPT_TIMEOUT_MS

            while (SystemClock.elapsedRealtime() < deadline) {
                _isPaused.first { !it }

                if (packageName in stoppedPackages) return true

                val service = ForceStopAccessibilityService.instance
                if (service == null) {
                    delay(EVENT_FALLBACK_MS)
                    continue
                }

                when (service.scanAndExecute(packageName)) {
                    ForceStopActionResult.ALREADY_DISABLED -> return true
                    ForceStopActionResult.CONFIRMATION_CLICKED -> {
                        // Wait briefly for the restart broadcast, then verify the final state.
                        waitUntil(CONFIRM_SETTLE_MS) { packageName in stoppedPackages }
                        if (packageName in stoppedPackages ||
                            service.isForceStopDisabled() ||
                            isPackageStopped(context, packageName)
                        ) {
                            return true
                        }
                        break // still active: go to the next attempt
                    }
                    ForceStopActionResult.FORCE_STOP_CLICKED,
                    ForceStopActionResult.NONE -> Unit
                }

                // Sleep until the UI changes (or the fallback interval elapses).
                ForceStopAccessibilityService.awaitChange(EVENT_FALLBACK_MS)
            }

            if (attempt < MAX_ATTEMPTS) delay(200) // Small delay before next attempt
        }
        return false
    }

    private suspend fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) {
            val remaining = end - SystemClock.elapsedRealtime()
            if (remaining <= 0) return false
            ForceStopAccessibilityService.awaitChange(remaining)
        }
        return true
    }

    private fun registerStopReceiver(context: Context, receiver: BroadcastReceiver): Boolean {
        return try {
            val filter = IntentFilter(Intent.ACTION_PACKAGE_RESTARTED).apply { addDataScheme("package") }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            true
        } catch (e: Exception) {
            false // Falls back to PackageManager / button-state checks.
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
        bringAppToForeground(context.applicationContext)
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
