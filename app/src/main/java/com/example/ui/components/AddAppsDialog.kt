package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.data.model.AppInfoItem
import com.example.ui.screens.EmptyStateCard
import com.example.ui.theme.AccentBorder
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun AddAppsDialog(
    isOpen: Boolean,
    apps: List<AppInfoItem>,
    isLoading: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onToggleWhitelist: (AppInfoItem) -> Unit,
    onOpenAppSettings: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!isOpen) return

    val filteredApps by remember(apps, searchQuery) {
        derivedStateOf {
            if (searchQuery.isBlank()) {
                apps
            } else {
                apps.filter {
                    it.appName.contains(searchQuery, ignoreCase = true) ||
                        it.packageName.contains(searchQuery, ignoreCase = true)
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars),
            containerColor = DarkBackground,
            topBar = {
                Surface(
                    color = DarkSurface,
                    modifier = Modifier.fillMaxWidth(),
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.testTag("dialog_back_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = TextPrimary
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.add_to_whitelist_title),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                )
                                Text(
                                    text = stringResource(R.string.available_apps_count, filteredApps.size),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = TextSecondary
                                    )
                                )
                            }
                        }

                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("dialog_done_button")
                        ) {
                            Text(
                                text = stringResource(R.string.done),
                                color = EmeraldPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Search Input
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dialog_search_field"),
                        placeholder = {
                            Text(
                                text = stringResource(R.string.search_installed_apps_hint),
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
                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { onSearchQueryChange("") },
                                    modifier = Modifier.testTag("dialog_clear_search_button")
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

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = EmeraldPrimary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                } else if (filteredApps.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        EmptyStateCard(
                            painter = painterResource(R.drawable.ic_shield),
                            title = if (searchQuery.isNotEmpty()) {
                                stringResource(R.string.no_apps_found)
                            } else {
                                stringResource(R.string.no_apps_to_add)
                            },
                            subtitle = if (searchQuery.isNotEmpty()) "" else "All installed launchable apps are currently added to your whitelist."
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                            .navigationBarsPadding(),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        items(
                            items = filteredApps,
                            key = { it.packageName }
                        ) { app ->
                            WhitelistRowItem(
                                app = app,
                                onToggleWhitelist = { onToggleWhitelist(app) },
                                onIconClick = { onOpenAppSettings(app.packageName) },
                                showClearCache = false
                            )
                        }
                    }
                }
            }
        }
    }
}
