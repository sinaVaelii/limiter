package com.limiter.app.ui

import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import android.util.LruCache
import com.limiter.app.policy.SettingsPolicy
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import com.limiter.app.R
import com.limiter.app.data.DiscoveredApp
import com.limiter.app.model.LockState
import com.limiter.app.model.PendingChange
import com.limiter.app.model.TargetAppConfig
import com.limiter.app.policy.SelfLockPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val iconBitmapCache = LruCache<String, ImageBitmap>(120)

fun formatDuration(context: android.content.Context, minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> context.getString(R.string.duration_hours_minutes, h.toString(), m.toString())
        h > 0 -> context.getString(R.string.duration_hours_only, h.toString())
        else -> context.getString(R.string.duration_minutes_only, m.toString())
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LimiterScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()

    // Refresh ticker driven by TimeSource for live countdown across all screens and exact-boundary updates
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000L)
            viewModel.tickCurrentTime()
        }
    }

    var showDashboardInSelfLock by remember { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }
    var showBatchConfigDialog by remember { mutableStateOf(false) }
    var editingTarget by remember { mutableStateOf<TargetAppConfig?>(null) }
    var lockedReadOnlyTarget by remember { mutableStateOf<Pair<String, LockState.LockedUntil>?>(null) }

    // 1. Self-Lock Mode Branching (Rule B)
    // While self-lock is active during a scheduled window, management controls show
    // the full-screen live countdown screen by default, while allowing adding new target apps
    // and navigating to the dashboard to view limits/usage and perform tightening.
    if (state.selfLockStatus.isSelfLocked && !showDashboardInSelfLock) {
        SelfLockScreen(
            state = state,
            onAddApp = {
                viewModel.loadAvailableApps()
                showAppPicker = true
            },
            onViewDashboard = {
                showDashboardInSelfLock = true
            }
        )

        if (showAppPicker) {
            AppPickerDialog(
                state = state,
                onSearchQueryChange = { viewModel.setSearchQuery(it) },
                onTogglePackage = { viewModel.togglePackageSelection(it) },
                onSelectAll = { viewModel.selectAllEligible(it) },
                onClearSelections = { viewModel.clearSelections() },
                onToggleShowSystemApps = { viewModel.setShowSystemApps(it) },
                onDismiss = { showAppPicker = false },
                onProceedToConfigure = {
                    showAppPicker = false
                    showBatchConfigDialog = true
                }
            )
        }

        if (showBatchConfigDialog) {
            BatchConfigDialog(
                selectedCount = state.selectedPackages.size,
                isSelfLocked = true,
                onDismiss = {
                    showBatchConfigDialog = false
                    viewModel.clearSelections()
                },
                onApply = { limit, hide, reveal ->
                    viewModel.submitBatchConfig(
                        packageNames = state.selectedPackages,
                        dailyLimitMinutes = limit,
                        hideAtMinuteOfDay = hide,
                        revealAtMinuteOfDay = reveal
                    )
                    showBatchConfigDialog = false
                }
            )
        }

        return
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    viewModel.loadAvailableApps()
                    showAppPicker = true
                },
                modifier = Modifier.size(56.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_apps))
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            // Self-lock countdown banner when viewing dashboard during self-lock
            if (state.selfLockStatus.isSelfLocked) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val unlockMillis = state.selfLockStatus.unlockTimeMillis ?: 0L
                        val remainingMillis = maxOf(0L, unlockMillis - state.currentMillis)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.self_lock_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "${stringResource(R.string.self_lock_countdown_label)} ${SelfLockPolicy.formatRemainingTime(remainingMillis)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        TextButton(
                            onClick = { showDashboardInSelfLock = false },
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text(stringResource(R.string.back), color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Feedback message banner
            if (state.feedbackMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = state.feedbackMessage ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { viewModel.clearFeedbackMessage() },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // System Status Card
            StatusCard(state = state)
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.configured_apps_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (state.targets.isEmpty() && state.pendingChanges.isEmpty()) {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = stringResource(R.string.empty_targets_title),
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.empty_targets_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.empty_targets_msg),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                viewModel.loadAvailableApps()
                                showAppPicker = true
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = stringResource(R.string.empty_targets_action),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.empty_targets_action))
                        }
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val allPackages = (state.targets.keys + state.pendingChanges.map { it.packageName }).distinct()

                    items(allPackages, key = { it }) { pkg ->
                        val config = state.targets[pkg]
                        val pending = state.pendingChanges.find { it.packageName == pkg }
                        val lockRecord = state.lockStates[pkg]
                        val usage = state.usages[pkg]
                        val lockState = lockRecord?.state ?: LockState.Unlocked

                        AppStatusCard(
                            packageName = pkg,
                            config = config,
                            pendingChange = pending,
                            usedMillis = if (usage is com.limiter.app.model.UsageSnapshot.Available) usage.usedMillis else 0L,
                            lockState = lockState,
                            nowMillis = state.currentMillis,
                            onEdit = {
                                if (lockState is LockState.LockedUntil && state.currentMillis < lockState.untilEpochMillis) {
                                    // Branch to LockedReadOnly
                                    lockedReadOnlyTarget = pkg to lockState
                                } else {
                                    editingTarget = pending?.desired ?: config ?: TargetAppConfig(pkg, 60, 1380, 420)
                                }
                            },
                            onLockedClick = {
                                if (lockState is LockState.LockedUntil) {
                                    lockedReadOnlyTarget = pkg to lockState
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // 1. App Picker Dialog
    if (showAppPicker) {
        AppPickerDialog(
            state = state,
            onSearchQueryChange = { viewModel.setSearchQuery(it) },
            onTogglePackage = { viewModel.togglePackageSelection(it) },
            onSelectAll = { filtered -> viewModel.selectAllEligible(filtered) },
            onClearSelections = { viewModel.clearSelections() },
            onToggleShowSystemApps = { viewModel.setShowSystemApps(it) },
            onDismiss = {
                showAppPicker = false
                viewModel.clearSelections()
            },
            onProceedToConfigure = {
                showAppPicker = false
                showBatchConfigDialog = true
            }
        )
    }

    // 2. Batch Configuration Dialog
    if (showBatchConfigDialog) {
        BatchConfigDialog(
            selectedCount = state.selectedPackages.size,
            isSelfLocked = state.selfLockStatus.isSelfLocked,
            onDismiss = {
                showBatchConfigDialog = false
                viewModel.clearSelections()
            },
            onApply = { limit, hide, reveal ->
                viewModel.submitBatchConfig(
                    packageNames = state.selectedPackages,
                    dailyLimitMinutes = limit,
                    hideAtMinuteOfDay = hide,
                    revealAtMinuteOfDay = reveal
                )
                showBatchConfigDialog = false
            }
        )
    }

    // 3. Edit Existing Target Dialog
    if (editingTarget != null) {
        val editingPkg = editingTarget!!.packageName
        val effectiveConfig = state.targets[editingPkg]
        val pendingChange = state.pendingChanges.find { it.packageName == editingPkg }

        EditTargetDialog(
            target = editingTarget!!,
            effectiveConfig = effectiveConfig,
            pendingChange = pendingChange,
            isSelfLocked = state.selfLockStatus.isSelfLocked,
            onDismiss = { editingTarget = null },
            onSave = { updatedConfig ->
                viewModel.submitConfig(updatedConfig)
                editingTarget = null
            },
            onRemove = { pkg ->
                viewModel.removeConfig(pkg)
                editingTarget = null
            }
        )
    }

    // 4. Locked Read-Only Dialog
    if (lockedReadOnlyTarget != null) {
        val (pkg, lockState) = lockedReadOnlyTarget!!
        LockedReadOnlyDialog(
            packageName = pkg,
            lockState = lockState,
            currentMillis = state.currentMillis,
            onDismiss = { lockedReadOnlyTarget = null }
        )
    }
}

/**
 * Dedicated, full-screen self-lock restricted mode screen.
 */
@Composable
fun SelfLockScreen(
    state: MainUiState,
    onTick: () -> Unit = {},
    onAddApp: () -> Unit = {},
    onViewDashboard: () -> Unit = {}
) {
    val context = LocalContext.current
    val nowMillis = state.currentMillis
    val unlockMillis = state.selfLockStatus.unlockTimeMillis ?: 0L
    val remainingMillis = maxOf(0L, unlockMillis - nowMillis)
    val formattedUnlock = remember(unlockMillis) {
        if (unlockMillis > 0L) {
            val dt = Instant.ofEpochMilli(unlockMillis).atZone(ZoneId.systemDefault())
            DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy - HH:mm").format(dt)
        } else {
            ""
        }
    }
    val scrollState = rememberScrollState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = stringResource(R.string.self_lock_title),
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.self_lock_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.self_lock_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))

            // Countdown Card
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.self_lock_countdown_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = SelfLockPolicy.formatRemainingTime(remainingMillis),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    if (formattedUnlock.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.self_lock_expected_unlock, formattedUnlock),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Explanation Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.self_lock_explanation),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Read-Only Managed App Limits Section
            if (state.targets.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.self_lock_configured_limits),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        state.targets.values.forEach { target ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AppIcon(packageName = target.packageName, modifier = Modifier.size(28.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = target.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Text(
                                    text = "${formatDuration(context, target.dailyLimitMinutes)} (${formatTime(target.hideAtMinuteOfDay)}→${formatTime(target.revealAtMinuteOfDay)})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            } else {
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Allowed actions during self-lock (Rule B): NO unlock/skip/snooze button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onAddApp,
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.self_lock_add_app_secondary),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.self_lock_add_app_secondary))
                }
                Button(
                    onClick = onViewDashboard,
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text(stringResource(R.string.self_lock_view_dashboard))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Onboarding and system status card health row.
 */
@Composable
fun HealthChipsRow(
    state: MainUiState,
    onShowDeviceOwnerDialog: () -> Unit,
    onShowBatteryDialog: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
    ) {
        // 1. Device Owner
        item {
            AssistChip(
                onClick = {
                    if (!state.isDeviceOwner) onShowDeviceOwnerDialog()
                },
                label = {
                    Text(
                        "${stringResource(R.string.chip_device_owner)}: ${
                            if (state.isDeviceOwner) stringResource(R.string.chip_ok) else stringResource(R.string.chip_fix)
                        }"
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = if (state.isDeviceOwner) Icons.Default.Check else Icons.Default.Warning,
                        contentDescription = if (state.isDeviceOwner) stringResource(R.string.chip_ok) else stringResource(R.string.chip_fix),
                        tint = if (state.isDeviceOwner) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (state.isDeviceOwner) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer
                )
            )
        }

        // 2. Usage Access
        item {
            AssistChip(
                onClick = {
                    if (!state.hasUsageAccess) {
                        try {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        } catch (e: Exception) {
                            android.util.Log.e("Limiter", "Failed to open usage access settings", e)
                        }
                    }
                },
                label = {
                    Text(
                        "${stringResource(R.string.chip_usage_access)}: ${
                            if (state.hasUsageAccess) stringResource(R.string.chip_ok) else stringResource(R.string.chip_fix)
                        }"
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = if (state.hasUsageAccess) Icons.Default.Check else Icons.Default.Warning,
                        contentDescription = if (state.hasUsageAccess) stringResource(R.string.chip_ok) else stringResource(R.string.chip_fix),
                        tint = if (state.hasUsageAccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (state.hasUsageAccess) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer
                )
            )
        }

        // 3. Exact Alarm
        item {
            AssistChip(
                onClick = {
                    if (!state.hasExactAlarmPermission) {
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            android.util.Log.e("Limiter", "Failed to open exact alarm settings", e)
                        }
                    }
                },
                label = {
                    Text(
                        "${stringResource(R.string.chip_exact_alarm)}: ${
                            if (state.hasExactAlarmPermission) stringResource(R.string.chip_ok) else stringResource(R.string.chip_fix)
                        }"
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = if (state.hasExactAlarmPermission) Icons.Default.Check else Icons.Default.Warning,
                        contentDescription = if (state.hasExactAlarmPermission) stringResource(R.string.chip_ok) else stringResource(R.string.chip_fix),
                        tint = if (state.hasExactAlarmPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (state.hasExactAlarmPermission) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer
                )
            )
        }

        // 4. Notifications
        item {
            AssistChip(
                onClick = {
                    if (!state.hasNotificationPermission) {
                        try {
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            android.util.Log.e("Limiter", "Failed to open notification settings", e)
                        }
                    }
                },
                label = {
                    Text(
                        "${stringResource(R.string.chip_notifications)}: ${
                            if (state.hasNotificationPermission) stringResource(R.string.chip_ok) else stringResource(R.string.chip_fix)
                        }"
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = if (state.hasNotificationPermission) Icons.Default.Check else Icons.Default.Warning,
                        contentDescription = if (state.hasNotificationPermission) stringResource(R.string.chip_ok) else stringResource(R.string.chip_fix),
                        tint = if (state.hasNotificationPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (state.hasNotificationPermission) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer
                )
            )
        }

        // 5. Self-Protection
        item {
            val isProtected = state.isUninstallBlocked && state.isUserControlDisabled
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        "${stringResource(R.string.chip_self_protection)}: ${
                            if (isProtected) stringResource(R.string.chip_ok) else stringResource(R.string.status_not_configured)
                        }"
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = if (isProtected) Icons.Default.Check else Icons.Default.Warning,
                        contentDescription = if (isProtected) stringResource(R.string.chip_ok) else stringResource(R.string.status_not_configured),
                        tint = if (isProtected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (isProtected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer
                )
            )
        }

        // 6. Battery Guidance
        item {
            AssistChip(
                onClick = onShowBatteryDialog,
                label = { Text(stringResource(R.string.chip_battery_optimization)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.chip_battery_optimization),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        }
    }
}

@Composable
fun StatusCard(state: MainUiState) {
    val context = LocalContext.current
    var showDeviceOwnerDialog by remember { mutableStateOf(false) }
    var showBatteryDialog by remember { mutableStateOf(false) }

    if (showDeviceOwnerDialog) {
        AlertDialog(
            onDismissRequest = { showDeviceOwnerDialog = false },
            title = { Text(stringResource(R.string.status_device_owner)) },
            text = { Text(stringResource(R.string.device_owner_cmd_hint)) },
            confirmButton = {
                TextButton(onClick = { showDeviceOwnerDialog = false }) {
                    Text(stringResource(R.string.confirm))
                }
            }
        )
    }

    if (showBatteryDialog) {
        AlertDialog(
            onDismissRequest = { showBatteryDialog = false },
            title = { Text(stringResource(R.string.battery_guidance_title)) },
            text = { Text(stringResource(R.string.battery_guidance_msg)) },
            confirmButton = {
                Button(
                    onClick = {
                        showBatteryDialog = false
                        try {
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            try {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }
                    }
                ) {
                    Text(stringResource(R.string.open_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatteryDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.status_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            HealthChipsRow(
                state = state,
                onShowDeviceOwnerDialog = { showDeviceOwnerDialog = true },
                onShowBatteryDialog = { showBatteryDialog = true }
            )

            if (!state.hasExactAlarmPermission) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            android.util.Log.e("Limiter", "Failed to open exact alarm settings", e)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text(text = stringResource(R.string.btn_grant_exact_alarm))
                }
            }
        }
    }
}

@Composable
fun StatusRow(
    label: String,
    isActive: Boolean,
    activeText: String,
    inactiveText: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isActive) Icons.Default.Check else Icons.Default.Warning,
                contentDescription = if (isActive) activeText else inactiveText,
                modifier = Modifier.size(16.dp),
                tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (isActive) activeText else inactiveText,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
fun AppStatusCard(
    packageName: String,
    config: TargetAppConfig?,
    pendingChange: PendingChange?,
    usedMillis: Long,
    lockState: LockState,
    nowMillis: Long,
    onEdit: () -> Unit,
    onLockedClick: () -> Unit
) {
    val isLocked = lockState is LockState.LockedUntil && nowMillis < lockState.untilEpochMillis

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (isLocked) onLockedClick() else onEdit()
            },
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isLocked) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row: App Icon + Name + Edit/Lock Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    AppIcon(packageName = packageName, modifier = Modifier.padding(end = 12.dp))
                    Column {
                        Text(
                            text = packageName,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (config != null) {
                            Text(
                                text = stringResource(
                                    R.string.target_schedule_window,
                                    formatTime(config.hideAtMinuteOfDay),
                                    formatTime(config.revealAtMinuteOfDay)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                IconButton(
                    onClick = { if (isLocked) onLockedClick() else onEdit() },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (isLocked) Icons.Default.Lock else Icons.Default.Edit,
                        contentDescription = if (isLocked) stringResource(R.string.locked_readonly_title) else stringResource(R.string.edit_target),
                        tint = if (isLocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Usage & Progress Bar
            if (config != null) {
                val usedMins = (usedMillis / 60_000).toInt()
                val limitMins = config.dailyLimitMinutes
                val remainingMins = maxOf(0, limitMins - usedMins)
                val progress = if (limitMins > 0) (usedMillis.toFloat() / (limitMins * 60_000f)).coerceIn(0f, 1f) else 0f

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = when {
                        progress >= 1f -> MaterialTheme.colorScheme.error
                        progress >= 0.85f -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = stringResource(R.string.target_used_limit, usedMins, limitMins),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = if (remainingMins == 0) stringResource(R.string.target_limit_reached)
                        else stringResource(R.string.target_remaining, remainingMins),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (remainingMins == 0) FontWeight.Bold else FontWeight.Normal,
                        color = if (remainingMins == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            // State Badge Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (lockState) {
                    is LockState.Unlocked -> {
                        AssistChip(
                            onClick = onEdit,
                            label = { Text(stringResource(R.string.target_state_active)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(R.string.target_state_active),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            )
                        )
                    }
                    is LockState.ReleasePending -> {
                        AssistChip(
                            onClick = onLockedClick,
                            label = { Text(stringResource(R.string.target_status_restoring)) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer
                            )
                        )
                    }
                    is LockState.LockedUntil -> {
                        val formattedTime = DateTimeFormatter.ofPattern("HH:mm")
                            .withZone(ZoneId.systemDefault())
                            .format(Instant.ofEpochMilli(lockState.untilEpochMillis))
                        AssistChip(
                            onClick = onLockedClick,
                            label = { Text(stringResource(R.string.target_state_locked, formattedTime)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = stringResource(R.string.locked_readonly_title),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        )
                    }
                }
            }

            // Pending Changes Card
            if (pendingChange != null) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        if (pendingChange.desired == null) {
                            Text(
                                text = stringResource(R.string.pending_removal),
                                color = MaterialTheme.colorScheme.secondary,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        } else {
                            Text(
                                text = stringResource(
                                    R.string.pending_change_summary,
                                    pendingChange.desired.dailyLimitMinutes,
                                    formatTime(pendingChange.desired.hideAtMinuteOfDay),
                                    formatTime(pendingChange.desired.revealAtMinuteOfDay)
                                ),
                                color = MaterialTheme.colorScheme.secondary,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.pending_applies_epoch_day, pendingChange.eligibleFromEpochDay),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Read-only dialog shown when user inspects a locked application.
 */
@Composable
fun LockedReadOnlyDialog(
    packageName: String,
    lockState: LockState.LockedUntil,
    currentMillis: Long,
    onDismiss: () -> Unit
) {
    val remainingMillis = maxOf(0L, lockState.untilEpochMillis - currentMillis)
    val formattedUnlock = remember(lockState.untilEpochMillis) {
        val dt = Instant.ofEpochMilli(lockState.untilEpochMillis).atZone(ZoneId.systemDefault())
        DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy - HH:mm").format(dt)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.locked_readonly_title)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(packageName = packageName, modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = packageName,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.locked_readonly_msg),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = SelfLockPolicy.formatRemainingTime(remainingMillis),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.self_lock_expected_unlock, formattedUnlock),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.height(48.dp)
            ) {
                Text(stringResource(R.string.back))
            }
        }
    )
}

@Composable
fun AppPickerDialog(
    state: MainUiState,
    onSearchQueryChange: (String) -> Unit,
    onTogglePackage: (String) -> Unit,
    onSelectAll: (List<DiscoveredApp>) -> Unit,
    onClearSelections: () -> Unit,
    onDismiss: () -> Unit,
    onProceedToConfigure: () -> Unit,
    onToggleShowSystemApps: (Boolean) -> Unit = {}
) {
    var hasConfirmedSystemAppsDialog by rememberSaveable { mutableStateOf(false) }
    var showSystemConfirmDialog by remember { mutableStateOf(false) }

    if (showSystemConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showSystemConfirmDialog = false },
            title = { Text(stringResource(R.string.show_system_apps_confirm_title)) },
            text = { Text(stringResource(R.string.show_system_apps_confirm_msg)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        hasConfirmedSystemAppsDialog = true
                        showSystemConfirmDialog = false
                        onToggleShowSystemApps(true)
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSystemConfirmDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    val filteredApps = remember(state.availableApps, state.searchQuery) {
        val q = state.searchQuery.trim().lowercase()
        if (q.isEmpty()) {
            state.availableApps
        } else {
            state.availableApps.filter {
                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.app_picker_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Search Bar
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.search_placeholder)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Show System Apps Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.show_system_apps),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = state.showSystemApps,
                        onCheckedChange = { isChecked ->
                            if (isChecked) {
                                if (!hasConfirmedSystemAppsDialog) {
                                    showSystemConfirmDialog = true
                                } else {
                                    onToggleShowSystemApps(true)
                                }
                            } else {
                                onToggleShowSystemApps(false)
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Selection Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.selected_count, state.selectedPackages.size),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row {
                        TextButton(
                            onClick = { onSelectAll(filteredApps) },
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text(stringResource(R.string.select_all))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        TextButton(
                            onClick = onClearSelections,
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text(stringResource(R.string.clear_selection))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // App List or Loading
                if (state.isLoadingApps) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (filteredApps.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.no_apps_found))
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            val isAlreadyLimited = state.selfLockStatus.isSelfLocked && app.packageName in state.targets
                            AppPickerRow(
                                app = app,
                                isSelected = state.selectedPackages.contains(app.packageName),
                                isAlreadyLimited = isAlreadyLimited,
                                onToggle = { onTogglePackage(app.packageName) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Bottom Action
                Button(
                    onClick = onProceedToConfigure,
                    enabled = state.selectedPackages.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text(stringResource(R.string.configure_selected))
                }
            }
        }
    }
}

@Composable
fun AppPickerRow(
    app: DiscoveredApp,
    isSelected: Boolean,
    isAlreadyLimited: Boolean = false,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isAlreadyLimited) Modifier
                else Modifier.clickable(onClick = onToggle)
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = if (isAlreadyLimited) null else { { onToggle() } },
            enabled = !isAlreadyLimited,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        AppIcon(packageName = app.packageName, modifier = Modifier.size(40.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = app.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(text = app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (isAlreadyLimited) {
                Text(
                    text = stringResource(R.string.picker_already_limited),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun DurationSelector(
    dailyLimitMinutes: Int,
    onDurationChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var hours by remember(dailyLimitMinutes) { mutableIntStateOf(dailyLimitMinutes / 60) }
    var minutes by remember(dailyLimitMinutes) { mutableIntStateOf(dailyLimitMinutes % 60) }

    val quickOptions = listOf(
        15 to R.string.duration_chip_15m,
        30 to R.string.duration_chip_30m,
        60 to R.string.duration_chip_1h,
        120 to R.string.duration_chip_2h,
        240 to R.string.duration_chip_4h,
        480 to R.string.duration_chip_8h,
        720 to R.string.duration_chip_12h
    )

    Column(modifier = modifier.fillMaxWidth()) {
        // Current Duration Display
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.custom_duration_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatDuration(context, dailyLimitMinutes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Quick Duration Chips
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
        ) {
            items(quickOptions) { (mins, labelRes) ->
                val isSelected = dailyLimitMinutes == mins
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        hours = mins / 60
                        minutes = mins % 60
                        onDurationChanged(mins)
                    },
                    label = { Text(stringResource(labelRes)) },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Hours Stepper
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.hours_stepper_label, hours.toString()),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        val newH = maxOf(0, hours - 1)
                        hours = newH
                        val newTotal = (newH * 60 + minutes).coerceIn(
                            SettingsPolicy.MIN_DAILY_LIMIT_MINUTES,
                            SettingsPolicy.MAX_DAILY_LIMIT_MINUTES
                        )
                        onDurationChanged(newTotal)
                    },
                    modifier = Modifier.size(48.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("-", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        val newH = minOf(12, hours + 1)
                        hours = newH
                        val newTotal = (newH * 60 + minutes).coerceIn(
                            SettingsPolicy.MIN_DAILY_LIMIT_MINUTES,
                            SettingsPolicy.MAX_DAILY_LIMIT_MINUTES
                        )
                        onDurationChanged(newTotal)
                    },
                    modifier = Modifier.size(48.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("+", style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Minutes Stepper (step = 5)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.minutes_stepper_label, minutes.toString()),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        val newM = if (minutes <= 0) 55 else minutes - 5
                        minutes = newM
                        val newTotal = (hours * 60 + newM).coerceIn(
                            SettingsPolicy.MIN_DAILY_LIMIT_MINUTES,
                            SettingsPolicy.MAX_DAILY_LIMIT_MINUTES
                        )
                        onDurationChanged(newTotal)
                    },
                    modifier = Modifier.size(48.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("-", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        val newM = if (minutes >= 55) 0 else minutes + 5
                        minutes = newM
                        val newTotal = (hours * 60 + newM).coerceIn(
                            SettingsPolicy.MIN_DAILY_LIMIT_MINUTES,
                            SettingsPolicy.MAX_DAILY_LIMIT_MINUTES
                        )
                        onDurationChanged(newTotal)
                    },
                    modifier = Modifier.size(48.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("+", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
fun BatchConfigDialog(
    selectedCount: Int,
    isSelfLocked: Boolean = false,
    onDismiss: () -> Unit,
    onApply: (limit: Int, hide: Int, reveal: Int) -> Unit
) {
    val context = LocalContext.current

    var dailyLimit by remember { mutableIntStateOf(60) }
    var hideMinute by remember { mutableIntStateOf(23 * 60) }
    var revealMinute by remember { mutableIntStateOf(7 * 60) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showConfirmSelfLockAdd by remember { mutableStateOf(false) }

    if (showConfirmSelfLockAdd) {
        AlertDialog(
            onDismissRequest = { showConfirmSelfLockAdd = false },
            title = { Text(stringResource(R.string.self_lock_add_confirmation_title)) },
            text = { Text(stringResource(R.string.self_lock_add_confirmation)) },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmSelfLockAdd = false
                        onApply(dailyLimit, hideMinute, revealMinute)
                    },
                    modifier = Modifier.height(48.dp)
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmSelfLockAdd = false }, modifier = Modifier.height(48.dp)) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.batch_config_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.selected_count, selectedCount),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(16.dp))

                DurationSelector(
                    dailyLimitMinutes = dailyLimit,
                    onDurationChanged = {
                        dailyLimit = it
                        errorMessage = null
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Window summary
                Text(
                    text = stringResource(R.string.window_summary, formatTime(hideMinute), formatTime(revealMinute)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = stringResource(R.string.hide_time_label))
                    OutlinedButton(
                        onClick = {
                            val h = hideMinute / 60
                            val m = hideMinute % 60
                            TimePickerDialog(context, { _, hourOfDay, minute ->
                                hideMinute = hourOfDay * 60 + minute
                            }, h, m, true).show()
                        },
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text(formatTime(hideMinute))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = stringResource(R.string.reveal_time_label))
                    OutlinedButton(
                        onClick = {
                            val h = revealMinute / 60
                            val m = revealMinute % 60
                            TimePickerDialog(context, { _, hourOfDay, minute ->
                                revealMinute = hourOfDay * 60 + minute
                            }, h, m, true).show()
                        },
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text(formatTime(revealMinute))
                    }
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (dailyLimit !in SettingsPolicy.MIN_DAILY_LIMIT_MINUTES..SettingsPolicy.MAX_DAILY_LIMIT_MINUTES) {
                        errorMessage = context.getString(R.string.error_daily_limit_range)
                        return@Button
                    }
                    if (hideMinute == revealMinute) {
                        errorMessage = context.getString(R.string.error_identical_times)
                        return@Button
                    }
                    if (isSelfLocked) {
                        showConfirmSelfLockAdd = true
                    } else {
                        onApply(dailyLimit, hideMinute, revealMinute)
                    }
                },
                modifier = Modifier.height(48.dp)
            ) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.height(48.dp)) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun EditTargetDialog(
    target: TargetAppConfig,
    effectiveConfig: TargetAppConfig? = null,
    pendingChange: PendingChange? = null,
    isSelfLocked: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (TargetAppConfig) -> Unit,
    onRemove: (String) -> Unit
) {
    val context = LocalContext.current

    var dailyLimit by remember(target.packageName) { mutableIntStateOf(target.dailyLimitMinutes) }
    var hideMinute by remember(target.packageName) { mutableIntStateOf(target.hideAtMinuteOfDay) }
    var revealMinute by remember(target.packageName) { mutableIntStateOf(target.revealAtMinuteOfDay) }
    var errorMessage by remember(target.packageName) { mutableStateOf<String?>(null) }
    var showConfirmRemove by remember(target.packageName) { mutableStateOf(false) }

    val currentActiveLimit = effectiveConfig?.dailyLimitMinutes ?: target.dailyLimitMinutes
    val currentActiveHide = effectiveConfig?.hideAtMinuteOfDay ?: target.hideAtMinuteOfDay
    val currentActiveReveal = effectiveConfig?.revealAtMinuteOfDay ?: target.revealAtMinuteOfDay

    if (showConfirmRemove) {
        AlertDialog(
            onDismissRequest = { showConfirmRemove = false },
            title = { Text(stringResource(R.string.confirm_remove_title)) },
            text = { Text(stringResource(R.string.confirm_remove_msg, target.packageName)) },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmRemove = false
                        onRemove(target.packageName)
                    },
                    modifier = Modifier.height(48.dp)
                ) {
                    Text(stringResource(R.string.remove_target))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmRemove = false }, modifier = Modifier.height(48.dp)) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_config_title)) },
        text = {
            Column {
                Text(text = target.packageName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(8.dp))

                if (effectiveConfig != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = stringResource(
                                    R.string.edit_current_effective,
                                    effectiveConfig.dailyLimitMinutes,
                                    formatTime(effectiveConfig.hideAtMinuteOfDay),
                                    formatTime(effectiveConfig.revealAtMinuteOfDay)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (pendingChange != null && pendingChange.desired != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(
                                        R.string.edit_pending_update,
                                        pendingChange.desired.dailyLimitMinutes,
                                        formatTime(pendingChange.desired.hideAtMinuteOfDay),
                                        formatTime(pendingChange.desired.revealAtMinuteOfDay)
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Localized Helper Text for Limits and Increases
                Text(
                    text = stringResource(
                        R.string.edit_limit_helper,
                        formatDuration(context, currentActiveLimit),
                        formatDuration(context, SettingsPolicy.MAX_LIMIT_INCREASE_MINUTES)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Custom stepped duration selector (no raw minute text input)
                DurationSelector(
                    dailyLimitMinutes = dailyLimit,
                    onDurationChanged = {
                        dailyLimit = it
                        errorMessage = null
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Window summary
                Text(
                    text = stringResource(R.string.window_summary, formatTime(hideMinute), formatTime(revealMinute)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = stringResource(R.string.hide_time_label))
                    OutlinedButton(
                        onClick = {
                            val h = hideMinute / 60
                            val m = hideMinute % 60
                            TimePickerDialog(context, { _, hourOfDay, minute ->
                                hideMinute = hourOfDay * 60 + minute
                            }, h, m, true).show()
                        },
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text(formatTime(hideMinute))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = stringResource(R.string.reveal_time_label))
                    OutlinedButton(
                        onClick = {
                            val h = revealMinute / 60
                            val m = revealMinute % 60
                            TimePickerDialog(context, { _, hourOfDay, minute ->
                                revealMinute = hourOfDay * 60 + minute
                            }, h, m, true).show()
                        },
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text(formatTime(revealMinute))
                    }
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(16.dp))
                if (isSelfLocked) {
                    Text(
                        text = stringResource(R.string.self_lock_controls_locked),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    OutlinedButton(
                        onClick = { showConfirmRemove = true },
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text(stringResource(R.string.remove_target), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (dailyLimit !in SettingsPolicy.MIN_DAILY_LIMIT_MINUTES..SettingsPolicy.MAX_DAILY_LIMIT_MINUTES) {
                        errorMessage = context.getString(R.string.error_daily_limit_range)
                        return@Button
                    }
                    if (hideMinute == revealMinute) {
                        errorMessage = context.getString(R.string.error_identical_times)
                        return@Button
                    }
                    if (isSelfLocked) {
                        if (dailyLimit > currentActiveLimit) {
                            errorMessage = context.getString(R.string.self_lock_controls_locked)
                            return@Button
                        }
                        if (SettingsPolicy.isWindowLoosening(currentActiveHide, currentActiveReveal, hideMinute, revealMinute)) {
                            errorMessage = context.getString(R.string.self_lock_controls_locked)
                            return@Button
                        }
                    } else {
                        val increase = dailyLimit - currentActiveLimit
                        if (increase > SettingsPolicy.MAX_LIMIT_INCREASE_MINUTES) {
                            errorMessage = context.getString(R.string.error_limit_increase_max)
                            return@Button
                        }
                    }
                    onSave(
                        target.copy(
                            dailyLimitMinutes = dailyLimit,
                            hideAtMinuteOfDay = hideMinute,
                            revealAtMinuteOfDay = revealMinute
                        )
                    )
                },
                modifier = Modifier.height(48.dp)
            ) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.height(48.dp)) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(packageName) {
        mutableStateOf(iconBitmapCache.get(packageName))
    }

    LaunchedEffect(packageName) {
        if (bitmap == null) {
            val loaded = withContext(Dispatchers.IO) {
                try {
                    val drawable = context.packageManager.getApplicationIcon(packageName)
                    drawable.toBitmap().asImageBitmap()
                } catch (_: Exception) {
                    null
                }
            }
            if (loaded != null) {
                iconBitmapCache.put(packageName, loaded)
                bitmap = loaded
            }
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = stringResource(R.string.cd_app_icon),
            modifier = modifier.size(40.dp)
        )
    } else {
        Box(
            modifier = modifier
                .size(40.dp)
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = stringResource(R.string.cd_app_icon),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

fun formatTime(minuteOfDay: Int): String {
    val hours = minuteOfDay / 60
    val minutes = minuteOfDay % 60
    return String.format("%02d:%02d", hours, minutes)
}
