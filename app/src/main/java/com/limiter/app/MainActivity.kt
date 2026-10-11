package com.limiter.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.limiter.app.model.TargetAppConfig
import com.limiter.app.ui.LimiterScreen
import com.limiter.app.ui.MainViewModel
import com.limiter.app.ui.theme.LimiterTheme
import kotlinx.coroutines.launch

/**
 * Main UI entry point for Limiter.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory(application as LimiterApp)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            LimiterTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LimiterScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val targetPkg = intent?.getStringExtra("add_target") ?: return
        val limit = intent.getIntExtra("limit", 60)
        val hide = intent.getIntExtra("hide", 0)
        val reveal = intent.getIntExtra("reveal", 1)
        val app = application as LimiterApp
        app.applicationScope.launch {
            try {
                val config = TargetAppConfig(targetPkg, limit, hide, reveal)
                app.lockCoordinator?.tryEditConfig(config)
                app.lockCoordinator?.reconcile()
                app.timeWindowScheduler?.reschedule()
            } catch (_: Exception) {}
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshSystemStatus()
        val app = application as LimiterApp
        app.applicationScope.launch {
            app.lockCoordinator?.reconcile()
        }
    }

    override fun onStart() {
        super.onStart()
        val app = application as LimiterApp
        if (app.isDeviceOwner()) {
            com.limiter.app.service.UsageMonitorService.start(this)
        }
    }
}

