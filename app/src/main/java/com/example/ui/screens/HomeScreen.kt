package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.service.AutomationProgress
import com.example.ui.MainViewModel
import com.example.ui.components.AccessibilityDialog
import com.example.ui.components.AddAppsDialog
import com.example.ui.components.AppRowItem
import com.example.ui.components.AutomationOverlayHud
import com.example.ui.components.MemoryCard
import com.example.ui.components.WhitelistRowItem
import com.example.ui.theme.AccentAmber
import com.example.ui.theme.AccentBorder
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.EmeraldOnPrimary
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val automationProgress by viewModel.automationProgress.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Show completion or custom snackbar messages
    LaunchedEffect(automationProgress) {
        if (automationProgress is AutomationProgress.Completed) {
            val count = (automationProgress as AutomationProgress.Completed).totalStopped
            snackbarHostState.showSnackbar(
                context.getString(R.string.automation_completed, count)
            )
            viewModel.resetAutomationProgress()
        }
    }

    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkAccessibilityStatus()
                viewModel.checkUsageAccessStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val runningListState = rememberLazyListState()
    val whitelistListState = rememberLazyListState()

    // Automatically refresh running apps when the Running Apps tab renders or becomes active
    LaunchedEffect(uiState.currentTab) {
        if (uiState.currentTab == 0) {
            viewModel.refreshRunningApps()
        }
    }

    val runningApps = uiState.runningApps

    val filteredWhitelistedApps by remember(uiState.whitelistedApps, uiState.searchQuery) {
        derivedStateOf {
            if (uiState.searchQuery.isBlank()) {
                uiState.whitelistedApps
            } else {
                uiState.whitelistedApps.filter {
                    it.appName.contains(uiState.searchQuery, ignoreCase = true) ||
                        it.packageName.contains(uiState.searchQuery, ignoreCase = true)
                }
            }
        }
    }

    val selectedCount by remember(uiState.runningApps) {
        derivedStateOf { uiState.runningApps.count { it.isSelected } }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
        containerColor = DarkBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (uiState.currentTab == 0 && runningApps.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    color = DarkSurface,
                    border = BorderStroke(1.dp, AccentBorder.copy(alpha = 0.6f)),
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.startStopApps(context) },
                            enabled = selectedCount > 0,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("stop_selected_apps_button"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmeraldPrimary,
                                contentColor = EmeraldOnPrimary,
                                disabledContainerColor = DarkSurfaceVariant,
                                disabledContentColor = TextTertiary
                            )
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_power_settings),
                                contentDescription = null,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.stop_apps_button, selectedCount),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            )
                        }
                    }
                }
            } else if (uiState.currentTab == 1) {
                // Bottom bar for Whitelist screen: Add Apps button
                Surface(
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    color = DarkSurface,
                    border = BorderStroke(1.dp, AccentBorder.copy(alpha = 0.6f)),
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.openAddAppsDialog() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("add_apps_button"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmeraldPrimary,
                                contentColor = EmeraldOnPrimary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.add_apps_button),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Top Header Bar
                TopAppBarHeader(
                    isAccessibilityEnabled = uiState.isAccessibilityEnabled,
                    onStatusClick = {
                        if (!uiState.isAccessibilityEnabled) {
                            viewModel.openAccessibilitySettings(context)
                        }
                    }
                )

                // Tab Switcher
                TabRow(
                    selectedTabIndex = uiState.currentTab,
                    containerColor = DarkBackground,
                    contentColor = EmeraldPrimary,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[uiState.currentTab]),
                            color = EmeraldPrimary,
                            height = 3.dp
                        )
                    },
                    divider = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(AccentBorder.copy(alpha = 0.6f))
                        )
                    }
                ) {
                    Tab(
                        selected = uiState.currentTab == 0,
                        onClick = { viewModel.selectTab(0) },
                        modifier = Modifier.testTag("tab_running_apps"),
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.tab_running_apps),
                                    fontWeight = if (uiState.currentTab == 0) FontWeight.Bold else FontWeight.Medium,
                                    color = if (uiState.currentTab == 0) EmeraldPrimary else TextSecondary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(if (uiState.currentTab == 0) EmeraldPrimary.copy(alpha = 0.2f) else DarkSurfaceVariant)
                                        .padding(horizontal = 7.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "${uiState.runningApps.size}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (uiState.currentTab == 0) EmeraldPrimary else TextSecondary
                                    )
                                }
                            }
                        }
                    )

                    Tab(
                        selected = uiState.currentTab == 1,
                        onClick = { viewModel.selectTab(1) },
                        modifier = Modifier.testTag("tab_whitelist"),
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.tab_whitelist),
                                    fontWeight = if (uiState.currentTab == 1) FontWeight.Bold else FontWeight.Medium,
                                    color = if (uiState.currentTab == 1) EmeraldPrimary else TextSecondary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                val whitelistedCount = uiState.whitelistedApps.size
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(if (uiState.currentTab == 1) EmeraldPrimary.copy(alpha = 0.2f) else DarkSurfaceVariant)
                                        .padding(horizontal = 7.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "$whitelistedCount",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (uiState.currentTab == 1) EmeraldPrimary else TextSecondary
                                    )
                                }
                            }
                        }
                    )
                }

                // Content for Running Apps tab
                if (uiState.currentTab == 0) {
                    LazyColumn(
                        state = runningListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        // RAM Usage Card (Running Apps screen only)
                        item {
                            MemoryCard(memoryStats = uiState.memoryStats)
                        }

                        if (!uiState.isUsageAccessGranted) {
                            item {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(1.dp, AccentBorder.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                        .clickable { viewModel.openUsageAccessSettings(context) }
                                        .testTag("usage_access_banner"),
                                    color = DarkSurfaceVariant
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = null,
                                            tint = EmeraldPrimary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = stringResource(R.string.usage_access_prompt),
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = TextSecondary,
                                                fontSize = 12.sp
                                            ),
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = "Enable",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = EmeraldPrimary,
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.running_apps_count, runningApps.size),
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextSecondary
                                    )
                                )

                                val allSelected = runningApps.isNotEmpty() && runningApps.all { it.isSelected }
                                TextButton(
                                    onClick = { viewModel.selectAllApps(!allSelected) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.testTag("select_all_button")
                                ) {
                                    Text(
                                        text = if (allSelected) stringResource(R.string.deselect_all) else stringResource(R.string.select_all),
                                        color = EmeraldPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        if (uiState.isLoading) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(40.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        color = EmeraldPrimary,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }
                        } else if (runningApps.isEmpty()) {
                            item {
                                EmptyStateCard(
                                    painter = painterResource(R.drawable.ic_check_circle),
                                    title = stringResource(R.string.no_running_apps_title),
                                    subtitle = stringResource(R.string.no_running_apps_desc)
                                )
                            }
                        } else {
                            items(
                                items = runningApps,
                                key = { it.packageName }
                            ) { app ->
                                AppRowItem(
                                    app = app,
                                    onToggleSelect = { viewModel.toggleAppSelection(app.packageName) },
                                    onIconClick = { viewModel.openAppSettings(context, app.packageName) },
                                    onClearCacheClick = { viewModel.clearAppCache(context, app) },
                                    onLongClick = {
                                        viewModel.whitelistAppFromRunning(
                                            app = app,
                                            confirmationMessage = context.getString(R.string.app_whitelisted_snackbar, app.appName)
                                        )
                                    }
                                )
                            }
                        }
                    }
                } else {
                    // Content for Whitelist tab with independent scroll state
                    LazyColumn(
                        state = whitelistListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        item {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(1.dp, AccentBorder.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                                color = DarkSurfaceVariant
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_shield),
                                        contentDescription = null,
                                        tint = EmeraldPrimary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "Whitelisted apps will never be stopped and are excluded from the running apps scan.",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = TextSecondary,
                                            lineHeight = 18.sp
                                        )
                                    )
                                }
                            }
                        }

                        // Search input for Whitelist tab
                        item {
                            OutlinedTextField(
                                value = uiState.searchQuery,
                                onValueChange = { viewModel.updateSearchQuery(it) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("search_text_field"),
                                placeholder = {
                                    Text(
                                        text = stringResource(R.string.search_hint),
                                        color = TextSecondary
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        tint = TextSecondary
                                    )
                                },
                                trailingIcon = {
                                    if (uiState.searchQuery.isNotEmpty()) {
                                        IconButton(
                                            onClick = { viewModel.updateSearchQuery("") },
                                            modifier = Modifier.testTag("clear_search_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Clear,
                                                contentDescription = "Clear",
                                                tint = TextSecondary
                                            )
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(14.dp),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = DarkSurface,
                                    unfocusedContainerColor = DarkSurface,
                                    focusedBorderColor = EmeraldPrimary,
                                    unfocusedBorderColor = AccentBorder,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                )
                            )
                        }

                        if (filteredWhitelistedApps.isEmpty()) {
                            item {
                                EmptyStateCard(
                                    painter = painterResource(R.drawable.ic_shield),
                                    title = if (uiState.searchQuery.isNotEmpty()) stringResource(R.string.no_apps_found) else stringResource(R.string.empty_whitelist_title),
                                    subtitle = if (uiState.searchQuery.isNotEmpty()) "" else stringResource(R.string.empty_whitelist_desc)
                                )
                            }
                        } else {
                            items(
                                items = filteredWhitelistedApps,
                                key = { it.packageName }
                            ) { app ->
                                WhitelistRowItem(
                                    app = app,
                                    onToggleWhitelist = { viewModel.toggleWhitelist(app) },
                                    onIconClick = { viewModel.openAppSettings(context, app.packageName) },
                                    onClearCacheClick = { viewModel.clearAppCache(context, app) }
                                )
                            }
                        }
                    }
                }
            }

            // Full-screen Dialog to Add Non-Whitelisted Apps
            AddAppsDialog(
                isOpen = uiState.showAddAppsDialog,
                apps = uiState.nonWhitelistedApps,
                isLoading = uiState.isLoadingAddApps,
                searchQuery = uiState.addAppsSearchQuery,
                onSearchQueryChange = { viewModel.updateAddAppsSearchQuery(it) },
                onToggleWhitelist = { viewModel.toggleWhitelist(it) },
                onOpenAppSettings = { viewModel.openAppSettings(context, it) },
                onDismiss = { viewModel.closeAddAppsDialog() }
            )

            // Floating progress overlay HUD when automation is active
            AutomationOverlayHud(
                progress = automationProgress,
                onPauseResume = { viewModel.pauseResumeAutomation() },
                onCancel = { viewModel.cancelAutomation(context) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
            )

            // Accessibility permission dialog if required
            if (uiState.showAccessibilityDialog) {
                AccessibilityDialog(
                    onEnableClick = { viewModel.openAccessibilitySettings(context) },
                    onDismiss = { viewModel.dismissAccessibilityDialog() }
                )
            }
        }
    }
}

