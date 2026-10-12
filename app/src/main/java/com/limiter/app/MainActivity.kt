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

    companion object {
        const val ACTION_INTERNAL_NAVIGATE = "com.limiter.app.action.INTERNAL_NAVIGATE"

        val FORBIDDEN_MUTATION_KEYS = setOf("add_target", "limit", "hide", "reveal")

        fun isUntrustedMutation(extraKeys: Set<String>?): Boolean {
            if (extraKeys == null) return false
            return extraKeys.any { it in FORBIDDEN_MUTATION_KEYS }
        }

        data class ParsedIntent(
            val action: String?,
            val extraKeys: Set<String> = emptySet(),
            val hasMalformedExtras: Boolean = false
        ) {
            companion object {
                fun fromIntent(intent: Intent?): ParsedIntent? {
                    if (intent == null) return null
                    return try {
                        val keys = intent.extras?.keySet() ?: emptySet()
                        ParsedIntent(action = intent.action, extraKeys = keys)
                    } catch (_: Exception) {
                        ParsedIntent(action = intent.action, hasMalformedExtras = true)
                    }
                }
            }
        }

        enum class IntentGateDecision {
            REJECT_MUTATION,
            REJECT_MALFORMED,
            ALLOW_MAIN,
            ALLOW_NAVIGATE,
            IGNORE_EXTERNAL
        }

        fun evaluateIntent(parsed: ParsedIntent?): IntentGateDecision {
            if (parsed == null) return IntentGateDecision.IGNORE_EXTERNAL
            if (parsed.hasMalformedExtras) return IntentGateDecision.REJECT_MALFORMED
            if (isUntrustedMutation(parsed.extraKeys)) return IntentGateDecision.REJECT_MUTATION

            return when (parsed.action) {
                Intent.ACTION_MAIN -> IntentGateDecision.ALLOW_MAIN
                ACTION_INTERNAL_NAVIGATE -> IntentGateDecision.ALLOW_NAVIGATE
                else -> IntentGateDecision.IGNORE_EXTERNAL
            }
        }

        fun isUntrustedMutationIntent(intent: Intent?): Boolean {
            val parsed = ParsedIntent.fromIntent(intent) ?: return false
            val decision = evaluateIntent(parsed)
            return decision == IntentGateDecision.REJECT_MUTATION || decision == IntentGateDecision.REJECT_MALFORMED
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    internal fun handleIntent(intent: Intent?) {
        val parsed = ParsedIntent.fromIntent(intent) ?: return
        val decision = evaluateIntent(parsed)

        when (decision) {
            IntentGateDecision.REJECT_MUTATION -> {
                android.util.Log.w(
                    "Limiter",
                    "Rejected untrusted intent attempting mutation: action=${parsed.action}, keys=${parsed.extraKeys}"
                )
            }
            IntentGateDecision.REJECT_MALFORMED -> {
                android.util.Log.w(
                    "Limiter",
                    "Rejected malformed intent with unreadable extras: action=${parsed.action}"
                )
            }
            IntentGateDecision.ALLOW_MAIN -> {
                // Legitimate launcher launch; display UI normally
            }
            IntentGateDecision.ALLOW_NAVIGATE -> {
                // Legitimate internal navigation (view transition only, no mutations)
            }
            IntentGateDecision.IGNORE_EXTERNAL -> {
                // Safely ignore unknown external actions
            }
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

