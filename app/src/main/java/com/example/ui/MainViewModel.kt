package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.AppDatabase
import com.example.data.model.AppInfoItem
import com.example.data.model.MemoryStats
import com.example.data.repository.AppRepository
import com.example.service.AutomationManager
import com.example.service.AutomationProgress
import com.example.service.CacheCleanerAutomationManager
import com.example.service.ForceStopAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val currentTab: Int = 0, // 0 = Running Apps, 1 = Whitelist
    val memoryStats: MemoryStats = MemoryStats(),
    val runningApps: List<AppInfoItem> = emptyList(),
    val whitelistedApps: List<AppInfoItem> = emptyList(),
    val nonWhitelistedApps: List<AppInfoItem> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    val showAddAppsDialog: Boolean = false,
    val isLoadingAddApps: Boolean = false,
    val addAppsSearchQuery: String = "",
    val isAccessibilityEnabled: Boolean = false,
    val isUsageAccessGranted: Boolean = false,
    val showAccessibilityDialog: Boolean = false,
    val snackbarMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: AppRepository
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    val automationProgress: StateFlow<AutomationProgress> = AutomationManager.progress

    init {
        val database = AppDatabase.getDatabase(application)
        repository = AppRepository(application, database.whitelistDao())

        checkAccessibilityStatus()
        checkUsageAccessStatus()
        refreshApps()

        // Observe accessibility service state changes
        viewModelScope.launch {
            ForceStopAccessibilityService.isServiceActive.collect { active ->
                _uiState.update { it.copy(isAccessibilityEnabled = active) }
            }
        }
    }

    fun checkAccessibilityStatus() {
        val isEnabled = ForceStopAccessibilityService.isAccessibilitySettingsEnabled(getApplication())
        _uiState.update { it.copy(isAccessibilityEnabled = isEnabled) }
    }

    fun checkUsageAccessStatus() {
        val isGranted = repository.isUsageAccessGranted()
        _uiState.update { it.copy(isUsageAccessGranted = isGranted) }
        if (isGranted) {
            loadCacheSizes()
        }
    }

    fun openUsageAccessSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (ignored: Exception) {}
        }
    }

    fun loadCacheSizes() {
        viewModelScope.launch {
            val currentRunning = _uiState.value.runningApps
            val currentWhitelisted = _uiState.value.whitelistedApps
            if (currentRunning.isNotEmpty()) {
                val updatedRunning = repository.populateCacheSizes(currentRunning)
                _uiState.update { it.copy(runningApps = updatedRunning) }
            }
            if (currentWhitelisted.isNotEmpty()) {
                val updatedWhitelisted = repository.populateCacheSizes(currentWhitelisted)
                _uiState.update { it.copy(whitelistedApps = updatedWhitelisted) }
            }
        }
    }

    fun clearAppCache(context: Context, app: AppInfoItem) {
        checkAccessibilityStatus()
        if (!_uiState.value.isAccessibilityEnabled) {
            _uiState.update { it.copy(showAccessibilityDialog = true) }
            return
        }

        CacheCleanerAutomationManager.clearCacheForApp(context, app.packageName) {
            // Update local cache size indicator to show negligible cache
            _uiState.update { state ->
                state.copy(
                    runningApps = state.runningApps.map {
                        if (it.packageName == app.packageName) it.copy(cacheSizeBytes = 0L, cacheSizeFormatted = "< 1 MB") else it
                    },
                    whitelistedApps = state.whitelistedApps.map {
                        if (it.packageName == app.packageName) it.copy(cacheSizeBytes = 0L, cacheSizeFormatted = "< 1 MB") else it
                    }
                )
            }
        }
    }

    fun selectTab(tab: Int) {
        _uiState.update { it.copy(currentTab = tab) }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun updateAddAppsSearchQuery(query: String) {
        _uiState.update { it.copy(addAppsSearchQuery = query) }
    }

    fun openAddAppsDialog() {
        _uiState.update { it.copy(showAddAppsDialog = true, isLoadingAddApps = true, addAppsSearchQuery = "") }
        viewModelScope.launch {
            val whitelistedList = repository.whitelistedPackageNames.first()
            val whitelistedSet = whitelistedList.toSet()
            val nonWhitelisted = repository.getNonWhitelistedApps(whitelistedSet)
            _uiState.update {
                it.copy(
                    nonWhitelistedApps = nonWhitelisted,
                    isLoadingAddApps = false
                )
            }
        }
    }

    fun closeAddAppsDialog() {
        _uiState.update { it.copy(showAddAppsDialog = false, addAppsSearchQuery = "") }
    }

    fun refreshApps() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val memStats = repository.getMemoryStats()
            val whitelistedList = repository.whitelistedPackageNames.first()
            val whitelistedSet = whitelistedList.toSet()

            var running = repository.getRunningApps(whitelistedSet)
            var whitelistedOnly = repository.getWhitelistedAppsOnly()

            val usageGranted = repository.isUsageAccessGranted()
            if (usageGranted) {
                running = repository.populateCacheSizes(running)
                whitelistedOnly = repository.populateCacheSizes(whitelistedOnly)
            }

            _uiState.update {
                it.copy(
                    memoryStats = memStats,
                    runningApps = running,
                    whitelistedApps = whitelistedOnly,
                    isUsageAccessGranted = usageGranted,
                    isLoading = false
                )
            }
        }
    }

    /**
     * Refreshes only running apps and memory stats without modifying whitelisted apps,
     * preserving scroll position and state on the Whitelist tab.
     */
    fun refreshRunningApps() {
        viewModelScope.launch {
            val memStats = repository.getMemoryStats()
            val whitelistedList = repository.whitelistedPackageNames.first()
            val whitelistedSet = whitelistedList.toSet()
            var running = repository.getRunningApps(whitelistedSet)

            val usageGranted = repository.isUsageAccessGranted()
            if (usageGranted) {
                running = repository.populateCacheSizes(running)
            }

            _uiState.update {
                it.copy(
                    memoryStats = memStats,
                    runningApps = running,
                    isUsageAccessGranted = usageGranted
                )
            }
        }
    }

    /**
     * Refreshes only the whitelisted apps list from the database.
     */
    fun refreshWhitelistedApps() {
        viewModelScope.launch {
            var whitelistedOnly = repository.getWhitelistedAppsOnly()
            val usageGranted = repository.isUsageAccessGranted()
            if (usageGranted) {
                whitelistedOnly = repository.populateCacheSizes(whitelistedOnly)
            }
            _uiState.update {
                it.copy(
                    whitelistedApps = whitelistedOnly,
                    isUsageAccessGranted = usageGranted
                )
            }
        }
    }

    fun toggleAppSelection(packageName: String) {
        _uiState.update { state ->
            val updated = state.runningApps.map { item ->
                if (item.packageName == packageName) {
                    item.copy(isSelected = !item.isSelected)
                } else {
                    item
                }
            }
            state.copy(runningApps = updated)
        }
    }

    fun selectAllApps(select: Boolean) {
        _uiState.update { state ->
            val updated = state.runningApps.map { it.copy(isSelected = select) }
            state.copy(runningApps = updated)
        }
    }

    fun toggleWhitelist(app: AppInfoItem) {
        val willBeWhitelisted = !app.isWhitelisted

        // 1. Instant optimistic in-memory update
        _uiState.update { state ->
            val updatedWhitelisted = if (willBeWhitelisted) {
                (state.whitelistedApps + app.copy(isWhitelisted = true)).sortedBy { it.appName.lowercase() }
            } else {
                state.whitelistedApps.filter { it.packageName != app.packageName }
            }

            // Update candidate list in Add Apps dialog if open
            val updatedNonWhitelisted = state.nonWhitelistedApps.map { item ->
                if (item.packageName == app.packageName) {
                    item.copy(isWhitelisted = willBeWhitelisted)
                } else {
                    item
                }
            }

            val updatedRunning = if (willBeWhitelisted) {
                state.runningApps.filter { it.packageName != app.packageName }
            } else {
                state.runningApps
            }

            state.copy(
                whitelistedApps = updatedWhitelisted,
                nonWhitelistedApps = updatedNonWhitelisted,
                runningApps = updatedRunning
            )
        }

        // 2. Persist to local database asynchronously in background
        viewModelScope.launch {
            if (willBeWhitelisted) {
                repository.addToWhitelist(app.packageName, app.appName)
            } else {
                repository.removeFromWhitelist(app.packageName)
            }
        }
    }

    /**
     * Whitelists an app directly from the Running Apps list on long-press.
     * Optimistically removes it from running list, adds it to whitelisted list, and shows a snackbar.
     */
    fun whitelistAppFromRunning(app: AppInfoItem, confirmationMessage: String? = null) {
        val whitelistedItem = app.copy(isWhitelisted = true)
        _uiState.update { state ->
            val updatedWhitelisted = (state.whitelistedApps.filter { it.packageName != app.packageName } + whitelistedItem)
                .sortedBy { it.appName.lowercase() }
            val updatedRunning = state.runningApps.filter { it.packageName != app.packageName }

            state.copy(
                whitelistedApps = updatedWhitelisted,
                runningApps = updatedRunning,
                snackbarMessage = confirmationMessage ?: "${app.appName} added to Whitelist"
            )
        }

        viewModelScope.launch {
            repository.addToWhitelist(app.packageName, app.appName)
        }
    }

    fun startStopApps(context: Context) {
        checkAccessibilityStatus()
        if (!_uiState.value.isAccessibilityEnabled) {
            _uiState.update { it.copy(showAccessibilityDialog = true) }
            return
        }

        val selected = _uiState.value.runningApps.filter { it.isSelected }
        if (selected.isEmpty()) {
            _uiState.update { it.copy(snackbarMessage = "No apps selected to stop.") }
            return
        }

        AutomationManager.startAutomation(context, selected) {
            refreshApps()
        }
    }

    fun pauseResumeAutomation() {
        AutomationManager.togglePause()
    }

    fun cancelAutomation(context: Context) {
        AutomationManager.stopAutomation(context)
        refreshApps()
    }

    fun resetAutomationProgress() {
        AutomationManager.resetProgress()
    }

    fun dismissAccessibilityDialog() {
        _uiState.update { it.copy(showAccessibilityDialog = false) }
    }

    fun openAccessibilitySettings(context: Context) {
        dismissAccessibilityDialog()
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {}
    }

    fun openAppSettings(context: Context, packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {}
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }
}