@Composable
fun TopAppBarHeader(
    isAccessibilityEnabled: Boolean,
    onStatusClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(DarkSurfaceVariant)
                    .border(1.dp, EmeraldPrimary.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_power_settings),
                    contentDescription = null,
                    tint = EmeraldPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = TextPrimary,
                        letterSpacing = 0.5.sp
                    )
                )
                Text(
                    text = "App Terminator",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                )
            }
        }

        // Accessibility Service Status Chip
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(if (isAccessibilityEnabled) EmeraldPrimary.copy(alpha = 0.15f) else AccentAmber.copy(alpha = 0.15f))
                .border(
                    1.dp,
                    if (isAccessibilityEnabled) EmeraldPrimary.copy(alpha = 0.4f) else AccentAmber.copy(alpha = 0.4f),
                    RoundedCornerShape(20.dp)
                )
                .clickable(onClick = onStatusClick)
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .testTag("accessibility_status_chip")
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = if (isAccessibilityEnabled) painterResource(R.drawable.ic_check_circle) else rememberVectorPainter(Icons.Default.Warning),
                    contentDescription = null,
                    tint = if (isAccessibilityEnabled) EmeraldPrimary else AccentAmber,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isAccessibilityEnabled) "Service Ready" else "Setup Required",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = if (isAccessibilityEnabled) EmeraldPrimary else AccentAmber
                    )
                )
            }
        }
    }
}

@Composable
fun EmptyStateCard(
    painter: androidx.compose.ui.graphics.painter.Painter,
    title: String,
    subtitle: String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, AccentBorder.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
        color = DarkSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(DarkSurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painter,
                    contentDescription = null,
                    tint = EmeraldPrimary,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
            )
        }
    }
}
