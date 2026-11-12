package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.example.service.AutomationManager
import com.example.ui.MainViewModel
import com.example.ui.screens.HomeScreen
import com.example.ui.theme.StopItTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val automationBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            isEnabled = false
            // Exit cleanly to Home so Android 12+ / Samsung OneUI does not reveal
            // a leftover Settings task underneath Stop it in the OS task stack.
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this, automationBackCallback)
        updateAutomationReturnFlag(intent)
        setContent {
            StopItTheme {
                HomeScreen(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateAutomationReturnFlag(intent)
    }

    private fun updateAutomationReturnFlag(intent: Intent?) {
        automationBackCallback.isEnabled =
            intent?.getBooleanExtra(AutomationManager.EXTRA_RETURNED_FROM_AUTOMATION, false) == true
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkAccessibilityStatus()
        // Only refresh running apps if the user is on the Running Apps tab.
        // If on the Whitelist tab, preserve the list and scroll position.
        if (viewModel.uiState.value.currentTab == 0) {
            viewModel.refreshRunningApps()
        }
    }
}
